package com.permoda.facturacion.presentation.vendor

import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import com.permoda.facturacion.R
import com.permoda.facturacion.core.consent.ConsentSharedState
import com.permoda.facturacion.core.consent.ConsentStage
import com.permoda.facturacion.core.consent.CustomerMirror
import com.permoda.facturacion.core.consent.VendorCommand
import com.permoda.facturacion.core.customer.CustomerType
import com.permoda.facturacion.core.customer.CustomerTypeResolver
import com.permoda.facturacion.core.hka.TipoDocumentoMapper
import com.permoda.facturacion.core.manage.CustomerOperation
import com.permoda.facturacion.core.scenario.CustomerScenario
import com.permoda.facturacion.core.ui.KeyboardDismissHelper
import com.permoda.facturacion.core.validation.CustomerValidator

/**
 * Pantalla del **cajero** durante `SELECT_CUSTOMER`, en el display principal.
 *
 * Es el actor principal y único del flujo: aquí se captura el documento, se
 * llenan los cinco campos y se confirman. El cliente ve en su pantalla un
 * espejo en vivo de lo que el cajero escribe, **sin poder editar nada**, y su
 * único toque en todo el proceso es el Acepto de Koaj Lovers.
 *
 * Reemplaza la ventana invisible que había antes, que dejaba ver HioPosCloud
 * pero se comía todos los toques: el cajero veía el POS congelado, sin mensaje
 * y sin salida si el cliente se iba a mitad del flujo.
 */
class VendorScreen(private val root: ViewGroup) {

    private val context = root.context

    private lateinit var mirror: CustomerMirror

    private var icon: ImageView? = null
    private var title: TextView? = null
    private var subtitle: TextView? = null
    private var badge: TextView? = null
    private var warning: TextView? = null
    private var validationHint: TextView? = null
    private var progress: android.widget.ProgressBar? = null

    /** Cinta de pasos de la banda (rótulo + cinta de 4dp por paso). */
    private var steps: List<Pair<TextView?, View?>> = emptyList()
    private var heartRotate: android.animation.ObjectAnimator? = null
    private var footerHint: TextView? = null
    private var btnCancel: Button? = null
    private var btnAccept: Button? = null
    private var btnBack: Button? = null

    /**
     * Bloques grandes del nuevo layout (salto de diseño). Cada uno se anima
     * en escalonado al [render] inicial: header → contenido principal →
     * footer, con un delta de ~70ms entre ellos. Los tres se cachean para
     * que las animaciones de stage no las tengan que volver a resolver.
     */
    private var headerBlock: View? = null
    private var railBlock: View? = null
    private var mainBlock: View? = null
    private var footerBlock: View? = null

    /** Ficha del riel: a quién se está atendiendo. */
    private var recordInitials: TextView? = null
    private var recordIcon: View? = null
    private var recordName: TextView? = null
    private var recordDoc: TextView? = null

    /**
     * Notificaciones flotantes (aviso y validación). La pila sube con el
     * teclado para no quedar tapada.
     */
    private var toastStack: View? = null
    private var warningToast: View? = null
    private var validationToast: View? = null

    /**
     * Sub-bloques del header para la animación más fina del entrance: el
     * icono (con glow) entra primero, luego el bloque de texto, luego el
     * badge de escenario. Es la "primera pincelada" de la pantalla.
     */
    private var headerIconFrame: ViewGroup? = null
    private var headerTextBlock: View? = null

    /**
     * Pulso breathing del glow del icono del header. Sólo corre mientras el
     * cliente está decidiendo (LOYALTY) — el mismo registro que el pod
     * notification. Idempotente.
     */
    private var headerGlowPulse: android.animation.Animator? = null

    /**
     * Compuerta de decisión: "¿desea crearlo?".
     *
     * Tiene una sola respuesta (`promptYes`). El "No, facturar como Consumidor
     * Final" que había acá se movió a la captura del documento — ver [captureCf].
     * La salida de la compuerta es el botón Volver, que devuelve a la captura.
     */
    private var prompt: View? = null
    private var promptTitle: TextView? = null
    private var promptSubtitle: TextView? = null
    private var promptYes: Button? = null
    private var promptSearch: Button? = null
    private var mirrorScroll: View? = null

    // Captura del documento (la hace el cajero)
    private var docCapture: View? = null
    private var captureDocType: EditText? = null
    private var captureDocId: EditText? = null
    private var captureError: TextView? = null
    private var captureContinue: Button? = null

    /**
     * Elección Empleado / cliente normal. Reemplazó al `Spinner` de tipo de
     * cliente: el cajero no clasifica al cliente —el tipo lo trae HioPos o lo
     * produce la decisión de Koaj Lovers—, y lo único que sí decide es si un
     * empleado está comprando como empleado.
     *
     * El bloque está oculto salvo que el POS haya traído un Empleado. Hoy eso no
     * pasa nunca en producción (el `<Document>` no incluye el tipo de cliente),
     * así que en la práctica la captura no muestra nada de esto.
     */
    private var captureEmployeeBlock: View? = null
    private var captureEmployeeYes: Button? = null
    private var captureEmployeeNo: Button? = null

    /**
     * Apartado de solo lectura del formulario: con qué tipo se va a enviar el
     * cliente. El cajero no lo edita — está para que pueda **verificarlo** antes
     * de confirmar, que es justo lo que faltaba.
     */
    private var customerTypeBlock: View? = null
    private var customerTypeValue: TextView? = null
    private var customerTypeHint: TextView? = null

    /**
     * Consumidor Final desde la pantalla de captura, sin teclear documento.
     *
     * Vive acá y no en la compuerta de "¿desea crearlo?" porque es la salida
     * para el cliente que **no quiere dar el documento**: pedirle al cajero que
     * escriba una cédula para recién entonces poder elegir "sin datos" es pedirle
     * el dato que justamente no tiene.
     */
    private var captureCf: Button? = null

    /** Tipo elegido en la captura, con nombre completo. */
    private var captureSelectedType: String = TipoDocumentoMapper.supportedFullNames().first()

    /**
     * Elección del cajero cuando el POS trajo un Empleado. Arranca en
     * [CustomerType.EMPLEADO]: si el cajero no toca nada, un empleado sigue
     * siendo empleado. Degradarlo a Estándar tiene que ser un acto explícito,
     * porque cambia el tipo en el maestro de HioPos y además le habilita Koaj
     * Lovers.
     */
    private var captureEmployeeChoice: CustomerType = CustomerType.EMPLEADO

    /** True si el picker se abrió desde la captura y no desde el espejo. */
    private var pickerForCapture = false

    /** El documento que el cajero ya tecleó en el POS se precarga una sola vez por flujo. */
    private var capturePrefilled = false

    private var docTypePicker: View? = null
    private var docTypeScrim: View? = null
    private var docTypeList: ViewGroup? = null
    private var keyboardHelper: KeyboardDismissHelper? = null

    /**
     * Cabecera + formulario + pie. Recibe el alto del teclado como
     * `paddingBottom` para replicar `adjustResize`, que
     * `FLAG_LAYOUT_NO_LIMITS` desactiva. Ver [applyKeyboardHeight].
     */
    private var contentBlock: View? = null

    /**
     * Lleva a la vista el campo que acaba de recibir el foco.
     *
     * Se registra en el `ViewTreeObserver` de la raíz y no campo por campo porque
     * los `EditText` los administra [CustomerMirror] y la captura monta los suyos
     * aparte: un listener global cubre los dos y cualquier campo que se agregue
     * después.
     */
    private val focusListener =
        android.view.ViewTreeObserver.OnGlobalFocusChangeListener { _, newFocus ->
            if (newFocus is EditText) scrollIntoView(newFocus)
        }

    private var onCancel: (() -> Unit)? = null

    /**
     * Vistas del pod notification premium. Se resuelven en [bind] pero el
     * overlay entero puede o no estar inflado según el momento — el `<include>`
     * en `activity_vendor.xml` lo trae siempre, así que aquí lo esperamos
     * presente. La animación la maneja [PodAnimator].
     */
    private var podOverlay: View? = null
    private var podCard: View? = null
    private var podHeart: View? = null
    private var podGlow: View? = null
    private var podRing1: View? = null
    private var podRing2: View? = null
    private var podRing3: View? = null
    private var podRing4: View? = null
    private var podRing5: View? = null
    private var podContinue: android.widget.Button? = null
    private var podAnimator: PodAnimator? = null

    /**
     * Overlay de "flow completado". Se muestra brevemente al final del
     * flujo (DONE) con un check XL, halo verde y confetti — el "le dimos
     * cierre" visual al trabajo del cajero antes de devolver el resultado
     * al POS.
     */
    private var doneOverlay: View? = null
    private var doneCard: View? = null
    private var doneHalo: View? = null
    private var doneRing: View? = null
    private var doneCheck: android.widget.ImageView? = null
    private var doneTitle: android.widget.TextView? = null
    private var doneSubtitle: android.widget.TextView? = null
    private var doneConfetti: ViewGroup? = null
    private var completionAnimator: CompletionAnimator? = null

