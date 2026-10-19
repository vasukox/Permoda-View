package com.permoda.facturacion.core.consent

/**
 * Órdenes que el **cajero** le da al flujo desde su pantalla.
 *
 * El cajero es el actor principal: captura el documento, llena los datos y
 * confirma. El cliente sólo ve el espejo en vivo, corrige si hace falta y
 * acepta Koaj Lovers.
 *
 * Viajan por [ConsentSharedState] como comando de **un solo uso**: la pantalla
 * del cliente lo atiende y llama a [ConsentSharedState.consumeVendorCommand].
 * Si no se bajara, cada emisión del flow lo volvería a disparar.
 *
 * Es una `sealed class` y no un enum porque [ConfirmDocument] lleva datos.
 * Un único campo nullable en el estado evita que dos órdenes queden activas a
 * la vez (p.ej. "aceptar" y "consumidor final" simultáneos).
 */
sealed class VendorCommand {

    /**
     * El cajero terminó de teclear tipo + número de documento. Es la entrada del
     * flujo: con esto se resuelve el escenario.
     *
     * @param tipo nombre legible del tipo de documento seleccionado.
     * @param numero número de documento tipeado.
     * @param employeeChoiceTypeId elección del cajero entre **Empleado** y
     *   "compra como cliente normal", como id de `CustomerType`.
     *
     *   ⚠️ Este campo era antes el tipo de cliente elegido en un desplegable, y
     *   se escribía encima del tipo real del maestro de HioPos. Ya no: el tipo de
     *   cliente **no lo elige el cajero**. Lo trae el POS, o lo produce la
     *   decisión de Koaj Lovers al final del flujo
     *   (`CustomerTypeResolver`). Lo único que el cajero decide es si un
     *   empleado está comprando como empleado, y sólo se le pregunta cuando el
     *   POS trajo un cliente Empleado. `null` en todos los demás casos, que son
     *   la inmensa mayoría.
     */
    data class ConfirmDocument(
        val tipo: String,
        val numero: String,
        val employeeChoiceTypeId: Int? = null
    ) : VendorCommand()

    /**
     * Sí a la compuerta, sea "¿desea crearlo?" (cliente nuevo) o
     * "¿completar sus datos?" (existe pero incompleto). Un solo comando
     * porque para el flujo la decisión es la misma: seguir adelante.
     */
    data object Approve : VendorCommand()

    /**
     * El cajero confirma los datos del cliente. Cierra el formulario y pasa a
     * Koaj Lovers, donde el cliente da su único toque obligatorio.
     */
    data object AcceptData : VendorCommand()

    /**
     * Volver a capturar el documento.
     *
     * Existe porque un dígito mal tecleado manda el flujo por el escenario
     * equivocado —crea un cliente nuevo en vez de reconocer al existente— y sin
     * esto el cajero tendría que cancelar la venta y empezar de cero.
     */
    data object BackToDocument : VendorCommand()

    /**
     * Facturar al genérico 222222222222.
     *
     * Es también la respuesta a un **No** en la compuerta: si no se va a
     * crear ni completar el cliente, igual hay que poder facturar.
     */
    data object ConsumidorFinal : VendorCommand()

    /** Permite continuar sin inscribir; no registra aceptación ni rechazo del cliente. */
    data object ContinueWithoutLoyalty : VendorCommand()
}
