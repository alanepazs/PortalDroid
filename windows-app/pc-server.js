// La PC escucha; el celular llama.
//
// ---- Por qué al revés de como estaba ----
//
// Antes el celular escuchaba y la PC lo llamaba a una IP escrita a mano en la
// config. Eso traía tres problemas: había que averiguar y tipear la IP, las
// IPs cambian solas (ya pasó), y con dos PCs y dos celulares en la misma red
// no había forma de saber quién se conecta con quién.
//
// Ahora la PC muestra un QR con su dirección y una clave, el celular lo
// escanea y llama. No se escribe ninguna IP, y sólo se conecta quien vio la
// pantalla de tu PC.
//
// El celular abre DOS conexiones: una para los toques y otra para el audio.
// Van separadas a propósito: el audio son ~1,5 Mbit por segundo y si
// compartiera socket con los comandos del mouse, los movimientos del puntero
// quedarían haciendo cola atrás del audio.
const net = require('net');
const os = require('os');
const crypto = require('crypto');

/** La IP de esta PC en la red local: la que va en el QR. */
function ipLocal() {
  const candidatas = [];
  for (const [nombre, direcciones] of Object.entries(os.networkInterfaces())) {
    for (const d of direcciones || []) {
      if (d.family !== 'IPv4' || d.internal) continue;
      // Las de VirtualBox, Docker o VPN casi nunca son la de tu red real, así
      // que van al final. No se descartan: si es la única, mejor esa que nada.
      const sospechosa = /virtual|vmware|docker|hyper-v|vethernet|wsl/i.test(nombre);
      candidatas.push({ ip: d.address, nombre, sospechosa });
    }
  }
  candidatas.sort((a, b) => Number(a.sospechosa) - Number(b.sospechosa));
  return candidatas.length ? candidatas[0].ip : null;
}

/** Clave de emparejamiento. Va en el QR y no se muestra en ningún otro lado. */
function nuevaClave() {
  return crypto.randomBytes(6).toString('hex');
}

class PcServer {
  constructor(opts) {
    this.controlPort = opts.controlPort;
    this.audioPort = opts.audioPort;
    this.token = opts.token;
    this.onEstado = opts.onEstado || (() => {});
    this.onAudioFormato = opts.onAudioFormato || (() => {});
    this.onAudioChunk = opts.onAudioChunk || (() => {});

    this.control = null;   // socket de toques del celular conectado
    this.audio = null;     // socket de audio
    this.celular = null;   // { nombre, ip, screenW, screenH }
    this.audioPend = Buffer.alloc(0);
    this.audioListo = false;
    this.latido = null;
  }

  escuchar() {
    this.srvControl = net.createServer((s) => this.entraControl(s));
    this.srvControl.on('error', (e) =>
      this.onEstado('error', `no pude escuchar en ${this.controlPort}: ${e.message}`));
    this.srvControl.listen(this.controlPort);

    this.srvAudio = net.createServer((s) => this.entraAudio(s));
    this.srvAudio.on('error', (e) =>
      this.onEstado('error', `no pude escuchar en ${this.audioPort}: ${e.message}`));
    this.srvAudio.listen(this.audioPort);
  }

  /**
   * Primera línea de cualquier conexión: el saludo con la clave del QR.
   * Sin clave válida se corta. Sin esto, cualquiera en tu WiFi con la app
   * podría hacerse pasar por tu celular y tomar el control.
   */
  leerSaludo(socket, alAceptar) {
    let buf = Buffer.alloc(0);
    const alLlegar = (d) => {
      buf = Buffer.concat([buf, d]);
      const corte = buf.indexOf(0x0a);
      if (corte === -1) {
        if (buf.length > 4096) socket.destroy();
        return;
      }
      const linea = buf.subarray(0, corte).toString('utf-8');
      const resto = buf.subarray(corte + 1);
      socket.removeListener('data', alLlegar);

      let saludo = null;
      try { saludo = JSON.parse(linea); } catch (_) { socket.destroy(); return; }
      if (!saludo || saludo.token !== this.token) {
        this.onEstado('rechazado', (saludo && saludo.name) || 'desconocido');
        try { socket.write(JSON.stringify({ ok: false, error: 'clave incorrecta' }) + '\n'); } catch (_) {}
        socket.destroy();
        return;
      }
      try { socket.write(JSON.stringify({ ok: true }) + '\n'); } catch (_) {}
      alAceptar(saludo, resto);
    };
    socket.on('data', alLlegar);
    socket.on('error', () => {});
  }