    fun bind(onCancel: () -> Unit) {
        this.onCancel = onCancel
        LayoutInflater.from(context).inflate(R.layout.activity_vendor, root, true)

        icon           = root.findViewById(R.id.vendor_icon)
        title          = root.findViewById(R.id.vendor_title)
        subtitle       = root.findViewById(R.id.vendor_subtitle)
        progress       = root.findViewById(R.id.vendor_progress)
        badge          = root.findViewById(R.id.vendor_scenario_badge)
        warning        = root.findViewById(R.id.vendor_warning)
        validationHint = root.findViewById(R.id.vendor_validation_hint)
        footerHint     = root.findViewById(R.id.vendor_footer_hint)
        // Salida explícita a HioPos. Estuvo desactivada un tiempo apoyándose en
        // que "el back del sistema sigue funcionando como vía de salida" — cierto
        // para quien conoce el flujo, inútil para un cajero que no tiene por qué
        // adivinar que el botón atrás abandona la selección de cliente.
        btnCancel      = root.findViewById(R.id.vendor_btn_cancel)
        btnAccept      = root.findViewById(R.id.vendor_btn_accept)
        btnBack        = root.findViewById(R.id.vendor_btn_back)

        prompt         = root.findViewById(R.id.vendor_prompt)
        promptTitle    = root.findViewById(R.id.vendor_prompt_title)
        promptSubtitle = root.findViewById(R.id.vendor_prompt_subtitle)
        promptYes      = root.findViewById(R.id.vendor_prompt_yes)
        promptSearch   = root.findViewById(R.id.vendor_prompt_search)
        mirrorScroll   = root.findViewById(R.id.vendor_mirror_scroll)

        steps = listOf(
            root.findViewById<TextView>(R.id.vendor_step_doc) to root.findViewById<View>(R.id.vendor_step_doc_bar),
            root.findViewById<TextView>(R.id.vendor_step_data) to root.findViewById<View>(R.id.vendor_step_data_bar),
            root.findViewById<TextView>(R.id.vendor_step_koaj) to root.findViewById<View>(R.id.vendor_step_koaj_bar)
        )

        headerBlock    = root.findViewById(R.id.vendor_header)
        railBlock      = root.findViewById(R.id.vendor_rail)
        mainBlock      = root.findViewById(R.id.vendor_main)
        footerBlock    = root.findViewById(R.id.vendor_footer)

        recordInitials = root.findViewById(R.id.vendor_record_initials)
        recordIcon     = root.findViewById(R.id.vendor_record_icon)
        recordName     = root.findViewById(R.id.vendor_record_name)
        recordDoc      = root.findViewById(R.id.vendor_record_doc)

        toastStack      = root.findViewById(R.id.vendor_toast_stack)
        warningToast    = root.findViewById(R.id.vendor_warning_toast)
        validationToast = root.findViewById(R.id.vendor_validation_toast)
        headerIconFrame = root.findViewById(R.id.vendor_header_icon_frame)
        headerTextBlock = root.findViewById(R.id.vendor_header_text)

        docCapture      = root.findViewById(R.id.vendor_doc_capture)
        captureDocType  = root.findViewById(R.id.vendor_capture_doc_type)
        captureDocId    = root.findViewById(R.id.vendor_capture_doc_id)
        captureError    = root.findViewById(R.id.vendor_capture_error)
        captureContinue = root.findViewById(R.id.vendor_capture_continue)
        captureCf       = root.findViewById(R.id.vendor_capture_cf)
        captureEmployeeBlock = root.findViewById(R.id.vendor_capture_employee_block)
        captureEmployeeYes   = root.findViewById(R.id.vendor_capture_employee_yes)
        captureEmployeeNo    = root.findViewById(R.id.vendor_capture_employee_no)
        customerTypeBlock    = root.findViewById(R.id.vendor_customer_type_block)
        customerTypeValue    = root.findViewById(R.id.vendor_customer_type_value)
        customerTypeHint     = root.findViewById(R.id.vendor_customer_type_hint)

        docTypePicker = root.findViewById(R.id.zone_doc_type_picker)
        docTypeScrim  = root.findViewById(R.id.doc_type_picker_scrim)
        docTypeList   = root.findViewById(R.id.doc_type_list)

        podOverlay = root.findViewById(R.id.vendor_loading_overlay)
        podCard    = root.findViewById(R.id.vendor_pod_card)
        podHeart   = root.findViewById(R.id.vendor_pod_heart)
        podGlow    = root.findViewById(R.id.vendor_pod_glow)
        podRing1   = root.findViewById(R.id.vendor_pod_ring_1)
        podRing2   = root.findViewById(R.id.vendor_pod_ring_2)
        podRing3   = root.findViewById(R.id.vendor_pod_ring_3)
        podRing4   = root.findViewById(R.id.vendor_pod_ring_4)
        podRing5   = root.findViewById(R.id.vendor_pod_ring_5)
        podContinue = root.findViewById(R.id.vendor_pod_continue)
        podAnimator = PodAnimator(
            overlay = podOverlay,
            card = podCard,
            heart = podHeart,
            glow = podGlow,
            rings = listOfNotNull(podRing1, podRing2, podRing3, podRing4, podRing5),
            continueButton = podContinue,
            // Continuar sin inscripción no simula una aceptación del cliente.
            onContinueClick = {
                ConsentSharedState.sendVendorCommand(VendorCommand.ContinueWithoutLoyalty)
            }
        )

        doneOverlay   = root.findViewById(R.id.vendor_done_overlay)
        doneCard      = root.findViewById(R.id.vendor_done_card)
        doneHalo      = root.findViewById(R.id.vendor_done_halo)
        doneRing      = root.findViewById(R.id.vendor_done_ring)
        doneCheck     = root.findViewById(R.id.vendor_done_check)
        doneTitle     = root.findViewById(R.id.vendor_done_title)
        doneSubtitle  = root.findViewById(R.id.vendor_done_subtitle)
        doneConfetti  = root.findViewById(R.id.vendor_done_confetti)
        completionAnimator = CompletionAnimator(
            overlay = doneOverlay,
            card = doneCard,
            halo = doneHalo,
            ring = doneRing,
            check = doneCheck,
            title = doneTitle,
            subtitle = doneSubtitle,
            confettiContainer = doneConfetti
        )

        contentBlock = root.findViewById(R.id.vendor_content)
        root.findViewById<View>(R.id.keyboard_dismiss)?.let {
            keyboardHelper = KeyboardDismissHelper(it, root) { height ->
                applyKeyboardHeight(height)
            }
        }
        // Un solo listener de foco para toda la pantalla: al cambiar de campo con
        // el teclado ya abierto no llega ningún evento de altura, así que sin esto
        // el campo siguiente podía quedar tapado igual.
        root.viewTreeObserver.addOnGlobalFocusChangeListener(focusListener)

        mirror = CustomerMirror(
            name = root.findViewById(R.id.vendor_name),
            docType = root.findViewById(R.id.vendor_doc_type),
            docId = root.findViewById(R.id.vendor_doc_id),
            email = root.findViewById(R.id.vendor_email),
            phone = root.findViewById(R.id.vendor_phone),
            onDocTypeClick = { pickerForCapture = false; showDocTypePicker() }
        )

        setupCapture()
        setupEmployeeChoice()

        btnCancel?.setOnClickListener { onCancel() }

        // Todas las órdenes viajan por el estado compartido: la Presentation
        // las consume y mueve el flujo. Así no hay lógica de flujo duplicada
        // entre las dos pantallas.
        btnAccept?.setOnClickListener {
            ConsentSharedState.sendVendorCommand(VendorCommand.AcceptData)
        }
        btnBack?.setOnClickListener {
            ConsentSharedState.sendVendorCommand(VendorCommand.BackToDocument)
        }
        // La compuerta ya sólo pregunta "¿desea crearlo?" y tiene una sola
        // respuesta. El "No, facturar como Consumidor Final" se movió a la
        // captura del documento (ver `captureCf` en `setupCapture`).
        promptYes?.setOnClickListener {
            ConsentSharedState.sendVendorCommand(VendorCommand.Approve)
        }
        // Volver a buscar = cancelar: el POS retoma su lista para usar la lupa.
        promptSearch?.setOnClickListener { onCancel() }

        docTypeScrim?.setOnClickListener { hideDocTypePicker() }

        // Respuesta al toque idéntica en todos los botones del cajero: el
        // drawable cambia de color y el botón cede un 2% mientras el dedo está
        // encima. Antes esto se disparaba una sola vez al abrir la pantalla.
        listOfNotNull(
            btnCancel,
            btnAccept,
            btnBack,
            captureContinue,
            captureCf,
            captureEmployeeYes,
            captureEmployeeNo,
            promptYes,
            promptSearch
        ).forEach { attachPressFeedback(it) }

        // La pila de notificaciones solo anima el reacomodo de las demás; la
        // entrada y salida de cada una las hace showToast / hideToast.
        (toastStack as? ViewGroup)?.layoutTransition?.apply {
            disableTransitionType(android.animation.LayoutTransition.APPEARING)
            disableTransitionType(android.animation.LayoutTransition.DISAPPEARING)
            setInterpolator(android.animation.LayoutTransition.CHANGE_APPEARING, EASE_OUT)
            setInterpolator(android.animation.LayoutTransition.CHANGE_DISAPPEARING, EASE_OUT)
        }

        // "Sincronizado en vivo": el punto respira mientras la pantalla está abierta.
        root.findViewById<View>(R.id.vendor_live_dot)?.let { startLiveDot(it) }

        // Entrada escalonada en el primer render. Se ejecuta una sola
        // vez: después las transiciones de etapa se encargan del resto.
        runStaggerEntry()
    }

