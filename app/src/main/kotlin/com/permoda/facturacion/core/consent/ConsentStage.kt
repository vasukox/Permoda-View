package com.permoda.facturacion.core.consent

/**
 * En qué punto del flujo está la selección de cliente.
 *
 * El flujo lo **conduce el cajero**: él captura el documento, llena los datos y
 * confirma. El cliente ve en vivo lo que el cajero escribe, puede corregirlo si
 * hace falta, y su único toque obligatorio es el Acepto de Koaj Lovers.
 */
enum class ConsentStage {

    /** Resolviendo el escenario (parseo, arranque de la Presentation). */
    STARTING,

    /**
     * ⛔ Compuerta. El cajero tiene que decidir antes de seguir. Mientras dure,
     * el cliente ve una pantalla de espera.
     *
     * Cubre dos casos con el mismo mecanismo:
     *  - el cliente no está en HioPosCloud → "¿desea crearlo?"
     *  - está pero le faltan datos         → "¿completar sus datos?"
     *
     * Es la **única** compuerta del flujo: una vez autorizado, el resto va
     * seguido. Antes había una segunda cuando la DIAN no encontraba el
     * documento, y era un toque de más por venta sin aportar decisión real.
     */
    AWAITING_VENDOR_APPROVAL,

    /**
     * El **cajero** teclea tipo + número de documento. Es la entrada del flujo.
     * El cliente ve el documento aparecer en vivo en su pantalla.
     */
    AWAITING_DOCUMENT,

    /** Consulta a la DIAN en curso. */
    VALIDATING_DIAN,

    /**
     * El **cajero** revisa y completa los 5 campos. El cliente los ve en
     * espejo y puede pedir corregirlos.
     */
    FILLING,

    /** Se le está ofreciendo la comunidad Koaj Lovers al cliente. */
    LOYALTY,

    /** El cliente eligió (o el cajero forzó) Consumidor Final. */
    CONSUMIDOR_FINAL,

    /** Terminó: aceptado, rechazado o error. */
    DONE;

    /** True si el flujo está detenido esperando una decisión del cajero. */
    val isVendorGate: Boolean
        get() = this == AWAITING_VENDOR_APPROVAL

    /**
     * True cuando el **cajero** puede editar los campos.
     *
     * Es el actor principal, así que edita durante toda la captura. En las
     * compuertas todavía no hay datos que editar; en Consumidor Final son fijos
     * (222222222222); y en Koaj el snapshot que se devuelve al POS ya está
     * congelado, así que editar daría la falsa impresión de guardar.
     */
    val allowsVendorEdit: Boolean
        get() = this == FILLING || this == AWAITING_DOCUMENT

    /**
     * True cuando la pantalla del cliente muestra el espejo de los 5 campos.
     *
     * **Siempre en solo lectura.** El cliente no edita nada: todo lo hace el
     * cajero. Su pantalla es una vista en vivo, y su único toque en todo el
     * flujo es el Acepto de Koaj Lovers.
     */
    val showsClientMirror: Boolean
        get() = this == FILLING

    /**
     * True mientras el Consumidor Final sigue siendo una salida válida.
     * Después de Koaj o del cierre ya no tiene sentido ofrecerlo.
     */
    val allowsConsumidorFinal: Boolean
        get() = this != LOYALTY && this != CONSUMIDOR_FINAL && this != DONE
}
