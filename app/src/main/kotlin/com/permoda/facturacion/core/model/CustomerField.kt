package com.permoda.facturacion.core.model

/**
 * Una sola línea de información del cliente.
 *
 * HioPosCloud envía los datos como un mapa Key → valor dentro de
 * un XML <CustomerResult><CustomerField Key="...">...</CustomerField>...
 */
data class CustomerField(
    val key: String,
    val value: String
)
