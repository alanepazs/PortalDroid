const { app, BrowserWindow, screen, globalShortcut, ipcMain, Tray, Menu, nativeImage } = require('electron');
const path = require('path');
const fs = require('fs');
const { uIOhook, UiohookKey } = require('uiohook-napi');
const { mouse, Point } = require('@nut-tree-fork/nut-js');
const QRCode = require('qrcode');
const { PcServer, ipLocal, nuevaClave } = require('./pc-server');
const MON = require('./monitores');

mouse.config.autoDelayMs = 0;
mouse.config.mouseSpeed = 5000; // que el warp del cursor sea instantáneo, no animado

// La consola de Windows arranca con una tabla de caracteres vieja que no
// entiende UTF-8: los acentos salen como "deber├¡a" y el guion largo como
// "ÔÇô". Esto la pasa a UTF-8 antes de imprimir nada.
if (process.platform === 'win32') {
  try {
    require('child_process').execSync('chcp 65001', { stdio: 'ignore' });
  } catch (_) { /* si no se puede, se ven mal los acentos y nada más */ }
}

/**
 * Dónde vive config.json.
 *
 * Corriendo con `npm start`, al lado del código: cómodo para editarlo a mano.
 *
 * Empaquetado en un .exe, el código queda DENTRO del ejecutable y es de sólo
 * lectura, así que ahí no se puede guardar nada. En ese caso el archivo va a
 * la carpeta de datos del usuario, y la primera vez se copia el que viene
 * adentro del .exe como punto de partida.
 */
const CONFIG_BUNDLED = path.join(__dirname, 'config.json');
const CONFIG_PATH = app.isPackaged
  ? path.join(app.getPath('userData'), 'config.json')
  : CONFIG_BUNDLED;

if (app.isPackaged && !fs.existsSync(CONFIG_PATH)) {
  try {
    fs.mkdirSync(path.dirname(CONFIG_PATH), { recursive: true });
    fs.copyFileSync(CONFIG_BUNDLED, CONFIG_PATH);
  } catch (e) {
    console.log('[config] no se pudo crear el archivo de usuario:', e.message);
  }
}

const config = JSON.parse(
  fs.readFileSync(fs.existsSync(CONFIG_PATH) ? CONFIG_PATH : CONFIG_BUNDLED, 'utf-8')
);

/**
 * Guardar los ajustes del usuario.
 *
 * Va con retraso a propósito. Las barras de la ventana de ajustes disparan un
 * cambio por cada píxel que arrastrás: escribir el archivo sesenta veces por
 * segundo castiga al disco sin ninguna ganancia. Se juntan los cambios y se
 * escribe una sola vez cuando parás de mover.
 *
 * El valor en memoria, en cambio, se actualiza al instante: lo que se ve y se
 * siente no depende de que el archivo ya esté en el disco.
 */
let guardadoPendiente = null;

function saveConfig(cambios) {
  Object.assign(config, cambios);
  if (guardadoPendiente) clearTimeout(guardadoPendiente);
  guardadoPendiente = setTimeout(() => {
    guardadoPendiente = null;
    escribirConfig();
  }, 400);
}

/**
 * Igual pero escribe ya, sin esperar.
 * Para lo que no se puede perder si la app se cierra en el medio, como la
 * clave de emparejamiento.
 */
function saveConfigDirecto(cambios) {
  Object.assign(config, cambios);
  if (guardadoPendiente) { clearTimeout(guardadoPendiente); guardadoPendiente = null; }
  escribirConfig();
}

function escribirConfig() {
  try {
    fs.mkdirSync(path.dirname(CONFIG_PATH), { recursive: true });
    fs.writeFileSync(
      CONFIG_PATH,
      JSON.stringify(config, null, 2) + String.fromCharCode(10),
      'utf-8'
    );
  } catch (e) {
    console.log('[config] no se pudo guardar:', e.message);
  }
}

/**
 * Registro en archivo.
 *
 * Empaquetado, la app no tiene ventana de consola: todo lo que se imprime se
 * pierde. Eso la vuelve imposible de diagnosticar, que es justo lo que hace
 * falta cuando algo anda mal y el usuario no está mirando una terminal.
 * Así que además de imprimir, escribimos a un archivo al lado de la config.
 * Se abre desde el menú de la bandeja.
 */
const LOG_PATH = path.join(path.dirname(CONFIG_PATH), 'portaldroid.log');
(function engancharLog() {
  const original = console.log;
  let stream = null;
  try {
    // 'w' y no 'a': arranca limpio en cada sesión. Si no, después de unos
    // días el archivo es tan largo que no sirve para mirar un problema.
    stream = fs.createWriteStream(LOG_PATH, { flags: 'w' });
  } catch (_) { return; }
  console.log = (...args) => {
    original(...args);
    try {
      const hora = new Date().toTimeString().slice(0, 8);
      stream.write(`${hora} ` + args.map(a =>
        typeof a === 'string' ? a : require('util').inspect(a)
      ).join(' ') + String.fromCharCode(10));
    } catch (_) {}
  };
})();

/**
 * Que ningún cierre quede sin explicación.
 *
 * La app se murió una vez sin dejar rastro: el registro cortaba de golpe y no
 * había forma de saber si fue un error, si Windows la mató, o si alguien le
 * dio "Salir" en la bandeja. Todos esos casos se veían igual: silencio.
 * Ahora cada uno deja su línea.
 */
process.on('uncaughtException', (e) => {
  console.log('[fatal] error no atrapado:', e && e.stack ? e.stack : e);
});
process.on('unhandledRejection', (e) => {
  console.log('[fatal] promesa rechazada sin atrapar:', e && e.stack ? e.stack : e);
});
app.on('before-quit', () => {
  console.log('[fin] cerrando (pedido de salida)');
  // Si quedó un guardado esperando su retraso, se escribe ahora: si no, el
  // último ajuste que hiciste antes de salir se perdería.
  if (guardadoPendiente) { clearTimeout(guardadoPendiente); guardadoPendiente = null; escribirConfig(); }
  // Avisarle al celular que nos vamos. Sin esto, el celular se queda
  // reintentando y la notificación de audio le queda puesta hasta que se
  // le acaba la paciencia (30 s) o la cierra a mano.
  try { if (typeof client !== 'undefined' && client.conectado) client.enviar({ type: 'bye' }); } catch (_) {}
});
app.on('render-process-gone', (_e, _wc, det) => {
  console.log(`[fatal] se murió una ventana interna: ${det.reason} (código ${det.exitCode})`);
});
app.on('child-process-gone', (_e, det) => {
  console.log(`[fatal] se murió un proceso hijo: ${det.type} / ${det.reason}`);
});

// Cambiable en caliente desde el menú de la bandeja.
let activeEdge = config.edge || 'right'; // right | left | top | bottom
const THRESH = config.edgeThresholdPx ?? 3;
// Cuánto hay que quedarse quieto sobre el halo antes de cruzar.
// Alan lo puso en 0: cruce inmediato. La contra es que si el halo queda sobre
// una zona de paso (la barra de tareas, por ejemplo) se cruza sin querer; la
// solución en ese caso es mover el halo, no subir este número.
const DWELL_MS = config.dwellMs ?? 250;
const EXIT_NORM = config.exitThresholdNorm ?? 0.02;
// Cuánto se mueve el puntero del celular por cada píxel de mouse.
// Se ajusta en vivo desde la ventana, así que no es constante.
let SENS = config.sensitivity ?? 0.0025;
// Colchón después de entrar al modo teléfono. Sin esto, el retroceso natural
// de la mano justo después de chocar el borde te devuelve a Windows al toque.
const ENTRY_GRACE_MS = config.entryGraceMs ?? 600;
// Cuánto "dedo" recorre un clic de la rueda, como fracción del alto del cel.
// 0.35 = poco más de un tercio de pantalla: alcanza para pasar de video.
const SCROLL_DIST = config.scrollDistance ?? 0.35;
// Si la rueda te mueve al revés de lo que esperás, poné true acá.
const SCROLL_INVERT = config.invertScroll ?? false;
const SCROLL_MIN_MS = config.scrollMinIntervalMs ?? 180;
const KEYBOARD_ENABLED = config.keyboard ?? true;
// Largo del halo como fracción del borde (0.25 = un cuarto de la pantalla).
// Va en fracción y no en píxeles para que se vea igual en cualquier resolución.
let HALO_LENGTH = Math.max(0.05, Math.min(1, config.haloLengthFraction ?? 0.25));

