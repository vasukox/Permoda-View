package com.permoda.facturacion.entry

import android.animation.ObjectAnimator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.permoda.facturacion.BuildConfig
import com.permoda.facturacion.PermodaViewApp
import com.permoda.facturacion.R
import com.permoda.facturacion.core.ParsedCustomer
import com.permoda.facturacion.core.Xml
import com.permoda.facturacion.core.consent.ConsentSharedState
import com.permoda.facturacion.core.hka.CustomerMerger
import com.permoda.facturacion.core.hka.DianStatus
import com.permoda.facturacion.core.hka.HkaAuditor
import com.permoda.facturacion.core.hka.HkaCache
import com.permoda.facturacion.core.hka.HkaClient
import com.permoda.facturacion.core.hka.HkaConfig
import com.permoda.facturacion.core.hka.HkaResponse
import com.permoda.facturacion.core.hka.TipoDocumentoMapper
import com.permoda.facturacion.core.loyalty.LoyaltyAuditor
import com.permoda.facturacion.core.loyalty.LoyaltyConfig
import com.permoda.facturacion.core.model.CustomerData
import com.permoda.facturacion.core.scenario.CustomerScenario
import com.permoda.facturacion.core.ui.KeyboardDismissHelper
import com.permoda.facturacion.presentation.consent.ConsentResult
import com.permoda.facturacion.presentation.display.ConsentController
import com.permoda.facturacion.presentation.display.ConsentPresentation
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Driver único — emula el ciclo completo de HioPosCloud sobre la APK.
 *
 * Pantalla del operador dividida en dos zonas SIEMPRE visibles:
 *
 *   · Header premium (40%): gradiente azul con ícono circular pulsante
 *     y texto dinámico que cambia según la fase:
 *       - Inicio:      "Esperando respuesta del cliente"
 *       - Editando:    "Cliente está llenando sus datos"
 *       - Confirmado:  "Cliente confirmó datos" (resultado breve, 1.5 s)
 *
 *   · Mirror del cliente (60%): réplica en vivo del formulario de la 10".
 *     Editable bidireccionalmente. Lo que el operador tipea se propaga
 *     a la pantalla del cliente, y viceversa.
 */
class TestDriverActivity : AppCompatActivity() {

    private val tag = "TestDriver"
    private val animOvershoot = OvershootInterpolator(0.7f)

    /** Token que el driver simula recibir en INITIALIZE. */
    private var auditToken: String = "test-driver-token"

    private lateinit var driverTop: LinearLayout
    private lateinit var driverBottom: FrameLayout
    private lateinit var avatar: ImageView
    private lateinit var avatarPulseBadge: View
    private lateinit var driverTitle: TextView
    private lateinit var driverSubtitle: TextView
    private lateinit var resultBlock: LinearLayout
    private lateinit var resultIcon: ImageView
    private lateinit var resultTitle: TextView
    private lateinit var resultSubtitle: TextView
    private lateinit var mirrorPhaseLabel: TextView

    private lateinit var mirrorName: EditText
    private lateinit var mirrorDocType: EditText
    private lateinit var mirrorDocId: EditText
    private lateinit var mirrorEmail: EditText
    private lateinit var mirrorPhone: EditText

    private var docTypePicker: View? = null
    private var docTypePickerScrim: View? = null
    private var docTypeList: LinearLayout? = null

    private val dotViews = mutableListOf<View>()
    private var avatarPulse: ObjectAnimator? = null
    private var badgePulse: ObjectAnimator? = null

    private val closeHandler = Handler(Looper.getMainLooper())
    private val closeRunnable = Runnable { finish() }

    private var keyboardHelper: KeyboardDismissHelper? = null

    /** True cuando estamos actualizando el mirror desde el shared state (evita bucle). */
    private var suppressMirrorEmit = false
    private var phase: Phase = Phase.Waiting

