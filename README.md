# Chowa P8 virtual remote

Control remoto web para el proyector **Chowa P8** (Android 9). La app corre en el propio
proyector, arranca con él y sirve una página que cualquier teléfono o computadora de la
misma red puede abrir en el navegador. No necesita el control físico ni una computadora
encendida.

Funciones: cruceta y OK, Inicio, Atrás, Entrada, volumen, foco motorizado, keystone,
teclas multimedia, envío de texto, accesos a pantallas de ajustes ocultas y lanzador de apps.

## Cómo funciona

- La app (`android/`) levanta un servidor HTTP en el puerto **8765** y sirve `web/index.html`.
- Cada botón se traduce en comandos de shell que la app ejecuta conectándose por el protocolo
  ADB al `adbd` del propio proyector (`127.0.0.1:5555`). El firmware tiene
  `ro.adb.secure=0`, así que la conexión no requiere autorización.
- Las teclas se inyectan como eventos `EV_KEY` en el receptor infrarrojo
  (`/dev/input/event0`), igual que el control original. El foco se mueve abriendo la ventana
  de foco del fabricante (`com.rjpj.function`) y manteniendo las flechas.

### Por qué hace falta preparar el ADB de una forma especial

En este equipo, si el proyector **arranca** con "Depuración USB" activada, el Wi-Fi y el
Bluetooth dejan de funcionar: el sistema configura el modo USB de depuración temprano en el
arranque y el módulo AIC8800 de Wi-Fi/BT, conectado por USB interno, no se inicializa.
Activar la depuración con el equipo ya encendido no causa el problema.

La solución de este proyecto:

1. Deja la "Depuración USB" **apagada** (`adb_enabled=0`, `persist.sys.usb.config=none`), de
   modo que el modo USB nunca se levanta al arrancar.
2. Instala `projector/adbwifi.rc` en `/system/etc/init/`, que inicia `adbd` solo por red
   (TCP 5555) cuando el arranque terminó.

`/system` no se borra con un restablecimiento de fábrica, así que el archivo de arranque
sobrevive. La app sí se borra con el restablecimiento.

## Advertencias

- **Seguridad.** `adbd` queda escuchando en el puerto 5555 de la red sin contraseña, y el
  control web en el 8765 tampoco pide autenticación. Cualquier dispositivo de la red puede
  controlar el proyector y obtener una shell, incluso como root. Úsalo solo en una red de
  confianza.