/**
 * DÓNDE va el halo a lo largo del borde, como fracción de 0 a 1.
 * 0 = pegado al principio del borde, 0.5 = al medio, 1 = pegado al final.
 *
 * Para los bordes verticales (izquierdo/derecho): 0 = arriba, 1 = abajo.
 * Para los horizontales (arriba/abajo): 0 = izquierda, 1 = derecha.
 *
 * Combinando borde + posición salen las esquinas: borde "right" con posición 1
 * es la esquina de abajo a la derecha.
 *
 * Acepta palabras o un número suelto, para poder afinarlo (0.8, 0.9, etc).
 */
function haloOffsetFraction() {
  const raw = config.haloPosition ?? 'center';
  if (typeof raw === 'number') return Math.max(0, Math.min(1, raw));
  const n = Number(raw);
  if (!Number.isNaN(n) && String(raw).trim() !== '') return Math.max(0, Math.min(1, n));

  const vertical = (activeEdge === 'right' || activeEdge === 'left');
  const words = vertical
    ? { start: 0, top: 0, arriba: 0, inicio: 0,
        center: 0.5, middle: 0.5, medio: 0.5, centro: 0.5,
        end: 1, bottom: 1, abajo: 1, fin: 1 }
    : { start: 0, left: 0, izquierda: 0, inicio: 0,
        center: 0.5, middle: 0.5, medio: 0.5, centro: 0.5,
        end: 1, right: 1, derecha: 1, fin: 1 };
  const v = words[String(raw).trim().toLowerCase()];
  if (v === undefined) {
    console.log(`[config] haloPosition "${raw}" no se entiende; uso "center"`);
    return 0.5;
  }
  return v;
}
// No es constante: se puede mover en vivo con Ctrl+Alt+RePág / AvPág, así no
// hay que editar el JSON y reiniciar para probar dónde queda mejor.
let haloOffset = haloOffsetFraction();
// Si es true, sólo se cruza donde está dibujado el halo. En false se cruza en
// todo el borde y el halo queda como simple indicador.
const TRIGGER_ON_HALO_ONLY = config.triggerOnHaloOnly ?? true;
// Log de diagnóstico cada vez que el mouse toca el borde.
const EDGE_DEBUG = config.edgeDebug ?? false;
// Color del halo, en #rrggbb. Se elige desde la ventana de ajustes.
// Si es true, el halo se queda visible (tenue) aunque no estés cruzando, para
// que se vea DÓNDE está la puerta. Invisible y chico es imposible de encontrar.
let haloSiempreVisible = config.haloAlwaysVisible ?? false;
let haloColor = /^#[0-9a-fA-F]{6}$/.test(config.haloColor || '') ? config.haloColor : '#50c8ff';

const AUDIO_ENABLED = config.audio ?? true;
const AUDIO_PORT = config.audioPort ?? 7100;
// Colchón de audio: cuánto se junta antes de sonar, y objetivo a mantener.
// Se midieron los baches reales de esta red: mediana 41 ms, pero 1 de cada
// 10 pasa de 172 ms. Con 100 ms de colchón ese 10% se quedaba sin audio; con
// 250 casi no cortaba pero Alan notaba el desfase contra la pantalla del
// celular. 200 es el punto donde quedó: si vuelve a cortar hay que subirlo,
// y si el desfase molesta, bajarlo. Medir con las líneas [red] antes de mover.
const AUDIO_PREFILL_MS = config.audioBufferMs ?? 200;
// Techo del retraso: pasado esto se tira lo más viejo y se vuelve al objetivo.
//
// Estaba en 1500 ms, pero ese número nunca se probó de verdad porque el techo
// no estaba conectado en el worklet (ver audio-worklet.js). Con el control ya
// funcionando, 1500 ms es demasiado: es un segundo y medio de desfase antes de
// que algo reaccione. 600 es el triple del objetivo, así que aguanta los
// baches normales del WiFi sin tirar nada, y corta antes de que el retraso se
// vuelva molesto.
const AUDIO_MAX_MS = config.audioMaxBufferMs ?? 600;
// Cuánto audio se junta antes de pasarlo a la ventana que lo reproduce.
// Es retraso puro: cuanto más chico, menos desfase.
const IPC_BATCH_MS = config.audioBatchMs ?? 20;

let audioVolume = clampVol(config.audioVolume ?? 1.0);
function clampVol(v) { return Math.max(0, Math.min(1.5, v)); }

let haloWindow = null;
let keyWindow = null;
let audioWindow = null;
let audioMuted = false;

// Ojo con esto: Windows maneja dos "reglas" distintas si tenés escalado
// (125%, 150%, etc). El mouse se mueve en píxeles reales de la pantalla,
// pero Electron ubica ventanas en píxeles "escalados". Guardamos las dos.
// Mapa de monitores: cuáles hay y qué bordes de cada uno sirven para cruzar.
// Un borde que da contra otro monitor NO sirve: es por donde el mouse pasa de
// una pantalla a la otra, y poner el halo ahí haría que cruzar de monitor te
// mande al celular.
let pantallas = [];      // el mapa completo, para la ventana de ajustes
let pantalla = null;     // la elegida para el halo
let tramoHalo = { inicio: 0, fin: 0, largoTotal: 0 };

function leerPantallas() {
  pantallas = MON.mapaDePantallas(screen.getAllDisplays(), screen.getPrimaryDisplay().id);
  pantalla = MON.elegirPantalla(pantallas, config.haloDisplay);
  // Si el borde guardado dejó de servir (enchufaste un monitor de ese lado),
  // se pasa al primero que sirva en vez de quedar en un borde inútil.
  if (!pantalla.bordes[activeEdge]) {
    const antes = activeEdge;
    activeEdge = MON.primerBordeUtil(pantalla);
    console.log(`[monitores] el borde "${antes}" ahora da contra otro monitor; uso "${activeEdge}"`);
  }
  recalcularTramo();

  const escalas = new Set(pantallas.map((p) => p.escala));
  if (escalas.size > 1) {
    console.log('[monitores] OJO: hay monitores con escalados distintos. ' +
      'El mouse va en píxeles reales y las ventanas en escalados, así que el ' +
      'halo puede quedar corrido. Sin resolver todavía.');
  }
  for (const p of pantallas) {
    const libres = MON.BORDES.filter((b) => p.bordes[b]).join(', ');
    console.log(`[monitores] ${p.indice + 1}${p.principal ? ' (principal)' : ''}: ` +
      `${p.bounds.width}x${p.bounds.height} en (${p.bounds.x}, ${p.bounds.y}) — bordes libres: ${libres}`);
  }
}

function recalcularTramo() {
  tramoHalo = MON.tramoDelHalo(pantalla, activeEdge, HALO_LENGTH, haloOffset);
}