  entraControl(socket) {
    socket.setNoDelay(true);
    // Keepalive del propio TCP, además de nuestro ping: trabaja más abajo y
    // ayuda a que los equipos del medio no den la conexión por abandonada.
    socket.setKeepAlive(true, 10000);
    this.leerSaludo(socket, (saludo) => {
      // Un solo celular a la vez. Si ya había uno, gana el nuevo: dos
      // celulares mandando toques se pisarían el gesto en curso, porque el
      // estado del trazo es uno solo del lado del Android.
      if (this.control && this.control !== socket) {
        this.onEstado('reemplazado', this.celular ? this.celular.nombre : '');
        try { this.control.destroy(); } catch (_) {}
      }
      this.control = socket;
      this.celular = {
        nombre: saludo.name || 'celular',
        ip: socket.remoteAddress,
        screenW: saludo.screenW,
        screenH: saludo.screenH,
      };
      this.onEstado('conectado', this.celular.nombre);
      this.arrancarLatido();

      socket.on('close', () => {
        this.pararLatido();
        if (this.control === socket) {
          this.control = null;
          this.celular = null;
          this.onEstado('desconectado', '');
        }
      });
    });
  }

  entraAudio(socket) {
    socket.setNoDelay(true);
    this.leerSaludo(socket, (_saludo, resto) => {
      if (this.audio && this.audio !== socket) {
        try { this.audio.destroy(); } catch (_) {}
      }
      this.audio = socket;
      this.audioListo = false;
      this.audioPend = Buffer.alloc(0);
      this.onEstado('audio-conectado', '');

      const alLlegar = (d) => {
        if (this.audioListo) { this.onAudioChunk(d); return; }
        // Falta la cabecera con el formato; puede venir partida.
        this.audioPend = Buffer.concat([this.audioPend, d]);
        const corte = this.audioPend.indexOf(0x0a);
        if (corte === -1) {
          if (this.audioPend.length > 1024) socket.destroy();
          return;
        }
        const linea = this.audioPend.subarray(0, corte).toString('utf-8');
        const sobra = this.audioPend.subarray(corte + 1);
        this.audioPend = Buffer.alloc(0);
        try {
          this.onAudioFormato(JSON.parse(linea));
          this.audioListo = true;
          if (sobra.length) this.onAudioChunk(sobra);
        } catch (_) { socket.destroy(); }
      };

      if (resto && resto.length) alLlegar(resto);
      socket.on('data', alLlegar);
      socket.on('close', () => {
        if (this.audio === socket) {
          this.audio = null;
          this.audioListo = false;
          this.onEstado('audio-desconectado', '');
        }
      });
    });
  }

  /**
   * Latido cada 4 segundos.
   *
   * Cuando no estás en modo teléfono, esta conexión no manda NADA en ninguna
   * dirección. Una conexión muda es frágil: el ahorro de energía del WiFi del
   * celular, el router o cualquier NAT en el medio la dan por muerta y la
   * cierran sin avisar. Se medió: se caía sola cada 10-15 segundos.
   *
   * El celular ignora estos mensajes; sólo sirven para que el enlace
   * siga vivo y para enterarnos rápido si se cortó de verdad.
   */
  arrancarLatido() {
    this.pararLatido();
    this.latido = setInterval(() => {
      if (!this.control) return;
      try {
        this.control.write(JSON.stringify({ type: 'ping' }) + String.fromCharCode(10));
      } catch (_) { /* si falla, el evento 'close' se encarga */ }
    }, 4000);
  }

  pararLatido() {
    if (this.latido) { clearInterval(this.latido); this.latido = null; }
  }

  get conectado() { return !!this.control; }

  enviar(obj) {
    if (!this.control) return;
    try { this.control.write(JSON.stringify(obj) + '\n'); } catch (_) {}
  }

  // Mismos nombres que tenía el cliente anterior, así el resto no cambia.
  down(x, y) { this.enviar({ type: 'down', x, y }); }
  move(x, y) { this.enviar({ type: 'move', x, y }); }
  up() { this.enviar({ type: 'up' }); }
  tap(x, y) { this.enviar({ type: 'tap', x, y }); }
  enter() { this.enviar({ type: 'enter' }); }
  leave() { this.enviar({ type: 'leave' }); }
  hover(x, y) { this.enviar({ type: 'hover', x, y }); }
  scroll(x, y, dy) { this.enviar({ type: 'scroll', x, y, dy }); }
  text(ch) { this.enviar({ type: 'text', ch }); }
  key(name) { this.enviar({ type: 'key', name }); }

  cerrar() {
    for (const s of [this.control, this.audio]) { try { if (s) s.destroy(); } catch (_) {} }
    for (const srv of [this.srvControl, this.srvAudio]) { try { if (srv) srv.close(); } catch (_) {} }
  }
}

module.exports = { PcServer, ipLocal, nuevaClave };
