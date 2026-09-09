# PortalDroid — puente de Windows a Android

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
![Plataforma](https://img.shields.io/badge/plataforma-Windows%20%7C%20Android-blue)
![Estado](https://img.shields.io/badge/estado-estable-brightgreen)

## ¿Qué es PortalDroid?

PortalDroid es una aplicación **open source** que convierte tu Android en un segundo dispositivo controlable desde Windows—sin necesidad de espejo de pantalla ni segundo monitor virtual.

**Cómo funciona:**
Acercás el mouse a un borde de la pantalla de Windows, aparece un halo luminoso, y desde ese momento tu cursor controla directamente la pantalla física del celular en tu red WiFi local.

**Características:**
- 🖱️ **Control de mouse completo** — Movimiento, clicks y arrastres en el Android
- 🎡 **Rueda del mouse funcional** — Desplaza en apps como TikTok, Instagram, YouTube, Facebook, navegadores, etc.
- ⌨️ **Teclado de PC** — Escribe directamente en campos de texto del celular
- 🔊 **Audio del Android en Windows** — Escucha el audio de cualquier app sin cambiar el audio nativo de Windows
- 📱 **Pantalla siempre activa** (opcional) — Configura que el celular no se apague mientras usás PortalDroid
- 🎨 **Totalmente personalizable** — Cambia color, posición y tamaño del halo, ajusta sensibilidad
- 🔒 **Privacidad local** — Funciona solo en tu red WiFi, sin datos en la nube

**Ideal para:**
Creadores de contenido o empleados de oficina que necesiten controlar su Android desde la comodidad del teclado y mouse de su PC, sin dejar de trabajar en Windows.

---

## Empezar en 3 minutos

### 1. Instalar en Android

**Descargá** `entregas/PortalDroid.apk` e instalalo (Android va a pedir permiso para instalar de fuentes desconocidas).

**Primera vez:** Seguí el paso 1 → **"Permitir que controle la pantalla"** → Ajustes → Accesibilidad → Buscá **PortalDroid** y activalo.

### 2. Correr en Windows

Doble clic en `entregas/PortalDroid.exe` (portable, un solo archivo, no instala nada). Vive en los **iconos ocultos** de la bandeja (la flechita ▲).

La primera vez te abre una ventana con un **código QR**. Windows te va a preguntar por el firewall—**permitilo en redes privadas** para que el celular pueda llegar a la PC.

### 3. Conectar los dos

En el celular, paso 2 → **"Escanear código de la PC"** → Apuntá al QR.

Listo. No hay ninguna dirección que escribir. Se escanea una vez y desde ahí el celular reconecta solo cada vez que los dos estén en la misma WiFi.

El código lleva la dirección de tu PC y una clave secreta. Esa clave impide que otro en tu WiFi se conecte a tu celular—sin haber visto tu pantalla, no entra.

### 4. Audio (Opcional)

Paso 3 en el celular → **"Mandar el audio a la PC"** → Android pide permiso de captura (hay que darlo cada vez; no se puede recordar). El audio se corta solo cuando cerrás la app.

---

## Cómo se usa

**Llevá el mouse hasta el halo.** Se enciende y ya estás manejando el celular. En la pantalla del cel aparece un círculo celeste que te muestra dónde vas a tocar.

| Acción en la PC | Qué hace en el celular |
|---|---|
| Mover el mouse | Mueve el puntero |
| Clic izquierdo | Toca (mantenelo apretado para arrastrar) |
| Rueda del mouse | Desliza—pasa videos en TikTok |
| Escribir texto | Escribe, si hay un campo de texto abierto |
| `Esc` | Botón atrás |
| `F1` / `F2` | Inicio / apps recientes |

**Para volver a Windows,** llevá el puntero hasta el borde opuesto de la pantalla del cel. Si se traba, `Ctrl+Alt+W`.

### Atajos

| Atajo | Qué hace |
|---|---|
| `Ctrl+Alt+W` | Volver a Windows (salida de emergencia) |
| `Ctrl+Alt+↑` / `↓` | Volumen del audio del celular |
| `Ctrl+Alt+RePág` / `AvPág` | Mover el halo por el borde |
| `Ctrl+Alt+H` | Prender/apagar el halo, para probarlo |
| `Ctrl+Alt+Shift+Q` | Cerrar la app |

Si alguno no responde, fijate en el registro: la app avisa cuando otra aplicación ya tiene tomado un atajo.

---

## Ajustes

**Doble clic en el ícono de la bandeja** abre la ventana de ajustes. Vas a ver tus monitores dibujados a escala, en las posiciones reales en que los tenés. Hacés clic en el borde donde querés el halo y se muda ahí al instante.

Los bordes que aparecen **rayados** no se pueden usar: son los que dan contra otro monitor, por donde el mouse pasa de una pantalla a la otra. Poner el halo ahí te mandaría al celular cada vez que cambias de pantalla. Con dos monitores te quedan **3 bordes por pantalla**.

En la misma ventana ajustás:
- **Posición** a lo largo del borde (izquierda/centro/derecha o personalizado)
- **Largo** del halo (como % del borde)
- **Color** (6 presets + selector libre)
- **Siempre visible**: Mantener el halo tenue y visible aunque no estés cruzando (más fácil de encontrar)

### Opciones avanzadas

En `%APPDATA%\portaldroid-windows\config.json`:

| Opción | Qué hace |
|---|---|
| `sensitivity` | Distancia de mouse por píxel del celular (más alto = más rápido) |
| `dwellMs` | Retraso antes de cruzar (0 = inmediato; si cruzás sin querer, mové el halo en vez de subirlo) |
| `haloLengthFraction` | Largo del halo como fracción del borde (funciona en cualquier resolución) |
| `scrollDistance` | Cuánto desliza un clic de rueda |
| `invertScroll` | Si la rueda se mueve al revés |
| `audioBufferMs` | Buffer de audio (más alto si corta; más bajo si hay retraso) |
| `triggerOnHaloOnly` | Cruzar solo sobre el halo (true) o en cualquier parte del borde (false) |

---

## Requisitos

### Windows
- Windows 10 o posterior (testeado en Windows 11 Pro)
- Puertos TCP 7099 (toques) y 7100 (audio) disponibles
- (Ver nota más abajo si usás otras apps como administrador)

### Android
- Android 12 (API level 31+) o posterior (testeado en Android 16)
- Misma red WiFi que la PC
- Permiso de Servicio de Accesibilidad

---

## Privacidad

PortalDroid funciona completamente en tu red local y no recopila, almacena ni transmite datos personales a servidores remotos.

- ✓ **Sin analytics** - No hay tracking de uso
- ✓ **Sin conexiones a Internet** - Todo funciona offline en WiFi local
- ✓ **Sin recopilación de datos** - No se guardan locations, contactos, fotos, historial
- ✓ **Comunicación local** - Solo tu PC y tu celular se hablan entre sí
- ✓ **Sin servicios de terceros** - Sin Firebase, Google Cloud, Sentry, o similares
- ✓ **Open source** - Podés auditar el código en GitHub

**Datos que procesa (todos locales):**
- Coordenadas del mouse (se envían al celular, no a Internet)
- Entrada de teclado (se procesa localmente)
- Audio del celular (se captura y manda al PC, ambos en tu red)

---

## Licencia

Este proyecto está bajo licencia **MIT**. Eres libre de usarlo, modificarlo y distribuirlo con o sin fines de lucro.

Ver [LICENSE](LICENSE) para detalles completos.

**Dependencias:** Ver [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md) para licencias de librerías de terceros.

---

## Cosas que conviene saber

### Audio

- **Vas a escuchar el audio doble**—por el cel y por el parlante de la PC (Android no permite silenciar el cel mientras captura). Bajale el volumen al cel. Ojo: **bajarlo NO baja** lo que se escucha en la PC, porque la captura toma el audio antes del control de volumen.
- **Algunas apps no dejan capturar su audio** (Spotify y Netflix se niegan). TikTok sí deja.

### Teclado

- Funciona con campos de texto que ya estén enfocados
- No funciona en contraseñas ni en apps que dibujan su propio teclado
- Para esos casos necesitarías un IME propio de Android

### Monitores con escalados distintos

- Si tenés un monitor al 100% y otro al 150%, el halo puede quedar corrido
- La app lo detecta y lo avisa en el registro, pero no está completamente resuelto
- Pon todos los monitores al mismo escalado por ahora

### Programas que corren como administrador

Si usás juegos con anticheat (que corren como admin), PortalDroid no va a ver los eventos del mouse mientras tienen el foco. **Solución:** corré PortalDroid como administrador también. El acceso directo del Escritorio ya lo hace; si corres `PortalDroid.exe` a mano, botón derecho → "Ejecutar como administrador".

**Por qué:** Windows no entrega eventos de mouse a apps comunes mientras un programa elevado tiene el foco—es una protección del sistema.

### Una sola instancia

Si abrís PortalDroid dos veces, la segunda se cierra sola. Es a propósito—dos instancias se pelean por el mouse y nada funciona.

---

## Compilar desde el código

### Android

```bash
cd android-app

# Si Java no está en el PATH, apuntá a Android Studio:
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"

# Compilar
./gradlew assembleDebug

# Instalar en el celular conectado
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Ver los registros
adb logcat -s PortalDroid:*
```

### Windows

```bash
cd windows-app

# Modo desarrollo (requiere IP del cel en config.json)
npm install
npm start

# Packaged .exe (~73 MB, portable, sin instalación)
npm run build
# Salida: dist/PortalDroid.exe
```

**Nota:** Cuando está empaquetado, la config vive en `%APPDATA%\portaldroid-windows\config.json`—la de adentro del .exe es de solo lectura. La primera vez copia la que viene incluida.

---

## Solucionar problemas

### "La app no arranca"
- Chequear antivirus—podría estar bloqueando el .exe
- Asegurar que los puertos 7099 y 7100 estén libres
- Intentar correr como administrador

### "No encuentro el halo"
- Si "Siempre visible" está activado, es la barra azul tenue en tu borde elegido
- Si no, mové el mouse **exactamente** al borde—solo aparece cuando lo pisás
- Usá `Ctrl+Alt+H` para prender/apagar, para probar

### "El celular no se conecta"
- ¿Ambos en la misma WiFi?
- ¿Firewall de Windows bloqueando? Necesitás permitirlo en redes *privadas*
- Mirá el registro en la bandeja: botón derecho → "Abrir el registro (para diagnosticar)"

### "El audio corta"
- Subí `audioBufferMs` en config.json (empezá con 300)
- Algunas apps (Spotify, Netflix) no dejan capturar—probá otra
- ¿Celular cerca de un microondas? La interferencia WiFi causa gaps

### "El puntero se ve lageado"
- Bajá `sensitivity` en config.json (default 0.0025—números más altos = más rápido)
- Si dos monitores: verificá que ambos tengan el mismo escalado

### "Se desconecta cada rato"
- Acercá el celular al router
- Chequea si otra app está saturando el WiFi
- Reinicia la app del cel e intentá de nuevo

---

## Cómo funciona

**Arquitectura:**

- **App de Windows** (`windows-app/`): app Electron en la bandeja del sistema. Engancha el mouse global con `uiohook-napi`, detecta el halo, abre un servidor TCP y manda comandos de toque/scroll/teclado al Android por WiFi.
  
- **Servicio de Android** (`android-app/`): Servicio de Accesibilidad que convierte los comandos TCP en gestos reales con `dispatchGesture`. También manda el audio de otras apps por TCP en tiempo real.

**¿Por qué WiFi en vez de USB?** USB (scrcpy) requiere que la pantalla del cel se espeje en la PC. PortalDroid funciona con el cel mostrando su *propia* pantalla en un stand al lado, haciéndolo de verdad útil para una mano.

**¿Por qué Servicio de Accesibilidad?** Sin necesidad de root. Es la única forma limpia de producir toques reales sin correr como sistema.

**La magia del mouse:** Un truco del "mouse infinito" devuelve el cursor a un punto central después de cada movimiento, así el mouse nunca se queda sin pantalla. Solo el *delta* se manda al cel y se acumula ahí.

---

## Limitaciones conocidas

- **Click-and-hold:** Funciona. El click derecho no está mapeado todavía.
- **Toques múltiples:** Solo entrada de un dedo por ahora.
- **Sincronización offline:** La info del emparejamiento es local.
- **Batería:** Algunos Android optimizan el proceso de fondo. Podría necesitar desactivar la optimización de batería para la app.

---

## Contribuir

¿Bugs o ideas? Abrí un issue. ¿Código? Fork, rama nueva, y mandá un PR.

Áreas que necesitan ayuda:
- Mapeo de click derecho (¿long-press?)
- IME personalizado de Android para contraseñas
- Manejo elegante de monitores con escalados distintos
- Mensajes de error mejores para problemas de red

---

## Licencia

MIT License. Mirá [LICENSE](LICENSE) para detalles.

---

## Agradecimientos

Construido en una semana de debugging y mediciones intensas. Cada feature (especialmente audio) pasó por varias rondas de búsqueda de edge-cases para funcionar de verdad. Si algo parece sobre-engineered, es probablemente porque se rompió tres veces antes.

Gracias a Alan por las pruebas brutales y los pedidos de features que hicieron esto útil.

---

**Testeado en:** Windows 11 Pro, Android 16, pantallas 2560×1440 + 1920×1080 al 100% de escala.