let mode = 'windows'; // 'windows' | 'phone'
let dwellTimer = null;
let pivot = { x: 0, y: 0 };
let phonePos = { x: 0.5, y: 0.5 }; // posición virtual acumulada dentro de la pantalla del cel
let buttonDown = false;
let enteredAt = 0;   // cuándo entramos al modo teléfono (para el colchón de salida)
let lastMoveSentAt = 0;
const MOVE_INTERVAL_MS = 16; // ~60 envíos por segundo como máximo

/**
 * La clave de emparejamiento. Se genera una vez y se guarda: si cambiara en
 * cada arranque, habría que reescanear el QR todos los días.
 */
if (!config.pairToken) {
  config.pairToken = nuevaClave();
  saveConfigDirecto({ pairToken: config.pairToken });
}

const client = new PcServer({
  controlPort: config.port,
  audioPort: AUDIO_PORT,
  token: config.pairToken,
  onEstado: (que, detalle) => manejarEstado(que, detalle),
  onAudioFormato: (fmt) => audioFormato(fmt),
  onAudioChunk: (buf) => audioChunk(buf),
});

function manejarEstado(que, detalle) {
  switch (que) {
    case 'conectado':
      console.log(`[celular] conectado: ${detalle}`);
      if (!config.pairedOnce) { config.pairedOnce = true; saveConfigDirecto({ pairedOnce: true }); }
      avisarVentana(`Conectado con ${detalle}`, 'ok');
      // Si ya estábamos en modo teléfono cuando se conectó, hay que repetir
      // el 'enter': el que mandamos antes se fue al vacío.
      if (mode === 'phone') { client.enter(); client.hover(phonePos.x, phonePos.y); }
      break;
    case 'desconectado':
      console.log('[celular] desconectado');
      avisarVentana('Se desconectó. Esperando…', '');
      if (mode === 'phone') exitPhoneMode();
      break;
    case 'reemplazado':
      console.log(`[celular] lo reemplazó otro celular (antes: ${detalle})`);
      break;
    case 'rechazado':
      console.log(`[celular] RECHAZADO: "${detalle}" mandó una clave que no es la de esta PC`);
      avisarVentana('Un celular intentó conectarse con una clave incorrecta', 'mal');
      break;
    case 'audio-conectado': console.log('[audio] el celular empezó a mandar audio'); break;
    case 'audio-desconectado': console.log('[audio] el celular dejó de mandar audio'); break;
    case 'error':
      console.log(`[red] ${detalle}`);
      avisarVentana(detalle, 'mal');
      break;
  }
  buildTrayMenu();
}

function createHaloWindow() {
  const bounds = haloBoundsForEdge(activeEdge);
  haloWindow = new BrowserWindow({
    ...bounds,
    // Arranca oculta desde el constructor. Si la creás visible y después
    // llamás hide(), en Windows queda un parpadeo blanco al iniciar.
    show: false,
    frame: false,
    transparent: true,
    // Sin esto, una ventana transparente en Windows a veces se dibuja opaca
    // o directamente no se dibuja. Los dos primeros dígitos son el alfa: 00 = invisible.
    backgroundColor: '#00000000',
    alwaysOnTop: true,
    skipTaskbar: true,
    focusable: false,
    resizable: false,
    hasShadow: false,
    // 'toolbar' evita que Windows la trate como ventana normal (nada de
    // Alt+Tab, no roba el foco, no aparece en la barra de tareas).
    type: 'toolbar',
    webPreferences: { contextIsolation: true },
  });
  // 'screen-saver' es el nivel más alto: queda arriba incluso de apps
  // en pantalla completa. Con alwaysOnTop a secas, un juego o un video
  // en fullscreen te tapa el halo.
  haloWindow.setAlwaysOnTop(true, 'screen-saver');
  haloWindow.setVisibleOnAllWorkspaces(true);
  haloWindow.setIgnoreMouseEvents(true);
  haloWindow.loadFile('halo.html', { query: { edge: activeEdge, color: haloColor } });
  const span = haloSpanReal();
  console.log(
    `Halo: ${bounds.width}x${bounds.height} px en (${bounds.x}, ${bounds.y}) ` +
    `— ${Math.round(HALO_LENGTH * 100)}% del borde "${activeEdge}", posición ${haloOffset}`
  );
  console.log(
    TRIGGER_ON_HALO_ONLY
      ? `Se cruza SÓLO sobre el halo (del ${span.start} al ${span.end} del borde).`
      : 'Se cruza en cualquier parte del borde; el halo es sólo indicador.'
  );
}

/**
 * Vigila que el halo siga a la vista cuando pediste que esté siempre visible.
 *
 * Alan lo reportó dos veces: el halo estaba, hacía clic y desaparecía. Una
 * ventana sin foco y sin barra de tareas se la puede llevar puesta otra que
 * pase a pantalla completa o que también se declare siempre arriba. Como no
 * hay aviso de eso, se revisa cada tres segundos y se la vuelve a subir.
 *
 * Sólo actúa si de verdad se escondió: volver a llamar a show() cuando ya está
 * visible le pelea el orden de ventanas al que estés usando.
 */
function vigilarHalo() {
  setInterval(() => {
    if (!haloWindow || haloWindow.isDestroyed()) return;
    const deberiaVerse = (mode === 'phone' || haloSiempreVisible);
    if (deberiaVerse && !haloWindow.isVisible()) {
      console.log('[halo] se había escondido solo; lo vuelvo a mostrar');
      aplicarVisibilidadHalo();
    }
  }, 3000);
}

/**
 * Ventana transparente a pantalla completa que captura TECLADO y RUEDA
 * mientras estás en modo teléfono. Invisible: no dibuja nada.
 *
 * Por qué no leemos estas dos cosas con uiohook, que ya lo tenemos:
 *
 * - RUEDA: uiohook, en esta máquina, devuelve siempre `rotation: 0`. No sabe
 *   para qué lado gira la rueda; ese dato simplemente no viene. Se verificó
 *   generando scrolls arriba y abajo: los eventos salen idénticos.
 *
 * - TECLADO: uiohook da CÓDIGOS de tecla en crudo, no letras. Traducirlos
 *   significa reimplementar la distribución del teclado a mano — y con el
 *   teclado español eso es la ñ, los acentos, las teclas muertas. Un quilombo.
 *
 * Chromium resuelve las dos cosas bien y respetando la configuración de
 * Windows: `deltaY` viene con signo, y `event.key` ya viene con la letra.
 *
 * Efectos de regalo, los dos deseables: mientras esta ventana está arriba, ni
 * lo que tipeás ni lo que clickeás le llega a la app de Windows que quedó
 * atrás. Si estás manejando el cel, no querés escribir en el Word de fondo.
 */
function createKeyWindow() {
  keyWindow = new BrowserWindow({
    // Chica y puesta donde está el cursor, NO a pantalla completa.
    //
    // Tiene que estar bajo el cursor porque los eventos de rueda le llegan a
    // la ventana que está abajo del puntero, no a la que tiene el foco. Pero
    // a pantalla completa rompía el video: una ventana transparente y siempre
    // arriba hace que Windows y Chrome abandonen la vía de hardware con la
    // que dibujan video, y YouTube quedaba en negro al cruzar.
    //
    // En modo teléfono el cursor se devuelve al pivote en cada movimiento, así
    // que vive prácticamente clavado ahí. Con 160x160 hay 80 px de margen para
    // los instantes entre que el mouse se mueve y lo devolvemos.
    width: TAM_CAPTURADOR, height: TAM_CAPTURADOR,
    x: 0, y: 0,
    show: false,
    frame: false,
    transparent: true,
    skipTaskbar: true,
    resizable: false,
    hasShadow: false,
    alwaysOnTop: true,
    webPreferences: {
      // Es un archivo local nuestro, no carga nada de internet: acá
      // nodeIntegration es seguro y evita tener que sumar un preload aparte.
      nodeIntegration: true,
      contextIsolation: false,
    },
  });
  keyWindow.loadFile('input.html');
  // El halo tiene que quedar POR ENCIMA de este capturador, si no se tapa.
  keyWindow.setAlwaysOnTop(true, 'floating');
}

