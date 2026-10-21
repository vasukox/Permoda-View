package com.permoda.facturacion.core.totalize

import com.permoda.facturacion.core.customer.ConsumidorFinal
import com.permoda.facturacion.core.model.CustomerData
import com.permoda.facturacion.core.modify.ModifyDocumentXml
import com.permoda.facturacion.core.validation.CustomerValidator

/**
 * Control de datos del cliente al totalizar la venta.
 *
 * HioPos llama a MODIFY_DOCUMENT antes de abrir las formas de pago
 * (`CallBeforeTotalizeSale`, HPCL.025 0.6.4 pág. 10). Con `CanContinue=false`,
 * un RESULT_CANCELED + ErrorMessage hace que el POS muestre el mensaje,
 * restaure el documento y no pase al cobro (`BeforeTotalProcess` de 15.14.3).
 * Así se validan también los clientes que ya estaban en el maestro antes de
 * que existiera el formulario.
 *
 * Política ante fallos: sólo bloquea cuando **los datos del cliente** no
 * cumplen. Si el módulo no puede leer el documento, deja seguir y lo deja en
 * el log: una falla técnica del módulo no puede detener la caja.
 *
 * Lógica pura — sin Android — para que sea testeable en la JVM.
 */
object TotalizeGate {

    /** Eventos de totalizar venta (`DocumentAPI.getBehaviorDescription` de 15.14.3). */
    val TOTALIZE_TRIGGERS = setOf("TOTALIZE_SALE", "TOTALIZE_SALE_AFTER_MIXANDMATCH")

    sealed interface Decision {
        /** @param reason sólo para el log; nunca lleva datos personales. */
        data class Allow(val reason: String) : Decision

        /** @param message lo que HioPos muestra al cajero. */
        data class Block(val message: String, val fields: List<CustomerValidator.Field>) : Decision
    }

    /**
     * @param documentXml `null` si el POS no envió el documento o no se pudo leer.
     */
    fun decide(triggerEvent: String?, documentXml: String?): Decision {
        if (triggerEvent !in TOTALIZE_TRIGGERS) return Decision.Allow("evento=${triggerEvent ?: "ninguno"} no se valida")
        if (documentXml.isNullOrBlank()) return Decision.Allow("documento no disponible; se deja seguir")

        val customer = try {
            ModifyDocumentXml.parseDocumentCustomer(documentXml)
        } catch (e: Exception) {
            return Decision.Allow("documento ilegible (${e.javaClass.simpleName}); se deja seguir")
        }
        return decide(customer)
    }

    fun decide(customer: CustomerData): Decision {
        if (isWithoutCustomer(customer)) return Decision.Allow("venta sin cliente asignado")
        if (isConsumidorFinal(customer)) return Decision.Allow("consumidor final")

        val result = CustomerValidator.validate(customer)
        if (result.isValid) return Decision.Allow("cliente válido")
        return Decision.Block(message(result), result.fields)
    }

    /**
     * El texto que ve el cajero: qué pasa, qué está mal campo por campo y qué
     * hacer. HioPos lo muestra en un diálogo, que respeta los saltos de línea.
     */
    fun message(result: CustomerValidator.Result): String = buildString {
        append("No se puede facturar: el cliente tiene datos incorrectos.\n\n")
        result.issues.forEach { append("• ").append(it.message).append('\n') }
        append("\nEdite el cliente, guarde los cambios y vuelva a totalizar.")
    }

    /** El POS manda un `<Customer>` vacío (o ninguno) cuando la venta no tiene cliente. */
    private fun isWithoutCustomer(c: CustomerData): Boolean =
        c.customerName.isBlank() && c.fiscalId.isNullOrBlank() && c.email.isNullOrBlank() && c.phone.isNullOrBlank()

    /**
     * El genérico de Permoda (222222222222) y el "CONSUMIDOR FINAL" por defecto de
     * HioPos, que se muestra con documento en ceros: ninguno es un cliente que
     * el cajero pueda corregir.
     */
    private fun isConsumidorFinal(c: CustomerData): Boolean {
        val id = c.fiscalId?.trim().orEmpty()
        if (ConsumidorFinal.matches(c)) return true
        if (id.isNotEmpty() && id.all { it == '0' }) return true
        return id.isEmpty() && c.customerName.trim().equals(ConsumidorFinal.NAME, ignoreCase = true)
    }
}
