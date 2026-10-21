package com.permoda.facturacion.core.consent

import com.permoda.facturacion.core.customer.CustomerTypeResolver
import com.permoda.facturacion.core.model.CustomerData
import com.permoda.facturacion.core.scenario.CustomerScenario
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Estado compartido entre la pantalla del cliente (ConsentPresentation)
 * y la del operador/QA (TestDriverActivity).
 *
 * Permite sincronización **bidireccional**:
 *  - Cuando el cliente edita un campo → ConsentScreen emite `updateCustomer(...)`.
 *  - Cuando el operador edita en su vista espejo → TestDriverActivity emite también.
 *  - Ambos lados observan el [state] y reflejan los cambios en su UI.
 *
 * Singleton (object) porque solo hay UN flujo de selección activo a la vez.
 * Se resetea cuando termina el flujo (`reset()`).
 */
object ConsentSharedState {

    data class State(
        val customer: CustomerData = CustomerData(customerName = ""),
        val warning: String? = null,
        val managedOperation: com.permoda.facturacion.core.manage.CustomerOperation? = null,
        val showMirror: Boolean = false,
        val scenario: CustomerScenario = CustomerScenario.CREATE_MANUAL,
        val stage: ConsentStage = ConsentStage.STARTING,
        /**
         * Orden pendiente del vendedor. Comando de un solo uso: la pantalla
         * del cliente lo atiende y llama a [consumeVendorCommand].
         */
        val vendorCommand: VendorCommand? = null,
        /**
         * Tipo de cliente con el que se va a enviar, y si todavía puede cambiar.
         *
         * Se publica acá y no se calcula en la pantalla del cajero porque los
         * tres insumos viven en sitios distintos: el tipo entrante en el
         * `<Document>`, la elección Empleado/normal en el estado interno de
         * `ValidationPresentation`, y la decisión de Koaj al final del flujo.
         * Que lo calcule quien los tiene todos es lo que garantiza que lo que el
         * cajero ve sea exactamente lo que viaja en el `ModifyDocumentResult`.
         *
         * `null` mientras no hay nada que afirmar (antes de capturar el
         * documento).
         */
        val customerTypeProjection: CustomerTypeResolver.TypeProjection? = null,
        /** La compuerta actual es el recordatorio "¿ya lo buscaste?" del botón Nuevo cliente. */
        val searchReminder: Boolean = false,
        /**
         * Campo que el cajero está escribiendo ("customerName", "fiscalDocumentType",
         * "fiscalId", "email" o "phone"), o `null`. La pantalla del cliente lo
         * resalta para que se vea dónde se escribe.
         */
        val activeField: String? = null
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Snapshot inmutable actual. Útil para consumidores que no necesitan observar. */
    val current: State get() = _state.value

    fun updateCustomer(customer: CustomerData) {
        _state.update { it.copy(customer = customer) }
    }

    fun updateWarning(warning: String?) {
        _state.update { it.copy(warning = warning) }
    }

    fun setManagementOperation(operation: com.permoda.facturacion.core.manage.CustomerOperation?) {
        _state.update { it.copy(managedOperation = operation) }
    }

    fun setShowMirror(show: Boolean) {
        _state.update { it.copy(showMirror = show) }
    }

    /**
     * El escenario se refina durante el flujo (CREATE_MANUAL → CREATE_FROM_DIAN
     * cuando la DIAN responde, o → CONSUMIDOR_FINAL si el cliente se niega),
     * así que el mirror del vendedor lo observa en vez de recibirlo una vez.
     */
    fun setScenario(scenario: CustomerScenario) {
        _state.update { it.copy(scenario = scenario) }
    }

    /** Publica el punto del flujo. Lo consumen las dos pantallas. */
    fun setStage(stage: ConsentStage) {
        _state.update { it.copy(stage = stage) }
    }

    /** Publica el tipo de cliente vigente. Lo consume el espejo del cajero. */
    fun setCustomerTypeProjection(projection: CustomerTypeResolver.TypeProjection?) {
        _state.update { it.copy(customerTypeProjection = projection) }
    }

    fun setSearchReminder(active: Boolean) {
        _state.update { it.copy(searchReminder = active) }
    }

    fun setActiveField(key: String?) {
        _state.update { it.copy(activeField = key) }
    }

    /** El vendedor dio una orden desde su pantalla. */
    fun sendVendorCommand(command: VendorCommand) {
        _state.update { it.copy(vendorCommand = command) }
    }

    /** Baja la orden una vez atendida, para que no se repita. */
    fun consumeVendorCommand() {
        _state.update { it.copy(vendorCommand = null) }
    }

    /** Restaura al estado inicial. Llamar al terminar cada flujo. */
    fun reset() {
        _state.value = State()
    }
}
