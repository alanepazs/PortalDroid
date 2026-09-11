/**
 * Reproductor de audio continuo, corre en el hilo de audio de Chromium.
 *
 * ---- Por qué no se agenda cada pedazo ----
 *
 * La primera versión agendaba cada pedazo que llegaba para sonar en un momento
 * futuro calculado a mano. Como el reloj del celular y el de la placa de sonido
 * no van exactamente igual, ese momento se corría cada vez más lejos, y cuando
 * el código lo corregía de golpe los pedazos ya agendados seguían sonando: el
 * audio se superponía consigo mismo.
 *
 * Acá hay UNA cola circular y este procesador saca de ella exactamente lo que
 * la placa pide, cuando lo pide. No hay nada agendado, así que no puede
 * superponerse.
 *
 * ---- Por qué se lee a velocidad variable ----
 *
 * Con la cola sola no alcanza. Cada tropiezo del WiFi o del celular deja audio
 * de más esperando, y ese retraso NO se va solo: la cola se quedaba en 330 ms
 * cuando el objetivo eran 100, y seguía subiendo hasta el techo, donde había
 * que tirar audio de golpe. Eso se escucha feo.
 *
 * La solución es leer un poquito más rápido cuando sobra y un poquito más
 * lento cuando falta, con interpolación entre muestras. El ajuste está
 * limitado al 2%, que en la práctica no se percibe (es menos de medio
 * semitono, y sólo mientras se está poniendo al día). Así el retraso vuelve
 * solo al objetivo en unos segundos, sin cortes ni saltos.
 *
 * ---- Por qué un faltante chico NO frena la reproducción ----
 *
 * La versión anterior, ante CUALQUIER faltante, volvía a juntar el colchón
 * entero desde cero. O sea que faltar 2 muestras costaba 100 ms de silencio:
 * el remedio era mil veces peor que la enfermedad, y era lo que se escuchaba
 * como entrecortado cada dos o tres videos.
 *
 * Ahora, si falta poco, se rellena sólo ese huequito con silencio y se sigue.
 * La velocidad variable baja sola y recupera el colchón sin que se note.
 * Recién si el audio no llega durante un rato largo y seguido vale la pena
 * frenar y rearmar el colchón, porque ahí el problema es de verdad.
 */
class PcmPlayer extends AudioWorkletProcessor {
  constructor(options) {
    super();
    const opt = (options && options.processorOptions) || {};
    // Cuánto audio juntar antes de empezar a sonar, y objetivo a mantener.
    // Es el precio del WiFi: sin colchón, cualquier microdemora se escucha.
    this.objetivo = opt.prefillFrames || 4800;   // ~100 ms a 48 kHz
    // Techo de seguridad. Con la velocidad variable casi nunca se llega.
    this.max = opt.maxFrames || 19200;           // ~400 ms a 48 kHz
    this.canales = opt.channels || 2;

    // Cola circular: 4 segundos alcanza de sobra para cualquier tropiezo.
    this.cap = 48000 * 4;
    this.ring = [];
    for (let c = 0; c < this.canales; c++) this.ring.push(new Float32Array(this.cap));
    this.w = 0;          // dónde se escribe (entero)
    this.r = 0;          // dónde se lee (FRACCIONARIO, por la velocidad variable)
    this.hay = 0;        // muestras disponibles
    this.sonando = false;
    // Bloques seguidos sin audio. Si son muchos, el problema no es un bache
    // sino que se cortó de verdad, y ahí sí conviene rearmar el colchón.
    this.vacioSeguido = 0;
    // 128 muestras por bloque: 60 bloques son ~160 ms sin nada de audio.
    this.vacioMax = 60;

    // Contadores, para poder diagnosticar en vez de adivinar.
    this.sobra = 0; this.framesTirados = 0;
    this.falta = 0; this.framesSilencio = 0;
    this.bloques = 0; this.sumaEspera = 0; this.sumaVel = 0;
    // Aparte de bloques: sólo los que realmente reprodujeron algo. El
    // promedio de velocidad tiene que salir de estos, si no los bloques de
    // silencio lo tiran para abajo e inventan velocidades imposibles.
    this.bloquesSonando = 0;

    this.port.onmessage = (e) => {
      const m = e.data;
      if (m.type === 'chunk') this.guardar(m.channels);
      else if (m.type === 'reset') {
        this.w = 0; this.r = 0; this.hay = 0; this.sonando = false;
      }
    };
  }