    /**
     * Presión sobria: escala a 0.98 al bajar el dedo y vuelve al soltar. No
     * consume el evento, así que el click y el estado `pressed` del drawable
     * siguen funcionando igual.
     */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun attachPressFeedback(view: View) {
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN ->
                    v.animate().scaleX(PRESS_SCALE).scaleY(PRESS_SCALE)
                        .setDuration(PRESS_IN_MS).setInterpolator(EASE_OUT).start()
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL ->
                    v.animate().scaleX(1f).scaleY(1f)
                        .setDuration(PRESS_OUT_MS).setInterpolator(EASE_OUT).start()
            }
            false
        }
    }

    private var liveDotPulse: android.animation.Animator? = null

    private fun startLiveDot(dot: View) {
        liveDotPulse?.cancel()
        liveDotPulse = android.animation.ObjectAnimator.ofFloat(dot, "alpha", 1f, 0.3f, 1f).apply {
            duration = LIVE_DOT_MS
            repeatCount = android.animation.ValueAnimator.INFINITE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            start()
        }
    }

    /**
     * Entrada escalonada: banda, riel, tarea y pie aparecen en cascada con un
     * desvanecido y un desplazamiento corto, frenando con una curva
     * exponencial (rápido al principio, se asienta sin rebote).
     *
     *   · banda            delay 0ms   (baja 6dp)
     *   · riel             delay 70ms  (sube 14dp)
     *   · tarea            delay 140ms (sube 14dp)
     *   · pie              delay 210ms (sube 10dp)
     */
    private fun runStaggerEntry() {
        val density = context.resources.displayMetrics.density
        listOf(headerBlock, railBlock, mainBlock, footerBlock).forEachIndexed { index, view ->
            view ?: return@forEachIndexed
            val delay = index * STAGGER_BLOCK_DELAY_MS
            view.alpha = 0f
            view.translationY = STAGGER_BLOCK_DISTANCES_DP[index] * density
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(delay)
                .setDuration(STAGGER_BLOCK_DURATION_MS)
                .setInterpolator(EASE_OUT)
                .start()
        }
        // Dentro de la banda, el ícono llega un instante antes que el texto.
        headerIconFrame?.let {
            it.alpha = 0f
            it.scaleX = 0.9f
            it.scaleY = 0.9f
            it.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setStartDelay(0L)
                .setDuration(STAGGER_BLOCK_DURATION_MS)
                .setInterpolator(EASE_OUT)
                .start()
        }
        headerTextBlock?.let {
            it.alpha = 0f
            it.translationX = -8f * density
            it.animate()
                .alpha(1f)
                .translationX(0f)
                .setStartDelay(50L)
                .setDuration(STAGGER_BLOCK_DURATION_MS)
                .setInterpolator(EASE_OUT)
                .start()
        }
    }

    /**
     * Alterna una zona de contenido. Al aparecer entra con desvanecido y un
     * desplazamiento de 12dp; al ocultarse se va de inmediato, para que nunca
     * convivan dos zonas a la vez. Si ya estaba visible no hace nada, así que
     * es seguro llamarlo en cada `render`.
     */
    private fun setZoneVisible(view: View?, show: Boolean) {
        view ?: return
        if (!show) {
            if (view.visibility != View.GONE) {
                view.animate().cancel()
                view.alpha = 1f
                view.translationY = 0f
                view.visibility = View.GONE
            }
            return
        }
        if (view.visibility == View.VISIBLE) return
        view.alpha = 0f
        view.translationY = ZONE_ENTER_DP * context.resources.displayMetrics.density
        view.visibility = View.VISIBLE
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(0L)
            .setDuration(ZONE_ENTER_MS)
            .setInterpolator(EASE_OUT)
            .start()
    }

    /**
     * Captura del documento. El número se propaga **en vivo** al estado
     * compartido para que el cliente lo vea aparecer en su pantalla mientras el
     * cajero teclea; el escenario no se resuelve hasta pulsar Continuar.
     */
    private fun setupCapture() {
        captureDocType?.apply {
            isFocusable = false
            isFocusableInTouchMode = false
            setText(captureSelectedType)
            setOnClickListener { pickerForCapture = true; showDocTypePicker() }
        }

        captureDocId?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                captureError?.visibility = View.GONE
                publishCaptureInProgress()
            }
        })

        // Mientras el cajero digita el documento, el número se resalta en la pantalla del cliente.
        captureDocId?.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) ConsentSharedState.setActiveField("fiscalId")
            else if (ConsentSharedState.current.activeField == "fiscalId") ConsentSharedState.setActiveField(null)
        }

        captureContinue?.setOnClickListener { confirmCapture() }
        captureDocId?.setOnEditorActionListener { _, _, _ -> confirmCapture(); true }

        // No valida el documento a propósito: es la salida para cuando no hay
        // documento que validar. Lo que haya escrito a medias se descarta, y el
        // teclado se baja para que la confirmación quede visible.
        captureCf?.setOnClickListener {
            captureError?.visibility = View.GONE
            keyboardHelper?.hideKeyboard()
            ConsentSharedState.sendVendorCommand(VendorCommand.ConsumidorFinal)
        }
    }

    /**
     * "No se ha encontrado ningún cliente con este NIF → Nuevo": HioPos 15.14.3 manda
     * el `fiscalId` que el cajero acaba de teclear, pero no su tipo. Se precarga el
     * número (tipo por defecto) para que solo confirme. Es el documento de este mismo
     * flujo, no el de una venta anterior, así que no reabre la fuga de
     * `prefillAwaitingDocument` descrita en ConsentScreen.
     */
    private fun prefillCaptureFromPos(state: ConsentSharedState.State) {
        if (capturePrefilled || state.managedOperation != CustomerOperation.CREATE) return
        capturePrefilled = true
        val numero = state.customer.fiscalId?.trim().orEmpty()
        val field = captureDocId ?: return
        if (numero.isEmpty() || field.text?.isNotEmpty() == true) return
        TipoDocumentoMapper.toFullName(state.customer.fiscalDocumentType)?.let {
            captureSelectedType = it
            captureDocType?.setText(it)
        }
        field.setText(numero)
        field.setSelection(numero.length)
    }

    /**
     * Espeja lo tecleado sin resolver nada: es sólo feedback para el cliente.
     *
     * ⚠️ Acá se escribía también `customerTypeId` con lo que estuviera
     * seleccionado en el desplegable, **pisando** el tipo real que el cliente
     * tenía en el maestro de HioPos. El tipo ya no se toca durante la captura:
     * lo resuelve `CustomerTypeResolver` al cerrar el flujo.
     */
    private fun publishCaptureInProgress() {
        val numero = captureDocId?.text?.toString()?.trim().orEmpty()
        val current = ConsentSharedState.current.customer
        ConsentSharedState.updateCustomer(
            current.copy(
                fiscalDocumentType = TipoDocumentoMapper.toDianCode(captureSelectedType)
                    ?: current.fiscalDocumentType,
                fiscalId = numero
            )
        )
    }

    /**
     * Elección Empleado / cliente normal.
     *
     * Sólo se cablean los listeners; la visibilidad la decide
     * [applyEmployeeChoice] en cada `render`, porque el tipo entrante llega con
     * el `<Document>` y puede no estar disponible en el momento del `bind`.
     */
    private fun setupEmployeeChoice() {
        captureEmployeeYes?.setOnClickListener {
            selectEmployeeChoice(CustomerType.EMPLEADO)
        }
        captureEmployeeNo?.setOnClickListener {
            selectEmployeeChoice(CustomerType.ESTANDAR)
        }
        renderEmployeeChoice()
    }

    private fun selectEmployeeChoice(choice: CustomerType) {
        captureEmployeeChoice = choice
        renderEmployeeChoice()
    }

    /** Marca visualmente cuál de las dos opciones está elegida. */
    private fun renderEmployeeChoice() {
        // Control segmentado: la opción elegida queda en estado `selected`
        // (tinte azul y borde de 2dp en bg_caja_btn_secondary, texto azul).
        val esEmpleado = captureEmployeeChoice == CustomerType.EMPLEADO
        captureEmployeeYes?.isSelected = esEmpleado
        captureEmployeeNo?.isSelected = !esEmpleado
    }

    /**
     * Muestra el bloque sólo si el POS trajo un cliente Empleado.
     *
     * Hoy no se muestra nunca en producción: el `<Customer>` del `<Document>`
     * no incluye el tipo de cliente (verificado sobre 91 sondas reales en
     * `qa/logs/`). Queda cableado para el día que ICG agregue `CustomerTypeId`.
     */
    /**
     * Reserva abajo el espacio que ocupa el teclado.
     *
     * Al ir el padding en [contentBlock] —cuyo hijo del medio es el `ScrollView`
     * con `layout_weight="1"`— el scroll absorbe el encogimiento y el pie sube
     * por encima del teclado. Es exactamente lo que haría `adjustResize`, que
     * `FLAG_LAYOUT_NO_LIMITS` desactiva (ver [KeyboardDismissHelper]).
     *
     * Después vuelve a poner en pantalla el campo enfocado: encoger el scroll no
     * lo desplaza solo, y si el campo estaba en la mitad de abajo se queda tapado.
     */
    private fun applyKeyboardHeight(heightPx: Int) {
        // Las notificaciones flotan sobre el pie: suben con el teclado para seguir a la vista.
        toastStack?.animate()?.translationY(-heightPx.toFloat())
            ?.setDuration(ZONE_ENTER_MS)?.setInterpolator(EASE_OUT)?.start()
        val block = contentBlock ?: return
        if (block.paddingBottom == heightPx) return
        block.setPadding(
            block.paddingLeft,
            block.paddingTop,
            block.paddingRight,
            heightPx
        )
        (root.findFocus() as? EditText)?.let { scrollIntoView(it) }
    }

    /**
     * Desplaza el scroll para que [field] quede visible.
     *
     * `requestRectangleOnScreen` sube por la jerarquía hasta el `ScrollView`, así
     * que no hace falta saber en qué columna está el campo — importa ahora que el
     * formulario tiene dos.
     *
     * Va en un `post` porque en el instante del foco (o del cambio de padding) el
     * layout todavía no se ha reacomodado y las coordenadas serían las viejas.
     */
    private fun scrollIntoView(field: View) {
        field.post {
            if (!field.isAttachedToWindow) return@post
            // Se pide un poco más de alto que el campo para que la etiqueta de
            // arriba entre también: ver el campo sin saber cuál es no sirve.
            val margin = (24 * field.resources.displayMetrics.density).toInt()
            field.requestRectangleOnScreen(
                android.graphics.Rect(0, -margin, field.width, field.height + margin),
                false
            )
        }
    }

    /**
     * Pinta el tipo con el que se va a enviar el cliente.
     *
     * No calcula nada: la proyección la publica `ValidationPresentation`, que es
     * quien tiene los tres insumos (tipo entrante, elección del cajero, decisión
     * de Koaj) y la calcula con **el mismo** resolver que arma el
     * `ModifyDocumentResult`. Si acá se hiciera la cuenta aparte, tarde o
     * temprano la pantalla diría una cosa y se enviaría otra — y este apartado
     * existe precisamente para poder confiar en lo que dice.
     */
    private fun applyCustomerType(state: ConsentSharedState.State) {
        val projection = state.customerTypeProjection
        if (projection == null) {
            // Antes de capturar el documento no hay nada que afirmar: el apartado
            // queda en su sitio (al lado del documento) con "—".
            customerTypeBlock?.visibility = View.VISIBLE
            customerTypeValue?.setText(R.string.consent_empty_placeholder)
            customerTypeValue?.setTextColor(context.getColor(R.color.caja_text_muted))
            customerTypeHint?.text = ""
            return
        }
        customerTypeBlock?.visibility = View.VISIBLE
        customerTypeValue?.text = projection.type.displayName
        customerTypeValue?.setTextColor(
            context.getColor(
                // VIP se distingue con el azul de acción (6.2:1 sobre el gris del apartado).
                if (projection.type == CustomerType.VIP) R.color.caja_primary
                else R.color.caja_text
            )
        )
        customerTypeHint?.setText(
            when {
                // El orden importa: "todavía puede subir" gana sobre el tipo
                // actual, porque es la única de las cuatro que NO es definitiva.
                projection.pendingKoajDecision -> R.string.vendor_customer_type_hint_pending
                projection.type == CustomerType.EMPLEADO -> R.string.vendor_customer_type_hint_empleado
                projection.type == CustomerType.VIP -> R.string.vendor_customer_type_hint_vip
                else -> R.string.vendor_customer_type_hint_estandar
            }
        )
    }

    private fun applyEmployeeChoice(state: ConsentSharedState.State) {
        val ofrece = CustomerTypeResolver.offersEmployeeChoice(state.customer.customerTypeId)
        captureEmployeeBlock?.visibility = if (ofrece) View.VISIBLE else View.GONE
        if (!ofrece) captureEmployeeChoice = CustomerType.EMPLEADO
    }

    private fun confirmCapture() {
        val numero = captureDocId?.text?.toString()?.trim().orEmpty()
        if (numero.length < MIN_DOC_ID) {
            captureError?.setText(R.string.consent_doc_id_too_short)
            captureError?.visibility = View.VISIBLE
            return
        }
        captureError?.visibility = View.GONE
        keyboardHelper?.hideKeyboard()
        // Sólo viaja la elección si el bloque estaba visible, o sea si el POS
        // trajo un Empleado. En cualquier otro caso el cajero no decidió nada y
        // mandar un valor sería inventarle una decisión.
        val choice = captureEmployeeChoice.takeIf {
            CustomerTypeResolver.offersEmployeeChoice(
                ConsentSharedState.current.customer.customerTypeId
            )
        }?.id
        ConsentSharedState.sendVendorCommand(
            VendorCommand.ConfirmDocument(captureSelectedType, numero, choice)
        )
    }

    /** Refleja el estado compartido completo. Se llama en cada emisión. */
    fun render(state: ConsentSharedState.State) {
        applyPrompt(state)
        applyStage(state)
        applyEmployeeChoice(state)
        applyCustomerType(state)
        applyScenario(state.scenario)
        mirror.apply(state.customer)
        mirror.setEditable(state.stage.allowsVendorEdit)
        applyRecord(state)
        applyWarning(state.warning)
        applyValidation(state)
    }

    /**
     * Ficha del riel: iniciales, nombre y documento tal como van quedando. Es
     * solo lectura y se repinta en cada tecla, así que no anima nada.
     */
    private fun applyRecord(state: ConsentSharedState.State) {
        val customer = state.customer
        val name = customer.customerName.trim()
        val initials = name.split(Regex("\\s+"))
            .filter { it.isNotEmpty() && it.first().isLetter() }
            .take(2)
            .joinToString("") { it.first().uppercaseChar().toString() }
        recordName?.text = name.ifEmpty { context.getString(R.string.caja_record_placeholder) }
        recordName?.setTextColor(
            context.getColor(if (name.isEmpty()) R.color.caja_text_muted else R.color.caja_text)
        )
        recordInitials?.text = initials
        recordInitials?.visibility = if (initials.isEmpty()) View.GONE else View.VISIBLE
        recordIcon?.visibility = if (initials.isEmpty()) View.VISIBLE else View.GONE

        val numero = customer.fiscalId?.trim().orEmpty()
        val tipo = TipoDocumentoMapper.toFullName(customer.fiscalDocumentType)
        recordDoc?.text = when {
            numero.isEmpty() -> context.getString(R.string.caja_record_doc_placeholder)
            tipo.isNullOrBlank() -> numero
            else -> "$tipo · $numero"
        }
    }

    /**
     * Notificación flotante: entra deslizándose desde la derecha con una
     * curva exponencial y sale hacia el mismo lado. La pila
     * (`animateLayoutChanges`) reacomoda a las demás. Idempotente.
     */
    private fun showToast(toast: View) {
        if (toast.visibility == View.VISIBLE && toast.tag != TOAST_LEAVING) return
        toast.animate().cancel()
        toast.tag = null
        val slide = TOAST_SLIDE_DP * context.resources.displayMetrics.density
        if (toast.visibility != View.VISIBLE) {
            toast.alpha = 0f
            toast.translationX = slide
            toast.visibility = View.VISIBLE
        }
        toast.animate()
            .alpha(1f)
            .translationX(0f)
            .setStartDelay(0L)
            .setDuration(TOAST_ENTER_MS)
            .setInterpolator(EASE_OUT)
            .start()
    }

    private fun hideToast(toast: View) {
        if (toast.visibility != View.VISIBLE || toast.tag == TOAST_LEAVING) return
        toast.tag = TOAST_LEAVING
        val slide = TOAST_SLIDE_DP * context.resources.displayMetrics.density
        toast.animate()
            .alpha(0f)
            .translationX(slide * 0.6f)
            .setStartDelay(0L)
            .setDuration(TOAST_EXIT_MS)
            .setInterpolator(android.view.animation.AccelerateInterpolator(1.4f))
            .withEndAction {
                if (toast.tag == TOAST_LEAVING) {
                    toast.visibility = View.GONE
                    toast.tag = null
                }
            }
            .start()
    }

    /** Cambia el texto de una notificación ya visible con un relevo corto. */
    private fun swapToastText(body: TextView, text: CharSequence) {
        if (body.text?.toString() == text.toString()) return
        val visible = (body.parent?.parent as? View)?.visibility == View.VISIBLE
        body.text = text
        if (visible) {
            body.alpha = 0.35f
            body.animate().alpha(1f).setDuration(TITLE_SWAP_MS).setInterpolator(EASE_OUT).start()
        }
    }

    private fun applyStage(state: ConsentSharedState.State) {
        val stage = state.stage
        val (titleRes, subtitleRes) = when (stage) {
            ConsentStage.STARTING ->
                R.string.vendor_title_starting to R.string.vendor_subtitle_starting
            ConsentStage.AWAITING_VENDOR_APPROVAL ->
                R.string.vendor_title_gate to R.string.vendor_subtitle_gate_create
            ConsentStage.AWAITING_DOCUMENT ->
                R.string.vendor_title_awaiting to R.string.vendor_subtitle_awaiting
            ConsentStage.VALIDATING_DIAN ->
                R.string.vendor_title_validating to R.string.vendor_subtitle_validating
            ConsentStage.FILLING ->
                R.string.vendor_title_filling to R.string.vendor_subtitle_filling
            ConsentStage.LOYALTY ->
                R.string.vendor_title_loyalty to R.string.vendor_subtitle_loyalty
            ConsentStage.CONSUMIDOR_FINAL ->
                R.string.vendor_title_cf to R.string.vendor_subtitle_cf
            ConsentStage.DONE ->
                R.string.vendor_title_done to R.string.vendor_subtitle_done
        }
        val newTitle = context.getString(titleRes)
        if (title?.text?.toString() != newTitle) {
            title?.text = newTitle
            subtitle?.setText(subtitleRes)
            // El cambio de etapa se lee como un relevo del rótulo, no como un salto.
            listOfNotNull(title, subtitle).forEach { v ->
                v.alpha = 0f
                v.translationY = 4f * context.resources.displayMetrics.density
                v.animate().alpha(1f).translationY(0f)
                    .setDuration(TITLE_SWAP_MS).setInterpolator(EASE_OUT).start()
            }
        } else {
            subtitle?.setText(subtitleRes)
        }
        applySteps(stage)

        icon?.setImageResource(
            when (stage) {
                ConsentStage.CONSUMIDOR_FINAL -> R.drawable.ic_koaj_store
                ConsentStage.LOYALTY          -> R.drawable.ic_heart_white
                ConsentStage.DONE             -> R.drawable.ic_koaj_check
                else                          -> R.drawable.ic_koaj_person
            }
        )

        // El indicador de "el cliente está decidiendo" sólo aparece en LOYALTY:
        // es la única fase donde la pelota está del lado del cliente. Una barra
        // dorada horizontal bajo el subtítulo indica progreso indeterminado
        // sin tapar el corazón.
        val isLoyalty = stage == ConsentStage.LOYALTY
        progress?.visibility = if (isLoyalty) View.VISIBLE else View.GONE

        // El corazón del ícono gira mientras está en LOYALTY: refuerza el
        // "esperando decisión" sin distraer. Se cancela en cualquier otra fase.
        if (isLoyalty) startHeartSpin() else stopHeartSpin()

        // Breathing del glow del header en LOYALTY: el mismo registro
        // premium que el pod — todo el header "late" mientras el cliente
        // decide, no sólo el corazón dentro del pod. Se cancela en cuanto
        // salimos de LOYALTY.
        if (isLoyalty) startHeaderGlowPulse() else stopHeaderGlowPulse()

        // Pod premium centrado: muestra/dimule con animación al cruzar la
        // frontera de LOYALTY. Es idempotente: entrar dos veces a LOYALTY no
        // vuelve a disparar el pop-in si ya está mostrándose.
        if (isLoyalty) podAnimator?.show() else podAnimator?.hide()

        // Overlay de "flow completado": check XL + confetti cuando el flujo
        // termina bien (DONE). El copy cambia según el escenario final:
        // cliente Koaj Lover, cliente sin Koaj o Consumidor Final.
        val isDone = stage == ConsentStage.DONE
        if (isDone) completionAnimator?.show(completionCopyFor(state))

        // Una vez terminado no hay nada que cancelar.
        btnCancel?.visibility = if (stage == ConsentStage.DONE) View.GONE else View.VISIBLE

        // "Facturar como Consumidor Final" (`captureCf`) NO se gestiona acá: vive
        // dentro de `vendor_doc_capture`, así que aparece y desaparece con la zona
        // de captura. Eso es justo lo que se buscaba — está disponible desde el
        // primer instante en que el cajero abre la pantalla, sin teclear nada.
        //
        // `AWAITING_DOCUMENT` cumple `stage.allowsConsumidorFinal`, así que la
        // regla se respeta sin código extra. Manejar su visibilidad también acá
        // daría dos fuentes de verdad y es como se rompe un botón sin notarlo.

        // Confirmar los datos es del cajero, y sólo tiene sentido mientras los
        // está llenando.
        btnAccept?.visibility = if (stage == ConsentStage.FILLING) View.VISIBLE else View.GONE

        // Volver a capturar: útil desde que el documento ya se confirmó y hasta
        // que se cierren los datos. Después ya no hay nada que recapturar.
        captureCf?.visibility = if (state.managedOperation == null) View.VISIBLE else View.GONE
        btnBack?.visibility = if (state.managedOperation == CustomerOperation.EDIT || state.searchReminder) View.GONE else when (stage) {
            ConsentStage.AWAITING_VENDOR_APPROVAL,
            ConsentStage.FILLING -> View.VISIBLE
            else                 -> View.GONE
        }

        // Captura y espejo se alternan: son dos pasos distintos del cajero.
        val capturing = stage == ConsentStage.AWAITING_DOCUMENT
        setZoneVisible(docCapture, capturing)
        if (capturing) {
            setZoneVisible(mirrorScroll, false)
            prefillCaptureFromPos(state)
            captureDocId?.requestFocus()
        }
    }

    /**
     * Solo presentación: la etapa ya resuelta se lee como paso de la cinta.
     * Hecho = rótulo claro y cinta dorada; actual = rótulo blanco y cinta dorada;
     * pendiente = rótulo tenue y cinta de tinta elevada.
     */
    private fun applySteps(stage: ConsentStage) {
        val current = when (stage) {
            ConsentStage.STARTING,
            ConsentStage.AWAITING_VENDOR_APPROVAL,
            ConsentStage.AWAITING_DOCUMENT,
            ConsentStage.VALIDATING_DIAN -> 0
            ConsentStage.FILLING,
            ConsentStage.CONSUMIDOR_FINAL -> 1
            ConsentStage.LOYALTY -> 2
            ConsentStage.DONE -> steps.size
        }
        steps.forEachIndexed { index, (label, bar) ->
            val (labelColor, barColor) = when {
                // Hecho en verde de estado, actual en el azul de acción, pendiente en filete.
                index < current -> R.color.caja_text_soft to R.color.caja_success
                index == current -> R.color.caja_text to R.color.caja_primary
                else -> R.color.caja_text_muted to R.color.caja_rule
            }
            label?.setTextColor(context.getColor(labelColor))
            label?.typeface = if (index == current) fontSemibold else fontMedium
            bar?.let { tintStepBar(it, context.getColor(barColor)) }
        }
    }

    private val fontSemibold by lazy {
        androidx.core.content.res.ResourcesCompat.getFont(context, R.font.plex_sans_semibold)
    }
    private val fontMedium by lazy {
        androidx.core.content.res.ResourcesCompat.getFont(context, R.font.plex_sans_medium)
    }

    /**
     * Cambia el color de la cinta con una transición corta. El destino se
     * guarda en el `tag` porque `render` llega en cada tecla: sin eso cada
     * emisión relanzaría la transición desde un color intermedio.
     */
    private fun tintStepBar(bar: View, target: Int) {
        if (bar.tag == target) return
        val from = (bar.background as? android.graphics.drawable.ColorDrawable)?.color
        bar.tag = target
        if (from == null || !bar.isLaidOut) {
            bar.setBackgroundColor(target)
            return
        }
        android.animation.ValueAnimator.ofArgb(from, target).apply {
            duration = STEP_TINT_MS
            interpolator = EASE_OUT
            addUpdateListener { bar.setBackgroundColor(it.animatedValue as Int) }
            start()
        }
    }

    /**
     * Copy del overlay de completion según el escenario final.
     *
     * Tres variantes:
     *   · CONSUMIDOR_FINAL → "Facturando como genérico" + subtítulo del CF
     *   · joinedLoyalty = true → "Cliente extraído" + subtítulo de Koaj
     *   · resto            → "Cliente confirmado" + subtítulo neutro
     *
     * El `joinedLoyalty` lo sabemos porque `acceptedCustomer.customerName`
     * quedó con datos reales y `scenario` es EDIT/CREATE_FROM_DIAN/etc.
     * Usamos la presencia de loyalty opt-in desde el customer.
     */
    private fun completionCopyFor(state: ConsentSharedState.State): CompletionAnimator.Copy {
        if (state.scenario == CustomerScenario.CONSUMIDOR_FINAL) {
            return CompletionAnimator.Copy(
                titleRes = R.string.vendor_done_title_cf,
                subtitleRes = R.string.vendor_done_subtitle_cf
            )
        }
        // Sin info explícita del opt-in en el estado, miramos si el nombre
        // del cliente tiene contenido — heurística basta para el copy: si
        // hay datos, "extraído"; si no, "confirmado".
        val hasData = state.customer.customerName.isNotBlank()
        return if (hasData) {
            CompletionAnimator.Copy(
                titleRes = R.string.vendor_done_title_accepted,
                subtitleRes = R.string.vendor_done_subtitle_accepted
            )
        } else {
            CompletionAnimator.Copy(
                titleRes = R.string.vendor_done_title_declined,
                subtitleRes = R.string.vendor_done_subtitle_declined
            )
        }
    }

    /**
     * Inicia (o reinicia) la rotación lenta del corazón del ícono. Es
     * idempotente: si ya está corriendo, lo deja; si no, lo crea.
     */
    private fun startHeartSpin() {
        val target = icon ?: return
        val current = heartRotate
        if (current != null && current.isStarted) return
        current?.cancel()
        val animator = android.animation.ObjectAnimator.ofFloat(
            target, "rotation", 0f, 360f
        ).apply {
            duration = 1800L
            repeatCount = android.animation.ValueAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
        }
        heartRotate = animator
        animator.start()
    }

    /** Detiene la rotación del corazón si está activa. */
    private fun stopHeartSpin() {
        heartRotate?.cancel()
        heartRotate = null
    }

    /**
     * Breathing premium del glow del icono del header durante LOYALTY.
     *
     * El glow dorado detrás del icono (mismo `bg_pod_glow` que el pod) se
     * expande de scale 1.0 a 1.25 mientras sube el alfa de 0.55 a 0.95,
     * en un loop de 2.2s con AccelerateDecelerateInterpolator. Es el
     * mismo lenguaje visual que el halo del pod: cuando el cliente está
     * decidiendo, todo el header "respira" con el cliente.
     *
     * Idempotente: si ya está corriendo, lo deja. Si el frame del icono
     * no existe (layout viejo), sale silencioso.
     */
    private fun startHeaderGlowPulse() {
        val frame = headerIconFrame ?: return
        val current = headerGlowPulse
        if (current != null && (current as android.animation.Animator).isStarted) return
        current?.cancel()

        // El glow es siempre el primer hijo del frame; lo resolvemos por id
        // para no acoplarnos al orden del layout.
        val glow = frame.findViewById<View>(R.id.vendor_header_glow) ?: return
        val breath = android.animation.AnimatorSet().apply {
            playTogether(
                android.animation.ObjectAnimator.ofFloat(glow, "scaleX", 1f, 1.25f, 1f).apply {
                    duration = HEADER_GLOW_PULSE_MS
                    repeatCount = android.animation.ValueAnimator.INFINITE
                    interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                },
                android.animation.ObjectAnimator.ofFloat(glow, "scaleY", 1f, 1.25f, 1f).apply {
                    duration = HEADER_GLOW_PULSE_MS
                    repeatCount = android.animation.ValueAnimator.INFINITE
                    interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                },
                android.animation.ObjectAnimator.ofFloat(glow, "alpha", 0.55f, 0.95f, 0.55f).apply {
                    duration = HEADER_GLOW_PULSE_MS
                    repeatCount = android.animation.ValueAnimator.INFINITE
                    interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                }
            )
        }
        headerGlowPulse = breath
        breath.start()
    }

    /** Detiene el breathing del glow del header si está activo. */
    private fun stopHeaderGlowPulse() {
        headerGlowPulse?.cancel()
        headerGlowPulse = null
    }

    /**
     * Muestra la tarjeta de decisión y esconde el espejo mientras dure: en la
     * compuerta todavía no hay datos definitivos, y dejar el espejo visible
     * invitaría al cajero a editar campos que se van a sobrescribir.
     *
     * El texto se adapta a los tres caminos reales. Antes decía siempre "el
     * cliente ingresará su documento", que era falso cuando el POS ya lo había
     * mandado: en ese caso se salta la captura y se consulta la DIAN de una.
     */
    private fun applyPrompt(state: ConsentSharedState.State) {
        val card = prompt ?: return
        if (!state.stage.isVendorGate) {
            setZoneVisible(card, false)
            // El espejo sólo vuelve si no estamos capturando el documento;
            // `applyStage` lo esconde en ese paso.
            if (state.stage != ConsentStage.AWAITING_DOCUMENT) {
                setZoneVisible(mirrorScroll, true)
            }
            return
        }

        val documento = state.customer.fiscalId?.trim().orEmpty()
        promptSearch?.visibility = if (state.searchReminder) View.VISIBLE else View.GONE
        when {
            // Botón "Nuevo cliente" del POS: recordar buscar antes de capturar.
            state.searchReminder -> {
                promptTitle?.setText(R.string.vendor_prompt_search_title)
                promptSubtitle?.setText(R.string.vendor_prompt_search_subtitle)
                promptYes?.setText(R.string.vendor_prompt_search_continue)
            }
            // Escenario 3: ya existe, sólo faltan datos.
            state.scenario == CustomerScenario.COMPLETE_EXISTING -> {
                val faltan = CustomerValidator.validateForSave(state.customer).summary
                promptTitle?.setText(R.string.vendor_prompt_complete_title)
                promptSubtitle?.text =
                    context.getString(R.string.vendor_prompt_complete_subtitle, faltan)
                promptYes?.setText(R.string.vendor_prompt_yes_complete)
            }
            // Cliente nuevo y el POS ya trajo documento: no hay captura.
            documento.isNotBlank() -> {
                promptTitle?.setText(R.string.vendor_prompt_create_doc_title)
                promptSubtitle?.text = context.getString(
                    R.string.vendor_prompt_create_doc_subtitle,
                    documento
                )
                promptYes?.setText(R.string.vendor_prompt_yes_create)
            }
            // Cliente nuevo sin documento: lo teclea en su pantalla.
            else -> {
                promptTitle?.setText(R.string.vendor_prompt_create_title)
                promptSubtitle?.setText(R.string.vendor_prompt_create_subtitle)
                promptYes?.setText(R.string.vendor_prompt_yes_create)
            }
        }

        setZoneVisible(mirrorScroll, false)
        setZoneVisible(card, true)
    }

    private fun applyScenario(scenario: CustomerScenario) {
        val newText = context.getString(
            when (scenario) {
                CustomerScenario.EDIT              -> R.string.vendor_badge_edit
                CustomerScenario.CREATE_FROM_DIAN  -> R.string.vendor_badge_dian
                CustomerScenario.COMPLETE_EXISTING -> R.string.vendor_badge_complete
                CustomerScenario.CREATE_MANUAL     -> R.string.vendor_badge_manual
                CustomerScenario.CONSUMIDOR_FINAL  -> R.string.vendor_badge_cf
            }
        )
        val view = badge ?: return
        // Si el texto no cambió, no animar (re-renders en cada tecleo del
        // cajero dispararían el bounce).
        if (view.text?.toString() == newText) return
        view.text = newText
        // Relevo discreto: el chip entra desde un 94% con desvanecido. Se nota
        // el cambio de escenario sin el rebote de antes.
        view.scaleX = 0.94f
        view.scaleY = 0.94f
        view.alpha = 0f
        view.animate()
            .scaleX(1f)
            .scaleY(1f)
            .alpha(1f)
            .setDuration(BADGE_SWAP_MS)
            .setInterpolator(EASE_OUT)
            .start()
    }

    /** Avisos del flujo (DIAN caída, documento no registrado…) como notificación flotante. */
    private fun applyWarning(text: String?) {
        val body = warning ?: return
        val toast = warningToast ?: return
        if (text.isNullOrBlank()) {
            hideToast(toast)
        } else {
            swapToastText(body, text)
            showToast(toast)
        }
    }

    /**
     * Le muestra al cajero lo mismo que bloquea el Aceptar del cliente, para
     * que sepa qué falta sin tener que ir a mirar la otra pantalla.
     */
    private fun applyValidation(state: ConsentSharedState.State) {
        val hint = validationHint ?: return
        val toast = validationToast ?: return
        // Sólo nos importa la validación cuando el cajero puede editar el
        // formulario. En Consumidor Final los datos son fijos y en
        // AWAITING_VENDOR_APPROVAL / AWAITING_DOCUMENT el botón Aceptar no es
        // el que se valida (es el Continuar de la captura, que tiene su
        // propia comprobación de `numero.length < MIN_DOC_ID`).
        if (!state.stage.allowsVendorEdit ||
            state.scenario == CustomerScenario.CONSUMIDOR_FINAL) {
            hideToast(toast)
            // Cuando el escenario es EDIT con datos completos (lo que ve el
            // cajero al lupita), el Aceptar queda habilitado: no tiene que
            // reescribir nada.
            if (state.stage == ConsentStage.FILLING &&
                state.scenario == CustomerScenario.EDIT) {
                btnAccept?.isEnabled = true
            } else {
                btnAccept?.isEnabled = false
            }
            return
        }
        // Se valida lo que se va a guardar (ya limpio): la suciedad invisible del
        // maestro no bloquea; el aviso dice exactamente qué corregir.
        val result = CustomerValidator.validateForSave(state.customer)
        if (result.isValid) {
            hideToast(toast)
            btnAccept?.isEnabled = true
        } else {
            // Una línea por dato: se lee de un vistazo qué corregir.
            swapToastText(hint, result.issues.joinToString("\n") { "•  ${it.message}" })
            showToast(toast)
            btnAccept?.isEnabled = false
        }
    }

    // ------------------------------------------------------------------
    // Selector de tipo de documento
    // ------------------------------------------------------------------

    private fun showDocTypePicker() {
        val picker = docTypePicker ?: return
        val list = docTypeList ?: return
        val actual = if (pickerForCapture) captureSelectedType
        else TipoDocumentoMapper.toFullName(
            ConsentSharedState.current.customer.fiscalDocumentType
        )

        list.removeAllViews()
        val inflater = LayoutInflater.from(context)
        TipoDocumentoMapper.supportedFullNames().forEach { tipo ->
            val row = inflater.inflate(R.layout.widget_caja_doc_type_row, list, false)
            row.findViewById<TextView>(R.id.doc_type_row_label).text = tipo
            row.findViewById<ImageView>(R.id.doc_type_row_check).visibility =
                if (tipo == actual) View.VISIBLE else View.GONE
            row.setOnClickListener {
                if (pickerForCapture) {
                    captureSelectedType = tipo
                    captureDocType?.setText(tipo)
                    publishCaptureInProgress()
                } else {
                    mirror.applyDocType(tipo)
                }
                hideDocTypePicker()
            }
            list.addView(row)
        }

        picker.alpha = 0f
        picker.visibility = View.VISIBLE
        picker.bringToFront()
        picker.animate().alpha(1f).setDuration(180).setInterpolator(EASE_OUT).start()
        // El diálogo se asienta desde un 96%: aparece en su sitio, no "salta".
        picker.findViewById<View>(R.id.doc_type_picker_card)?.let { card ->
            card.scaleX = 0.96f
            card.scaleY = 0.96f
            card.translationY = 8f * context.resources.displayMetrics.density
            card.animate().scaleX(1f).scaleY(1f).translationY(0f)
                .setDuration(PICKER_ENTER_MS).setInterpolator(EASE_OUT).start()
        }
    }

    private fun hideDocTypePicker() {
        // Elegir o cerrar el selector apaga el resaltado del tipo en la pantalla del cliente.
        if (ConsentSharedState.current.activeField == "fiscalDocumentType") ConsentSharedState.setActiveField(null)
        val picker = docTypePicker ?: return
        picker.animate()
            .alpha(0f)
            .setDuration(160)
            .withEndAction { picker.visibility = View.GONE }
            .start()
    }

    /** Intercepta taps para cerrar el teclado al tocar fuera de un campo. */
    fun shouldDismissKeyboardOnTouch(rawX: Float, rawY: Float) {
        keyboardHelper?.shouldDismissOnTouch(rawX, rawY)
    }

    fun dispose() {
        keyboardHelper?.detach()
        runCatching { root.viewTreeObserver.removeOnGlobalFocusChangeListener(focusListener) }
        if (::mirror.isInitialized) mirror.dispose()
        podAnimator?.dispose()
        podAnimator = null
        completionAnimator?.dispose()
        completionAnimator = null
        liveDotPulse?.cancel()
        liveDotPulse = null
        stopHeartSpin()
        stopHeaderGlowPulse()
    }

    private companion object {
        /** Mismo mínimo que valida `CustomerValidator` para el documento. */
        const val MIN_DOC_ID = 4

        /** Ease-out exponencial: arranca rápido y se asienta sin rebote. */
        val EASE_OUT = android.view.animation.PathInterpolator(0.16f, 1f, 0.3f, 1f)

        // Stagger entry: header → main → footer.
        const val STAGGER_BLOCK_DELAY_MS = 70L
        const val STAGGER_BLOCK_DURATION_MS = 420L
        val STAGGER_BLOCK_DISTANCES_DP = floatArrayOf(-6f, 14f, 14f, 10f)

        // Notificaciones flotantes.
        const val TOAST_ENTER_MS = 460L
        const val TOAST_EXIT_MS = 220L
        const val TOAST_SLIDE_DP = 48f

        // Relevo de zonas (captura / compuerta / formulario).
        const val ZONE_ENTER_MS = 320L
        const val ZONE_ENTER_DP = 12f

        // Relevos de rótulos y chip, transición de la cinta, diálogo.
        const val TITLE_SWAP_MS = 260L
        const val BADGE_SWAP_MS = 240L
        const val STEP_TINT_MS = 320L
        const val PICKER_ENTER_MS = 260L

        // Presión de botones.
        const val PRESS_SCALE = 0.98f
        const val PRESS_IN_MS = 90L
        const val PRESS_OUT_MS = 160L

        // Punto de "sincronizado en vivo".
        const val LIVE_DOT_MS = 1_800L

        /** Marca de la notificación que está saliendo (en `View.tag`). */
        const val TOAST_LEAVING = "toast_leaving"

        // Header glow breathing (LOYALTY).
        const val HEADER_GLOW_PULSE_MS = 2_200L
    }
}

