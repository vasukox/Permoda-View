package com.permoda.facturacion.presentation.display

import android.app.Presentation
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.ViewGroup
import android.widget.FrameLayout
import com.permoda.facturacion.core.ParsedCustomer
import com.permoda.facturacion.core.consent.ConsentSharedState
import com.permoda.facturacion.core.consent.ConsentStage
import com.permoda.facturacion.core.consent.VendorCommand
import com.permoda.facturacion.core.customer.ConsumidorFinal
import com.permoda.facturacion.core.customer.CustomerType
import com.permoda.facturacion.core.customer.CustomerTypeResolver
import com.permoda.facturacion.core.hka.DianStatus
import com.permoda.facturacion.core.loyalty.LoyaltyConfig
import com.permoda.facturacion.core.loyalty.LoyaltyOutcome
import com.permoda.facturacion.core.model.CustomerData
import com.permoda.facturacion.core.scenario.CustomerScenario
import com.permoda.facturacion.core.validation.CustomerValidator
import com.permoda.facturacion.presentation.consent.ConsentResult
import com.permoda.facturacion.presentation.consent.ConsentScreen
import com.permoda.facturacion.presentation.consent.ScreenState
import com.permoda.facturacion.core.manage.ManagementStart
import com.permoda.facturacion.core.manage.CustomerOperation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Muestra el formulario de validación del cliente en la pantalla del cliente.
 *
 * La terminal Hiopos siempre expone esa pantalla. Si por alguna razón
 * no está disponible, devolvemos un error claro para que el caller lo muestre.
 *
 * Importante: la inicialización del [ConsentScreen] ocurre dentro de
 * [ValidationPresentation.onCreate] — la única garantía de que el contexto
 * del Display está listo. Esto evita la race condition donde se llamaba
 * a start() antes de que la Presentation tuviera su jerarquía de views.
 */
object ConsentPresentation {

    private const val TAG = "ConsentPresentation"

    /**
     * Lanza la presentación del formulario de consentimiento.
     *
     * El escenario **no** lo decide el caller: se resuelve dentro, cuando el
     * cliente teclea su documento. Es el único dato que permite saber de qué
     * cliente estamos hablando, y sólo PermodaView lo captura.
     *
     * @param onDocumentConfirmed se invoca para consultar la DIAN, ya con el
     *   vendedor autorizado y sólo para clientes nuevos.
     */
    suspend fun request(
        context: Context,
        parsed: ParsedCustomer,
        loadingMessage: String? = null,
        onDocumentConfirmed: (suspend (tipo: String, numero: String, controller: ConsentController) -> Unit)? = null,
        documentTypes: List<String> = com.permoda.facturacion.core.hka.TipoDocumentoMapper.supportedShortNames(),
        loyaltyConfig: LoyaltyConfig = LoyaltyConfig.defaults(),
        onLoyaltyOutcome: ((LoyaltyOutcome) -> Unit)? = null,
        onScenarioResolved: ((CustomerScenario) -> Unit)? = null,
        validationRules: CustomerValidator.Rules = CustomerValidator.Rules(),
        managedOperation: CustomerOperation? = null
    ): ConsentResult {
        val display = secondaryDisplay(context)
        if (display == null) {
            val manager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val info = manager.displays.joinToString {
                "id=${it.displayId} valid=${it.isValid} name=${it.name}"
            }
            Log.w(TAG, "Pantallas disponibles: $info")
            return ConsentResult.Error("No hay pantalla del cliente vinculada")
        }
        return showOn(
            context = context,
            display = display,
            parsed = parsed,
            loadingMessage = loadingMessage,
            onDocumentConfirmed = onDocumentConfirmed,
            documentTypes = documentTypes,
            loyaltyConfig = loyaltyConfig,
            onLoyaltyOutcome = onLoyaltyOutcome,
            onScenarioResolved = onScenarioResolved,
            validationRules = validationRules,
            managedOperation = managedOperation
        )
    }

    private suspend fun showOn(
        context: Context,
        display: Display,
        parsed: ParsedCustomer,
        loadingMessage: String?,
        onDocumentConfirmed: (suspend (String, String, ConsentController) -> Unit)?,
        documentTypes: List<String>,
        loyaltyConfig: LoyaltyConfig,
        onLoyaltyOutcome: ((LoyaltyOutcome) -> Unit)?,
        onScenarioResolved: ((CustomerScenario) -> Unit)?,
        validationRules: CustomerValidator.Rules,
        managedOperation: CustomerOperation?
    ): ConsentResult = suspendCancellableCoroutine { cont ->
        // Inicializa el estado compartido con los datos que llegaron del POS
        ConsentSharedState.reset()
        ConsentSharedState.setManagementOperation(managedOperation)
        ConsentSharedState.updateCustomer(parsed.data)
        ConsentSharedState.setShowMirror(true)  // pedir al operador que muestre el mirror

        val holder = PresentationHolder()
        val onResult: (ConsentResult) -> Unit = complete@{ result ->
            if (!cont.isActive) return@complete
            cont.resume(result)
            runCatching { holder.presentation?.dismiss() }
            ConsentSharedState.reset()  // limpia al terminar
        }
        // Si el vendedor cancela desde su pantalla, se cancela la corrutina:
        // hay que bajar la Presentation o quedaría colgada en la 11".
        cont.invokeOnCancellation {
            runCatching { holder.presentation?.dismiss() }
            ConsentSharedState.reset()
        }
        val presentation = ValidationPresentation(
            outerContext = context,
            display = display,
            initialData = parsed.data,
            extraFieldsXml = parsed.extraFieldsXml,
            loadingMessage = loadingMessage,
            onDocumentConfirmed = onDocumentConfirmed,
            documentTypes = documentTypes,
            loyaltyConfig = loyaltyConfig,
            onLoyaltyOutcome = onLoyaltyOutcome,
            onScenarioResolved = onScenarioResolved,
            validationRules = validationRules,
            managedOperation = managedOperation,
            onComplete = onResult
        )
        holder.presentation = presentation
        presentation.show()
    }

