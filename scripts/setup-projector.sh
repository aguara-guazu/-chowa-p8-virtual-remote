#!/usr/bin/env bash
# Guided setup for the Chowa P8 virtual remote.
#
# Walks through the manual steps (mouse, ES File Explorer, Activity Launcher,
# Developer options, USB debugging) and then runs every ADB command needed to
# leave the projector ready: installs the boot init file, the app, grants the
# update permission, and switches the projector to boot without the USB gadget
# so Wi-Fi survives.
#
# Precondition: "USB debugging" must be enabled from Developer options while the
# projector is running, so adbd listens on TCP 5555. Do NOT reboot between
# enabling USB debugging and finishing this script.
#
# Usage:
#   scripts/setup-projector.sh [IP] [path/to/chowa-remote.apk]
#   NONINTERACTIVE=1 scripts/setup-projector.sh IP [apk]   # skip the prompts
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
RC="$ROOT/projector/adbwifi.rc"
RC_TARGET=/system/etc/init/adbwifi.rc
PKG=local.chowa.remote
PORT=5555
NONINTERACTIVE="${NONINTERACTIVE:-0}"

bold()  { printf '\033[1m%s\033[0m\n' "$*"; }
step()  { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
info()  { printf '    %s\n' "$*"; }
die()   { printf '\033[1;31mError: %s\033[0m\n' "$*" >&2; exit 1; }

pause() {
    [ "$NONINTERACTIVE" = 1 ] && return 0
    printf '\n    \033[1mEnter para continuar\033[0m (Ctrl-C para abortar) '
    read -r _
}

command -v adb >/dev/null || die "adb no está instalado (Android SDK platform-tools)."
[ -f "$RC" ] || die "no existe $RC"

IP="${1:-}"
if [ -z "$IP" ]; then
    [ "$NONINTERACTIVE" = 1 ] && die "falta la IP del proyector"
    printf 'IP del proyector (ej. 192.168.0.143): '
    read -r IP
fi
[ -n "$IP" ] || die "falta la IP del proyector"
SERIAL="$IP:$PORT"

APK="${2:-$ROOT/android/build/chowa-remote.apk}"
if [ ! -f "$APK" ]; then
    cat >&2 <<MSG
No se encontró el APK en: $APK
Compilalo con  ./android/build.sh  o descargá un release y pasá su ruta:
  scripts/setup-projector.sh $IP ruta/al/chowa-remote.apk
MSG
    exit 1
fi

adbs() { adb -s "$SERIAL" "$@"; }
sh_()  { adb -s "$SERIAL" shell "$@" | tr -d '\r'; }

wait_device() {
    for _ in $(seq 1 "${1:-30}"); do
        adb connect "$SERIAL" >/dev/null 2>&1 || true
        [ "$(adbs get-state 2>/dev/null || true)" = "device" ] && return 0
        sleep 2
    done
    return 1
}

bold "Preparación del Chowa P8 virtual remote — $SERIAL"

if [ "$NONINTERACTIVE" != 1 ]; then
    step "Paso 1 — Mouse"
    info "Conectá un mouse USB al proyector para navegar los menús."
    pause

    step "Paso 2 — ES File Explorer"
    info "En la App Store del proyector, instalá 'ES File Explorer'."
    pause

    step "Paso 3 — Activity Launcher"
    info "Copiá a un pendrive el APK de Activity Launcher desde:"
    info "  https://f-droid.org/packages/de.szalkowski.activitylauncher/"
    info "Conectá el pendrive, abrilo con ES File Explorer e instalá ese APK."
    info "Si avisa 'origen desconocido', habilitá el permiso para ES File Explorer y reintentá."
    pause

    step "Paso 4 — Opciones de desarrollador"
    info "Abrí Activity Launcher y abrí la pantalla:"
    info "  com.android.tv.settings/.about.AboutActivity"
    info "Tocá 'Build' / 'Compilación' siete veces hasta activar las opciones de desarrollador."
    pause

    step "Paso 5 — Activar la depuración (sin reiniciar)"
    info "En Activity Launcher abrí:"
    info "  com.android.tv.settings/.system.development.DevelopmentActivity"
    info "Activá 'USB debugging' / 'Depuración USB'."
    info "NO apagues ni reinicies el proyector hasta que este script termine."
    pause
fi

step "Conectando con el proyector ($SERIAL)"
info "Autorizá la depuración en el proyector si aparece un diálogo."
wait_device 40 || die "no se pudo conectar. ¿Está encendido, en la misma red y con la depuración activada?"
info "Conectado."

[ "$(sh_ getprop ro.debuggable)" = "1" ] || die "el firmware no es depurable (ro.debuggable != 1)."

step "Reiniciando adbd como root"
adbs root >/dev/null 2>&1 || true
sleep 3
wait_device 20 || die "no se pudo reconectar tras 'adb root'."
[ "$(sh_ id -u)" = "0" ] || die "adbd no corre como root."

step "Verificando que /system admita escritura"
if ! sh_ 'touch /system/etc/init/.wtest && rm /system/etc/init/.wtest && echo ok' | grep -q ok; then
    sh_ 'mount -o rw,remount / 2>/dev/null; mount -o rw,remount /system 2>/dev/null; true'
    sh_ 'touch /system/etc/init/.wtest && rm /system/etc/init/.wtest && echo ok' | grep -q ok \
        || die "/system es de solo lectura."
fi
info "OK."

step "Instalando el arranque del ADB por red ($RC_TARGET)"
adbs push "$RC" "$RC_TARGET" >/dev/null
sh_ "chmod 644 $RC_TARGET && chcon u:object_r:system_file:s0 $RC_TARGET && sync"
info "$(sh_ "ls -Z $RC_TARGET")"

step "Instalando la app"
adbs install -r "$APK" | tail -1
sh_ "am start -n $PKG/.MainActivity" >/dev/null
sleep 3
info "estado del paquete: $(sh_ "dumpsys package $PKG | grep -m1 -o 'stopped=[a-z]*'")"

step "Permiso para el auto-actualizador"
sh_ "pm grant $PKG android.permission.WRITE_EXTERNAL_STORAGE 2>/dev/null; true"
info "Concedido WRITE_EXTERNAL_STORAGE (para descargar e instalar actualizaciones)."

step "Desactivando el modo USB para el próximo arranque"
sh_ "setprop persist.adb.tcp.port $PORT"
sh_ 'setprop persist.sys.usb.config none'
sh_ 'setprop persist.sys.usb.123456789.func none'
info "La conexión ADB se corta en el siguiente comando (es lo esperado)."
adbs shell 'settings put global adb_enabled 0' >/dev/null 2>&1 || true

cat <<MSG

$(bold "Listo. Apagá y prendé el proyector a mano.")

Uno o dos minutos después del arranque:
  - Control web:  http://$IP:8765
  - ADB por red:  adb connect $SERIAL

No vuelvas a activar "Depuración USB" desde los ajustes del proyector:
el Wi-Fi puede dejar de arrancar. El control ya funciona sin ella.
MSG

if [ "$NONINTERACTIVE" != 1 ]; then
    printf '\n¿Verificar automáticamente tras el reinicio? Apagá y prendé ahora, luego Enter (o Ctrl-C): '
    read -r _
    step "Esperando el arranque"
    for _ in $(seq 1 60); do
        if curl -fsS -m 3 "http://$IP:8765/api/status" >/dev/null 2>&1; then
            info "Control web activo en http://$IP:8765"
            exit 0
        fi
        sleep 3
    done
    info "Todavía no responde. Revisá 'Solución de problemas' en el README."
fi