/**
 * Ventana oculta que sólo reproduce el audio que llega del celular.
 *
 * Es una ventana y no código de Node porque quien sabe hacer sonar audio es
 * Chromium (Web Audio), no el proceso principal de Electron. No se ve nunca.
 *
 * Esto suena como suena cualquier programa de Windows: el sistema lo mezcla
 * con lo que ya estés escuchando. No cambiamos tu salida de sonido ni pedimos
 * acceso exclusivo a la placa.
 */
function createAudioWindow() {
  audioWindow = new BrowserWindow({
    width: 1, height: 1, show: false, frame: false, skipTaskbar: true,
    webPreferences: {
      nodeIntegration: true,
      contextIsolation: false,
      // Sin esto, Chromium exige que el usuario haga clic en algo antes de
      // dejar sonar audio. Acá no hay nada que clickear: la ventana es invisible.
      autoplayPolicy: 'no-user-gesture-required',
      // Chromium ralentiza las ventanas ocultas. Esta tiene que seguir
      // atendiendo audio a tiempo aunque nunca se muestre.
      backgroundThrottling: false,
    },
  });
  audioWindow.loadFile('audio.html');
}

/**
 * Llega la cabecera con el formato del audio. Ahora la manda el celular al
 * conectarse, no la pedimos nosotros.
 */
function audioFormato(fmt) {
  console.log(`[audio] formato: ${fmt.sampleRate} Hz, ${fmt.channels} canales`);
  pendingAudio = [];
  pendingBytes = 0;
  batchBytes = Math.round(fmt.sampleRate * fmt.channels * 2 * IPC_BATCH_MS / 1000);
  if (!audioWindow || audioWindow.isDestroyed()) return;
  audioWindow.webContents.send('tb-audio-format', {
    ...fmt, prefillMs: AUDIO_PREFILL_MS, maxMs: AUDIO_MAX_MS,
  });
  sendVolume();
}

function audioChunk(buf) {
  if (!audioWindow || audioWindow.isDestroyed()) return;
  medirLlegada(buf.length);
  // El celular manda de a ~10 ms. Cruzar al proceso de la ventana 100 veces
  // por segundo tiene su costo y llega a los tirones, así que juntamos varios.
  // OJO: esto es retraso puro que se suma al colchón, por eso son 20 ms y no
  // 50 como al principio.
  pendingAudio.push(buf);
  pendingBytes += buf.length;
  if (pendingBytes < batchBytes) return;
  const merged = Buffer.concat(pendingAudio, pendingBytes);
  pendingAudio = [];
  pendingBytes = 0;
  audioWindow.webContents.send('tb-audio-chunk', merged);
}

/**
 * Mide CÓMO llega el audio por la red, no cuánto.
 *
 * Sirve para separar dos culpables que se ven igual desde la PC:
 *   - si aparecen huecos grandes seguidos de golpes de datos, el que se atasca
 *     es el celular (o el WiFi) y hay que arreglarlo allá;
 *   - si llega parejo pero igual se acumula, el que no consume a tiempo es
 *     esta PC y hay que arreglarlo acá.
 */
let llegadaUltima = 0;
let llegadaHuecoMax = 0;
let llegadaBytes = 0;
let llegadaDesde = Date.now();

function medirLlegada(bytes) {
  const ahora = Date.now();
  if (llegadaUltima) {
    const hueco = ahora - llegadaUltima;
    if (hueco > llegadaHuecoMax) llegadaHuecoMax = hueco;
  }
  llegadaUltima = ahora;
  llegadaBytes += bytes;

  const transcurrido = ahora - llegadaDesde;
  if (transcurrido < 5000) return;
  // A 48 kHz estéreo de 16 bits son 192000 bytes por segundo. Si llega menos,
  // el celular se está quedando corto; si llega más, mandó un golpe atrasado.
  const esperado = 192000 * transcurrido / 1000;
  const porcentaje = Math.round(llegadaBytes / esperado * 100);
  console.log(
    `[red] en ${Math.round(transcurrido / 1000)}s llegó el ${porcentaje}% del audio esperado, ` +
    `hueco más largo ${llegadaHuecoMax} ms`
  );
  llegadaHuecoMax = 0;
  llegadaBytes = 0;
  llegadaDesde = ahora;
}

/** Muestras a milisegundos. 48000 muestras = 1 segundo. */
function msDe(frames) { return Math.round(frames / 48); }

let pendingAudio = [];
let pendingBytes = 0;
let batchBytes = 3840; // ~20 ms a 48 kHz estéreo; se recalcula con el formato

function sendVolume() {
  if (!audioWindow || audioWindow.isDestroyed()) return;
  audioWindow.webContents.send('tb-audio-volume', audioMuted ? 0 : audioVolume);
}

function changeVolume(delta) {
  audioVolume = clampVol(Math.round((audioVolume + delta) * 100) / 100);
  // Subir el volumen mientras está silenciado da a entender que querés oírlo.
  if (audioMuted && delta > 0) audioMuted = false;
  sendVolume();
  const pct = Math.round(audioVolume * 100);
  console.log(`[audio] volumen ${pct}%${audioMuted ? ' (silenciado)' : ''}`);
}

/**
 * Mueve el halo a lo largo del borde, en vivo.
 *
 * Existe porque probar dónde queda mejor editando el JSON y reiniciando es
 * insufrible: no ves el resultado hasta después de arrancar de nuevo. Al
 * moverlo se enciende solo un rato para que veas dónde quedó, y se imprime
 * el número exacto para dejarlo fijo en config.json.
 */
let haloPeekTimer = null;
let haloKeyBack = null;
let haloKeyFwd = null;
let volUpKey = null;
let volDownKey = null;
const TAM_CAPTURADOR = 160;
let tray = null;
let ventana = null;

/**
 * La ventana única: ajustes del halo y conexión por QR, en dos pestañas.
 *
 * Antes eran dos ventanas separadas y dos entradas en el menú de la bandeja.
 * Se juntaron porque son lo mismo desde el punto de vista de quien la usa:
 * "configurar PortalDroid". El menú de la bandeja quedó para lo que de
 * verdad es una acción suelta.
 */
async function mostrarVentana() {
  if (ventana && !ventana.isDestroyed()) { ventana.show(); ventana.focus(); return; }
  ventana = new BrowserWindow({
    width: 560, height: 700, resizable: true, maximizable: false,
    title: 'PortalDroid', autoHideMenuBar: true,
    backgroundColor: '#12151a',
    webPreferences: { nodeIntegration: true, contextIsolation: false },
  });
  ventana.on('closed', () => { ventana = null; });
  await ventana.loadFile('settings.html');
  enviarEstadoAjustes();
  await mandarQR();
}

/** Arma el QR con la dirección de esta PC y la clave de emparejamiento. */
async function mandarQR() {
  if (!ventana || ventana.isDestroyed()) return;
  const ip = ipLocal();
  if (!ip) {
    avisarVentana('No encontré la red. ¿Estás conectado a WiFi o cable?', 'mal');
    return;
  }
  // Compacto a propósito: cuanto más corto el texto, menos denso el QR y más
  // fácil de leer con la cámara.
  const carga = JSON.stringify({
    h: ip, c: config.port, a: AUDIO_PORT, t: config.pairToken, n: require('os').hostname(),
  });
  const imagen = await QRCode.toDataURL(carga, { width: 480, margin: 1 });
  ventana.webContents.send('qr', { imagen, ip });
  if (client.conectado) avisarVentana(`Conectado con ${client.celular.nombre}`, 'ok');
}

