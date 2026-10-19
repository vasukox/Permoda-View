package com.permoda.facturacion.core.model

/**
 * Datos del cliente que muestra la pantalla de consentimiento.
 * Modelo inmutable (Kotlin `data class`).
 */
data class CustomerData(
    val customerId: String? = null,
    val customerName: String,
    val fiscalDocumentType: String? = null,
    val fiscalId: String? = null,
    val email: String? = null,
    val phone: String? = null,
    /**
     * ISO 3166-1 alpha-2. Se modela aparte de [extraFieldsXml] porque
     * PermodaView lo escribe siempre en la respuesta: si quedara en los extras
     * saldría duplicado.
     */
    val countryIso2: String? = null,
    /**
     * Tipo de cliente seleccionado por el cajero (id numérico de HioPos).
     * Se modela aparte de [extraFieldsXml] por la misma razón que
     * [countryIso2]: lo escribimos siempre en `ModifyDocumentResult` y tiene
     * que sobrevivir al round-trip con lupita. `null` significa "sin tipo
     * asignado" (id = 0 en el enum CustomerType).
     */
    val customerTypeId: Int? = null,
    /**
     * Campos extra del CustomerResult que NO pintamos en pantalla
     * pero conservamos para devolverlos tal cual al POS cuando el cliente acepte.
     */
    val extraFieldsXml: String = ""
)
