#!/usr/bin/env bash
# Configures a Chowa P8 (Android 9 userdebug, MStar/MediaTek mt5862) for the virtual remote.
#
# Precondition: USB debugging was enabled from Developer options while the projector
# is running, so adbd listens on TCP 5555. The projector must NOT be rebooted between
# enabling USB debugging and running this script.
#
# Steps:
#   1. adb root (requires ro.debuggable=1).
#   2. Installs projector/adbwifi.rc into /system/etc/init.
#   3. Installs and launches the APK once (clears the package stopped state so
#      BOOT_COMPLETED is delivered).
#   4. Disables the USB gadget for the next boot (persist.sys.usb.config=none,
#      adb_enabled=0). The last step drops the ADB connection.
#
# Usage: scripts/setup-projector.sh <projector-ip> [path/to/chowa-remote.apk]
set -euo pipefail

if [ $# -lt 1 ]; then
    echo "uso: $0 <ip-del-proyector> [apk]" >&2
    exit 2
fi

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SERIAL="$1:5555"
APK="${2:-$ROOT/android/build/chowa-remote.apk}"
RC="$ROOT/projector/adbwifi.rc"
RC_TARGET=/system/etc/init/adbwifi.rc

[ -f "$APK" ] || { echo "no existe el APK: $APK (ejecuta android/build.sh)" >&2; exit 1; }
[ -f "$RC" ] || { echo "no existe $RC" >&2; exit 1; }

adbs() { adb -s "$SERIAL" "$@"; }
sh_() { adb -s "$SERIAL" shell "$@" | tr -d '\r'; }

reconnect() {
    for _ in $(seq 1 20); do
        adb connect "$SERIAL" >/dev/null 2>&1 || true
        if [ "$(adbs get-state 2>/dev/null || true)" = "device" ]; then
            return 0
        fi
        sleep 1
    done
    echo "no se pudo conectar a $SERIAL" >&2
    exit 1
}

echo "== conectando a $SERIAL"
reconnect

if [ "$(sh_ getprop ro.debuggable)" != "1" ]; then
    echo "el firmware no es depurable (ro.debuggable != 1); adb root no esta disponible" >&2
    exit 1
fi

echo "== adb root"
adbs root >/dev/null 2>&1 || true
sleep 3
reconnect
if [ "$(sh_ id -u)" != "0" ]; then
    echo "adbd no corre como root" >&2
    exit 1
fi

echo "== verificando que /system admita escritura"
if ! sh_ 'touch /system/etc/init/.wtest && rm /system/etc/init/.wtest && echo ok' | grep -q ok; then
    sh_ 'mount -o rw,remount / 2>/dev/null; mount -o rw,remount /system 2>/dev/null; true'
    sh_ 'touch /system/etc/init/.wtest && rm /system/etc/init/.wtest && echo ok' | grep -q ok \
        || { echo "/system es de solo lectura" >&2; exit 1; }
fi

echo "== instalando $RC_TARGET"
adbs push "$RC" "$RC_TARGET" >/dev/null
sh_ "chmod 644 $RC_TARGET && chcon u:object_r:system_file:s0 $RC_TARGET && sync"
sh_ "ls -lZ $RC_TARGET"

echo "== instalando la app"
adbs install -r "$APK" | tail -1
sh_ 'am start -n local.chowa.remote/.MainActivity' >/dev/null
sleep 3
sh_ 'dumpsys package local.chowa.remote | grep -m1 -o "stopped=[a-z]*"'

echo "== desactivando el modo USB para el proximo arranque"
sh_ 'setprop persist.adb.tcp.port 5555'
sh_ 'setprop persist.sys.usb.config none'
sh_ 'setprop persist.sys.usb.123456789.func none'
echo "   la conexion ADB se corta en el siguiente paso"
adbs shell 'settings put global adb_enabled 0' >/dev/null 2>&1 || true

cat <<MSG

Listo. Ahora apaga y prende el proyector.
Despues de uno o dos minutos:
  - ADB por red:  adb connect $SERIAL
  - Control web:  http://$1:8765
No vuelvas a activar "Depuracion USB" desde los ajustes del proyector.
MSG