function avisarVentana(texto, tipo) {
  if (ventana && !ventana.isDestroyed()) {
    ventana.webContents.send('conexion', { texto, tipo });
  }
}

function enviarEstadoAjustes() {
  if (!ventana || ventana.isDestroyed()) return;
  ventana.webContents.send('estado', {
    pantallas: pantallas.map((p) => ({ ...p, elegida: p.clave === pantalla.clave })),
    borde: activeEdge,
    posicion: haloOffset,
    largo: HALO_LENGTH,
    color: haloColor,
    siempreVisible: haloSiempreVisible,
    sensibilidad: SENS,
  });
}

function buildTrayMenu() {
  if (!tray) return;
  tray.setContextMenu(Menu.buildFromTemplate([
    { label: 'PortalDroid', enabled: false },
    { type: 'separator' },
    {
      label: client.conectado
        ? `Celular: ${client.celular.nombre}`
        : 'Sin celular conectado',
      enabled: false,
    },
    { type: 'separator' },
    {
      label: 'Halo siempre visible',
      type: 'checkbox',
      checked: haloSiempreVisible,
      click: () => {
        haloSiempreVisible = !haloSiempreVisible;
        saveConfig({ haloAlwaysVisible: haloSiempreVisible });
        aplicarVisibilidadHalo();
        buildTrayMenu();
        enviarEstadoAjustes();
      },
    },
    { label: 'Mostrar el halo un momento', click: () => peekHalo() },
    {
      label: 'Volver a Windows (Ctrl+Alt+W)',
      enabled: mode === 'phone',
      click: () => { if (mode === 'phone') exitPhoneMode(); },
    },
    { type: 'separator' },
    {
      label: 'Abrir el registro (para diagnosticar)',
      click: () => require('electron').shell.openPath(LOG_PATH),
    },
    { label: 'Salir', click: () => app.quit() },
  ]));
}

function createTray() {
  const icono = nativeImage.createFromPath(path.join(__dirname, 'tray-icon.png'));
  tray = new Tray(icono);
  tray.setToolTip('PortalDroid');
  // Doble clic en el ícono: abre la ventana con los ajustes y el QR. Es la
  // única forma de abrirla, a pedido de Alan: el menú del botón derecho quedó
  // sólo para acciones sueltas.
  tray.on('double-click', () => mostrarVentana());
  buildTrayMenu();
}

/** Mueve el halo de a poco con el teclado. La bandeja da saltos fijos. */
function moveHalo(delta) {
  setHaloPosition(haloOffset + delta);
}

/**
 * Mueve el halo a lo largo del borde. `v` va de 0 (principio) a 1 (final).
 *
 * Guarda, reubica la ventana y recalcula el tramo. El tramo va aparte del
 * tamaño de la ventana a propósito: el mouse se mide en píxeles reales y las
 * ventanas se ubican en escalados. Mezclarlos ya fue un bug de este proyecto.
 */
function setHaloPosition(v) {
  haloOffset = Math.max(0, Math.min(1, v));
  saveConfig({ haloPosition: haloOffset });
  recalcularTramo();
  if (haloWindow && !haloWindow.isDestroyed()) {
    haloWindow.setBounds(haloBoundsForEdge(activeEdge));
  }
  if (haloSiempreVisible) aplicarVisibilidadHalo(); else peekHalo();
  buildTrayMenu();
  enviarEstadoAjustes();
}

/**
 * Muestra el halo un momento y lo vuelve a esconder.
 *
 * Hace falta porque el halo es invisible hasta que lo pisás con el mouse: sin
 * esto estarías moviendo a ciegas algo que no se ve, y no habría manera de
 * saber dónde quedó salvo yendo a buscarlo con el cursor.
 */
let peekTimer = null;
function peekHalo(ms = 1400) {
  if (!haloWindow || haloWindow.isDestroyed()) return;
  showHalo();
  marcarHaloActivo(false);
  if (peekTimer) clearTimeout(peekTimer);
  peekTimer = setTimeout(() => {
    peekTimer = null;
    // No se esconde a la fuerza: si mientras tanto cruzaste al celular o
    // prendiste "siempre visible", tiene que quedarse.
    aplicarVisibilidadHalo();
  }, ms);
}

function showHalo() {
  // showInactive en vez de show: muestra la ventana SIN darle el foco.
  // Con show() normal, Windows le saca el foco a lo que estabas usando.
  haloWindow.showInactive();
  haloWindow.setAlwaysOnTop(true, 'screen-saver');
}

/** Prende o apaga el latido del halo (tenue en reposo, a pleno al cruzar). */
function marcarHaloActivo(activo) {
  if (!haloWindow || haloWindow.isDestroyed()) return;
  haloWindow.webContents
    .executeJavaScript(`document.body.classList.toggle('activo', ${!!activo})`)
    .catch(() => {});
}

/**
 * Deja el halo como corresponde al estado actual.
 * En modo teléfono siempre se ve. Fuera, depende del ajuste.
 */
function aplicarVisibilidadHalo() {
  if (!haloWindow || haloWindow.isDestroyed()) return;
  if (mode === 'phone' || haloSiempreVisible) {
    showHalo();
    marcarHaloActivo(mode === 'phone');
    console.log(`[halo] visible (${mode === 'phone' ? 'a pleno' : 'tenue'}), ` +
      `¿la ventana está visible?: ${haloWindow.isVisible()}`);
  } else {
    haloWindow.hide();
    console.log('[halo] oculto');
  }
}

function haloBoundsForEdge(edge) {
  // 40 y no 16: la barra en sí es fina, pero el resplandor se dibuja HACIA
  // AFUERA de la barra. Si la ventana mide justo lo que la barra, el glow
  // queda recortado y el halo se ve como una raya plana.
  const GROSOR = 40;
  return MON.ventanaDelHalo(pantalla, edge, HALO_LENGTH, haloOffset, GROSOR);
}

/**
 * El tramo del borde donde vive el halo, en píxeles REALES (no escalados).
 * Se calcula aparte del tamaño de la ventana porque el mouse se mueve en
 * píxeles reales y las ventanas se ubican en escalados: mezclarlos fue el
 * bug 5 de este proyecto y no queremos repetirlo.
 */
/** El tramo del halo a lo largo del borde, para saber dónde se puede cruzar. */
function haloSpanReal() {
  recalcularTramo();
  return { start: tramoHalo.inicio, end: tramoHalo.fin };
}

function isAtTriggerEdge(x, y) {
  return MON.estaEnElHalo(pantalla, activeEdge, tramoHalo, x, y, THRESH, TRIGGER_ON_HALO_ONLY);
}

/**
 * Punto al que se devuelve el cursor real en cada movimiento (mouse infinito).
 *
 * Va al CENTRO del monitor, no pegado al borde.
 *
 * Antes se quedaba a 2 píxeles del borde por el que cruzabas, y eso rompía el
 * movimiento en esa dirección: con el halo abajo, empujar el mouse hacia abajo
 * daba 2 píxeles de recorrido antes de chocar contra el fin de la pantalla,
 * mientras que hacia arriba había 1080 libres. El puntero del celular subía
 * rápido y bajaba arrastrándose. Se sentía como lag, pero era falta de lugar.
 *
 * Desde el centro hay margen parejo para los cuatro lados.
 */