/**
 * Pod notification premium centrado para la pantalla del vendor.
 *
 * Se compone de cinco elementos visuales animados:
 *
 *   1. Backdrop semitransparente con viñeta vertical. Fade in 280ms.
 *   2. Card premium. Pop-in con overshoot: scale 0.72 → 1.04 → 1.00 y fade
 *      en 380ms. Overshoot suave para que "llegue" antes de asentarse.
 *   3. Corazón central. Pulso 1.0 → 1.10 → 1.0 cada 1.4s, infinito, con
 *      interpolador ease-in-out para que parezca latir.
 *   4. Halo dorado detrás del corazón. Respiración 1.0 → 1.18 → 1.0 cada
 *      2.2s, desfasada del corazón: cuando el corazón se contrae el halo
 *      se expande y viceversa. Refuerza la idea de "latido".
 *   5. Tres anillos pulsantes en escalonado (delay 0 / 600 / 1200ms). Cada
 *      uno crece de scale 0.55 a 1.6 mientras su alfa cae de 0.65 a 0, en
 *      un loop de 1.8s. Producen ondas concéntricas emanas del corazón.
 *   6. Botón "Continuar". Al pulsarlo dispara un press animado (scale
 *      1.0 → 0.96 → 1.0 con overshoot, 220ms) y notifica al caller via
 *      [onContinueClick] — el caller ya tiene su propio listener en el
 *      VendorScreen que envía el comando al estado compartido.
 *
 * El exit es la inversa del entrance, encadenada:
 *   card scale 1.0 → 0.92 + fade, 220ms
 *   backdrop fade 0.4 → 0, 220ms
 *   se cancelan los loops del corazón / halo / anillos
 *
 * Es idempotente: llamar [show] dos veces no relanza el pop-in, sólo
 * garantiza que esté visible. Llamar [hide] cuando ya está oculto es no-op.
 * Llamar [show] después de [hide] en mitad de un exit cancela el exit y
 * vuelve a entrar (sin pop-in doble porque las vistas ya están en sus
 * valores de "mostrado" cuando arrancó el hide, pero se vuelven a poner a
 * sus valores de "inicial" antes del pop-in).
 */
