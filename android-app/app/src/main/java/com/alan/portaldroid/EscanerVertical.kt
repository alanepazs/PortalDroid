package com.alan.portaldroid

import com.journeyapps.barcodescanner.CaptureActivity

/**
 * La pantalla de escaneo, forzada a vertical.
 *
 * La que trae la librería queda horizontal: incómoda para apuntar a un QR en
 * el monitor. Esta clase no agrega código, existe sólo para poder declararla
 * en el manifiesto con screenOrientation="portrait".
 */
class EscanerVertical : CaptureActivity()