function pivotFor(_x, _y) {
  const b = pantalla.bounds;
  return {
    x: Math.round(b.x + b.width / 2),
    y: Math.round(b.y + b.height / 2),
  };
}

/**
 * ¿Hay que volver a Windows?
 *
 * La regla es: se vuelve moviendo el mouse HACIA ATRÁS, en el sentido
 * contrario al que usaste para cruzar. Si entraste al celular empujando hacia
 * abajo, volvés tirando hacia arriba.
 *
 * Los bordes de arriba y abajo estaban al revés: se salía en la MISMA
 * dirección en la que se entraba. Como al cruzar por abajo tu mano ya viene
 * bajando, seguir bajando un poco —lo más natural— te expulsaba a los pocos
 * segundos. No se había notado porque hasta ahora el halo siempre estuvo en
 * el borde derecho, donde la lógica sí era correcta.
 */
function shouldExit() {
  // Colchón: recién después de ENTRY_GRACE_MS dejamos que el gesto de salida cuente.
  if (Date.now() - enteredAt < ENTRY_GRACE_MS) return false;
  switch (activeEdge) {
    case 'right': return phonePos.x <= EXIT_NORM;          // entraste yendo a la derecha
    case 'left': return phonePos.x >= 1 - EXIT_NORM;       // entraste yendo a la izquierda
    case 'top': return phonePos.y >= 1 - EXIT_NORM;        // entraste subiendo: volvés bajando
    case 'bottom': return phonePos.y <= EXIT_NORM;         // entraste bajando: volvés subiendo
  }
}

async function warpTo(p) {
  await mouse.setPosition(new Point(p.x, p.y));
}

function enterPhoneMode(x, y) {
  const t0 = Date.now();
  mode = 'phone';
  phonePos = { x: 0.5, y: 0.5 };
  enteredAt = Date.now();
  pivot = pivotFor(x, y);
  warpTo(pivot);
  // Ya no se llama a nadie: la PC escucha y el celular es el que llamó. Si
  // todavía no hay celular conectado, esto no hace nada y el halo igual se
  // muestra; cuando el celular aparezca, manejarEstado() repite el 'enter'.
  client.enter();
  client.hover(phonePos.x, phonePos.y);
  // El capturador se pone adelante, centrado en el pivote: ahí es donde vive
  // el cursor mientras estás en modo teléfono.
  if (keyWindow) {
    keyWindow.setBounds({
      x: pivot.x - TAM_CAPTURADOR / 2,
      y: pivot.y - TAM_CAPTURADOR / 2,
      width: TAM_CAPTURADOR, height: TAM_CAPTURADOR,
    });
    keyWindow.show();
  }
  // Después el halo, para que quede por encima del capturador.
  showHalo();
  marcarHaloActivo(true);
  buildTrayMenu();   // habilita "Volver a Windows" en la bandeja
  const extra = cancelaciones > 0
    ? ` | SE TRABÓ: perdió la zona ${cancelaciones} veces en ${desdeElPrimerContacto} ms, ` +
      `última salida en ${ultimaSalida}`
    : '';
  console.log(`[modo] -> phone (esperó ${esperaEnElBorde} ms sobre el halo, ` +
    `armar todo ${Date.now() - t0} ms)${extra}`);
  cancelaciones = 0;
}

function exitPhoneMode() {
  mode = 'windows';
  if (buttonDown) { client.up(); buttonDown = false; }
  client.leave();
  marcarHaloActivo(false);
  if (!haloSiempreVisible) haloWindow.hide();
  // Al esconderla, Windows le devuelve el foco a la app que estabas usando.
  if (keyWindow && keyWindow.isVisible()) keyWindow.hide();
  // reubicar el cursor real justo adentro del borde, para que Windows lo recupere ahí
  const inside = insidePoint();
  warpTo(inside);
  buildTrayMenu();
  console.log('[modo] -> windows');
}

/**
 * Dónde dejar el cursor al volver a Windows: en el medio del halo, justo
 * adentro del borde.
 *
 * Antes usaba la coordenada del pivote. Eso servía cuando el pivote estaba
 * pegado al borde por el que cruzabas, pero al mudarlo al centro del monitor
 * (ver pivotFor) el cursor empezó a volver al centro de la pantalla, lejos
 * del halo. Salías por un lado y aparecías en otro.
 *
 * Ahora vuelve por la misma puerta por la que se fue.
 */
function insidePoint() {
  const b = pantalla.bounds;
  const M = THRESH + 20;
  const centroDelHalo = Math.round((tramoHalo.inicio + tramoHalo.fin) / 2);
  switch (activeEdge) {
    case 'right': return { x: b.x + b.width - M, y: b.y + centroDelHalo };
    case 'left': return { x: b.x + M, y: b.y + centroDelHalo };
    case 'top': return { x: b.x + centroDelHalo, y: b.y + M };
    case 'bottom': return { x: b.x + centroDelHalo, y: b.y + b.height - M };
  }
}

// Diagnóstico del cruce. Sirve para separar dos causas que se ven igual desde
// afuera: que uiohook no esté viendo el mouse (problema de foco de Windows) o
// que lo vea pero estemos fuera de la zona del halo.
let lastEdgeLogAt = 0;
function logEdgeApproach(x, y) {
  if (!EDGE_DEBUG) return;
  const now = Date.now();
  if (now - lastEdgeLogAt < 700) return;
  lastEdgeLogAt = now;
  const vertical = (activeEdge === 'right' || activeEdge === 'left');
  const along = vertical ? y : x;
  const span = haloSpanReal();
  const inside = along >= span.start && along <= span.end;
  console.log(
    `[borde] mouse en (${x}, ${y}) — a lo largo del borde: ${along}, ` +
    `halo va de ${span.start} a ${span.end} → ${inside ? 'DENTRO, debería cruzar' : 'FUERA del halo'}`
  );
}

function onMouseMove(e) {
  if (mode === 'windows') {
    if (EDGE_DEBUG) {
      const b = pantalla.bounds;
      const dentroDeLaPantalla =
        e.x >= b.x && e.x <= b.x + b.width && e.y >= b.y && e.y <= b.y + b.height;
      if (dentroDeLaPantalla) logEdgeApproach(e.x, e.y);
    }
    if (isAtTriggerEdge(e.x, e.y)) {
      if (!dwellTimer) {
        // Se guarda cuándo tocamos el borde por PRIMERA vez de la racha, no
        // la última: si no, un cruce que costó un segundo se reportaría como
        // instantáneo y el número mentiría.
        if (!primerContacto) primerContacto = Date.now();
        const tocado = Date.now();
        dwellTimer = setTimeout(() => {
          esperaEnElBorde = Date.now() - tocado;
          desdeElPrimerContacto = Date.now() - primerContacto;
          primerContacto = 0;
          dwellTimer = null;
          enterPhoneMode(e.x, e.y);
        }, DWELL_MS);
      }
    } else {
      if (dwellTimer) {
        clearTimeout(dwellTimer);
        dwellTimer = null;
        cancelaciones++;
        // Dónde estaba el mouse al salirse: es el dato que dice POR QUÉ se
        // perdió la zona.
        ultimaSalida = `(${e.x}, ${e.y})`;
      }
      // Si se alejó de verdad del borde, se corta la racha.
      if (primerContacto && Date.now() - primerContacto > 1500) primerContacto = 0;
    }
    return;
  }

  // mode === 'phone': truco de "mouse infinito"
  const dx = e.x - pivot.x;
  const dy = e.y - pivot.y;
  if (dx === 0 && dy === 0) return;

  phonePos.x = clamp01(phonePos.x + dx * SENS);
  phonePos.y = clamp01(phonePos.y + dy * SENS);
  warpTo(pivot); // reseteamos el cursor real al pivote, así nunca "choca" contra el borde

  // Frenamos el envío a ~60 por segundo: si mandamos un gesto por CADA
  // mousemove (pueden ser cientos por segundo), Android los amontona y
  // el swipe se ve entrecortado en vez de fluido.
  const now = Date.now();
  if (now - lastMoveSentAt >= MOVE_INTERVAL_MS) {
    lastMoveSentAt = now;
    // Con el botón apretado es un arrastre de verdad; sin apretar es solo
    // el puntero moviéndose, para que veas dónde estás parado antes de tocar.
    if (buttonDown) client.move(phonePos.x, phonePos.y);
    else client.hover(phonePos.x, phonePos.y);
  }

  if (!buttonDown && shouldExit()) exitPhoneMode();
}