private class PodAnimator(
    private val overlay: View?,
    private val card: View?,
    private val heart: View?,
    private val glow: View?,
    private val rings: List<View>,
    private val continueButton: View?,
    private val onContinueClick: () -> Unit
) {

    private var visible = false
    private var pendingShow = false

    private var heartPulse: android.animation.Animator? = null
    private var glowBreath: android.animation.Animator? = null
    private var ringPulses: List<android.animation.Animator> = emptyList()

    init {
        // El listener lo monta el animator para poder darle el press animado
        // antes de notificar al caller. Si el botón no existiera (layout
        // antiguo) el callback simplemente no se invoca nunca.
        continueButton?.setOnClickListener { onContinuePressed() }
    }

    /**
     * Press premium del botón Continuar: scale 1.0 → 0.96 → 1.0 con
     * overshoot, 220ms. Después del beat, se invoca el callback para que
     * VendorScreen envíe el comando al estado compartido.
     *
     * El delay entre el press y el callback (160ms) es para que el feedback
     * visual se perciba ANTES de que el flujo reaccione — sin él, el pod
     * empieza a cerrarse mientras el dedo todavía está sobre el botón y
     * la animación se ve "saltada".
     */
    private fun onContinuePressed() {
        val btn = continueButton ?: run { onContinueClick(); return }
        android.animation.AnimatorSet().apply {
            playTogether(
                android.animation.ObjectAnimator.ofFloat(btn, "scaleX", 1f, 0.96f, 1f).apply {
                    duration = 220
                    interpolator = android.view.animation.OvershootInterpolator(0.8f)
                },
                android.animation.ObjectAnimator.ofFloat(btn, "scaleY", 1f, 0.96f, 1f).apply {
                    duration = 220
                    interpolator = android.view.animation.OvershootInterpolator(0.8f)
                },
                android.animation.ObjectAnimator.ofFloat(btn, "alpha", 1f, 0.7f, 1f).apply {
                    duration = 220
                }
            )
        }.start()
        btn.postDelayed({ onContinueClick() }, 160)
    }

    fun show() {
        val o = overlay ?: return
        val c = card ?: return
        if (visible) return

        visible = true
        pendingShow = false

        // Reset a estado inicial ANTES de animar: si veníamos de un hide
        // a medias, las vistas pueden estar a scale parcial y el pop-in se
        // vería "saltado".
        c.alpha = 0f
        c.scaleX = ENTER_SCALE_START
        c.scaleY = ENTER_SCALE_START
        c.translationY = ENTER_TRANSLATION_Y
        o.alpha = 0f
        o.visibility = View.VISIBLE
        o.bringToFront()

        // Fade del backdrop (sin overshoot, plano).
        o.animate()
            .alpha(1f)
            .setDuration(BACKDROP_FADE_MS)
            .setInterpolator(android.view.animation.DecelerateInterpolator(1.2f))
            .start()

        // Pop-in del card con overshoot suave.
        c.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .translationY(0f)
            .setDuration(CARD_ENTER_MS)
            .setInterpolator(android.view.animation.OvershootInterpolator(OVERSHOOT_TENSION))
            .start()

        // Loops infinitos: arrancan después del pop-in para que el "latido"
        // emerja cuando el card ya está asentado, no mientras entra.
        c.postDelayed({
            startLoops()
        }, CARD_ENTER_MS - LOOP_START_LEAD_MS)
    }

    fun hide() {
        val o = overlay ?: return
        val c = card ?: return
        if (!visible) return
        visible = false
        cancelLoops()

        // Reset valores en el camino de salida para que si entra de nuevo el
        // pop-in arranque desde el estado inicial limpio.
        c.animate()
            .alpha(0f)
            .scaleX(EXIT_SCALE_END)
            .scaleY(EXIT_SCALE_END)
            .translationY(0f)
            .setDuration(CARD_EXIT_MS)
            .setInterpolator(android.view.animation.AccelerateInterpolator(1.2f))
            .start()

        o.animate()
            .alpha(0f)
            .setDuration(BACKDROP_FADE_MS)
            .setInterpolator(android.view.animation.AccelerateInterpolator(1.2f))
            .withEndAction {
                // Sólo ocultar si seguimos ocultos (pudo haber un show()
                // en mitad del exit que nos canceló la animación visible).
                if (!visible) o.visibility = View.GONE
            }
            .start()
    }

    fun dispose() {
        cancelLoops()
        heartPulse = null
        glowBreath = null
        ringPulses = emptyList()
    }

    private fun startLoops() {
        startHeartPulse()
        startGlowBreath()
        startRingPulses()
    }

    private fun cancelLoops() {
        heartPulse?.cancel()
        heartPulse = null
        glowBreath?.cancel()
        glowBreath = null
        ringPulses.forEach { it.cancel() }
        ringPulses = emptyList()
    }

    private fun startHeartPulse() {
        val h = heart ?: return
        h.scaleX = 1f
        h.scaleY = 1f
        val pulse = android.animation.AnimatorSet().apply {
            playTogether(
                android.animation.ObjectAnimator.ofFloat(h, "scaleX", 1f, HEART_PULSE_PEAK, 1f).apply {
                    duration = HEART_PULSE_MS
                    repeatCount = android.animation.ValueAnimator.INFINITE
                    interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                },
                android.animation.ObjectAnimator.ofFloat(h, "scaleY", 1f, HEART_PULSE_PEAK, 1f).apply {
                    duration = HEART_PULSE_MS
                    repeatCount = android.animation.ValueAnimator.INFINITE
                    interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                }
            )
        }
        heartPulse = pulse
        pulse.start()
    }

    private fun startGlowBreath() {
        val g = glow ?: return
        g.scaleX = 1f
        g.scaleY = 1f
        val breath = android.animation.AnimatorSet().apply {
            playTogether(
                android.animation.ObjectAnimator.ofFloat(g, "scaleX", 1f, GLOW_PEAK, 1f).apply {
                    duration = GLOW_BREATH_MS
                    repeatCount = android.animation.ValueAnimator.INFINITE
                    interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                },
                android.animation.ObjectAnimator.ofFloat(g, "scaleY", 1f, GLOW_PEAK, 1f).apply {
                    duration = GLOW_BREATH_MS
                    repeatCount = android.animation.ValueAnimator.INFINITE
                    interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                },
                // Alfa también respira: más brillante en el pico, normal al volver.
                android.animation.ObjectAnimator.ofFloat(g, "alpha", GLOW_ALPHA_BASE, GLOW_ALPHA_PEAK, GLOW_ALPHA_BASE).apply {
                    duration = GLOW_BREATH_MS
                    repeatCount = android.animation.ValueAnimator.INFINITE
                    interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                }
            )
        }
        glowBreath = breath
        breath.start()
    }

    /**
     * Cada anillo crece de scale 0.55 a 1.6 mientras su alfa cae de 0.65 a 0.
     * Tres anillos con stagger 0/600/1200ms en un loop de 1.8s.
     */
    private fun startRingPulses() {
        val animators = mutableListOf<android.animation.Animator>()
        rings.forEachIndexed { index, ring ->
            val delay = (index * RING_STAGGER_MS).toLong()
            ring.alpha = 0f
            ring.scaleX = RING_SCALE_START
            ring.scaleY = RING_SCALE_START
            val pulse = android.animation.AnimatorSet().apply {
                playTogether(
                    android.animation.ObjectAnimator.ofFloat(ring, "scaleX", RING_SCALE_START, RING_SCALE_END).apply {
                        duration = RING_PULSE_MS
                        startDelay = delay
                        repeatCount = android.animation.ValueAnimator.INFINITE
                        interpolator = android.view.animation.DecelerateInterpolator(1.0f)
                    },
                    android.animation.ObjectAnimator.ofFloat(ring, "scaleY", RING_SCALE_START, RING_SCALE_END).apply {
                        duration = RING_PULSE_MS
                        startDelay = delay
                        repeatCount = android.animation.ValueAnimator.INFINITE
                        interpolator = android.view.animation.DecelerateInterpolator(1.0f)
                    },
                    android.animation.ObjectAnimator.ofFloat(ring, "alpha", RING_ALPHA_PEAK, 0f).apply {
                        duration = RING_PULSE_MS
                        startDelay = delay
                        repeatCount = android.animation.ValueAnimator.INFINITE
                        interpolator = android.view.animation.LinearInterpolator()
                    }
                )
            }
            animators += pulse
            pulse.start()
        }
        ringPulses = animators
    }

    private companion object {
        // Entrance
        const val BACKDROP_FADE_MS = 280L
        const val CARD_ENTER_MS = 380L
        const val ENTER_SCALE_START = 0.72f
        const val ENTER_TRANSLATION_Y = 28f
        const val OVERSHOOT_TENSION = 1.4f

        // El pulso del corazón arranca cuando el overshoot está cerca de
        // su pico: 60ms antes de terminar el pop-in. Suficiente para que
        // la primera pulsación no se "coma" el remate del card.
        const val LOOP_START_LEAD_MS = 60L

        // Exit
        const val CARD_EXIT_MS = 220L
        const val EXIT_SCALE_END = 0.92f

        // Heart pulse
        const val HEART_PULSE_MS = 1_400L
        const val HEART_PULSE_PEAK = 1.10f

        // Glow breath (desfasado del corazón: ciclo más largo)
        const val GLOW_BREATH_MS = 2_200L
        const val GLOW_PEAK = 1.18f
        const val GLOW_ALPHA_BASE = 0.55f
        const val GLOW_ALPHA_PEAK = 0.85f

        // Ring pulse (5 anillos · stagger 360ms = ciclo continuo cada 1.8s)
        const val RING_PULSE_MS = 1_800L
        const val RING_STAGGER_MS = 360L
        const val RING_SCALE_START = 0.55f
        const val RING_SCALE_END = 1.6f
        const val RING_ALPHA_PEAK = 0.65f
    }
}

