package com.permoda.facturacion.presentation.consent

import com.permoda.facturacion.core.model.CustomerData
import com.permoda.facturacion.core.scenario.CustomerScenario

/**
 * Resultado del flujo de validación del cliente.
 *
 * Sólo hay dos salidas contractuales hacia HioPosCloud:
 *  - [Accepted]  → RESULT_OK   + extra CustomerResult
 *  - [Rejected]  → RESULT_CANCELED + extra ErrorMessage
 *
 * [Error] representa un fallo técnico irrecuperable (XML ilegible, sin
 * pantalla de cliente vinculada). Los fallos de la DIAN **no** llegan aquí:
 * degradan a formulario manual.
 */
sealed class ConsentResult {

    /**
     * @param scenario con qué escenario se resolvió. HioPosCloud lo necesita
     *   para saber si tiene que crear el cliente
     *   ([CustomerScenario.createsCustomer]) o si es el genérico.
     * @param loyaltyOptIn decisión sobre la comunidad Koaj Lovers. `null`
     *   cuando no se preguntó: pantalla deshabilitada por configuración, o
     *   Consumidor Final. Para clientes VIP también se omite (id=1) — son
     *   clientes especiales que no necesitan la membresía.
     */
    data class Accepted(
        val customer: CustomerData,
        val extraFieldsXml: String,
        val scenario: CustomerScenario = CustomerScenario.EDIT,
        val loyaltyOptIn: Boolean? = null
    ) : ConsentResult() {

        val isConsumidorFinal: Boolean
            get() = scenario == CustomerScenario.CONSUMIDOR_FINAL
    }

    data class Rejected(val reason: String) : ConsentResult()

    data class Error(val message: String) : ConsentResult()
}