function clamp01(v) { return Math.max(0, Math.min(1, v)); }

let lastScrollAt = 0;
let esperaEnElBorde = 0;
// Para cazar el cruce que "se traba": cuántas veces se perdió la zona antes
// de entrar, y dónde estaba el mouse la última vez que se salió.
let primerContacto = 0;
let desdeElPrimerContacto = 0;
let cancelaciones = 0;
let ultimaSalida = '';
let loggedWheel = false;

function onWheelFromWindow(payload) {
  if (mode !== 'phone') return;

  const now = Date.now();
  if (now - lastScrollAt < SCROLL_MIN_MS) return;
  lastScrollAt = now;

  // deltaY > 0 = rueda hacia abajo (hacia vos) = querés el video siguiente.
  // El dedo tiene que ir hacia ARRIBA para eso, y arriba es y negativo.
  let dy = (payload.dy > 0 ? -1 : 1) * SCROLL_DIST;
  if (SCROLL_INVERT) dy = -dy;

  if (!loggedWheel) {
    loggedWheel = true;
    console.log(`[rueda] andando (deltaY=${payload.dy}). Si va al revés, poné "invertScroll": true`);
  }
  client.scroll(phonePos.x, phonePos.y, dy);
}

function onKeyFromWindow(payload) {
  if (mode !== 'phone') return;
  if (payload.special) client.key(payload.special);
  else if (payload.ch) client.text(payload.ch);
}

function onMouseDown(e) {
  if (mode !== 'phone') return;
  if (e.button !== 1) return; // solo botón izquierdo (1 = izquierdo en uiohook)
  buttonDown = true;
  client.down(phonePos.x, phonePos.y);
}

function onMouseUp(e) {
  if (mode !== 'phone') return;
  if (e.button !== 1) return;
  if (buttonDown) {
    buttonDown = false;
    // mandamos la posición final por si el throttle se había frenado
    // un poco antes del soltado, así el gesto termina en el punto exacto
    client.move(phonePos.x, phonePos.y);
    client.up();
  }
}

// Una sola instancia a la vez. Si corrés npm start dos veces (fácil: la app no
// tiene ventana visible, así que no se nota que ya estaba abierta), las dos
// enganchan el mouse y las dos tratan de mover el cursor. Se pelean y el modo
// teléfono queda inutilizable, sin ningún mensaje de error que lo explique.
if (!app.requestSingleInstanceLock()) {
  console.log('PortalDroid ya estaba corriendo. Esta segunda copia se cierra.');
  console.log('Para cerrar la que está abierta: Ctrl+Alt+Shift+Q');
  app.quit();
  process.exit(0);
}

/**
 * Registra un atajo y AVISA si no se pudo.
 *
 * globalShortcut.register devuelve false cuando otra app ya tiene tomada esa
 * combinación. Sin mirar ese resultado, el atajo no hace nada y no hay forma
 * de saber por qué: parece que la función está rota cuando en realidad nunca
 * llegó a registrarse.
 */
function bind(accel, fn, what) {
  const ok = globalShortcut.register(accel, fn);
  if (!ok) console.log(`[atajo] ${accel} (${what}) NO se pudo registrar: otra app lo tiene tomado`);
  return ok;
}

/** Prueba varias combinaciones y se queda con la primera que enganche. */
function bindFirst(accels, fn, what) {
  for (const a of accels) {
    if (globalShortcut.register(a, fn)) return a;
  }
  console.log(`[atajo] ninguna combinación libre para ${what}: probé ${accels.join(', ')}`);
  return null;
}