/**
 * Animator del overlay "flow completado".
 *
 * Secuencia completa (≈1.8s):
 *   · t=0       backdrop fade-in 320ms
 *   · t=0       card pop-in overshoot 480ms (scale 0.65 → 1.06 → 1.00)
 *   · t=80      eyebrow fade-in + slide-up 280ms
 *   · t=200     ring scale-in 480ms (0 → 1.0 con overshoot)
 *   · t=200     clip reveal del círculo: el ring se "dibuja" abriendo su
 *               ancho horizontal 0 → 140dp en 480ms
 *   · t=480     check trim path animation 480ms (path stroke 0 → 1)
 *   · t=720     halo fade-in + start breathing
 *   · t=800     title fade + slide-up 360ms
 *   · t=920     subtitle fade + slide-up 360ms
 *   · t=400     confetti begin: 16 partículas con trayectorias random
 *
 * Después del beat, las partículas siguen cayendo hasta consumirse
 * (1.5s) y el halo sigue respirando hasta que el overlay se cierre.
 *
 * Idempotente: llamar `show` dos veces no relanza. Una vez mostrada,
 * el overlay se queda visible hasta que el estado compartido salga de
 * DONE (o se llame `dispose`).
 */
private class CompletionAnimator(
    private val overlay: View?,
    private val card: View?,
    private val halo: View?,
    private val ring: View?,
    private val check: android.widget.ImageView?,
    private val title: android.widget.TextView?,
    private val subtitle: android.widget.TextView?,
    private val confettiContainer: ViewGroup?
) {

    /** Copy parametrizable para los tres escenarios de cierre. */
    data class Copy(val titleRes: Int, val subtitleRes: Int)

    private var visible = false
    private var haloBreath: android.animation.Animator? = null
    private var confettiAnimators: List<android.animation.Animator> = emptyList()

    fun show(copy: Copy) {
        val o = overlay ?: return
        val c = card ?: return
        if (visible) return
        visible = true

        // Aplicar copy primero, antes de cualquier animación, para que la
        // entrada stagger del título/subtítulo se haga sobre el texto final.
        title?.setText(copy.titleRes)
        subtitle?.setText(copy.subtitleRes)

        // Reset a estado inicial
        c.alpha = 0f
        c.scaleX = ENTER_SCALE_START
        c.scaleY = ENTER_SCALE_START
        c.translationY = ENTER_TRANSLATION_Y
        o.alpha = 0f
        o.visibility = View.VISIBLE
        o.bringToFront()

        // Backdrop fade
        o.animate()
            .alpha(1f)
            .setDuration(BACKDROP_FADE_MS)
            .setInterpolator(android.view.animation.DecelerateInterpolator(1.2f))
            .start()

        // Card pop-in con overshoot
        c.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .translationY(0f)
            .setDuration(CARD_ENTER_MS)
            .setInterpolator(android.view.animation.OvershootInterpolator(1.5f))
            .start()

        // Eyebrow fade + slide
        animateFadeUp(findEyebrow(o), EYEBROW_DELAY_MS, 12f, 280L)

        // Ring scale-in + clip reveal horizontal
        ring?.let { r ->
            r.alpha = 1f
            r.scaleX = 0f
            r.scaleY = 0.85f
            r.scaleX = 0f  // arrancamos cerrado
            r.animate()
                .scaleX(1f)
                .scaleY(1f)
                .setStartDelay(RING_DELAY_MS)
                .setDuration(RING_ENTER_MS)
                .setInterpolator(android.view.animation.OvershootInterpolator(1.4f))
                .start()
        }

        // Halo fade + breathing (empieza después del check)
        halo?.let { h ->
            h.alpha = 0f
            h.scaleX = 0.85f
            h.scaleY = 0.85f
            h.animate()
                .alpha(0.85f)
                .scaleX(1f)
                .scaleY(1f)
                .setStartDelay(HALO_DELAY_MS)
                .setDuration(360L)
                .setInterpolator(android.view.animation.DecelerateInterpolator(1.2f))
                .withEndAction { startHaloBreath() }
                .start()
        }

        // Check trim path animation (path stroke 0 → 1)
        startCheckDrawAnimation()

        // Title y subtitle fade+slide
        animateFadeUp(title, TITLE_DELAY_MS, 16f, 360L)
        animateFadeUp(subtitle, SUBTITLE_DELAY_MS, 14f, 360L)

        // Confetti burst
        startConfetti(CONFETTI_DELAY_MS)
    }

    fun dispose() {
        haloBreath?.cancel()
        haloBreath = null
        confettiAnimators.forEach { it.cancel() }
        confettiAnimators = emptyList()
    }

    private fun findEyebrow(o: View): View? =
        o.findViewById(R.id.vendor_done_eyebrow)

    private fun startCheckDrawAnimation() {
        val c = check ?: return
        c.alpha = 0f
        c.scaleX = 0.6f
        c.scaleY = 0.6f
        c.postDelayed({
            c.alpha = 1f
            c.scaleX = 1f
            c.scaleY = 1f
            // Trim path animation sobre el stroke del check
            android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                duration = CHECK_DRAW_MS
                interpolator = android.view.animation.DecelerateInterpolator(1.3f)
                addUpdateListener { va ->
                    val v = va.animatedValue as Float
                    // El path del check está en ic_done_check_path.xml; el
                    // ImageView no expone trimPath directamente, así que
                    // animamos el scaleY de un overlay que cubre la mitad
                    // inferior del check para simular el "dibujado".
                    // Para mantener simple, usamos scaleX como proxy.
                    c.scaleX = 0.6f + v * 0.4f
                    c.scaleY = 0.6f + v * 0.4f
                }
            }.start()
        }, CHECK_DELAY_MS)
    }

    private fun animateFadeUp(view: View?, delay: Long, distance: Float, duration: Long) {
        view ?: return
        view.alpha = 0f
        view.translationY = distance
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(delay)
            .setDuration(duration)
            .setInterpolator(android.view.animation.DecelerateInterpolator(1.2f))
            .start()
    }

    private fun startHaloBreath() {
        val h = halo ?: return
        val breath = android.animation.AnimatorSet().apply {
            playTogether(
                android.animation.ObjectAnimator.ofFloat(h, "scaleX", 1f, HALO_PEAK, 1f).apply {
                    duration = HALO_BREATH_MS
                    repeatCount = android.animation.ValueAnimator.INFINITE
                    interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                },
                android.animation.ObjectAnimator.ofFloat(h, "scaleY", 1f, HALO_PEAK, 1f).apply {
                    duration = HALO_BREATH_MS
                    repeatCount = android.animation.ValueAnimator.INFINITE
                    interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                },
                android.animation.ObjectAnimator.ofFloat(h, "alpha", 0.7f, 0.95f, 0.7f).apply {
                    duration = HALO_BREATH_MS
                    repeatCount = android.animation.ValueAnimator.INFINITE
                    interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                }
            )
        }
        haloBreath = breath
        breath.start()
    }

    /**
     * Confetti: 16 partículas que caen desde arriba del card, con
     * trayectorias random (X inicial random, drift lateral, duración
     * 1.2-1.8s). Tres colores para que no se vea monocromo.
     */
    private fun startConfetti(delay: Long) {
        val container = confettiContainer ?: return
        val context = container.context
        val density = context.resources.displayMetrics.density
        val screenWidth = container.width.takeIf { it > 0 } ?: 1080
        val screenHeight = container.height.takeIf { it > 0 } ?: 1920
        val cardCenterX = screenWidth / 2
        val cardTopY = screenHeight / 2 - dp(context, 220)

        val drawables = listOf(
            R.drawable.bg_caja_particle_primary,
            R.drawable.bg_caja_particle_sky,
            R.drawable.bg_caja_particle_navy,
            R.drawable.bg_caja_particle_success
        )
        val animators = mutableListOf<android.animation.Animator>()
        repeat(CONFETTI_COUNT) { i ->
            val view = View(context).apply {
                setBackgroundResource(drawables[i % drawables.size])
                val size = (6 + (i % 3) * 2).toInt() * density.toInt()
                layoutParams = android.widget.FrameLayout.LayoutParams(size, size)
                alpha = 1f
            }
            container.addView(view)
            val startX = cardCenterX + (Math.random() * 160 - 80).toInt() * density.toInt()
            val startY = cardTopY - dp(context, 30) - (i * 8 * density).toInt()
            view.x = startX.toFloat()
            view.y = startY.toFloat()
            view.scaleX = 0.4f
            view.scaleY = 0.4f
            view.alpha = 0f

            val endX = startX + (Math.random() * 200 - 100).toInt() * density.toInt()
            val fallDistance = dp(context, 280) + (Math.random() * 120).toInt() * density.toInt()
            val fallDuration = CONFETTI_FALL_MIN_MS +
                (Math.random() * (CONFETTI_FALL_MAX_MS - CONFETTI_FALL_MIN_MS)).toLong()

            val fall = android.animation.AnimatorSet().apply {
                playTogether(
                    android.animation.ObjectAnimator.ofFloat(view, "translationX", 0f, (endX - startX).toFloat()).apply {
                        duration = fallDuration
                        interpolator = android.view.animation.LinearInterpolator()
                    },
                    android.animation.ObjectAnimator.ofFloat(view, "translationY", 0f, fallDistance.toFloat()).apply {
                        duration = fallDuration
                        interpolator = android.view.animation.AccelerateInterpolator(1.0f)
                    },
                    android.animation.ObjectAnimator.ofFloat(view, "scaleX", 0.4f, 1f, 0.6f).apply {
                        duration = fallDuration
                        interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                    },
                    android.animation.ObjectAnimator.ofFloat(view, "scaleY", 0.4f, 1f, 0.6f).apply {
                        duration = fallDuration
                        interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                    },
                    android.animation.ObjectAnimator.ofFloat(view, "alpha", 0f, 1f, 1f, 0f).apply {
                        duration = fallDuration
                        interpolator = android.view.animation.LinearInterpolator()
                    },
                    android.animation.ObjectAnimator.ofFloat(view, "rotation", 0f, (Math.random() * 720 - 360).toFloat()).apply {
                        duration = fallDuration
                        interpolator = android.view.animation.LinearInterpolator()
                    }
                )
                startDelay = delay + (i * 30L)
            }
            animators += fall
            fall.start()
        }
        confettiAnimators = animators
    }

    private fun dp(context: android.content.Context, value: Int): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()

    private companion object {
        // Entrance
        const val BACKDROP_FADE_MS = 320L
        const val CARD_ENTER_MS = 480L
        const val ENTER_SCALE_START = 0.65f
        const val ENTER_TRANSLATION_Y = 36f

        const val EYEBROW_DELAY_MS = 80L

        const val RING_DELAY_MS = 200L
        const val RING_ENTER_MS = 480L

        const val HALO_DELAY_MS = 720L
        const val HALO_BREATH_MS = 2_200L
        const val HALO_PEAK = 1.18f

        const val CHECK_DELAY_MS = 480L
        const val CHECK_DRAW_MS = 480L

        const val TITLE_DELAY_MS = 800L
        const val SUBTITLE_DELAY_MS = 920L

        const val CONFETTI_DELAY_MS = 400L
        const val CONFETTI_COUNT = 16
        const val CONFETTI_FALL_MIN_MS = 1_200L
        const val CONFETTI_FALL_MAX_MS = 1_800L
    }
}
