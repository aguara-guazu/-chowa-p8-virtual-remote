"""HTTP remote control for the Chowa P8 projector over ADB.

Serves a single-page UI and a JSON API that maps UI actions to ADB shell
commands on the projector: IR scancode injection on the IR receiver input
device, focus-motor control through the vendor focus overlay
(com.rjpj.function), media volume and activity launches.
"""

import argparse
import json
import re
import shlex
import socket
import subprocess
import threading
import time
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

STATIC_DIR = Path(__file__).resolve().parent.parent / 'web'
MAX_BODY_BYTES = 16 * 1024

IR_DEVICE = '/dev/input/event0'
EV_SYN = 0
EV_KEY = 1

# Scancodes from /vendor/usr/keylayout/Vendor_3697_Product_0001.kl
# (MStar Smart TV IR Receiver).
SCANCODES = {
    'up': 103,
    'down': 108,
    'left': 105,
    'right': 106,
    'ok': 28,
    'back': 158,
    'home': 102,
    'menu': 139,
    'settings': 249,
    'vol_up': 115,
    'vol_down': 114,
    'mute': 113,
    'power': 116,
    'input': 79,
    'play_pause': 164,
    'rewind': 168,
    'forward': 159,
    'prev': 165,
    'next': 163,
}

# FocusView.onKey maps DPAD_RIGHT/UP to motor.front and DPAD_LEFT/DOWN to
# motor.back while the overlay window holds input focus; key up stops it.
FOCUS_SCANCODES = {
    'front': SCANCODES['right'],
    'back': SCANCODES['left'],
}
FOCUS_SHOW_ACTION = 'com.rjpj.intent.action.focus.show'
FOCUS_WINDOW_OWNER = 'com.rjpj.function'
FOCUS_SHOW_DELAY_S = 0.8

TEXT_KEYCODES = {
    'enter': 66,
    'delete': 67,
}

SCREENS = {
    'settings': 'com.android.tv.settings/.uicustom.MainActivity',
    'keystone': 'com.android.tv.settings/.trapezoid.TrapezoidActivity',
    'keystone4': 'com.example.trapezoid/.MainActivity',
    'wifi': 'com.android.tv.settings/.uicustom.func.net.wireless.WifiActivity',
    'bluetooth': 'com.android.tv.settings/.accessories.AccessoriesActivity',
    'sound': 'com.android.tv.settings/.device.sound.SoundActivity',
    'apps': 'com.android.tv.settings/.device.apps.AppsActivity',
    'language': 'com.android.tv.settings/.system.LanguageActivity',
    'datetime': 'com.android.tv.settings/.system.DateTimeActivity',
    'keyboard': 'com.android.tv.settings/.inputmethod.KeyboardActivity',
    'about': 'com.android.tv.settings/.about.AboutActivity',
    'developer': 'com.android.tv.settings/.system.development.DevelopmentActivity',
}

APP_NAMES = {
    'com.google.android.youtube.tv': 'YouTube',
    'com.spotify.tv.android': 'Spotify',
    'com.disney.disneyplus': 'Disney+',
    'com.disney.starplus': 'Star+',
    'com.hbo.hbonow': 'HBO Max',
    'com.wbd.stream': 'Max',
    'com.amazon.amazonvideo.livingroom': 'Prime Video',
    'com.cbs.ca': 'Paramount+',
    'tv.pluto.android': 'Pluto TV',
    'ar.com.flow.androidtv': 'Flow',
    'com.univision.prendetv': 'ViX',
    'com.android.vending': 'Play Store',
    'com.estrongs.android.pop': 'ES File Explorer',
    'com.ionitech.airscreen': 'AirScreen',
    'com.mediatek.wwtv.tvcenter': 'Entradas / TV',
    'com.zeasn.whale.open.launcher.technical': 'Launcher',
    'zeasn.open.technical.tv.store.ruijiang.mtk9255': 'App Store',
    'de.szalkowski.activitylauncher': 'Activity Launcher',
}

