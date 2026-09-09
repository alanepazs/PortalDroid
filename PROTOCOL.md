# Protocolo PC ↔ Android

Conexión: TCP directo, misma red WiFi. **La PC escucha, el celular llama.**

- Formato: una línea de texto = un JSON, separadas por `\n`
- Coordenadas: normalizadas de 0.0 a 1.0 (fracción del ancho/alto real de pantalla del Android). El Android las convierte a píxeles.

El celular abre **dos conexiones separadas**: una para los toques y otra para el
audio. Van aparte a propósito: el audio son ~1,5 Mbit por segundo y si
compartiera socket con los comandos del mouse, los movimientos del puntero
quedarían haciendo cola atrás del audio.

## Emparejamiento (QR)

Antes se escribía la IP del Android a mano. Eso se rompía solo cuando cambiaba
el DHCP, y con dos PCs y dos celulares en la misma red no había forma de saber
quién se conecta con quién. Ahora:

1. La PC muestra un QR con un JSON compacto (nombres cortos para que el código
   sea poco denso y fácil de leer con la cámara):

   ```
   {"h":"192.168.0.120","c":7099,"a":7100,"t":"a1b2c3d4e5f6","n":"MI-PC"}
   ```

   | campo | qué es |
   |-------|--------|
   | `h`   | IP de la PC en la red local |
   | `c`   | puerto de toques (por defecto `7099`) |
   | `a`   | puerto de audio (por defecto `7100`) |
   | `t`   | clave de emparejamiento: 12 hex, generada una vez y guardada en la PC |
   | `n`   | nombre de la PC, solo para mostrar |

2. El celular escanea el QR **una sola vez** y lo guarda en disco. Desde ahí
   llama solo a esa PC cada vez que los dos están prendidos en la misma red.

3. La clave `t` es lo que impide que otro en tu WiFi con la app se haga pasar
   por tu celular: sin haber visto tu pantalla, no la tiene.

Si cambia el token de la PC (por ejemplo tras reinstalar), el celular queda con
la clave vieja y hay que volver a escanear el QR.

## Saludo (Android → PC, primera línea de cada conexión)

Apenas se conecta, el celular manda una línea con la clave. Sin clave válida la
PC corta.

**Conexión de toques:**

```
{"token":"a1b2c3d4e5f6","name":"moto g55 5G","screenW":1080,"screenH":2400}
```

**Conexión de audio:**

```
{"token":"a1b2c3d4e5f6","name":"moto g55 5G"}
```

La PC responde en las dos:

```
{"ok":true}
```

o, si la clave no coincide:

```
{"ok":false,"error":"clave incorrecta"}
```

`screenW`/`screenH` los usa Windows solo para loguear; los cálculos de
coordenadas ya vienen normalizados, así que no es obligatorio usarlos.

## Reconexión

El celular reintenta la conexión de toques cada ~2 segundos mientras el
servicio esté activo. Cada intento tiene un límite de 4 segundos: sin eso, un
intento contra una PC apagada se cuelga minutos y el reintento no corre nunca.
Si la PC rechaza la clave, espera 5 segundos antes de volver a probar.

El audio solo se abre mientras la transmisión está encendida desde la app del
celular. Si está apagada, no se abre el 7100 y no pasa nada.

## Latido (PC → Android)

Fuera del modo teléfono, la conexión de toques no manda nada en ninguna
dirección, y una conexión muda la matan el ahorro de energía del WiFi, el
router o cualquier NAT del medio (se medía: se caía sola cada 10-15 segundos).
Para evitarlo, la PC manda cada 4 segundos:

```
{"type":"ping"}
```

El celular los ignora; solo sirven para que el enlace siga vivo y para
enterarse rápido si se cortó de verdad.

## Comandos (PC → Android)

| type   | campos extra   | qué hace |
|--------|-----------------|----------|
| `down` | `x`, `y`        | apoya un dedo virtual en ese punto (arranca un gesto) |
| `move` | `x`, `y`        | mueve el dedo apoyado hasta ese punto (arrastre/scroll) |
| `up`   | (ninguno)       | levanta el dedo, termina el gesto |
| `tap`  | `x`, `y`        | toque corto (equivale a down+up rápido en el mismo punto) |
| `ping` | (ninguno)       | latido de la PC; el celular lo ignora |
| `enter`| (ninguno)       | la PC cruzó el borde: mostrar el puntero en la pantalla del cel |
| `leave`| (ninguno)       | la PC volvió a Windows: esconder el puntero |
| `hover`| `x`, `y`        | mover el puntero **sin tocar** la pantalla |
| `scroll`| `x`, `y`, `dy` | un clic de rueda: deslizada corta y rápida desde `y` hasta `y+dy` |
| `text` | `ch`            | escribir un caracter en el campo de texto enfocado |
| `key`  | `name`          | tecla especial: `backspace`, `enter`, `back`, `home`, `recents` |

En `scroll`, `dy` es cuánto recorre el dedo, normalizado y con signo:
**negativo = el dedo sube = siguiente video**. Si el punto de partida queda muy
pegado a un borde, el Android corre el tramo entero hacia adentro en vez de
recortarlo (un swipe recortado queda demasiado corto y TikTok lo ignora).

Sobre `text`: la API de accesibilidad no puede mandar teclas, solo REEMPLAZAR
el contenido de un campo. Así que el Android lee lo que hay, inserta el
caracter donde está el cursor y reescribe el campo entero. Consecuencias:
- Necesita un campo de texto **enfocado**. Si no hay, el comando se descarta.
- No anda en campos de contraseña ni en apps que dibujan su propio texto
  (juegos, algunos lectores). Ahí no hay un nodo editable que tocar.

`back`, `home` y `recents` son botones del sistema: andan siempre, haya o no
campo de texto.

`hover` no dispara ningún gesto: solo reubica el círculo que se dibuja encima.
Es lo que evita manejar a ciegas — ves dónde vas a caer antes de apretar.
El Android también esconde el puntero solo si se corta el socket, así no queda
clavado en la pantalla cuando la PC se apaga o se va del WiFi.

Ejemplo real de una secuencia (deslizar hacia arriba, como en TikTok):

```
{"type":"enter"}
{"type":"hover","x":0.5,"y":0.5}
{"type":"hover","x":0.5,"y":0.8}
{"type":"down","x":0.5,"y":0.8}
{"type":"move","x":0.5,"y":0.6}
{"type":"move","x":0.5,"y":0.4}
{"type":"move","x":0.5,"y":0.2}
{"type":"up"}
{"type":"leave"}
```

## Canal de audio (puerto 7100, aparte)

Después del saludo con la clave (ver arriba), el celular manda **una línea JSON
con el formato** y a partir de ahí PCM crudo sin parar, sin más marcas ni
cabeceras:

```
{"sampleRate":48000,"channels":2,"bits":16}
<bytes de PCM 16 bits con signo, little endian, canales intercalados L R L R...>
```

Son 48 kHz estéreo. El audio se captura con `AudioPlaybackCapture` (Android
10+), que toma lo que reproducen otras apps sin root. Capturar NO silencia el
parlante del celular, y bajarle el volumen al celular NO baja lo que llega a la
PC: la captura toma el audio antes del control de volumen del dispositivo.
