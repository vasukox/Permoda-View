package com.permoda.facturacion.core.customer

/** Membresía explícita; ni el cajero ni una falta de respuesta convierten un empleado. */
object CustomerTypeResolver {
    @Suppress("UNUSED_PARAMETER")
    fun resolve(incomingTypeId: Int?, cashierEmployeeChoice: CustomerType?,
                loyaltyOptIn: Boolean?, isConsumidorFinal: Boolean): CustomerType {
        if (isConsumidorFinal) return CustomerType.ESTANDAR
        return when (CustomerType.fromIdOrUnknown(incomingTypeId)) {
            CustomerType.EMPLEADO -> CustomerType.EMPLEADO
            CustomerType.VIP -> CustomerType.VIP // No representa una revocación de membresía.
            CustomerType.ESTANDAR -> if (loyaltyOptIn == true) CustomerType.VIP else CustomerType.ESTANDAR
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun offersKoajLovers(incomingTypeId: Int?, cashierEmployeeChoice: CustomerType?,
                         isConsumidorFinal: Boolean): Boolean =
        !isConsumidorFinal && CustomerType.fromIdOrUnknown(incomingTypeId) != CustomerType.EMPLEADO

    @Suppress("UNUSED_PARAMETER")
    fun offersEmployeeChoice(incomingTypeId: Int?): Boolean = false

    fun project(incomingTypeId: Int?, cashierEmployeeChoice: CustomerType?, loyaltyOptIn: Boolean?,
                koajActive: Boolean, koajDecided: Boolean, isConsumidorFinal: Boolean): TypeProjection {
        val type = resolve(incomingTypeId, cashierEmployeeChoice, loyaltyOptIn, isConsumidorFinal)
        return TypeProjection(type, type == CustomerType.ESTANDAR && !koajDecided && koajActive &&
            offersKoajLovers(incomingTypeId, cashierEmployeeChoice, isConsumidorFinal))
    }

    data class TypeProjection(val type: CustomerType, val pendingKoajDecision: Boolean)
}