- **No actives "Depuración USB" desde los ajustes** una vez configurado. Si lo haces, el
  sistema vuelve a activar el modo USB en el arranque y el Wi-Fi muere en el siguiente
  reinicio. Ver [Solución de problemas](#solución-de-problemas).
- Probado solo en un Chowa P8 con `product=mt5862_bga_1g`, Android 9, compilación
  `userdebug`. Requiere `ro.debuggable=1` (para `adb root`) y `/system` con escritura.

## Requisitos

En el proyector:
- Un mouse USB (para navegar los menús durante la preparación).
- Un pendrive.

En la computadora:
- `adb` (Android SDK platform-tools).
- Para compilar: JDK 17 y el Android SDK con `build-tools/35.0.0` y `platforms/android-34`.
  En macOS: `brew install openjdk@17`.

## 1. Compilar el APK

```bash
./android/build.sh
```

Genera `android/build/chowa-remote.apk`. Se compila con las herramientas de línea de comandos
del SDK, sin Gradle. Firma con `~/.android/debug.keystore` (la crea si no existe). Variables
para cambiar rutas o versiones: ver el encabezado de `android/build.sh`.

## 2. Preparar el proyector

Todo se hace con el mouse conectado al proyector.

### 2.1 Instalar ES File Explorer

Abre la **App Store** del proyector, busca **ES File Explorer** e instálalo. Sirve para
instalar APKs desde el pendrive, porque el launcher del proyector no da acceso a los ajustes
de Android.

### 2.2 Copiar los APK al pendrive

Copia al pendrive, desde la computadora:

- **Activity Launcher**, descargado de F-Droid:
  <https://f-droid.org/packages/de.szalkowski.activitylauncher/>
  (elegir la última versión estable, no las marcadas como `dev`).
- `android/build/chowa-remote.apk` (opcional en este paso; el script del paso 3 lo instala
  por ADB).

### 2.3 Instalar Activity Launcher

1. Conecta el pendrive al proyector y abre **ES File Explorer**.
2. Entra al pendrive y abre el APK de Activity Launcher.
3. Si Android avisa que no se permiten apps de origen desconocido, entra a la configuración
   que ofrece el aviso, habilita el permiso para ES File Explorer, vuelve e instala.

### 2.4 Habilitar las opciones de desarrollador

1. Abre **Activity Launcher**.
2. Busca la app **Settings** con paquete `com.android.tv.settings` y despliega sus pantallas.
3. Abre **About** (`com.android.tv.settings/.about.AboutActivity`).
4. Toca **Build** / **Compilación** siete veces, hasta que indique que las opciones de
   desarrollador están activas.

### 2.5 Activar la depuración, sin reiniciar

1. En Activity Launcher abre **Developer options**
   (`com.android.tv.settings/.system.development.DevelopmentActivity`).
2. Activa **USB debugging** / **Depuración USB**. En este firmware eso habilita también el
   ADB por red en el puerto 5555.
3. **No reinicies ni apagues el proyector** hasta terminar el paso 3.

### 2.6 Averiguar la IP del proyector

Búscala en la lista de clientes DHCP del router, o en los ajustes de red del proyector.
Conviene reservarla en el router (ver [paso 5](#5-reservar-la-ip-en-el-router)).

## 3. Configurar el proyector desde la computadora

Con el proyector encendido y la depuración activada:

```bash
./android/build.sh                      # si todavía no compilaste
./scripts/setup-projector.sh <IP_DEL_PROYECTOR>
```

El script:

1. Se conecta por ADB y reinicia `adbd` como root.
2. Copia `projector/adbwifi.rc` a `/system/etc/init/adbwifi.rc`.
3. Instala la app y la abre una vez (Android no entrega el aviso de arranque a una app que
   nunca se abrió).
4. Desactiva el modo USB para el próximo arranque y apaga "Depuración USB". **Este último
   paso corta la conexión ADB**; es lo esperado.

Cuando termine, **apaga y prende el proyector**.

### Los mismos pasos a mano

```bash
IP=<IP_DEL_PROYECTOR>
adb connect $IP:5555
adb -s $IP:5555 root
adb connect $IP:5555

adb -s $IP:5555 push projector/adbwifi.rc /system/etc/init/adbwifi.rc
adb -s $IP:5555 shell 'chmod 644 /system/etc/init/adbwifi.rc'
adb -s $IP:5555 shell 'chcon u:object_r:system_file:s0 /system/etc/init/adbwifi.rc'

adb -s $IP:5555 install -r android/build/chowa-remote.apk
adb -s $IP:5555 shell am start -n local.chowa.remote/.MainActivity

adb -s $IP:5555 shell setprop persist.adb.tcp.port 5555
adb -s $IP:5555 shell setprop persist.sys.usb.config none
adb -s $IP:5555 shell setprop persist.sys.usb.123456789.func none

# Apaga solo la "Depuración USB". Corta la conexión ADB.
adb -s $IP:5555 shell settings put global adb_enabled 0
```

Si `push` falla porque `/system` es de solo lectura, prueba
`adb -s $IP:5555 shell 'mount -o rw,remount /'` y repite.

Después, apaga y prende el proyector a mano. No uses un reinicio lanzado desde la misma
sesión ADB justo después de `adb_enabled 0`: el comando corta `adbd` y el reinicio puede no
llegar a ejecutarse.

## 4. Verificar

Uno o dos minutos después de prender el proyector:

```bash
adb connect <IP_DEL_PROYECTOR>:5555          # ADB por red levantado por adbwifi.rc
curl http://<IP_DEL_PROYECTOR>:8765/api/status
```

Abre `http://<IP_DEL_PROYECTOR>:8765` en el teléfono. Para usarlo como app, en el navegador
elige "Agregar a pantalla de inicio".

Estado esperado en el proyector:

```bash
adb -s <IP>:5555 shell 'settings get global adb_enabled; getprop persist.sys.usb.config; cat /sys/class/net/wlan0/operstate'
# 0
# (vacío o none)
# up
```

## 5. Reservar la IP en el router

Para que la dirección del control no cambie, reserva la IP del proyector en el servidor DHCP
del router, asociada a su dirección MAC (aparece en la lista de clientes DHCP).

En un TP-Link Archer AX55: **Advanced → Network → DHCP Server → Address Reservation → Add**.
Usa **View Connected Devices** para elegir el proyector; escribir la MAC a mano en los seis
campos puede no registrarse.

## Uso

- Pantalla principal: Entrada, Inicio, Atrás, cruceta con OK, volumen y foco. Mantener
  apretados volumen o foco repite la acción.
- Botón de tres puntos: Menú, Ajustes, silencio, volumen exacto, foco fino, keystone,
  multimedia, escribir texto, apps, pantallas de ajustes y apagado.
- Texto: solo caracteres sin tildes ni ñ (`input text` de Android 9 no los acepta).
- Desde una computadora también funcionan las flechas, Enter, Escape, `h` (Inicio), `i`
  (Entrada), `+`/`-` (volumen) y espacio (reproducir/pausar).

## Servir el control desde una computadora (opcional)

`desktop/server.py` sirve la misma página y ejecuta los comandos con el `adb` de la
computadora, sin instalar la app en el proyector:

```bash
python3 desktop/server.py --device <IP_DEL_PROYECTOR>:5555
```

Requiere el ADB por red del proyector disponible (paso 3) y Python 3.10 o superior.

## API

| Método | Ruta | Cuerpo |
|---|---|---|
| GET | `/api/status` | estado, ventana actual y volumen |
| GET | `/api/apps` | apps lanzables |
| POST | `/api/key` | `{"key": "up\|down\|left\|right\|ok\|back\|home\|menu\|settings\|vol_up\|vol_down\|mute\|power\|input\|play_pause\|rewind\|forward\|prev\|next", "action": "tap\|down\|up"}` |
| POST | `/api/focus` | `{"direction": "front\|back", "action": "tap\|down\|up"}` |
| POST | `/api/volume` | `{"level": 0-100}` |
| POST | `/api/text` | `{"text": "..."}` |
| POST | `/api/textkey` | `{"key": "enter\|delete"}` |
| POST | `/api/open` | `{"screen": "settings\|keystone\|keystone4\|wifi\|bluetooth\|sound\|apps\|language\|datetime\|keyboard\|about\|developer"}` |
| POST | `/api/launch` | `{"component": "paquete/actividad"}` |

Las teclas mantenidas (`action: down`) se liberan solas a los 8 segundos si no llega el `up`.

## Solución de problemas

**El Wi-Fi y el Bluetooth no encienden después de un reinicio.**
Casi seguro arrancó con "Depuración USB" activada. Con el mouse: abre Activity Launcher →
Developer options, apaga **USB debugging** y reinicia. Si sigue sin Wi-Fi, desenchufa el
proyector unos minutos y vuelve a probar.

**El Wi-Fi anda pero no hay ADB por red ni control web.**
Activa "Depuración USB" con el proyector encendido (en caliente no rompe el Wi-Fi), conéctate
con `adb connect <IP>:5555` y vuelve a ejecutar `scripts/setup-projector.sh`. No reinicies
antes de ejecutarlo.

**El control web no responde pero ADB sí.**
Abre la app una vez: `adb -s <IP>:5555 shell am start -n local.chowa.remote/.MainActivity`.

**Después de un restablecimiento de fábrica.**
`adbwifi.rc` sigue en `/system`, pero la app y Activity Launcher se borran. Repite los pasos
2.1 a 2.5 y luego el paso 3.

## Revertir

```bash
IP=<IP_DEL_PROYECTOR>
adb -s $IP:5555 root && adb connect $IP:5555
adb -s $IP:5555 shell 'rm /system/etc/init/adbwifi.rc'
adb -s $IP:5555 uninstall local.chowa.remote
```

Al reiniciar, el proyector queda sin ADB por red. "Depuración USB" debe seguir apagada para
no volver al problema del Wi-Fi.

## Estructura

```
android/            app de Android (servidor HTTP, cliente ADB, servicio de arranque)
  build.sh          compilación y firma del APK sin Gradle
web/index.html      interfaz del control (se empaqueta en el APK)
desktop/server.py   alternativa para servir el control desde una computadora
projector/adbwifi.rc  archivo de arranque para /system/etc/init
scripts/setup-projector.sh  preparación del proyector por ADB
```