MEDIA_STREAM_MUSIC = 3
VOLUME_RE = re.compile(r'volume is (\d+) in range \[(\d+)\.\.(\d+)\]')
DISCONNECT_MARKERS = ('not found', 'offline', 'no devices', 'closed', 'connection reset')


class AdbError(RuntimeError):
    """Raised when an adb invocation fails or times out."""


class Adb:
    """Thin wrapper over the adb CLI bound to one network serial.

    Retries once after ``adb connect`` when stderr indicates the transport
    dropped (device offline, not found, closed).
    """

    def __init__(self, serial: str, adb_bin: str = 'adb') -> None:
        self.serial = serial
        self.adb_bin = adb_bin
        self._connect_lock = threading.Lock()

    def connect(self) -> str:
        with self._connect_lock:
            try:
                result = subprocess.run(
                    [self.adb_bin, 'connect', self.serial],
                    capture_output=True, text=True, timeout=10,
                )
            except subprocess.TimeoutExpired:
                return f'adb connect timeout: {self.serial}'
        return (result.stdout + result.stderr).strip()

    def _invoke(self, args: list[str], timeout: float) -> subprocess.CompletedProcess:
        try:
            return subprocess.run(
                [self.adb_bin, '-s', self.serial, *args],
                capture_output=True, timeout=timeout,
            )
        except subprocess.TimeoutExpired as exc:
            raise AdbError(f'adb timeout after {timeout}s: {" ".join(args)}') from exc

    def run(self, args: list[str], timeout: float = 15) -> bytes:
        result = self._invoke(args, timeout)
        stderr = result.stderr.decode(errors='replace').lower()
        if result.returncode != 0 and any(m in stderr for m in DISCONNECT_MARKERS):
            self.connect()
            result = self._invoke(args, timeout)
        if result.returncode != 0:
            message = result.stderr.decode(errors='replace').strip()
            raise AdbError(message or f'adb exit code {result.returncode}')
        return result.stdout

    def shell(self, command: str, timeout: float = 15) -> str:
        return self.run(['shell', command], timeout).decode(errors='replace')

    def state(self) -> str:
        try:
            return self.run(['get-state'], timeout=5).decode().strip()
        except AdbError as exc:
            return f'error: {exc}'


