# Protocolo PC ↔ Android

Conexión: TCP directo, misma red WiFi. Android escucha, Windows se conecta.

- Puerto por defecto: `7099`
- Formato: una línea de texto = un JSON, separadas por `\n`
- Coordenadas: normalizadas de 0.0 a 1.0 (fracción del ancho/alto real de pantalla del Android). El Android las convierte a píxeles.

## Comandos (PC → Android)

| type   | campos extra   | qué hace |
|--------|-----------------|----------|
| `down` | `x`, `y`        | apoya un dedo virtual en ese punto (arranca un gesto) |
| `move` | `x`, `y`        | mueve el dedo apoyado hasta ese punto (arrastre/scroll) |
| `up`   | (ninguno)       | levanta el dedo, termina el gesto |
| `tap`  | `x`, `y`        | toque corto (equivale a down+up rápido en el mismo punto) |
| `ping` | (ninguno)       | solo para probar que la conexión está viva |
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

El audio del celular viaja por **otro puerto**, no por el 7099. Es a propósito:
son ~1,5 Mbit por segundo, y si compartiera socket con los comandos del mouse,
los movimientos del puntero quedarían haciendo cola atrás del audio y volvería
el lag que tanto costó sacar.

El Android sólo abre el 7100 mientras la transmisión está encendida desde la
app. Si está apagada, la PC reintenta cada 3 segundos y no pasa nada.

Al conectarse, el celular manda **una línea JSON con el formato** y después
PCM crudo sin parar, sin más marcas ni cabeceras:

```
{"sampleRate":48000,"channels":2,"bits":16}
<bytes de PCM 16 bits con signo, little endian, canales intercalados L R L R...>
```

## Respuestas (Android → PC)

No hace falta para que funcione, pero el Android manda una línea de estado al conectarse:

```
{"status":"ready","screenW":1080,"screenH":2400}
```

Windows usa `screenW`/`screenH` solo si quiere loguear algo; los cálculos de coordenadas ya vienen normalizados así que no es obligatorio usarlo.
