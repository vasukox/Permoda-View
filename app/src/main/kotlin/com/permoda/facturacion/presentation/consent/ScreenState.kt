package com.permoda.facturacion.presentation.consent

import com.permoda.facturacion.core.model.CustomerData
import com.permoda.facturacion.core.scenario.CustomerScenario

/**
 * Estado visual de la pantalla del cliente.
 *
 * Diseñada para que las transiciones sean explícitas y testeables.
 */
sealed class ScreenState {
    /** 1. Spinner centrado + "Obteniendo datos del cliente…". */
    data object Loading : ScreenState()

    /**
     * 1b. Spinner + mensaje personalizado. Se usa durante la consulta a
     * la DIAN (HKA) para que el cliente vea "Validando con DIAN…".
     */
    data class LoadingWithMessage(val message: String) : ScreenState()

    /**
     * 1b-bis. El flujo está detenido esperando una decisión del **vendedor**
     * (crear el cliente / crearlo completo). El cliente no tiene ninguna
     * acción disponible: sólo ve que hay algo en curso.
     */
    data object WaitingForVendor : ScreenState()

    /**
     * 1c. Captura inicial: sólo "Tipo de documento" + "Número" + Continuar.
     * Se usa cuando el POS no mandó documento y hay que pedírselo al cliente
     * antes de poder consultar la DIAN.
     */
    data object AwaitingDocument : ScreenState()

    /*
     * 1d. `Validated` — el estado intermedio post-consulta DIAN ("Datos
     * validados en la DIAN") se ELIMINÓ.
     *
     * Nunca llegó a emitirse: ningún punto del flujo lo construía, así que
     * su zona del layout jamás se pintó. De la consulta a la DIAN se pasa
     * directo a [Showing].
     *
     * El resultado real de la DIAN no se perdió — lo consume
     * `CustomerScenario.afterDian` para decidir el escenario, y el vendedor
     * lo ve en el badge y el banner de su VendorScreen, que es quien puede
     * actuar sobre esa información. Al cliente sólo le agregaba un paso.
     */

    /**
     * 2. Formulario con los 5 campos editables.
     *
     * @param scenario decide el subtítulo: en los escenarios de creación se
     *   avisa "Cliente no creado en HioPos".
     */
    data class Showing(
        val customer: CustomerData,
        val scenario: CustomerScenario = CustomerScenario.EDIT
    ) : ScreenState()

    /**
     * 2b. Confirmación del **Consumidor Final**: datos genéricos ya cargados
     * y en modo lectura, con un único botón Aceptar.
     *
     * No es editable a propósito — son valores fijos de negocio
     * (222222222222), no datos que el cliente pueda corregir.
     */
    data class ConsumidorFinalReview(val customer: CustomerData) : ScreenState()

    /**
     * 2c. Invitación a la comunidad **Koaj Lovers**. Aparece tras Aceptar en
     * los escenarios nominales. Se salta para el Consumidor Final: no tiene
     * sentido ofrecer una comunidad de descuentos a un cliente genérico.
     */
    data object Loyalty : ScreenState()

    /**
     * 3. Cierre. Check verde sobre cream; si el cliente se unió a Koaj Lovers
     * ([joinedLoyalty] = true) muestra la bienvenida a la comunidad.
     */
    data class Confirmed(val joinedLoyalty: Boolean = false) : ScreenState()

    /** 4. Gris/rojo suave + "Esperando corrección del vendedor…". Cierre. */
    data object Rejected : ScreenState()

    /**
     * 5. Icono + mensaje + botón Reintentar.
     *
     * Reservado para fallos técnicos irrecuperables (XML ilegible, sin
     * pantalla de cliente). **Un fallo de la DIAN nunca llega aquí**: se
     * degrada a [Showing] con banner de aviso.
     */
    data class Error(val message: String) : ScreenState()

    /** 6. Defensivo. Caso no debería ocurrir. */
    data object NoData : ScreenState()
}