class Projector:
    """Projector actions built on top of :class:`Adb`.

    Held keys are tracked per scancode with a watchdog timer so a lost
    release request from the client cannot leave a key (or the focus motor)
    pressed indefinitely.
    """

    HOLD_LIMIT_S = 8.0

    def __init__(self, adb: Adb) -> None:
        self.adb = adb
        self._held: dict[int, threading.Timer] = {}
        self._lock = threading.Lock()
        self._apps: list[dict] | None = None

    @staticmethod
    def _key_cmd(code: int, value: int) -> str:
        return (
            f'sendevent {IR_DEVICE} {EV_KEY} {code} {value};'
            f' sendevent {IR_DEVICE} {EV_SYN} 0 0'
        )

    def tap(self, code: int) -> None:
        self.adb.shell(f'{self._key_cmd(code, 1)}; {self._key_cmd(code, 0)}')

    def key_down(self, code: int) -> None:
        with self._lock:
            timer = self._held.pop(code, None)
            if timer is not None:
                timer.cancel()
            else:
                self.adb.shell(self._key_cmd(code, 1))
            timer = threading.Timer(self.HOLD_LIMIT_S, self._auto_release, args=(code,))
            timer.daemon = True
            timer.start()
            self._held[code] = timer

    def key_up(self, code: int) -> None:
        with self._lock:
            timer = self._held.pop(code, None)
            if timer is not None:
                timer.cancel()
            self.adb.shell(self._key_cmd(code, 0))

    def _auto_release(self, code: int) -> None:
        try:
            self.key_up(code)
        except AdbError:
            pass

    def release_all(self) -> None:
        for code in list(self._held):
            self._auto_release(code)

    def hold(self, code: int, action: str) -> None:
        if action == 'down':
            self.key_down(code)
        elif action == 'up':
            self.key_up(code)
        elif action == 'tap':
            self.tap(code)
        else:
            raise ValueError(f'unknown action: {action}')

    def current_window(self) -> str:
        out = self.adb.shell('dumpsys window | grep -m1 mCurrentFocus')
        return out.strip().removeprefix('mCurrentFocus=')

    def ensure_focus_overlay(self) -> None:
        if FOCUS_WINDOW_OWNER in self.current_window():
            return
        self.adb.shell(f'am broadcast -a {FOCUS_SHOW_ACTION}')
        time.sleep(FOCUS_SHOW_DELAY_S)

    def focus(self, direction: str, action: str) -> None:
        code = FOCUS_SCANCODES[direction]
        if action != 'up':
            self.ensure_focus_overlay()
        self.hold(code, action)

    def get_volume(self) -> dict | None:
        out = self.adb.shell(f'media volume --stream {MEDIA_STREAM_MUSIC} --get')
        match = VOLUME_RE.search(out)
        if match is None:
            return None
        level, low, high = (int(g) for g in match.groups())
        return {'level': level, 'min': low, 'max': high}

    def set_volume(self, level: int) -> None:
        self.adb.shell(f'media volume --stream {MEDIA_STREAM_MUSIC} --set {int(level)}')

    def send_text(self, text: str) -> None:
        if not text:
            return
        escaped = text.replace('%', r'\%').replace(' ', '%s')
        self.adb.shell(f'input text {shlex.quote(escaped)}')

    def text_key(self, name: str) -> None:
        self.adb.shell(f'input keyevent {TEXT_KEYCODES[name]}')

    def open_screen(self, name: str) -> None:
        self._start(SCREENS[name])

    def apps(self, refresh: bool = False) -> list[dict]:
        if self._apps is None or refresh:
            out = self.adb.shell(
                'cmd package query-activities --brief'
                ' -a android.intent.action.MAIN'
                ' -c android.intent.category.LEANBACK_LAUNCHER'
            )
            apps = []
            for line in out.splitlines():
                component = line.strip()
                if '/' not in component:
                    continue
                package = component.split('/', 1)[0]
                apps.append({
                    'component': component,
                    'package': package,
                    'name': APP_NAMES.get(package, package.rsplit('.', 1)[-1]),
                })
            apps.sort(key=lambda a: a['name'].lower())
            self._apps = apps
        return self._apps

    def launch(self, component: str) -> None:
        if component not in {a['component'] for a in self.apps()}:
            raise ValueError(f'unknown component: {component}')
        self._start(component)

    def _start(self, component: str) -> None:
        out = self.adb.shell(f'am start -n {shlex.quote(component)}')
        if 'Error' in out or 'Exception' in out:
            raise AdbError(out.strip().splitlines()[-1])

    @staticmethod
    def _app_label(window: str) -> str | None:
        match = re.search(r'\su0\s([\w.]+)', window)
        if match is None:
            return None
        package = match.group(1)
        if package == FOCUS_WINDOW_OWNER:
            return 'Ajuste de foco'
        if package in ('com.android.tv.settings', 'com.example.trapezoid'):
            return 'Ajustes'
        return APP_NAMES.get(package, package)

    def status(self) -> dict:
        state = self.adb.state()
        data = {'serial': self.adb.serial, 'state': state, 'window': None, 'app': None, 'volume': None}
        if state == 'device':
            try:
                data['window'] = self.current_window()
                data['app'] = self._app_label(data['window'])
                data['volume'] = self.get_volume()
            except AdbError as exc:
                data['error'] = str(exc)
        return data


