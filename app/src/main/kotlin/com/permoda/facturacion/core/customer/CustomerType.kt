package com.permoda.facturacion.core.customer

/** Categorías semánticas internas de UI. Sus IDs NO son IDs del catálogo remoto. */
enum class CustomerType(val id: Int, val displayName: String, val skipKoajLovers: Boolean) {
    ESTANDAR(0, "Estándar", false),
    VIP(1, "VIP", false),
    EMPLEADO(2, "Empleado", true);

    companion object {
        fun fromId(id: Int?): CustomerType? = entries.firstOrNull { it.id == id }
        fun fromIdOrUnknown(id: Int?): CustomerType = fromId(id) ?: ESTANDAR
        val supportedTypes: List<CustomerType> = entries
    }
}