    private enum class Phase { Waiting, Filling, Confirmed }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_driver)

        driverTop         = findViewById(R.id.driver_top)
        driverBottom      = findViewById(R.id.driver_bottom)
        avatar            = findViewById(R.id.driver_avatar)
        avatarPulseBadge  = findViewById(R.id.driver_avatar_pulse)
        driverTitle       = findViewById(R.id.driver_title)
        driverSubtitle    = findViewById(R.id.driver_subtitle)
        resultBlock       = findViewById(R.id.driver_result_block)
        resultIcon        = findViewById(R.id.driver_result_icon)
        resultTitle       = findViewById(R.id.driver_result_title)
        resultSubtitle    = findViewById(R.id.driver_result_subtitle)
        mirrorPhaseLabel  = findViewById(R.id.mirror_phase_label)

        mirrorName    = findViewById(R.id.mirror_name)
        mirrorDocType = findViewById(R.id.mirror_doc_type)
        mirrorDocId   = findViewById(R.id.mirror_doc_id)
        mirrorEmail   = findViewById(R.id.mirror_email)
        mirrorPhone   = findViewById(R.id.mirror_phone)

        docTypePicker       = findViewById(R.id.zone_doc_type_picker)
        docTypePickerScrim  = findViewById(R.id.doc_type_picker_scrim)
        docTypeList         = findViewById(R.id.doc_type_list)

        dotViews += findViewById<View>(R.id.driver_dot_1)
        dotViews += findViewById<View>(R.id.driver_dot_2)
        dotViews += findViewById<View>(R.id.driver_dot_3)

        setupMirrorListeners()
        observeSharedState()

        // Helper del teclado: muestra el botón flotante ▼ cuando se abre
        findViewById<View>(R.id.keyboard_dismiss)?.let { btn ->
            keyboardHelper = KeyboardDismissHelper(btn, driverBottom)
        }

        startHeaderAnimations()
        applyPhase(Phase.Waiting)

        startConsentFlow()
    }

    private fun setupMirrorListeners() {
        val mirrorListener = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (suppressMirrorEmit) return
                val current = ConsentSharedState.current.customer
                val updated = current.copy(
                    customerName       = mirrorName.text?.toString().orEmpty(),
                    fiscalDocumentType = mirrorDocType.text?.toString().orEmpty(),
                    fiscalId           = mirrorDocId.text?.toString().orEmpty(),
                    email              = mirrorEmail.text?.toString().orEmpty(),
                    phone              = mirrorPhone.text?.toString().orEmpty()
                )
                ConsentSharedState.updateCustomer(updated)
            }
        }
        mirrorName.addTextChangedListener(mirrorListener)
        mirrorDocId.addTextChangedListener(mirrorListener)
        mirrorEmail.addTextChangedListener(mirrorListener)
        mirrorPhone.addTextChangedListener(mirrorListener)

        // El tipo de documento se selecciona via AlertDialog (no es editable a mano)
        mirrorDocType.setOnClickListener { showMirrorDocTypePicker() }
        mirrorDocType.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) showMirrorDocTypePicker()
        }
    }

    /**
     * Picker de tipo de documento para el mirror del cajero. Mismo overlay
     * inline que la pantalla del cliente (zone_doc_type_picker).
     */
    private fun showMirrorDocTypePicker() {
        val picker = docTypePicker ?: return
        val list = docTypeList ?: return

        // Cerrar teclado primero para que no tape el picker
        keyboardHelper?.hideKeyboard()

        val nombres = TipoDocumentoMapper.supportedFullNames()
        val actual = ConsentSharedState.current.customer.fiscalDocumentType

        // Construir las filas dinámicamente (mismo aspecto que cliente)
        list.removeAllViews()
        val inflater = LayoutInflater.from(this)
        nombres.forEach { nombre ->
            val row = inflater.inflate(R.layout.widget_doc_type_row, list, false)
            val label = row.findViewById<TextView>(R.id.doc_type_row_label)
            val check = row.findViewById<ImageView>(R.id.doc_type_row_check)
            label.text = nombre
            check.visibility = if (nombre.equals(actual, ignoreCase = true)) View.VISIBLE else View.GONE
            row.setOnClickListener {
                val current = ConsentSharedState.current.customer
                ConsentSharedState.updateCustomer(current.copy(fiscalDocumentType = nombre))
                suppressMirrorEmit = true
                mirrorDocType.setText(nombre)
                suppressMirrorEmit = false
                hideMirrorDocTypePicker()
            }
            list.addView(row)
        }

        // Cerrar al tocar el fondo oscuro
        docTypePickerScrim?.setOnClickListener { hideMirrorDocTypePicker() }

        // Mostrar con crossfade
        picker.alpha = 0f
        picker.visibility = View.VISIBLE
        picker.bringToFront()
        picker.animate().alpha(1f).setDuration(200).start()
    }

    private fun hideMirrorDocTypePicker() {
        val picker = docTypePicker ?: return
        picker.animate()
            .alpha(0f)
            .setDuration(180)
            .withEndAction { picker.visibility = View.GONE }
            .start()
    }

    private fun applyMirrorLockedField(@Suppress("UNUSED_PARAMETER") key: String?) {
        // No-op: ya no hay lock. Cajero y cliente editan libremente.
    }

    private fun observeSharedState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ConsentSharedState.state.collectLatest { shared ->
                    applyMirrorFromState(shared.customer)
                    updatePhaseFromState(shared)
                }
            }
        }
    }

    private fun applyMirrorFromState(c: com.permoda.facturacion.core.model.CustomerData) {
        suppressMirrorEmit = true
        try {
            val tipoCompleto = TipoDocumentoMapper.toFullName(c.fiscalDocumentType)
                ?: c.fiscalDocumentType.orEmpty()
            if (mirrorName.text?.toString() != c.customerName)
                mirrorName.setText(c.customerName)
            if (mirrorDocType.text?.toString() != tipoCompleto)
                mirrorDocType.setText(tipoCompleto)
            if (mirrorDocId.text?.toString() != c.fiscalId)
                mirrorDocId.setText(c.fiscalId)
            if (mirrorEmail.text?.toString() != c.email)
                mirrorEmail.setText(c.email)
            if (mirrorPhone.text?.toString() != c.phone)
                mirrorPhone.setText(c.phone)
        } finally {
            suppressMirrorEmit = false
        }
    }

    private fun updatePhaseFromState(state: ConsentSharedState.State) {
        val c = state.customer
        val hasAnyData = c.customerName.isNotBlank() ||
            !c.fiscalId.isNullOrBlank() ||
            !c.email.isNullOrBlank() ||
            !c.phone.isNullOrBlank()
        val newPhase = when {
            hasAnyData -> Phase.Filling
            else       -> Phase.Waiting
        }
        if (newPhase != phase) applyPhase(newPhase)

        mirrorPhaseLabel.text = when (state.scenario) {
            CustomerScenario.CONSUMIDOR_FINAL ->
                "Facturando como Consumidor Final"
            CustomerScenario.EDIT ->
                if (hasAnyData) "Cliente está editando datos"
                else "Esperando que el cliente confirme…"
            CustomerScenario.CREATE_FROM_DIAN ->
                "Cliente no está en HioPos · datos de la DIAN"
            CustomerScenario.COMPLETE_EXISTING ->
                "Existe en HioPos · completando datos faltantes"
            CustomerScenario.CREATE_MANUAL ->
                if (c.customerName.isBlank()) "Cliente está ingresando tipo y documento…"
                else "Cliente no está en HioPos · completando a mano"
        }
    }

    private fun applyPhase(newPhase: Phase) {
        phase = newPhase
        when (newPhase) {
            Phase.Waiting -> {
                driverTitle.text = "Esperando respuesta del cliente"
                driverSubtitle.text = "Esperando que se identifique en la pantalla de 10\""
                startDotAnimation()
                setResultBlockVisible(false)
            }
            Phase.Filling -> {
                driverTitle.text = "Cliente está llenando datos"
                driverSubtitle.text = "Editando en la pantalla de 10\" · sincronizado"
                stopDotAnimation()
                setResultBlockVisible(false)
            }
            Phase.Confirmed -> {
                stopDotAnimation()
            }
        }
    }

    private fun setResultBlockVisible(visible: Boolean) {
        resultBlock.alpha = if (visible) 1f else 0f
        resultBlock.visibility = if (visible) View.VISIBLE else View.GONE
    }

    /**
     * Header premium: avatar central con pulso + badge luminoso en esquina
     * superior derecha que también pulsa.
     */
    private fun startHeaderAnimations() {
        avatarPulse?.cancel()
        avatarPulse = ObjectAnimator.ofFloat(avatar, "scaleX", 1f, 1.06f, 1f).apply {
            duration = 1800
            repeatCount = ObjectAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
        ObjectAnimator.ofFloat(avatar, "scaleY", 1f, 1.06f, 1f).apply {
            duration = 1800
            repeatCount = ObjectAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }

        badgePulse?.cancel()
        badgePulse = ObjectAnimator.ofFloat(avatarPulseBadge, "alpha", 0.4f, 1f, 0.4f).apply {
            duration = 1400
            repeatCount = ObjectAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun startDotAnimation() {
        var phase = 0
        val dotRunnable = object : Runnable {
            override fun run() {
                phase = (phase + 1) % (dotViews.size * 2)
                dotViews.forEachIndexed { i, v ->
                    val active = when {
                        phase == i -> 1f
                        phase == i + dotViews.size -> 1f
                        else -> 0.35f
                    }
                    v.alpha = active
                }
                dotHandler.postDelayed(this, 220)
            }
        }
        dotHandler.removeCallbacksAndMessages(null)
        dotHandler.post(dotRunnable)
    }

    private fun stopDotAnimation() {
        dotHandler.removeCallbacksAndMessages(null)
        dotViews.forEach { it.alpha = 0.35f }
    }

    private val dotHandler = Handler(Looper.getMainLooper())

    private fun showResult(result: ConsentResult) {
        applyPhase(Phase.Confirmed)
        when (result) {
            is ConsentResult.Accepted -> {
                driverTop.setBackgroundColor(0xFF059669.toInt())
                resultIcon.setImageResource(R.drawable.ic_check_white_24)
                resultTitle.text = if (result.isConsumidorFinal) "Consumidor Final" else "Aceptado"
                val koaj = when (result.loyaltyOptIn) {
                    true  -> " · se unió a Koaj Lovers 💜"
                    false -> " · no se unió a Koaj Lovers"
                    null  -> ""
                }
                resultSubtitle.text = when (result.scenario) {
                    CustomerScenario.EDIT ->
                        "Cliente existente en HioPos$koaj"
                    CustomerScenario.CREATE_FROM_DIAN ->
                        "Nuevo en HioPos · datos de la DIAN$koaj"
                    CustomerScenario.COMPLETE_EXISTING ->
                        "Existente en HioPos · datos completados$koaj"
                    CustomerScenario.CREATE_MANUAL ->
                        "Nuevo en HioPos · capturado a mano$koaj"
                    CustomerScenario.CONSUMIDOR_FINAL ->
                        "Facturado como genérico ${com.permoda.facturacion.core.customer.ConsumidorFinal.DOCUMENT_ID}"
                }
            }
            is ConsentResult.Rejected -> {
                driverTop.setBackgroundColor(0xFFDC2626.toInt())
                resultIcon.setImageResource(R.drawable.ic_close_white)
                resultTitle.text = "Rechazado"
                resultSubtitle.text = result.reason
            }
            is ConsentResult.Error -> {
                driverTop.setBackgroundColor(0xFFFEF2F2.toInt())
                resultIcon.setImageResource(R.drawable.ic_error_red)
                resultTitle.text = "No se pudo validar"
                resultSubtitle.text = result.message
            }
        }

        // Ocultar el mirror — solo se ve el resultado en el header
        driverBottom.animate()
            .alpha(0f)
            .setDuration(280L)
            .withEndAction { driverBottom.visibility = View.GONE }
            .start()

        // Cubrir el header con el resultado (overlay full-screen)
        resultBlock.alpha = 0f
        resultBlock.visibility = View.VISIBLE
        resultBlock.animate().alpha(1f).setDuration(280).start()

        closeHandler.removeCallbacks(closeRunnable)
        closeHandler.postDelayed(closeRunnable, CLOSE_DELAY_MS)
    }

    private fun startConsentFlow() {
        val apiEnv = intent?.getStringExtra("api")?.lowercase()
        val baseUrl = when (apiEnv) {
            "demo" -> HkaConfig.URL_DEMO
            else   -> HkaConfig.URL_PRODUCCION
        }
        val testParameters = """
            <Configuration>
              <Parameters>
                <Param Key="HKA_BASE_URL">$baseUrl</Param>
                <Param Key="HKA_TIMEOUT_MS">5000</Param>
                <Param Key="HKA_ENABLED">TRUE</Param>
                <Param Key="HKA_FALLBACK_MODE">HIBRIDO</Param>
                <Param Key="HKA_CACHE_MINUTES">0</Param>
                <Param Key="HKA_AUDIT_ENABLED">TRUE</Param>
              </Parameters>
            </Configuration>
        """.trimIndent()

        val app = application as PermodaViewApp
        app.configStore.setToken("test-driver-token")
        app.configStore.setParameters(testParameters)
        app.configStore.setShop("<Shop><Name>Demo</Name></Shop>")

        // `mode` selecciona qué escenario simular:
        //  · EDIT   → trae customerId: el cliente ya existe en HioPosCloud
        //  · CREATE → sin customerId pero con documento: se consulta la DIAN
        //  · MANUAL → sin customerId ni documento: se le pide al cliente
        val modeArg = intent?.getStringExtra("mode")?.uppercase() ?: "EDIT"

        val xml = when (modeArg) {
            "CREATE" -> """
                <?xml version="1.0" encoding="UTF-8"?>
                <CustomerResult>
                  <CustomerField Key="customerName"></CustomerField>
                  <CustomerField Key="fiscalDocumentType">13</CustomerField>
                  <CustomerField Key="fiscalId">1011086580</CustomerField>
                  <CustomerField Key="address">Av. Corrientes 1234</CustomerField>
                </CustomerResult>
            """.trimIndent()
            "MANUAL" -> """
                <?xml version="1.0" encoding="UTF-8"?>
                <CustomerResult>
                  <CustomerField Key="customerName"></CustomerField>
                  <CustomerField Key="address">Av. Corrientes 1234</CustomerField>
                </CustomerResult>
            """.trimIndent()
            else -> """
                <?xml version="1.0" encoding="UTF-8"?>
                <CustomerResult>
                  <CustomerField Key="customerId">1001</CustomerField>
                  <CustomerField Key="customerName">Juan Pérez Gómez</CustomerField>
                  <CustomerField Key="fiscalDocumentType">13</CustomerField>
                  <CustomerField Key="fiscalId">1011086580</CustomerField>
                  <CustomerField Key="email">juan.perez@empresa.com</CustomerField>
                  <CustomerField Key="phone">+57 311 1234567</CustomerField>
                  <CustomerField Key="address">Av. Corrientes 1234</CustomerField>
                </CustomerResult>
            """.trimIndent()
        }

        val parsed = try {
            Xml.parseCustomer(xml)
        } catch (t: Throwable) {
            Log.e(tag, "Error parseando XML", t)
            showResult(ConsentResult.Error("Parse XML: ${t.message}"))
            return
        }

        // QA: aplicar overrides desde extras del Intent. Permite inyectar
        // customerTypeId, customerId, etc. sin recompilar para cada persona.
        // Vacíos (no nulos) se aceptan como override — sirve para probar
        // escenario COMPLETE_EXISTING pasando email="" o phone="".
        val typeIdExtra   = intent?.getStringExtra("customerTypeId")?.toIntOrNull()
        val custIdExtra   = intent?.getStringExtra("customerId")
        val nameExtra     = intent?.getStringExtra("customerName")
        val emailExtra    = intent?.getStringExtra("email")
        val fiscalIdExtra = intent?.getStringExtra("fiscalId")
        val phoneExtra    = intent?.getStringExtra("phone")

        val patchedParsed = parsed.copy(
            data = parsed.data.copy(
                customerTypeId = typeIdExtra   ?: parsed.data.customerTypeId,
                customerId     = custIdExtra   ?: parsed.data.customerId,
                customerName   = nameExtra     ?: parsed.data.customerName,
                email          = emailExtra    ?: parsed.data.email,
                fiscalId       = fiscalIdExtra ?: parsed.data.fiscalId,
                phone          = phoneExtra    ?: parsed.data.phone
            )
        )
        if (BuildConfig.DEBUG && (typeIdExtra != null || custIdExtra != null ||
                nameExtra != null || emailExtra != null ||
                fiscalIdExtra != null || phoneExtra != null)) {
            Log.i(tag, "Configuración QA aplicada")
        }

        val config = app.configStore.snapshot()
        val hkaConfig = HkaConfig.fromParametersXml(config.parametersXml)
        val hkaCache = HkaCache(hkaConfig.cacheMinutes)
        val hkaClient = HkaClient(hkaConfig)
        val loyaltyConfig = LoyaltyConfig.fromParametersXml(config.parametersXml)

        lifecycleScope.launch {
            val result = try {
                ConsentPresentation.request(
                    context = this@TestDriverActivity,
                    parsed = patchedParsed,
                    loadingMessage = if (hkaConfig.isActive)
                        getString(R.string.hka_loading_message) else null,
                    onDocumentConfirmed = { tipoCorto, numero, controller ->
                        handleDocumentConfirmed(
                            patchedParsed, tipoCorto, numero, hkaConfig, hkaClient, hkaCache, controller
                        )
                    },
                    loyaltyConfig = loyaltyConfig,
                    onLoyaltyOutcome = { outcome ->
                        LoyaltyAuditor.emit(
                            context = this@TestDriverActivity,
                            enabled = loyaltyConfig.auditEnabled,
                            token = auditToken,
                            outcome = outcome,
                            numeroIdentificacion = ConsentSharedState.current.customer.fiscalId
                        )
                    }
                )
            } catch (t: Throwable) {
                Log.e(tag, "Error en ConsentPresentation", t)
                ConsentResult.Error("Presentation: ${t.message}")
            }
            showResult(result)
        }
    }

    private suspend fun handleDocumentConfirmed(
        parsed: ParsedCustomer,
        tipoCorto: String,
        numero: String,
        hkaConfig: HkaConfig,
        hkaClient: HkaClient,
        hkaCache: HkaCache,
        controller: ConsentController
    ) {
        val tipoDoc = TipoDocumentoMapper.toDianCode(tipoCorto)
        val numeroDoc = numero.trim()

        val localBase = parsed.data.copy(
            customerName = "",
            email = "",
            phone = null,
            fiscalDocumentType = tipoCorto,
            fiscalId = numeroDoc
        )

        if (tipoDoc == null) {
            controller.showCustomer(
                localBase,
                "Tipo de documento inválido: $tipoCorto",
                DianStatus.UNVERIFIED
            )
            return
        }

        val cached = hkaCache.get(numeroDoc)
        if (cached != null) {
            val merge = CustomerMerger.merge(localBase, cached, hkaConfig.fallbackMode)
            HkaAuditor.emitConsulta(
                context = this,
                enabled = hkaConfig.auditEnabled,
                token = auditToken,
                tipoIdentificacion = tipoDoc,
                numeroIdentificacionMasked = HkaAuditor.maskDocumento(numeroDoc),
                response = cached
            )
            controller.showCustomer(merge.customer, merge.warning, merge.dianStatus)
            return
        }

        val response = hkaClient.consultaAdquiriente(tipoDoc, numeroDoc)
        val merge = CustomerMerger.merge(
            local = localBase,
            hka = response as? HkaResponse.Success,
            fallbackMode = hkaConfig.fallbackMode,
            hkaError = if (response !is HkaResponse.Success) response else null
        )
        HkaAuditor.emitConsulta(
            context = this,
            enabled = hkaConfig.auditEnabled,
            token = auditToken,
            tipoIdentificacion = tipoDoc,
            numeroIdentificacionMasked = HkaAuditor.maskDocumento(numeroDoc),
            response = response,
            mergeSource = merge.source
        )
        if (response is HkaResponse.Success && response.isExitoso) {
            hkaCache.put(numeroDoc, response)
        }
        controller.showCustomer(merge.customer, merge.warning, merge.dianStatus)
    }

    override fun onDestroy() {
        super.onDestroy()
        avatarPulse?.cancel()
        badgePulse?.cancel()
        stopDotAnimation()
        closeHandler.removeCallbacks(closeRunnable)
        keyboardHelper?.detach()
    }

    /**
     * Intercepta taps en el mirror: si NO son sobre un EditText con focus,
     * cierra el teclado. Esto permite al cajero tocar zonas vacías del
     * mirror para descartar el teclado.
     */
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.action == android.view.MotionEvent.ACTION_DOWN) {
            keyboardHelper?.shouldDismissOnTouch(ev.rawX, ev.rawY)
        }
        return super.dispatchTouchEvent(ev)
    }

    private companion object {
        const val CLOSE_DELAY_MS = 1500L
    }
}