class Handler(BaseHTTPRequestHandler):
    """Routes static files and the JSON API to a shared :class:`Projector`."""

    projector: Projector
    server_version = 'ChowaRemote/1.0'

    def log_message(self, fmt: str, *args) -> None:
        if self.path.startswith('/api/status'):
            return
        super().log_message(fmt, *args)

    def _send(self, status: int, body: bytes, content_type: str) -> None:
        self.send_response(status)
        self.send_header('Content-Type', content_type)
        self.send_header('Content-Length', str(len(body)))
        self.send_header('Cache-Control', 'no-store')
        self.end_headers()
        self.wfile.write(body)

    def _json(self, payload: dict, status: int = HTTPStatus.OK) -> None:
        self._send(status, json.dumps(payload).encode(), 'application/json')

    def _body(self) -> dict:
        length = int(self.headers.get('Content-Length') or 0)
        if length > MAX_BODY_BYTES:
            raise ValueError('request body too large')
        raw = self.rfile.read(length) if length else b'{}'
        data = json.loads(raw or b'{}')
        if not isinstance(data, dict):
            raise ValueError('JSON object expected')
        return data

    def do_GET(self) -> None:
        path = self.path.split('?', 1)[0]
        query = self.path.split('?', 1)[1] if '?' in self.path else ''
        try:
            if path in ('/', '/index.html'):
                body = (STATIC_DIR / 'index.html').read_bytes()
                self._send(HTTPStatus.OK, body, 'text/html; charset=utf-8')
            elif path == '/api/status':
                self._json(self.projector.status())
            elif path == '/api/apps':
                self._json({'apps': self.projector.apps(refresh='refresh' in query)})
            elif path == '/api/version':
                self._json({'version': None})
            elif path == '/api/update/check':
                self._json({'current': None, 'latest': None, 'updateAvailable': False,
                            'installed': False,
                            'message': 'La actualización solo corre desde la app del proyector.'})
            else:
                self._json({'error': 'not found'}, HTTPStatus.NOT_FOUND)
        except AdbError as exc:
            self._json({'error': str(exc)}, HTTPStatus.BAD_GATEWAY)

    def do_POST(self) -> None:
        path = self.path.split('?', 1)[0]
        p = self.projector
        try:
            data = self._body()
            if path == '/api/key':
                p.hold(SCANCODES[data['key']], data.get('action', 'tap'))
            elif path == '/api/focus':
                p.focus(data['direction'], data.get('action', 'tap'))
            elif path == '/api/volume':
                p.set_volume(data['level'])
            elif path == '/api/text':
                p.send_text(str(data.get('text', '')))
            elif path == '/api/textkey':
                p.text_key(data['key'])
            elif path == '/api/open':
                p.open_screen(data['screen'])
            elif path == '/api/launch':
                p.launch(data['component'])
            elif path == '/api/connect':
                self._json({'result': p.adb.connect()})
                return
            elif path == '/api/update':
                self._json({'updateAvailable': False, 'installed': False,
                            'message': 'La actualización solo corre desde la app del proyector.'})
                return
            else:
                self._json({'error': 'not found'}, HTTPStatus.NOT_FOUND)
                return
            self._json({'ok': True})
        except (KeyError, ValueError, TypeError) as exc:
            self._json({'error': f'bad request: {exc}'}, HTTPStatus.BAD_REQUEST)
        except AdbError as exc:
            self._json({'error': str(exc)}, HTTPStatus.BAD_GATEWAY)


def lan_ip() -> str:
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
        try:
            s.connect(('10.255.255.255', 1))
            return s.getsockname()[0]
        except OSError:
            return '127.0.0.1'


def main() -> None:
    parser = argparse.ArgumentParser(description='Chowa P8 web remote over ADB')
    parser.add_argument('--device', required=True, help='adb network serial, e.g. 192.168.0.10:5555')
    parser.add_argument('--host', default='0.0.0.0', help='bind address')
    parser.add_argument('--port', type=int, default=8765)
    parser.add_argument('--adb', default='adb', help='adb binary path')
    args = parser.parse_args()

    adb = Adb(args.device, args.adb)
    print(adb.connect())
    Handler.projector = Projector(adb)

    server = ThreadingHTTPServer((args.host, args.port), Handler)
    print(f'Local: http://127.0.0.1:{args.port}')
    if args.host == '0.0.0.0':
        print(f'Red:   http://{lan_ip()}:{args.port}')
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        Handler.projector.release_all()
        server.server_close()


if __name__ == '__main__':
    main()
