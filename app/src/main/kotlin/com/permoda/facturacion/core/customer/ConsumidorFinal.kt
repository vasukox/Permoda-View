package com.permoda.facturacion.core.customer

import com.permoda.facturacion.core.model.CustomerData

/**
 * Cliente genérico para facturar cuando el cliente **no quiere dar sus datos**.
 *
 * Es la válvula de escape del flujo: existe para que negarse a entregar datos
 * personales nunca deje al vendedor sin poder facturar. Por eso el botón que
 * lo dispara está visible en todas las pantallas de captura.
 *
 * ⚠️ `DOCUMENT_TYPE = "13"` (cédula de ciudadanía) es el valor confirmado por
 * negocio para el NIT genérico 222222222222. Se documenta explícitamente
 * porque la especificación original lo rotulaba como "NIT" — en la tabla DIAN
 * el NIT es 31, no 13. Si ICG pide cambiarlo, este es el único punto a tocar.
 */
object ConsumidorFinal {

    const val NAME = "Consumidor Final"
    const val DOCUMENT_TYPE = "13"
    const val DOCUMENT_ID = "222222222222"
    const val COUNTRY_ISO2 = "CO"

    /**
     * Buzón corporativo al que la DIAN envía las facturas de consumidor final.
     *
     * No es un correo inventado: la factura electrónica necesita un
     * destinatario válido, y el cliente genérico por definición no aporta uno.
     * Sin esto la DIAN no tendría a dónde entregarla.
     */
    const val EMAIL = "facturaelectronicapos@permoda.com.co"

    /**
     * Teléfono corporativo del genérico.
     *
     * Antes iba vacío, con el argumento de que "no hay un teléfono genérico que
     * tenga sentido y la DIAN no lo exige". Se cambió el 20-ago-2026 por decisión
     * de negocio: el cliente genérico queda con el conmutador de Permoda.
     *
     * Son exactamente 10 dígitos, así que cumple el mínimo que
     * `CustomerValidator` exige desde el 19-ago. No es casualidad que importe:
     * aunque el Consumidor Final salta la validación de campos obligatorios, el
     * número viaja al maestro de HioPos como cualquier otro y un teléfono corto
     * ahí queda inservible.
     */
    const val PHONE = "6012948999"

    /**
     * Construye el cliente genérico.
     *
     * @param extraFieldsXml campos no pintados que llegaron del POS. Se
     *   preservan para no perder información del documento en curso.
     */
    fun build(extraFieldsXml: String = ""): CustomerData = CustomerData(
        // customerId nulo a propósito: HioPosCloud debe resolver su propio
        // cliente genérico, no queremos inventarle un id.
        customerId = null,
        customerName = NAME,
        fiscalDocumentType = DOCUMENT_TYPE,
        fiscalId = DOCUMENT_ID,
        // Correo corporativo: la factura electrónica necesita destinatario.
        email = EMAIL,
        phone = PHONE,
        // El consumidor final siempre es local.
        countryIso2 = COUNTRY_ISO2,
        extraFieldsXml = extraFieldsXml
    )

    /** True si estos datos corresponden al consumidor final genérico. */
    fun matches(customer: CustomerData): Boolean =
        customer.fiscalId?.trim() == DOCUMENT_ID
}
