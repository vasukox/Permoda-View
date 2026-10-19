package com.permoda.facturacion.presentation.display

import com.permoda.facturacion.core.hka.DianStatus
import com.permoda.facturacion.core.model.CustomerData
import com.permoda.facturacion.presentation.consent.ScreenState

/**
 * Permite que el callback `onDocumentConfirmed` (típicamente en
 * [com.permoda.facturacion.entry.CustomerSelectionActivity]) actualice la
 * pantalla del cliente mientras se ejecuta la consulta HKA.
 *
 * Es seguro llamar estos métodos desde cualquier thread; internamente
 * se hace `post` al thread principal de la Presentation.
 */
class ConsentController internal constructor(
    private val onShow: (CustomerData, String?, DianStatus) -> Unit,
    private val onState: (ScreenState) -> Unit,
    private val onErrorMessage: (String) -> Unit
) {

    /**
     * Muestra el resultado de la consulta a la DIAN en la pantalla.
     *
     * Flujo visual:
     *   1. Estado intermedio (~900 ms) cuyo copy depende de [status]:
     *      validado / no registrado / no verificado.
     *   2. Pasa automáticamente a la pantalla de 5 campos con stagger fade-in,
     *      para que el cliente complete los datos faltantes.
     *
     * @param customer datos mergeados finales (DIAN + locales).
     * @param warning texto a mostrar como banner amarillo; `null` si no hay.
     * @param status qué respondió la DIAN realmente. **Debe** venir del
     *   merge: pasar `VALIDATED` cuando la DIAN devolvió 404 le afirma al
     *   cliente algo falso.
     */
    fun showCustomer(
        customer: CustomerData,
        warning: String? = null,
        status: DianStatus = DianStatus.UNVERIFIED
    ) {
        onShow(customer, warning, status)
    }

    /**
     * Cambia el estado visual manualmente (ej. `LoadingWithMessage("Validando…")`,
     * `Error("...")`, etc.). Usar solo en casos especiales; el flujo normal
     * debería usar [showCustomer].
     */
    fun updateState(state: ScreenState) {
        onState(state)
    }

    /**
     * Muestra un mensaje de error breve sin cambiar de zona.
     * (Reservado para errores inline; no usado actualmente.)
     */
    fun showInlineError(message: String) {
        onErrorMessage(message)
    }
}