    private class PresentationHolder {
        var presentation: ValidationPresentation? = null
    }

    private fun secondaryDisplay(context: Context): Display? {
        val manager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        return manager.displays.firstOrNull {
            it.displayId != Display.DEFAULT_DISPLAY && it.isValid
        }
    }
}

/**
 * Presentation interna. Su ciclo de vida crea la jerarquía de views y
 * monta el [ConsentScreen] con los datos ya pasados.
 */
private class ValidationPresentation(
    outerContext: Context,
    display: Display,
    private val initialData: CustomerData,
    private val extraFieldsXml: String,
    private val loadingMessage: String?,
    private val onDocumentConfirmed: (suspend (String, String, ConsentController) -> Unit)?,
    private val documentTypes: List<String>,
    private val loyaltyConfig: LoyaltyConfig,
    private val onLoyaltyOutcome: ((LoyaltyOutcome) -> Unit)?,
    private val onScenarioResolved: ((CustomerScenario) -> Unit)?,
    private val validationRules: CustomerValidator.Rules,
    private val managedOperation: CustomerOperation?,
    private val onComplete: (ConsentResult) -> Unit
) : Presentation(outerContext, display) {

    private val enrichScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var enrichJob: Job? = null
    private var queryGeneration = 0L

    /**
     * Escenario vigente. Se resuelve cuando el cliente teclea su documento y
     * se refina cuando la DIAN responde (CREATE_MANUAL → CREATE_FROM_DIAN) o
     * cuando se elige Consumidor Final.
     *
     * Arranca en [CustomerScenario.CREATE_MANUAL] como provisional: es el caso
     * más restrictivo, así que si algo fallara antes de tener el documento no
     * afirmaríamos que el cliente ya existía.
     */
    private var scenario: CustomerScenario = CustomerScenario.CREATE_MANUAL

    /**
     * Snapshot tomado en el instante del Aceptar. Se congela aquí porque la
     * pantalla de Koaj Lovers ya no muestra los campos: lo que se devuelve al
     * POS es lo que el cliente confirmó, no lo que el estado compartido
     * tuviera después.
     */
    private var acceptedCustomer: CustomerData? = null

    /**
     * Documento que tecleó el cliente. Se guarda porque la consulta a la DIAN
     * ocurre más tarde, cuando el vendedor autoriza en la compuerta.
     */
    private var pendingDocType: String? = null
    private var pendingDocId: String? = null

    /**
     * Elección del cajero entre Empleado y "compra como cliente normal".
     *
     * Sólo se puebla cuando el POS trajo un cliente Empleado —es la única
     * situación en la que se le ofrece elegir, ver
     * [CustomerTypeResolver.offersEmployeeChoice]—. `null` en todo el resto de
     * los casos, que es la inmensa mayoría.
     *
     * Vive acá y no en el cliente del estado compartido a propósito: no es un
     * dato del cliente, es una decisión del cajero sobre esta venta. Mezclarlas
     * fue lo que hizo que el tipo elegido en la captura se escribiera encima del
     * tipo real del maestro.
     */
    private var employeeChoice: CustomerType? = null

    /**
     * Decisión de Koaj Lovers, ya tomada o todavía pendiente.
     *
     * [loyaltyDecided] es lo que separa "hoy es Estándar y puede subir a VIP" de
     * "es Estándar, punto". Sin esa distinción el cajero vería "Estándar" durante
     * todo el formulario y no sabría que al cliente le falta un paso.
     */
    private var loyaltyDecided: Boolean = false
    private var loyaltyOptInSoFar: Boolean? = null

    private lateinit var screen: ConsentScreen

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        setContentView(container)

        screen = ConsentScreen(container)
        screen.bind(
            customer = initialData,
            documentTypes = documentTypes,
            onAccept = { handleAccept() },
            onReject = {
                screen.render(ScreenState.Rejected)
                Handler(Looper.getMainLooper()).postDelayed({
                    onComplete(ConsentResult.Rejected("Cliente rechazó la confirmación de datos"))
                }, REJECTED_CLOSE_DELAY_MS)
            },
            onRetry = { screen.render(ScreenState.Showing(initialData, scenario)) },
            onDocumentConfirmed = { tipo, numero -> handleDocumentConfirmed(tipo, numero) },
            onLoyaltyDecision = { outcome -> handleLoyaltyDecision(outcome) },
            onConsumidorFinalAccept = { handleConsumidorFinalAccept() },
            loyaltyTimeoutMs = if (loyaltyConfig.isActive) loyaltyConfig.timeoutMs else 0L,
            loyaltyAllowDecline = loyaltyConfig.allowDecline,
            validationRules = validationRules
        )

        renderInitialState(container)

        // Observar cambios externos del SharedState (mirror del operador)
        // para reflejar ediciones del operador en la pantalla del cliente.
        observeSharedState()
    }

    /**
     * Paso 1-2 del flujo, ya resuelto por el caller:
     *
     *  - EDIT (existe en HioPosCloud) → datos locales directo, **sin DIAN**.
     *  - No existe + ya hay documento → consultar DIAN de una.
     *  - No existe + sin documento    → pedirle el documento al cliente.
     */
    /**
     * **La entrada del flujo es siempre el documento.**
     *
     * PermodaView es el único punto donde se captura, así que sin él no hay
     * factura y no se puede saber de qué cliente estamos hablando. Recién con
     * el documento en mano se resuelve el escenario.
     */
    private fun renderInitialState(container: FrameLayout) {
        if (ManagementStart.resolve(managedOperation, initialData) == ManagementStart.EDIT_FORM) {
            publishCustomer(initialData)
            publishScenario(CustomerScenario.EDIT)
            ConsentSharedState.setStage(ConsentStage.FILLING)
            screen.updateData(initialData)
            screen.render(ScreenState.Showing(initialData, CustomerScenario.EDIT))
        } else if (ManagementStart.resolve(managedOperation, initialData) == ManagementStart.CREATE_LOOKUP) {
            handleDocumentConfirmed(requireNotNull(initialData.fiscalDocumentType), requireNotNull(initialData.fiscalId))
        } else if (ManagementStart.needsSearchReminder(managedOperation, initialData)) {
            ConsentSharedState.setSearchReminder(true)
            ConsentSharedState.setStage(ConsentStage.AWAITING_VENDOR_APPROVAL)
            screen.render(ScreenState.WaitingForVendor)
        } else {
            ConsentSharedState.setStage(ConsentStage.AWAITING_DOCUMENT)
            screen.render(ScreenState.AwaitingDocument)
        }
    }

    /**
     * Compuerta superada. El documento ya está capturado, así que lo único que
     * falta es saber si hay que consultar la DIAN:
     *
     *  - Escenario 3 (existe pero incompleto) → **no** se consulta: sólo se
     *    consulta para clientes nuevos. Directo al formulario.
     *  - Cliente nuevo → ahora sí se consulta la DIAN; su respuesta decide si
     *    es el escenario 2 (trae datos) o el 4 (formulario vacío).
     */
    private fun onVendorApproved() {
        // "Continuar como nuevo" tras el recordatorio de buscar: recién ahora se captura.
        if (ConsentSharedState.current.searchReminder) {
            ConsentSharedState.setSearchReminder(false)
            ConsentSharedState.setStage(ConsentStage.AWAITING_DOCUMENT)
            screen.render(ScreenState.AwaitingDocument)
            return
        }
        val tipo = pendingDocType
        val numero = pendingDocId

        if (scenario == CustomerScenario.COMPLETE_EXISTING) {
            val withDocument = initialData.copy(
                fiscalDocumentType = tipo ?: initialData.fiscalDocumentType,
                fiscalId = numero ?: initialData.fiscalId
            )
            ConsentSharedState.setStage(ConsentStage.FILLING)
            screen.updateData(withDocument)
            screen.render(ScreenState.Showing(withDocument, CustomerScenario.COMPLETE_EXISTING))
            publishScenario(CustomerScenario.COMPLETE_EXISTING)
            return
        }

        if (tipo == null || numero == null) {
            // Defensivo: no debería ocurrir, el documento se captura antes de
            // llegar a la compuerta.
            ConsentSharedState.setStage(ConsentStage.AWAITING_DOCUMENT)
            screen.render(ScreenState.AwaitingDocument)
            return
        }

        // ⚠️ 14-08-2026 · Cambio crítico de UX: el cliente **NO** debe ver
        // ninguna pantalla de "validando" mientras se consulta la DIAN.
        // La pantalla del cliente sólo muestra estados del propio cliente
        // (esperando doc, llenando datos, Koaj Lovers). Mientras la DIAN
        // responde (o se cae al timeout), el cliente sigue en su último
        // estado visible — lo más natural es esperar a que el cajero le
        // pida llenar el formulario, sin un loader encima.
        //
        // Lo que sí hacemos: stage interno `VALIDATING_DIAN` para que
        // cualquier observer del estado sepa que la consulta está en vuelo,
        // pero NO se renderiza `ScreenState.LoadingWithMessage`. La consulta
        // vive con timeout de 15 s en [HkaConfig.DEFAULT_TIMEOUT_MS]; si
        // vence, caemos al formulario manual (escenario CREATE_MANUAL).
        ConsentSharedState.setStage(ConsentStage.VALIDATING_DIAN)
        queryDian(tipo, numero)
    }

    private fun observeSharedState() {
        enrichScope.launch {
            var lastAppliedCustomer: CustomerData? = null
            ConsentSharedState.state.collectLatest { shared ->
                if (!isShowing) return@collectLatest

                // Órdenes del vendedor. Se atienden antes de cualquier
                // sincronización de datos y se bajan de inmediato para que no
                // se repitan en la siguiente emisión del flow.
                shared.vendorCommand?.let { command ->
                    ConsentSharedState.consumeVendorCommand()
                    runOnUi { handleVendorCommand(command) }
                    return@collectLatest
                }

                // No sincronizamos durante el Consumidor Final: sus datos son
                // fijos y una edición del cajero no debe alterarlos.
                if (scenario == CustomerScenario.CONSUMIDOR_FINAL) return@collectLatest
                // Solo aplicar si el customer cambió y NO fue por nuestro propio snapshot
                val current = screen.snapshotCustomer()
                if (shared.customer != current && shared.customer != lastAppliedCustomer) {
                    lastAppliedCustomer = shared.customer
                    runOnUi { screen.applyExternal(shared.customer) }
                }
                if (shared.warning != null) {
                    runOnUi { screen.setWarning(shared.warning) }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Consumidor Final
    // ------------------------------------------------------------------

    /**
     * Atiende una orden del vendedor.
     *
     * Es el único punto donde el vendedor mueve el flujo, así que aquí se
     * concentra la validación de que la orden tenga sentido en el estado
     * actual (p.ej. no aprobar la creación dos veces).
     */
    private fun handleVendorCommand(command: VendorCommand) {
        val stage = ConsentSharedState.current.stage
        when (command) {
            // La entrada del flujo. Sólo vale mientras se está capturando.
            is VendorCommand.ConfirmDocument ->
                if (stage == ConsentStage.AWAITING_DOCUMENT) {
                    handleDocumentConfirmed(
                        command.tipo,
                        command.numero,
                        command.employeeChoiceTypeId
                    )
                }

            // Sólo tiene efecto en la compuerta: evita que un doble toque
            // reabra un paso ya superado.
            VendorCommand.Approve ->
                if (stage == ConsentStage.AWAITING_VENDOR_APPROVAL) onVendorApproved()

            // El cajero confirma los datos. Pasa a Koaj, donde el cliente da
            // su único toque obligatorio.
            VendorCommand.AcceptData ->
                if (stage == ConsentStage.FILLING) handleAccept()

            VendorCommand.BackToDocument -> backToDocumentCapture()

            VendorCommand.ConsumidorFinal ->
                if (scenario != CustomerScenario.CONSUMIDOR_FINAL) handleConsumidorFinal()

            // Continuar no es aceptar: no se audita como decisión del cliente.
            VendorCommand.ContinueWithoutLoyalty ->
                if (stage == ConsentStage.LOYALTY) {
                    closeLoyaltyDecision(optIn = null)
                    completeAcceptedSilent(loyaltyOptIn = null)
                }
        }
    }

    /**
     * Se factura al genérico 222222222222. Llega por dos caminos: el botón del
     * vendedor, o su "No" en cualquiera de las dos compuertas.
     *
     * HU-HIOPOS · al click en CF, vamos directo a `onComplete(Accepted(CF))`
     * sin pasar por la pantalla de confirmación intermedia: menos clicks,
     * menos latencia. El vendor ve el genérico en su espejo y al instante
     * la venta vuelve al POS con el cliente 222222222222.
     */
    private fun handleConsumidorFinal() {
        if (managedOperation != null) return // El genérico se selecciona en el POS nativo.
        scenario = CustomerScenario.CONSUMIDOR_FINAL
        val generic = ConsumidorFinal.build(extraFieldsXml)
        acceptedCustomer = generic
        ConsentSharedState.setStage(ConsentStage.FILLING)
        // El vendor ve el genérico en su espejo mientras la venta cierra.
        ConsentSharedState.updateCustomer(generic)
        ConsentSharedState.setScenario(CustomerScenario.CONSUMIDOR_FINAL)
        // El genérico nunca ve Koaj: la decisión queda cerrada acá mismo, y el
        // cajero tiene que ver "Estándar" firme y no "puede subir a VIP".
        loyaltyDecided = true
        loyaltyOptInSoFar = null
        publishTypeProjection()
        runOnUi {
            onComplete(buildAccepted(generic, generic.extraFieldsXml, loyaltyOptIn = null))
        }
    }

    /**
     * Punto **único** donde se construye el `Accepted`, y por tanto el único
     * donde se estampa el tipo de cliente.
     *
     * Antes el tipo se fijaba en la captura del documento. No puede: la regla de
     * negocio lo hace depender de la decisión de Koaj Lovers, que ocurre al
     * final. Concentrarlo acá es lo que garantiza que las cuatro salidas del
     * flujo —Consumidor Final (por botón o por confirmación), aceptar con
     * membresía y aceptar sin ella— apliquen la misma regla. Cuando estaban
     * repartidas, tres de las cuatro se olvidaban del tipo.
     *
     * El `customerTypeId` de [customer] es el que trajo el POS: nadie lo escribe
     * durante el flujo, y [forgetPreviousIdentity] lo borra si el documento
     * tecleado resultó ser de otra persona.
     */
    private fun buildAccepted(
        customer: CustomerData,
        extras: String,
        loyaltyOptIn: Boolean?
    ): ConsentResult.Accepted {
        val resolved = CustomerTypeResolver.resolve(
            incomingTypeId = customer.customerTypeId,
            cashierEmployeeChoice = employeeChoice,
            loyaltyOptIn = loyaltyOptIn,
            isConsumidorFinal = scenario == CustomerScenario.CONSUMIDOR_FINAL
        )
        android.util.Log.i(
            "ConsentPresentation",
            "tipo de cliente → entrante=${customer.customerTypeId} " +
                "eleccionCajero=$employeeChoice koaj=$loyaltyOptIn " +
                "escenario=$scenario ⇒ ${resolved.name}(${resolved.id})"
        )
        return ConsentResult.Accepted(
            // id=0 (Estándar) se escribe igual en el modelo; es
            // `appendCustomerData` quien lo omite del XML porque equivale al
            // default del POS.
            customer = customer.copy(customerTypeId = resolved.id),
            extraFieldsXml = extras,
            scenario = scenario,
            loyaltyOptIn = loyaltyOptIn
        )
    }

    /**
     * Confirmó el Consumidor Final.
     *
     * Se salta la validación de campos obligatorios (el correo del genérico
     * está vacío por definición) y también Koaj Lovers: no tiene sentido
     * ofrecer una comunidad de descuentos a un cliente sin identidad.
     */
    private fun handleConsumidorFinalAccept() {
        val generic = acceptedCustomer ?: ConsumidorFinal.build(extraFieldsXml)
        publishScenario(CustomerScenario.CONSUMIDOR_FINAL)
        screen.render(ScreenState.Confirmed(joinedLoyalty = false))
        Handler(Looper.getMainLooper()).postDelayed({
            // El genérico ya trae sus extras con countryIso2 forzado.
            onComplete(buildAccepted(generic, generic.extraFieldsXml, loyaltyOptIn = null))
        }, ACCEPTED_CLOSE_DELAY_MS)
    }

    // ------------------------------------------------------------------
    // Aceptar / Koaj Lovers
    // ------------------------------------------------------------------

    /**
     * El cliente pulsó Aceptar. Los datos ya pasaron la validación mínima
     * dentro de [ConsentScreen] (botón deshabilitado si no).
     *
     * Después de aceptar se ofrece Koaj Lovers, salvo dos casos:
     *
     *  - **Empleado.** Regla de negocio: a un empleado jamás se le ofrece la
     *    comunidad. Si el cajero marcó que esta compra es "como cliente normal",
     *    ya no cuenta como empleado y sí la ve.
     *  - Operaciones apagó la pantalla por configuración (`KOAJ_ENABLED`).
     *
     * ⚠️ El VIP **sí** la ve. Antes se saltaba, cuando VIP significaba "cliente
     * especial de la casa". Hoy significa "ya es Koaj Lover", y quien decide qué
     * pasa si declina es [CustomerTypeResolver]: no lo degrada.
     */
    private fun handleAccept() {
        // La fuente de verdad es el estado compartido, no la pantalla del
        // cliente: el que llenó los datos fue el cajero.
        acceptedCustomer = ConsentSharedState.current.customer
        publishScenario(scenario)

        val typeId = acceptedCustomer?.customerTypeId
        val offersKoaj = CustomerTypeResolver.offersKoajLovers(
            incomingTypeId = typeId,
            cashierEmployeeChoice = employeeChoice,
            isConsumidorFinal = scenario == CustomerScenario.CONSUMIDOR_FINAL
        )

        android.util.Log.i(
            "ConsentPresentation",
            "handleAccept ← stage=${ConsentSharedState.current.stage} " +
                "scenario=${scenario} " +
                "customerTypeId=$typeId eleccionCajero=$employeeChoice " +
                "ofreceKoaj=$offersKoaj " +
                "loyaltyConfig.enabled=${loyaltyConfig.enabled} " +
                "loyaltyConfig.timeoutMs=${loyaltyConfig.timeoutMs} " +
                "loyaltyConfig.allowDecline=${loyaltyConfig.allowDecline} " +
                "loyaltyConfig.isActive=${loyaltyConfig.isActive}"
        )

        if (!loyaltyConfig.isActive) {
            android.util.Log.w("ConsentPresentation", "handleAccept → loyalty INACTIVA, completeAccepted directo (sin Koaj)")
            closeLoyaltyDecision(optIn = null)
            completeAccepted(loyaltyOptIn = null, joinedLoyalty = false)
            return
        }
        if (!offersKoaj) {
            // `loyaltyOptIn = null` y no `false`: no declinó, no se le preguntó.
            // La auditoría distingue las dos cosas y el campo `koajLoversOptIn`
            // se omite del XML cuando es null.
            android.util.Log.i(
                "ConsentPresentation",
                "handleAccept → tipo ${CustomerType.fromId(typeId)} no recibe Koaj Lovers"
            )
            closeLoyaltyDecision(optIn = null)
            completeAccepted(loyaltyOptIn = null, joinedLoyalty = false)
            return
        }
        ConsentSharedState.setStage(ConsentStage.LOYALTY)
        screen.render(ScreenState.Loyalty)
        android.util.Log.i("ConsentPresentation", "handleAccept → render ScreenState.Loyalty OK")
    }

    /**
     * El cliente decidió sobre Koaj Lovers (o venció el auto-avance).
     *
     * Si aceptó → cerramos con el overlay "✓ Datos confirmados" brevemente
     * antes de devolver al POS.
     *
     * Si rechazó (No acepto) → **no mostramos nada**. 14-08-2026: el cajero
     * interpretó ese overlay como "la venta se canceló", cuando en realidad
     * es sólo la decisión de Koaj Lovers. La decisión de Koaj NO es la
     * decisión de la venta — el cliente ya aceptó sus datos, los del POS
     * están bien, la venta sigue. Cerramos directo sin overlay para no
     * confundir.
     */
    private fun handleLoyaltyDecision(outcome: LoyaltyOutcome) {
        onLoyaltyOutcome?.invoke(outcome)
        closeLoyaltyDecision(optIn = outcome.optIn)
        if (outcome.optIn) {
            completeAccepted(loyaltyOptIn = true, joinedLoyalty = true)
        } else {
            completeAcceptedSilent(loyaltyOptIn = false)
        }
    }

    /**
     * Cierra la decisión de Koaj Lovers y republica el tipo.
     *
     * Se llama por los cuatro caminos que la cierran —aceptar, declinar, timeout
     * y "no se le preguntó"— para que el cajero vea el tipo definitivo en el
     * mismo instante en que deja de poder cambiar.
     *
     * @param optIn `null` cuando no se le preguntó. No es lo mismo que `false`
     *   para la auditoría, aunque para el tipo ambos dejen Estándar.
     */
    private fun closeLoyaltyDecision(optIn: Boolean?) {
        loyaltyDecided = true
        loyaltyOptInSoFar = optIn
        publishTypeProjection()
    }

    /**
     * Variante "silenciosa" de [completeAccepted] para cuando el cliente
     * rechaza Koaj Lovers. Sin overlay, sin delays visuales: pasamos
     * directo a [onComplete] con stage DONE para que el cajero deje de
     * esperar el feedback del cliente.
     */
    private fun completeAcceptedSilent(loyaltyOptIn: Boolean?) {
        ConsentSharedState.setStage(ConsentStage.DONE)
        Handler(Looper.getMainLooper()).post({
            onComplete(
                buildAccepted(acceptedCustomer ?: initialData, extraFieldsXml, loyaltyOptIn)
            )
        })
    }

    private fun completeAccepted(loyaltyOptIn: Boolean?, joinedLoyalty: Boolean) {
        ConsentSharedState.setStage(ConsentStage.DONE)
        screen.render(ScreenState.Confirmed(joinedLoyalty = joinedLoyalty))
        val delay = if (joinedLoyalty) JOINED_CLOSE_DELAY_MS else ACCEPTED_CLOSE_DELAY_MS
        Handler(Looper.getMainLooper()).postDelayed({
            onComplete(
                buildAccepted(acceptedCustomer ?: initialData, extraFieldsXml, loyaltyOptIn)
            )
        }, delay)
    }

    // ------------------------------------------------------------------
    // Consulta a la DIAN
    // ------------------------------------------------------------------

    /**
     * El cliente terminó de teclear su documento. **Aquí se decide todo**, y
     * sin tocar la red:
     *
     *  - Es el cliente que trajo el POS y sus datos sirven → escenario 1,
     *    directo a confirmar.
     *  - Es el cliente del POS pero le faltan datos → escenario 3, compuerta.
     *  - No es el del POS (o el POS no trajo ninguno) → nuevo, compuerta; la
     *    DIAN se consulta **después** de que el vendedor autorice.
     */
    /**
     * Vuelve a la captura del documento.
     *
     * Se descarta todo lo derivado del documento anterior: el escenario, la
     * consulta a la DIAN en vuelo y los datos que hubiera traído. Si no se
     * limpiara, un cajero que corrige un dígito seguiría viendo el nombre del
     * documento equivocado.
     */
    private fun backToDocumentCapture() {
        if (managedOperation == CustomerOperation.EDIT) return
        queryGeneration++
        enrichJob?.cancel()
        pendingDocType = null
        pendingDocId = null
        scenario = CustomerScenario.CREATE_MANUAL
        acceptedCustomer = null
        // Se descartan también la elección Empleado/normal y la decisión de Koaj:
        // pertenecían al cliente del documento anterior.
        employeeChoice = null
        loyaltyDecided = false
        loyaltyOptInSoFar = null

        ConsentSharedState.updateWarning(null)
        publishCustomer(initialData)
        publishScenario(scenario)
        ConsentSharedState.setStage(ConsentStage.AWAITING_DOCUMENT)
        screen.render(ScreenState.AwaitingDocument)
    }

    private fun handleDocumentConfirmed(tipo: String, numero: String) {
        if (managedOperation == CustomerOperation.EDIT) return
        if (managedOperation == CustomerOperation.CREATE) {
            queryGeneration++
            enrichJob?.cancel()
            pendingDocType = tipo
            pendingDocId = numero
            publishCustomer(forgetPreviousIdentity(tipo, numero).copy(customerTypeId = initialData.customerTypeId))
            publishScenario(CustomerScenario.CREATE_MANUAL)
            ConsentSharedState.setStage(ConsentStage.VALIDATING_DIAN)
            queryDian(tipo, numero)
            return
        }
        pendingDocType = tipo
        pendingDocId = numero

        scenario = CustomerScenario.afterDocument(initialData, numero, validationRules)
        publishScenario(scenario)

        // Es el cliente que trajo el POS: se conservan sus datos y su
        // customerId, sólo se normaliza el documento con lo que tecleó.
        val isTheIncomingCustomer = scenario == CustomerScenario.EDIT ||
            scenario == CustomerScenario.COMPLETE_EXISTING

        val customer = if (isTheIncomingCustomer) {
            initialData.copy(fiscalDocumentType = tipo, fiscalId = numero)
        } else {
            // Es OTRA persona. Hay que borrar toda la identidad del cliente
            // anterior; si no, el vendedor ve el nombre de quien estuviera
            // preseleccionado y el POS recibiría datos cruzados.
            forgetPreviousIdentity(tipo, numero)
        }

        publishCustomer(customer)

        when (scenario) {
            CustomerScenario.EDIT -> {
                ConsentSharedState.setStage(ConsentStage.FILLING)
                screen.updateData(customer)
                screen.render(ScreenState.Showing(customer, CustomerScenario.EDIT))
            }
            else -> {
                // Escenario 3 (completar) o cliente nuevo: en los dos casos
                // decide el vendedor antes de que el cliente siga.
                ConsentSharedState.setStage(ConsentStage.AWAITING_VENDOR_APPROVAL)
                screen.render(ScreenState.WaitingForVendor)
            }
        }
    }

    /**
     * Variante con la elección Empleado/normal del cajero — la que dispara la
     * captura.
     *
     * ⚠️ Antes este parámetro era el tipo elegido por el cajero en un
     * desplegable, y se escribía **encima** del `customerTypeId` del cliente en
     * el estado compartido. Eso pisaba el tipo real del maestro de HioPos con lo
     * que hubiera quedado seleccionado en la pantalla. Hoy la elección no toca al
     * cliente: se guarda aparte y sólo la consulta
     * [CustomerTypeResolver] al cerrar, que además la ignora salvo que el POS
     * haya traído un Empleado.
     */
    private fun handleDocumentConfirmed(tipo: String, numero: String, employeeChoiceId: Int?) {
        employeeChoice = CustomerType.fromId(employeeChoiceId)
        handleDocumentConfirmed(tipo, numero)
    }

    /**
     * Cliente en blanco con sólo el documento que se acaba de teclear.
     *
     * ⚠️ **`customerId = null` es obligatorio.** Es el identificador del
     * registro que HioPosCloud traía preseleccionado; si lo devolviéramos junto
     * a los datos de otra persona, el POS **sobrescribiría el cliente
     * equivocado**. Lo mismo con nombre, correo y teléfono: pertenecen a quien
     * estuviera antes, no a quien está en la caja.
     */
    private fun forgetPreviousIdentity(tipo: String, numero: String): CustomerData {
        // El tipo de cliente también es identidad. Sin borrarlo, quien está en la
        // caja heredaba el VIP o el Empleado del cliente que estuviera
        // preseleccionado — y con la regla nueva eso se escribe en el maestro del
        // POS, no sólo en la pantalla.
        employeeChoice = null
        return initialData.copy(
            customerId = null,
            customerName = "",
            email = "",
            phone = "",
            countryIso2 = initialData.countryIso2,
            customerTypeId = null,
            fiscalDocumentType = tipo,
            fiscalId = numero
        )
    }

    /**
     * Publica el cliente vigente en el estado compartido.
     *
     * Explícito y no como efecto colateral de los `TextWatcher` de
     * [ConsentScreen]: si el estado compartido se queda con datos viejos, el
     * espejo del vendedor los reenvía y **sobrescribe** lo que acabe de llegar
     * de la DIAN.
     */
    private fun publishCustomer(customer: CustomerData) {
        ConsentSharedState.updateCustomer(customer)
        // El tipo entrante es un campo del cliente, así que cualquier cambio de
        // cliente puede cambiar la proyección — sobre todo
        // [forgetPreviousIdentity], que borra el tipo del cliente anterior.
        publishTypeProjection()
    }

    /**
     * Consulta a la DIAN para un cliente nuevo, ya con el vendedor autorizado.
     */
    private fun queryDian(tipo: String, numero: String) {
        if (managedOperation == CustomerOperation.EDIT) return
        val generation = ++queryGeneration
        val base = ConsentSharedState.current.customer.copy(customerId = null, fiscalDocumentType = tipo, fiscalId = numero)
        val cb = onDocumentConfirmed ?: run {
            val updated = base
            showAfterDian(updated, warning = null, status = DianStatus.UNVERIFIED)
            return
        }

        val controller = ConsentController(
            onShow = { customer, warning, status ->
                runOnUi { if (isShowing && generation == queryGeneration) showAfterDian(customer, warning, status) }
            },
            onState = { state -> runOnUi { if (isShowing && generation == queryGeneration) screen.render(state) } },
            onErrorMessage = { /* reservado */ }
        )

        enrichJob = enrichScope.launch {
            try {
                withContext(Dispatchers.IO) { cb(tipo, numero, controller) }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Exception) {
                // Regla de oro: ni una excepción del cliente HTTP bloquea la
                // venta. Caemos al formulario manual con aviso.
                //
                // ⚠️ 14-08-2026: ya NO pintamos `ScreenState.LoadingWithMessage`
                // antes de este catch — el cliente no debe ver pantalla de
                // "validando" en ningún momento. El estado `VALIDATING_DIAN`
                // sigue activo internamente por si algún observer lo necesita,
                // pero la pantalla del cliente sigue en su último estado
                // visible hasta que `showAfterDian` la cambia.
                Log.w("ConsentPresentation", "Consulta HKA sin resultado; captura manual")
                if (isShowing && generation == queryGeneration) {
                    runOnUi {
                        if (!isShowing || generation != queryGeneration) return@runOnUi
                        showAfterDian(
                            base,
                            warning = "No se pudo validar con la DIAN (timeout o error). Completa los datos manualmente.",
                            status = DianStatus.UNVERIFIED
                        )
                    }
                }
            }
        }
    }

    /**
     * Punto único de entrada al formulario después de consultar la DIAN.
     *
     * Resuelve el escenario definitivo (2 si validó, 3 si no) y pinta el
     * estado intermedio con el copy honesto antes de los 5 campos.
     */
    private fun showAfterDian(customer: CustomerData, warning: String?, status: DianStatus) {
        scenario = CustomerScenario.afterDian(status)
        publishScenario(scenario)
        // El aviso también tiene que llegarle al vendedor, no sólo al cliente.
        ConsentSharedState.updateWarning(warning)

        // ⚠️ Publicar el resultado de la DIAN es obligatorio. Faltaba, y el
        // estado compartido se quedaba con el cliente anterior: el espejo del
        // vendedor lo reenviaba y borraba el nombre que acababa de traer la
        // DIAN de la pantalla del cliente.
        publishCustomer(customer)

        // El vendedor ya autorizó al abrir la compuerta: no se le vuelve a
        // preguntar. Encuentre o no la DIAN, el cliente pasa al formulario —
        // con los datos precargados (escenario 2) o vacío (escenario 4).
        //
        // HU-HIOPOS · la pantalla "Cliente validado en la DIAN" ya no se
        // muestra. El cliente solo ve el form; el feedback informativo lo
        // recibe el vendor por su VendorScreen. Saltamos directo al form.
        ConsentSharedState.setStage(ConsentStage.FILLING)
        screen.updateData(customer)
        screen.setWarning(warning)
        if (isShowing) screen.render(ScreenState.Showing(customer, scenario))
    }

    /**
     * Publica el escenario vigente en los dos consumidores: el mirror del
     * vendedor (vía estado compartido) y la auditoría del caller.
     */
    private fun publishScenario(resolved: CustomerScenario) {
        ConsentSharedState.setScenario(resolved)
        onScenarioResolved?.invoke(resolved)
        // El escenario y el tipo cambian juntos casi siempre (Consumidor Final es
        // el caso claro), así que se republican juntos y no puede quedar uno
        // desactualizado respecto del otro.
        publishTypeProjection()
    }

    /**
     * Recalcula y publica el tipo con el que se enviaría el cliente **ahora
     * mismo**.
     *
     * Usa exactamente los mismos insumos que [buildAccepted], por el mismo
     * resolver: es lo que garantiza que lo que el cajero ve en pantalla no pueda
     * discrepar de lo que viaja en el `ModifyDocumentResult`. Si acá hiciéramos
     * una cuenta aparte "para mostrar", tarde o temprano mostraría una cosa y se
     * enviaría otra.
     */
    private fun publishTypeProjection() {
        ConsentSharedState.setCustomerTypeProjection(
            CustomerTypeResolver.project(
                incomingTypeId = ConsentSharedState.current.customer.customerTypeId,
                cashierEmployeeChoice = employeeChoice,
                loyaltyOptIn = loyaltyOptInSoFar,
                koajActive = loyaltyConfig.isActive,
                koajDecided = loyaltyDecided,
                isConsumidorFinal = scenario == CustomerScenario.CONSUMIDOR_FINAL
            )
        )
    }

    private fun runOnUi(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else Handler(Looper.getMainLooper()).post(block)
    }

    override fun onStop() {
        queryGeneration++
        enrichJob?.cancel()
        enrichScope.cancel()
        // Suelta el listener global del teclado y cualquier timer pendiente
        // (incluido el auto-avance de Koaj Lovers).
        if (::screen.isInitialized) screen.dispose()
        super.onStop()
    }

    /**
     * Intercepta eventos touch para cerrar el teclado cuando el usuario
     * toca fuera de cualquier EditText. Si el tap es sobre un campo
     * editable, deja que el evento se propague normalmente.
     */
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.action == android.view.MotionEvent.ACTION_DOWN) {
            screen.shouldDismissKeyboardOnTouch(ev.rawX, ev.rawY)
        }
        return super.dispatchTouchEvent(ev)
    }

    private companion object {
        // Breves pero suficientes para que el cliente vea el feedback visual
        // antes de cerrar y liberar el POS para la facturación.
        const val ACCEPTED_CLOSE_DELAY_MS = 700L
        const val REJECTED_CLOSE_DELAY_MS = 400L

        // `VALIDATED_HOLD_MS` y `NOT_VALIDATED_HOLD_MS` se eliminaron junto
        // con `ScreenState.Validated`: eran el tiempo de espera de una
        // pantalla intermedia que ya no existe.

        /** La bienvenida a Koaj Lovers merece un beat más que el ✓ normal. */
        const val JOINED_CLOSE_DELAY_MS = 1_500L
    }
}
