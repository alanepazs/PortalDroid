// Geometría de monitores: qué bordes sirven para cruzar y dónde va el halo.
//
// Todo acá son funciones puras que reciben los datos de las pantallas. Así se
// pueden probar sin levantar Electron, que es lo que hace falta para no ir
// adivinando con dos monitores.

/** ¿Se solapan dos rangos [a1,a2) y [b1,b2)? Con un poco de tolerancia. */
function solapan(a1, a2, b1, b2, tol = 2) {
  return Math.min(a2, b2) - Math.max(a1, b1) > tol;
}

function casiIgual(a, b, tol = 2) {
  return Math.abs(a - b) <= tol;
}

/**
 * ¿Este borde da contra otro monitor?
 *
 * Un borde así NO sirve para cruzar al celular: es por donde el mouse pasa de
 * una pantalla a la otra. Si pusiéramos el halo ahí, cada vez que quisieras
 * mover el mouse al otro monitor te mandaría al teléfono.
 */
function bordePegadoAOtro(pantalla, borde, todas) {
  const b = pantalla.bounds;
  for (const o of todas) {
    if (o.id === pantalla.id) continue;
    const c = o.bounds;
    const vecinoVertical = solapan(b.y, b.y + b.height, c.y, c.y + c.height);
    const vecinoHorizontal = solapan(b.x, b.x + b.width, c.x, c.x + c.width);
    if (borde === 'right' && casiIgual(c.x, b.x + b.width) && vecinoVertical) return true;
    if (borde === 'left' && casiIgual(c.x + c.width, b.x) && vecinoVertical) return true;
    if (borde === 'bottom' && casiIgual(c.y, b.y + b.height) && vecinoHorizontal) return true;
    if (borde === 'top' && casiIgual(c.y + c.height, b.y) && vecinoHorizontal) return true;
  }
  return false;
}

const BORDES = ['right', 'left', 'top', 'bottom'];

/** Los cuatro bordes de cada pantalla, marcando cuáles se pueden usar. */
function mapaDePantallas(todas, principalId) {
  return todas.map((p, i) => ({
    id: p.id,
    indice: i,
    principal: p.id === principalId,
    escala: p.scaleFactor || 1,
    bounds: p.bounds,
    clave: claveDe(p),
    bordes: Object.fromEntries(
      BORDES.map((b) => [b, !bordePegadoAOtro(p, b, todas)])
    ),
  }));
}

/**
 * Identidad estable de una pantalla.
 *
 * NO se usa el `id` de Electron: cambia al reiniciar Windows, así que la
 * elección del usuario se perdería. La posición y el tamaño sí se mantienen
 * mientras no muevas los monitores.
 */
function claveDe(p) {
  const b = p.bounds;
  return `${b.x},${b.y},${b.width},${b.height}`;
}

/** Encuentra la pantalla elegida; si ya no está, cae en la principal. */
function elegirPantalla(mapa, clave) {
  return mapa.find((p) => p.clave === clave) || mapa.find((p) => p.principal) || mapa[0];
}

/**
 * Primer borde utilizable de una pantalla, con preferencia por la derecha.
 * Sirve cuando el borde guardado dejó de servir (por ejemplo si enchufaste
 * un monitor nuevo justo de ese lado).
 */
function primerBordeUtil(pantalla) {
  return BORDES.find((b) => pantalla.bordes[b]) || 'right';
}

/**
 * El tramo que ocupa el halo a lo largo de un borde.
 * @returns {{inicio:number, fin:number, largoTotal:number}} en píxeles del borde
 */
function tramoDelHalo(pantalla, borde, fraccionLargo, fraccionPos) {
  const b = pantalla.bounds;
  const vertical = borde === 'right' || borde === 'left';
  const total = vertical ? b.height : b.width;
  const largo = Math.max(80, Math.round(total * fraccionLargo));
  const desde = Math.round((total - largo) * fraccionPos);
  return { inicio: desde, fin: desde + largo, largoTotal: total };
}

/** Dónde ubicar la ventana del halo, en coordenadas de escritorio. */
function ventanaDelHalo(pantalla, borde, fraccionLargo, fraccionPos, grosor) {
  const b = pantalla.bounds;
  const t = tramoDelHalo(pantalla, borde, fraccionLargo, fraccionPos);
  const largo = t.fin - t.inicio;
  switch (borde) {
    case 'right':  return { x: b.x + b.width - grosor, y: b.y + t.inicio, width: grosor, height: largo };
    case 'left':   return { x: b.x, y: b.y + t.inicio, width: grosor, height: largo };
    case 'top':    return { x: b.x + t.inicio, y: b.y, width: largo, height: grosor };
    case 'bottom': return { x: b.x + t.inicio, y: b.y + b.height - grosor, width: largo, height: grosor };
  }
}

/**
 * ¿El mouse está sobre el halo?
 *
 * Recibe coordenadas del escritorio completo, que con varios monitores puede
 * arrancar en negativo si hay una pantalla a la izquierda de la principal.
 */
function estaEnElHalo(pantalla, borde, tramo, x, y, umbral, soloSobreElHalo) {
  const b = pantalla.bounds;
  const vertical = borde === 'right' || borde === 'left';

  // Cuánto se admite que el mouse se pase del borde HACIA AFUERA.
  //
  // Hace falta porque uiohook informa la posición CRUDA del mouse, antes de
  // que Windows la recorte a la pantalla. Empujando hacia arriba contra el
  // borde superior, vos ves el cursor frenado en el borde pero acá llegan
  // lecturas de más allá. Se midió: con el monitor empezando en y=357
  // llegaban y=355 e y=356.
  //
  // Si esas lecturas se descartan, cada una cancela el disparo del cruce y
  // el halo parece trabarse. Y como sólo se puede poner el halo en un borde
  // que NO da contra otro monitor, más allá de ese borde no hay nada: pasarse
  // es exactamente la intención de cruzar.
  const AFUERA = 400;

  let enElEje;
  switch (borde) {
    case 'right':  enElEje = x >= b.x + b.width - 1 - umbral && x <= b.x + b.width + AFUERA; break;
    case 'left':   enElEje = x <= b.x + umbral && x >= b.x - AFUERA; break;
    case 'top':    enElEje = y <= b.y + umbral && y >= b.y - AFUERA; break;
    case 'bottom': enElEje = y >= b.y + b.height - 1 - umbral && y <= b.y + b.height + AFUERA; break;
  }
  if (!enElEje) return false;

  // Además tiene que estar dentro de la pantalla en el otro eje: si no,
  // apoyarse en el borde del monitor de al lado contaría como cruce.
  const aLoLargo = vertical ? y - b.y : x - b.x;
  const dentro = vertical
    ? (y >= b.y && y <= b.y + b.height)
    : (x >= b.x && x <= b.x + b.width);
  if (!dentro) return false;

  if (!soloSobreElHalo) return true;
  return aLoLargo >= tramo.inicio && aLoLargo <= tramo.fin;
}

module.exports = {
  BORDES, solapan, bordePegadoAOtro, mapaDePantallas, claveDe,
  elegirPantalla, primerBordeUtil, tramoDelHalo, ventanaDelHalo, estaEnElHalo,
};