  guardar(canales) {
    const n = canales[0].length;
    for (let c = 0; c < this.canales; c++) {
      const src = canales[Math.min(c, canales.length - 1)];
      const dst = this.ring[c];
      // Puede cruzar el final del anillo: se copia en dos tramos.
      const hastaElFinal = Math.min(n, this.cap - this.w);
      dst.set(src.subarray(0, hastaElFinal), this.w);
      if (n > hastaElFinal) dst.set(src.subarray(hastaElFinal), 0);
    }
    this.w = (this.w + n) % this.cap;
    this.hay += n;

    // Techo del retraso.
    //
    // Este control existía desde el principio (this.max) pero nunca se
    // comparaba contra nada: lo único que recortaba era el desborde del
    // anillo, que son 4 segundos. Resultado: el retraso podía crecer hasta
    // varios segundos sin que nada reaccionara. Se vio midiendo: la cola
    // pasó de 198 ms a 797 ms en media hora y siguió subiendo.
    //
    // Por qué crece: el WiFi no entrega parejo. Llega el 100% del audio
    // esperado, pero a los tirones — baches de 40 a 170 ms y después una
    // ráfaga que entra de golpe. Cada ráfaga deja la cola más alta, y leer
    // 2% más rápido (lo máximo que se puede sin que se note el tono) tarda
    // ~15 segundos en drenar 300 ms. Si los baches vienen más seguido que
    // eso, el retraso sube en escalera y no baja nunca.
    //
    // Acá se corta: pasado el techo se tira lo más viejo y se vuelve al
    // objetivo de una. Es un salto de audio, sí, pero uno cada tanto es
    // mucho menos molesto que medio segundo de retraso permanente. Los
    // desvíos chicos los sigue corrigiendo la velocidad variable, sin
    // tirar nada y sin que se note.
    if (this.hay > this.max) {
      const exceso = this.hay - this.objetivo;
      this.r = (this.r + exceso) % this.cap;
      this.hay -= exceso;
      this.framesTirados += exceso;
      this.sobra++;
    }
  }

  /** Qué tan rápido leer para que la cola vuelva al objetivo, sin que se note. */
  velocidad() {
    const error = this.hay - this.objetivo;
    let v = 1 + (error / this.objetivo) * 0.02;
    if (v > 1.02) v = 1.02;
    if (v < 0.98) v = 0.98;
    return v;
  }

  process(_inputs, outputs) {
    const out = outputs[0];
    const nCh = out.length;
    const need = out[0].length;

    if (!this.sonando) {
      if (this.hay < this.objetivo) {
        // Juntando colchón: esto también es silencio que se escucha, así que
        // se cuenta. Antes no se contaba y por eso los cortes parecían de
        // 2 ms cuando en realidad eran de más de 100.
        this.framesSilencio += need;
        this.bloques++;
        this.reportar();
        return true;
      }
      this.sonando = true;
      this.vacioSeguido = 0;
    }

    const vel = this.velocidad();
    // Cuántas muestras podemos sacar de verdad. Si no alcanzan para el bloque
    // entero, sacamos las que hay y el resto va en silencio, pero SIN frenar.
    const posibles = Math.min(need, Math.max(0, Math.floor((this.hay - 1) / vel)));

    for (let i = 0; i < posibles; i++) {
      const base = Math.floor(this.r);
      const frac = this.r - base;
      const i0 = base % this.cap;
      const i1 = (base + 1) % this.cap;
      for (let c = 0; c < nCh; c++) {
        const anillo = this.ring[Math.min(c, this.canales - 1)];
        // Interpolación lineal: si leemos "entre" dos muestras, mezclamos las
        // dos en proporción. Sin esto, leer a velocidad variable chirriaría.
        out[c][i] = anillo[i0] * (1 - frac) + anillo[i1] * frac;
      }
      this.r += vel;
      if (this.r >= this.cap) this.r -= this.cap;
      this.hay -= vel;
    }

    if (posibles < need) {
      for (let c = 0; c < nCh; c++) out[c].fill(0, posibles);
      this.falta++;
      this.framesSilencio += need - posibles;
      this.vacioSeguido++;
      // Silencio sostenido: se cortó de verdad. Frenamos y rearmamos colchón,
      // que es mejor que ir raspando el fondo bloque a bloque.
      if (this.vacioSeguido >= this.vacioMax) this.sonando = false;
    } else {
      this.vacioSeguido = 0;
    }

    this.bloques++;
    this.bloquesSonando++;
    this.sumaEspera += this.hay;
    this.sumaVel += vel;
    this.reportar();
    return true;
  }

  /**
   * Manda los contadores al proceso principal cada ~5 segundos.
   * Se cuenta en bloques y no con un reloj porque acá adentro no hay
   * temporizadores: este código corre invitado por la placa de sonido cada vez
   * que necesita más muestras.
   */
  reportar() {
    // 128 muestras por bloque a 48 kHz = 375 bloques por segundo.
    if (this.bloques < 375 * 5) return;
    this.port.postMessage({
      type: 'stats',
      sobra: this.sobra,
      framesTirados: Math.round(this.framesTirados),
      falta: this.falta,
      framesSilencio: this.framesSilencio,
      esperaPromedio: Math.round(this.sumaEspera / Math.max(1, this.bloquesSonando)),
      velocidadPromedio: this.sumaVel / Math.max(1, this.bloquesSonando),
      // Qué proporción del tiempo salió silencio en vez de audio. Este es EL
      // número que importa: es lo que se escucha mal.
      mudo: this.bloques ? (this.bloques - this.bloquesSonando) / this.bloques : 0,
      segundos: Math.round(this.bloques / 375),
    });
    this.sobra = 0; this.framesTirados = 0;
    this.falta = 0; this.framesSilencio = 0;
    this.bloques = 0; this.bloquesSonando = 0;
    this.sumaEspera = 0; this.sumaVel = 0;
  }
}

registerProcessor('pcm-player', PcmPlayer);