app.whenReady().then(() => {
  leerPantallas(); // recién acá Electron deja preguntar por las pantallas
  createHaloWindow();
  vigilarHalo();
  createTray();
  // Este capturador hace falta para la rueda SIEMPRE; el teclado se puede
  // apagar por config, pero la ventana se crea igual.
  createKeyWindow();
  ipcMain.on('tb-wheel', (_evt, payload) => onWheelFromWindow(payload));

  // Pedido desde la pestaña de QR: redibujarlo con la IP actual. Hace falta
  // porque el QR se arma una sola vez al abrir la ventana; si el DHCP le dio
  // otra IP a la PC después (por ejemplo, reconectaste el WiFi), el código
  // que se ve queda apuntando a una dirección vieja y el celular no la
  // encuentra más, aunque siga emparejado. No cambia la clave: sólo
  // recalcula IP y vuelve a dibujar el mismo QR de siempre.
  ipcMain.on('refrescar-qr', () => mandarQR());

  // La ventana mide su propio contenido y pide el alto que necesita. Así no
  // queda una barra de desplazamiento ni hay que adivinar una altura fija que
  // después no sirve en otra pantalla o con otra cantidad de monitores.
  ipcMain.on('ajustar-ventana', (_evt, { alto }) => {
    if (!ventana || ventana.isDestroyed()) return;
    const [ancho] = ventana.getContentSize();
    const pantallaDeLaVentana = screen.getDisplayMatching(ventana.getBounds());
    const tope = pantallaDeLaVentana.workArea.height - 40;
    ventana.setContentSize(ancho, Math.min(Math.max(alto, 300), tope));
  });

  // Cambios desde la ventana de ajustes. Se aplican en el momento y se
  // guardan: probar a ciegas y reiniciar para ver el resultado es insufrible.
  ipcMain.on('ajuste', (_evt, a) => {
    if (a.pantalla && a.borde) {
      const nueva = MON.elegirPantalla(pantallas, a.pantalla);
      if (!nueva.bordes[a.borde]) return;   // borde pegado a otro monitor
      pantalla = nueva;
      activeEdge = a.borde;
      saveConfig({ haloDisplay: pantalla.clave, edge: activeEdge });
      haloWindow.loadFile('halo.html', { query: { edge: activeEdge, color: haloColor } });
      console.log(`[halo] monitor ${pantalla.indice + 1}, borde ${activeEdge}`);
    }
    if (typeof a.posicion === 'number') {
      haloOffset = Math.max(0, Math.min(1, a.posicion));
      saveConfig({ haloPosition: haloOffset });
    }
    if (typeof a.largo === 'number') {
      HALO_LENGTH = Math.max(0.05, Math.min(1, a.largo));
      saveConfig({ haloLengthFraction: HALO_LENGTH });
    }
    if (typeof a.sensibilidad === 'number') {
      // Los extremos son a ojo pero acotados: con 0.001 hacen falta 1000 px de
      // mouse para cruzar la pantalla del celular, y con 0.008 apenas 125.
      SENS = Math.max(0.001, Math.min(0.008, a.sensibilidad));
      saveConfig({ sensitivity: SENS });
    }
    if (typeof a.siempreVisible === 'boolean') {
      haloSiempreVisible = a.siempreVisible;
      saveConfig({ haloAlwaysVisible: haloSiempreVisible });
    }
    if (a.color && /^#[0-9a-fA-F]{6}$/.test(a.color)) {
      haloColor = a.color;
      saveConfig({ haloColor });
      haloWindow.loadFile('halo.html', { query: { edge: activeEdge, color: haloColor } });
    }
    recalcularTramo();
    haloWindow.setBounds(haloBoundsForEdge(activeEdge));
    if (haloSiempreVisible) aplicarVisibilidadHalo(); else peekHalo();
    buildTrayMenu();
    enviarEstadoAjustes();
  });
  if (KEYBOARD_ENABLED) {
    ipcMain.on('tb-key', (_evt, payload) => onKeyFromWindow(payload));
  }

  if (AUDIO_ENABLED) {
    createAudioWindow();
    ipcMain.on('tb-audio-playing', (_evt, info) => {
      console.log(`[audio] sonando (${info.rate} Hz, ${info.channels} canales)`);
    });

    // Diagnóstico del entrecortado. Las dos causas posibles se arreglan
    // distinto, así que hay que saber cuál domina antes de tocar nada:
    //   FALTA (silencios) = la red no llega a tiempo -> subir audioBufferMs
    //   SOBRA (descartes) = desfase entre el reloj del celular y el de la
    //                       placa de sonido -> hay que resamplear, no tirar
    ipcMain.on('tb-audio-stats', (_evt, s) => {
      // La velocidad muestra si está poniéndose al día. 1.000 = al ritmo
      // justo; 1.02 = leyendo 2% más rápido para bajar el retraso acumulado.
      const vel = s.velocidadPromedio ? ` vel ${s.velocidadPromedio.toFixed(3)}x` : '';
      // Si no llega nada, el reproductor se queda esperando colchón y antes
      // eso se reportaba como "sin cortes (cola 0 ms)": el registro mentía
      // justo cuando peor estaba. Ahora el silencio se declara siempre.
      if (s.mudo > 0.5) {
        console.log(`[audio] ${s.segundos}s MUDO (${Math.round(s.mudo * 100)}% sin audio): no está llegando nada`);
        return;
      }
      if (s.falta === 0 && s.sobra === 0 && !s.framesSilencio) {
        console.log(`[audio] ${s.segundos}s sin cortes (cola ${msDe(s.esperaPromedio)} ms${vel})`);
        return;
      }
      console.log(
        `[audio] en ${s.segundos}s: ` +
        `FALTA ${s.falta} vez/veces (${msDe(s.framesSilencio)} ms de silencio) | ` +
        `SOBRA ${s.sobra} vez/veces (${msDe(s.framesTirados)} ms tirados) | ` +
        `cola ${msDe(s.esperaPromedio)} ms de ${AUDIO_PREFILL_MS} esperados${vel}` +
        (s.mudo > 0.01 ? ` | ${Math.round(s.mudo * 100)}% del tiempo en silencio` : '')
      );
    });
  }

  uIOhook.on('mousemove', onMouseMove);
  uIOhook.on('mousedown', onMouseDown);
  uIOhook.on('mouseup', onMouseUp);
  uIOhook.start();

  // salida de emergencia: por si el gesto de "volver" no engancha
  bind('Control+Alt+W', () => {
    if (mode === 'phone') exitPhoneMode();
  }, 'volver a Windows');
  bind('Control+Alt+Shift+Q', () => {
    app.quit();
  }, 'cerrar la app');

  // Mover el halo a lo largo del borde sin editar el JSON ni reiniciar.
  // El primero lo lleva hacia el principio del borde (arriba, o a la
  // izquierda), el segundo hacia el final (abajo, o a la derecha).
  // Se prueban varias combinaciones porque RePág/AvPág con Ctrl+Alt las suelen
  // tomar los drivers de video, y en muchos teclados de notebook ni existen.
  haloKeyBack = bindFirst(
    ['Control+Alt+PageUp', 'Control+Alt+F7', 'Control+Alt+9'],
    () => moveHalo(-0.05), 'mover el halo hacia el principio'
  );
  haloKeyFwd = bindFirst(
    ['Control+Alt+PageDown', 'Control+Alt+F8', 'Control+Alt+0'],
    () => moveHalo(+0.05), 'mover el halo hacia el final'
  );

  // Prueba aislada del halo: prende y apaga la barra sin tocar el modo teléfono.
  // Sirve para separar "el halo no se dibuja" de "el modo teléfono no dura".
  bind('Control+Alt+H', () => {
    if (haloWindow.isVisible()) {
      haloWindow.hide();
      console.log('[halo] oculto');
    } else {
      showHalo();
      const b = haloWindow.getBounds();
      console.log(`[halo] visible en x=${b.x} y=${b.y} ${b.width}x${b.height}`);
    }
  }, 'probar el halo');

  // Silenciar el audio del celular sin cortar la transmisión.
  if (AUDIO_ENABLED) {
    // Sin atajo de silenciar: se maneja desde el mezclador de volumen de
    // Windows, que ya sabe silenciar una app sola.
    volUpKey = bindFirst(
      ['Control+Alt+Up', 'Control+Alt+Add', 'Control+Alt+F11'],
      () => changeVolume(+0.1), 'subir volumen'
    );
    volDownKey = bindFirst(
      ['Control+Alt+Down', 'Control+Alt+Subtract', 'Control+Alt+F10'],
      () => changeVolume(-0.1), 'bajar volumen'
    );
  }

  // Arrancar a escuchar. Windows va a pedir permiso del firewall la primera
  // vez: hay que aceptarlo para redes PRIVADAS, si no el celular no llega.
  client.escuchar();
  console.log(`PortalDroid escuchando en ${ipLocal()} (toques ${config.port}, audio ${AUDIO_PORT})`);

  // Sin ningún celular emparejado todavía, mostramos el QR solo: es el único
  // camino posible y esconderlo en un menú sería adivinanza.
  if (!config.pairedOnce) mostrarVentana();
  // Si está pedido, el halo se muestra desde el arranque.
  //
  // Ojo con la carrera: si la página del halo YA terminó de cargar cuando
  // llegamos acá, el evento 'did-finish-load' no vuelve a dispararse y el
  // halo no aparecería nunca. Por eso se pregunta primero si sigue cargando.
  if (haloSiempreVisible) {
    if (haloWindow.webContents.isLoading()) {
      haloWindow.webContents.once('did-finish-load', () => aplicarVisibilidadHalo());
    } else {
      aplicarVisibilidadHalo();
    }
  }
  console.log('Atajo de emergencia para volver a Windows: Ctrl+Alt+W');
  console.log('Prueba del halo (prender/apagar): Ctrl+Alt+H');
  if (haloKeyBack && haloKeyFwd) {
    console.log(`Mover el halo por el borde: ${haloKeyBack} / ${haloKeyFwd}`);
  }
  if (AUDIO_ENABLED) {
    // Se nombran los atajos que REALMENTE quedaron registrados, no los que
    // pedimos: anunciar uno que otra app tiene tomado sólo confunde.
    const partes = [];
    if (volUpKey && volDownKey) partes.push(`volumen: ${volUpKey} / ${volDownKey}`);
    console.log(
      `Audio del celular (arranca en ${Math.round(audioVolume * 100)}%)` +
      (partes.length ? ' — ' + partes.join(' | ') : ' — sin atajos libres, se ajusta con "audioVolume" en config.json')
    );
  }
});

// No hacemos nada acá a propósito: así el proceso sigue vivo en segundo
// plano aunque la ventana del halo esté oculta/cerrada (no es una app de
// ventana única, es más un "servicio" corriendo).
app.on('window-all-closed', () => {});
