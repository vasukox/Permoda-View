package com.permoda.facturacion.presentation.consent

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import com.permoda.facturacion.R
import com.permoda.facturacion.core.consent.ConsentSharedState
import com.permoda.facturacion.core.hka.TipoDocumentoMapper
import com.permoda.facturacion.core.loyalty.LoyaltyOutcome
import com.permoda.facturacion.core.model.CustomerData
import com.permoda.facturacion.core.scenario.CustomerScenario
import com.permoda.facturacion.core.ui.KeyboardDismissHelper
import com.permoda.facturacion.core.validation.CustomerValidator

/**
 * Vista bindable de la pantalla del cliente (10"/11").
 *
 * Zonas + crossfade animado entre estados:
 *   · zone_loading           → spinner + subtítulo dinámico
 *   · zone_awaiting_document → captura inicial (modo CREATE): tipo + CC
 *   · zone_data              → tarjeta premium con 5 campos editables
 *   · zone_loyalty           → invitación a la comunidad Koaj Lovers
 *   · zone_confirmed         → ✓ Datos confirmados / bienvenida Koaj
 *   · zone_rejected          → "Esperando corrección del vendedor…"
 *   · zone_error             → mensaje + botón Reintentar
 *   · zone_no_data           → defensivo
 */
class ConsentScreen(private val root: ViewGroup) {

    private val context: Context = root.context

    private val allZones = mutableListOf<View>()
    private var zoneLoading: View? = null
    private var zoneAwaiting: View? = null
    private var zoneData: View? = null
    private var zoneConfirmed: View? = null
    private var zoneRejected: View? = null
    private var zoneError: View? = null
    private var zoneNoData: View? = null
    private var zoneLoyalty: View? = null
    private var zoneConsumidorFinal: View? = null
    private var zoneWaitingVendor: View? = null
    private var zoneDocTypePicker: View? = null
    private var docTypeList: ViewGroup? = null
    private var docTypePickerScrim: View? = null
    private var docTypePickerCard: ViewGroup? = null
    private var keyboardDismissButton: View? = null
    private var keyboardHelper: KeyboardDismissHelper? = null

    // Sin `avatar`: el muñeco sobre el formulario del cliente se retiró para
    // dejar todo el espacio a los datos.
    private var confirmedCheck: ImageView? = null
    private var confirmedTitle: TextView? = null
    private var confirmedSubtitle: TextView? = null
    private var consentTitle: TextView? = null
    private var consentSubtitle: TextView? = null
    private var loadingSubtitle: TextView? = null
    private var btnAccept: Button? = null
    private var linkReject: TextView? = null
    private var btnRetry: Button? = null
    private var errorMessage: TextView? = null
    private var warningBanner: TextView? = null
    private var validationHint: TextView? = null

    // zone_loyalty
    /**
     * `View` y no `Button`: en el diseño de marca el botón "¡ACEPTO!" lleva dos
     * líneas con color y tamaño distintos ("¡ACEPTO!" en blanco y "QUIERO SER
     * KOAJ LOVER" en dorado), así que en el layout es un LinearLayout clickable.
     * Sólo se le pone un listener y se lo anima, y las dos cosas son de `View`.
     */
    private var btnKoajAccept: View? = null
    private var linkKoajSkip: TextView? = null
    private var koajCountdown: TextView? = null
    private var koajHeart: ImageView? = null

    // Consumidor Final
    // El botón para elegir Consumidor Final vive sólo en la pantalla del
    // vendedor; aquí únicamente se confirma el genérico ya elegido.
    private var btnCfAccept: Button? = null
    private var linkCfBack: TextView? = null
    private var cfValueName: TextView? = null
    private var cfValueDocType: TextView? = null
    private var cfValueDocId: TextView? = null
    private var cfIcon: ImageView? = null

    // zone_awaiting_document
    private var awaitingPrompt: TextView? = null
    private var awaitingDocType: ViewGroup? = null
    private var awaitingDocTypeValue: TextView? = null
    private var awaitingDocId: EditText? = null
    private var awaitingLinkReject: TextView? = null
    private var btnContinue: Button? = null

    private val fields = mutableMapOf<String, FieldRow>()
    private var initialCustomer: CustomerData? = null
    private var anyFieldEditing = false
        set(value) { field = value; updateAcceptEnabled() }

    /**
     * País seleccionado en el toolbox del teléfono. Colombia por defecto; el
     * cajero lo cambia con el PopupMenu ([PHONE_COUNTRIES]). El campo display
     * guarda sólo los dígitos locales (que es lo que teclea) y el `currentValue`
     * del FieldRow concatena automáticamente `+<dial>` (lo que va al XML para
     * HioPosCloud). Mantener esta propiedad del lado del cajero en lugar de
     * persistirla es deliberado: la próxima venta empieza siempre en Colombia,
     * sin arrastrar el país de un cliente anterior.
     */
    private var currentPhoneCountry: PhoneCountry = DEFAULT_PHONE_COUNTRY

    /** Reglas de completitud mínima. Inyectables para poder relajarlas por config. */
    private var validationRules = CustomerValidator.Rules()

    /** Última validación calculada. Se recalcula en cada cambio de campo. */
    private var lastValidation = CustomerValidator.Result(emptyList())

    /** True cuando un observador externo (mirror del operador) ya pintó
     *  los valores en el EditText y NO debemos disparar updateCustomer
     *  para evitar un bucle de sincronización. */
    private var suppressEmit = false

    private var onAccept: (() -> Unit)? = null
    private var onReject: (() -> Unit)? = null
    private var onRetry: (() -> Unit)? = null
    private var onDocumentConfirmed: ((tipo: String, numero: String) -> Unit)? = null
    private var onLoyaltyDecision: ((LoyaltyOutcome) -> Unit)? = null
    private var onConsumidorFinalAccept: (() -> Unit)? = null

    /** Estado al que volver si el cliente cancela el Consumidor Final. */
    private var stateBeforeConsumidorFinal: ScreenState? = null

    private var documentTypes: List<String> = emptyList()
    private var selectedDocType: String = "CC"

    /** 0 = sin auto-avance en la pantalla de Koaj Lovers. */
    private var loyaltyTimeoutMs: Long = 0L

    /**
     * Si es false, la pantalla de Koaj Lovers **no ofrece salida**: sólo el
     * botón Aceptar la cierra, y un cliente que se va deja la caja esperando.
     *
     * El default es `true` porque el mockup aprobado incluye "NO ACEPTO"; se
     * apaga con `KOAJ_ALLOW_DECLINE=FALSE` desde CloudLicense. Ver
     * [com.permoda.facturacion.core.loyalty.LoyaltyConfig].
     */
    private var loyaltyAllowDecline: Boolean = true

    private val animDecelerate = DecelerateInterpolator(1.2f)
    private val animOvershoot = OvershootInterpolator(0.8f)

    fun bind(
        customer: CustomerData,
        documentTypes: List<String>,
        onAccept: () -> Unit,
        onReject: () -> Unit,
        onRetry: () -> Unit,
        onDocumentConfirmed: ((tipo: String, numero: String) -> Unit)? = null,
        onLoyaltyDecision: ((LoyaltyOutcome) -> Unit)? = null,
        onConsumidorFinalAccept: (() -> Unit)? = null,
        loyaltyTimeoutMs: Long = 0L,
        loyaltyAllowDecline: Boolean = false,
        validationRules: CustomerValidator.Rules = CustomerValidator.Rules()
    ) {
        this.initialCustomer = customer
        this.onAccept = onAccept
        this.onReject = onReject
        this.onRetry = onRetry
        this.onDocumentConfirmed = onDocumentConfirmed
        this.onLoyaltyDecision = onLoyaltyDecision
        this.onConsumidorFinalAccept = onConsumidorFinalAccept
        this.loyaltyTimeoutMs = loyaltyTimeoutMs
        this.loyaltyAllowDecline = loyaltyAllowDecline
        this.validationRules = validationRules
        this.documentTypes = documentTypes.ifEmpty { TipoDocumentoMapper.supportedFullNames() }
        this.selectedDocType = TipoDocumentoMapper.toFullName(customer.fiscalDocumentType)
            ?: TipoDocumentoMapper.supportedFullNames().first()

        LayoutInflater.from(context).inflate(R.layout.presentation_consent, root, true)
        resolveViews()
        wireListeners()
        hideVendorOwnedControls()
        applyCustomer(customer)
    }

    /**
     * Retira de la pantalla del cliente todo lo que ahora hace el cajero.
     *
     * El flujo se invirtió: el cajero captura el documento, llena los datos y
     * confirma. Al cliente le queda una vista en vivo y un único toque, el
     * Acepto de Koaj Lovers. Dejar estos controles visibles daría dos fuentes
     * de verdad para la misma venta.
     */
    private fun hideVendorOwnedControls() {
        btnAccept?.visibility = View.GONE
        linkReject?.visibility = View.GONE
        validationHint?.visibility = View.GONE
        awaitingLinkReject?.visibility = View.GONE
        // La captura del documento la hace el cajero: el cliente sólo ve el
        // número aparecer, no lo teclea.
        btnContinue?.visibility = View.GONE
        awaitingDocId?.apply {
            isEnabled = false
            isFocusable = false
            isFocusableInTouchMode = false
        }
        awaitingDocType?.apply {
            isClickable = false
            setOnClickListener(null)
        }
    }

    private fun resolveViews() {
        zoneLoading   = root.findViewById(R.id.zone_loading)
        zoneAwaiting  = root.findViewById(R.id.zone_awaiting_document)
        zoneData      = root.findViewById(R.id.zone_data)
        zoneLoyalty   = root.findViewById(R.id.zone_loyalty)
        // zoneConsumidorFinal eliminado: el CF se maneja en el vendor gate,
        // no en la pantalla del cliente. Mantenemos la variable comentada
        // por si en el futuro se reactiva.
        // zoneConsumidorFinal = root.findViewById(R.id.zone_consumidor_final)
        zoneWaitingVendor   = root.findViewById(R.id.zone_waiting_vendor)
        zoneConfirmed = root.findViewById(R.id.zone_confirmed)
        zoneRejected  = root.findViewById(R.id.zone_rejected)
        zoneError     = root.findViewById(R.id.zone_error)
        zoneNoData    = root.findViewById(R.id.zone_no_data)
        allZones += listOfNotNull(
            zoneLoading, zoneAwaiting, zoneData, zoneLoyalty,
            // zoneConsumidorFinal,
            zoneWaitingVendor, zoneConfirmed,
            zoneRejected, zoneError, zoneNoData
        )

        confirmedCheck    = root.findViewById(R.id.confirmed_check)
        confirmedTitle    = root.findViewById(R.id.confirmed_title)
        confirmedSubtitle = root.findViewById(R.id.confirmed_subtitle)
        consentTitle      = root.findViewById(R.id.consent_title)
        consentSubtitle   = root.findViewById(R.id.consent_subtitle)
        loadingSubtitle   = root.findViewById(R.id.loading_subtitle)
        btnAccept         = root.findViewById(R.id.btn_accept)
        linkReject        = root.findViewById(R.id.link_reject)
        btnRetry          = root.findViewById(R.id.btn_retry)
        errorMessage      = root.findViewById(R.id.error_message)
        warningBanner     = root.findViewById(R.id.warning_banner)
        validationHint    = root.findViewById(R.id.validation_hint)

        btnKoajAccept = root.findViewById(R.id.btn_koaj_accept)
        linkKoajSkip  = root.findViewById(R.id.link_koaj_skip)
        koajCountdown = root.findViewById(R.id.koaj_countdown)
        koajHeart     = root.findViewById(R.id.koaj_heart)

        // Por defecto la pantalla de Koaj sólo se cierra con Aceptar: el link
        // para declinar se retira del layout, no se deja invisible pero
        // clicable.
        if (!loyaltyAllowDecline) linkKoajSkip?.visibility = View.GONE

        // btn_cf_accept / link_cf_back / cf_value_* / cf_icon eliminados:
        // el CF se maneja en el vendor gate, no en la pantalla del cliente.
        // Mantenemos las variables comentadas por si en el futuro se reactivan.
        // btnCfAccept   = root.findViewById(R.id.btn_cf_accept)
        // linkCfBack    = root.findViewById(R.id.link_cf_back)
        // cfValueName    = root.findViewById(R.id.cf_value_name)
        // cfValueDocType = root.findViewById(R.id.cf_value_doc_type)
        // cfValueDocId   = root.findViewById(R.id.cf_value_doc_id)
        // cfIcon         = root.findViewById(R.id.cf_icon)

        awaitingPrompt       = root.findViewById(R.id.awaiting_prompt)
        awaitingDocType      = root.findViewById(R.id.awaiting_field_doc_type)
        awaitingDocTypeValue = root.findViewById(R.id.awaiting_field_doc_type_value)
        awaitingDocId        = root.findViewById(R.id.awaiting_field_doc_id)
        awaitingLinkReject   = root.findViewById(R.id.awaiting_link_reject)
        btnContinue          = root.findViewById(R.id.btn_continue)

        zoneDocTypePicker    = root.findViewById(R.id.zone_doc_type_picker)
        docTypeList          = root.findViewById(R.id.doc_type_list)
        docTypePickerScrim   = root.findViewById(R.id.doc_type_picker_scrim)
        docTypePickerCard    = root.findViewById(R.id.doc_type_picker_card)
        keyboardDismissButton = root.findViewById(R.id.keyboard_dismiss)

        // Activa el helper que muestra el botón ▼ cuando el teclado está abierto
        if (keyboardDismissButton != null) {
            keyboardHelper = KeyboardDismissHelper(keyboardDismissButton!!, root)
        }

        FIELD_SPECS.forEach { registerField(it) }

        // El documento de la captura inicial usa el mismo límite que el campo
        // del formulario, para que no se pueda teclear algo que luego no cabe.
        awaitingDocId?.filters = arrayOf(InputFilter.LengthFilter(MAX_DOC_ID))

        refreshAwaitingView()
    }

    /**
     * La pantalla del cliente es **solo lectura**.
     *
     * El cajero es el único que captura y confirma; el cliente ve un espejo en
     * vivo y su único toque en todo el flujo es el Acepto de Koaj Lovers. Un
     * espejo editable hacía que un roce con la táctil borrara lo que el cajero
     * acababa de digitar.
     */
    private fun makeReadOnly(row: FieldRow) {
        row.displayRow.isClickable = false
        row.displayRow.isFocusable = false
        row.displayRow.setOnClickListener(null)
        row.input.isEnabled = false
        row.input.isFocusable = false
        row.input.isFocusableInTouchMode = false
    }

    private fun registerField(spec: FieldSpec) {
        val key = spec.key
        val row = root.findViewById<ViewGroup>(spec.rowId) ?: return
        val displayRow = row.findViewById<ViewGroup>(R.id.field_display_row) ?: return
        val editRow = row.findViewById<ViewGroup>(R.id.field_edit_row) ?: return
        val value = row.findViewById<TextView>(R.id.field_value) ?: return
        val label = row.findViewById<TextView>(R.id.field_label) ?: return
        val icon = row.findViewById<ImageView>(R.id.field_icon) ?: return
        val input = row.findViewById<EditText>(R.id.field_input) ?: return
        val editLabel = row.findViewById<TextView>(R.id.field_edit_label)
        val counter = row.findViewById<TextView>(R.id.field_counter)

        label.setText(spec.labelRes)
        editLabel?.setText(spec.labelRes)
        icon.setImageResource(spec.iconRes)
        input.inputType = spec.inputType

        val labelDefaultColor = label.currentTextColor

        // Unidireccional: el cliente sólo mira. No hay TextWatcher que emita al
        // estado compartido ni listeners de edición, porque un roce con la
        // táctil borraría lo que el cajero acaba de digitar. La fuente de
        // verdad es su pantalla.
        val fieldRow = FieldRow(
            displayRow = displayRow,
            editRow = editRow,
            valueView = value,
            input = input,
            labelView = label,
            labelDefaultColor = labelDefaultColor,
            counterView = counter,
            maxLength = spec.maxLength,
            currentValue = ""
        )
        makeReadOnly(fieldRow)
        fields[key] = fieldRow

        // Si es teléfono, además del setup genérico inflamos el toolbox de país
        // a la izquierda del input. Es programático para no contaminar
        // widget_field.xml con un caso especial.
        if (key == "phone") {
            mountPhoneCountryToolbox(input, fieldRow)
        }
    }

    /**
     * Inserta el toolbox selector de país a la izquierda del EditText del
     * teléfono y conecta el PopupMenu que cambia de país.
     *
     * El EditText default se quita del `edit_row` (que es vertical) y se mete
     * en un LinearLayout horizontal nuevo junto al toolbox. La referencia que
     * devolvemos es la misma, así el resto del flujo la sigue viendo igual.
     */
    private fun mountPhoneCountryToolbox(input: EditText, fieldRow: FieldRow) {
        val editRow = input.parent as ViewGroup
        val inputIndex = editRow.indexOfChild(input)
        editRow.removeView(input)

        // Toolbox con padding interno para el "+dial" y el chevron.
        val toolbox = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_phone_country_toolbox)
            val pad = dp(10)
            setPadding(pad, pad, dp(14), pad)
            isClickable = true
            isFocusable = true
            contentDescription = context.getString(R.string.phone_country_picker_title)
        }
        // La bandera va como compound drawable del propio TextView y no como un
        // ImageView aparte: `repaintToolbox` y `preselectPhoneCountry` acceden al
        // dial con `toolbox.getChildAt(0)`, y meter una vista delante correría
        // ese índice y los rompería en silencio.
        val toolboxDial = TextView(context).apply {
            textSize = 16f
            setTextColor(context.getColor(R.color.koaj_text_ink))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            text = "+" + currentPhoneCountry.dial
            compoundDrawablePadding = dp(7)
            setCompoundDrawablesRelative(scaledFlag(currentPhoneCountry.flagRes, dp(18)), null, null, null)
        }
        val toolboxChevron = ImageView(context).apply {
            setImageResource(R.drawable.ic_phone_chevron)
            val iconPad = dp(6)
            setPadding(iconPad, iconPad, 0, iconPad)
            contentDescription = null
        }
        toolbox.addView(toolboxDial)
        toolbox.addView(toolboxChevron)
        toolbox.setOnClickListener { showCountryPicker(toolbox) }

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        // Toolbox: ancho fijo al contenido. EditText: peso 1 para expandirse.
        row.addView(toolbox, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        input.setPadding(dp(12), 0, dp(12), 0)
        input.background = context.getDrawable(R.drawable.bg_phone_field_input)
        input.filters = arrayOf(InputFilter.LengthFilter(MAX_PHONE))
        row.addView(input, LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.MATCH_PARENT,
            1f
        ))
        editRow.addView(row, inputIndex)

        // Cada vez que el cajero teclea un dígito, se reconstruye el valor
        // completo (+dial + número) que va a CustomerValidator y al XML.
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                syncPhoneFieldToShared(fieldRow)
            }
        })
    }

    /**
     * Popup con los cuatro países. El número de menú usa el ISO2 como id para
     * que el callback sea inmune al orden de la lista y al idioma de la
     * etiqueta.
     */
    private fun showCountryPicker(anchor: View) {
        val popup = PopupMenu(context, anchor)
        PHONE_COUNTRIES.forEachIndexed { index, country ->
            popup.menu.add(
                0,
                country.iso2.hashCode(),
                index,
                countryMenuTitle(country)
            )
        }
        // El setOnMenuItemClickListener no nos da el objeto PhoneCountry
        // directamente; resolvemos por el orden en PHONE_COUNTRIES usando
        // el índice numérico del item. Ese índice se preserva aquí.
        popup.setOnMenuItemClickListener { item ->
            PHONE_COUNTRIES.getOrNull(item.order)?.let { country ->
                currentPhoneCountry = country
                onPhoneCountryChanged()
            }
            true
        }
        popup.show()
    }

    /**
     * Llamado tras seleccionar un país. Repinta el "+dial" del toolbox y
     * recomputa el currentValue para que CustomerValidator y el serializador
     * XML vean el número completo correcto.
     */
    private fun onPhoneCountryChanged() {
        val phoneRow = fields["phone"] ?: return
        val editRow = phoneRow.editRow
        val outerRow = editRow.getChildAt(0) as? LinearLayout ?: return
        val toolbox = outerRow.getChildAt(0) as? LinearLayout ?: return
        (toolbox.getChildAt(0) as? TextView)?.let { dial ->
            dial.text = "+" + currentPhoneCountry.dial
            dial.setCompoundDrawablesRelative(
                scaledFlag(currentPhoneCountry.flagRes, dp(18)), null, null, null
            )
        }
        syncPhoneFieldToShared(phoneRow)
    }

    /**
     * Bandera + nombre + indicativo en cada fila del selector.
     *
     * Como `ImageSpan` en el título y no con `MenuItem.setIcon`, porque
     * `PopupMenu` no muestra iconos: `setForceShowIcon` es API 29 y por debajo
     * hace falta reflexión sobre `MenuPopupHelper`. Un span funciona igual en
     * todas las versiones sin tocar APIs privadas.
     */
    private fun countryMenuTitle(country: PhoneCountry): CharSequence {
        val label = context.getString(country.labelRes)
        val texto = "   $label  ·  +${country.dial}"
        val flag = scaledFlag(country.flagRes, dp(20)) ?: return texto
        return android.text.SpannableString(texto).apply {
            setSpan(
                android.text.style.ImageSpan(
                    flag, android.text.style.DynamicDrawableSpan.ALIGN_CENTER
                ),
                0, 1,
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    /**
     * Carga la bandera acotada al tamaño pedido.
     *
     * Los PNG son de 96 px: un Drawable sin bounds explícitos se pinta a su
     * tamaño intrínseco y reventaría la fila del teléfono. El cuadro es cuadrado
     * a propósito — la bandera de Ecuador es apaisada y se centra dentro sin
     * deformarse.
     */
    private fun scaledFlag(resId: Int, size: Int): android.graphics.drawable.Drawable? =
        context.getDrawable(resId)?.apply { setBounds(0, 0, size, size) }

    /**
     * Compone el número completo (`+<dial><número>`) que CustomerValidator y
     * `ModifyDocumentXml` esperan. Devuelve `null` si no hay dígitos, para
     * que el teléfono quede opcional en el CustomerData cuando el cajero
     * aún no tecleó nada.
     */
    private fun buildPhoneFullNumber(localDigits: String): String? {
        val digits = localDigits.filter { it.isDigit() }
        if (digits.isBlank()) return null
        return "+" + currentPhoneCountry.dial + digits
    }

    /**
     * Sincroniza lo que CustomerValidator ve con lo que el cajero tiene
     * escrito: el currentValue completo (con prefijo) y el contador del input
     * (que cuenta dígitos locales, no totales).
     */
    private fun syncPhoneFieldToShared(fieldRow: FieldRow) {
        val local = fieldRow.input.text?.toString().orEmpty()
        val full = buildPhoneFullNumber(local)
        fieldRow.currentValue = full.orEmpty()
        paintDisplayValue(fieldRow, full.orEmpty())
        updateCounter(fieldRow.counterView, local.length, MAX_PHONE)
    }

    /**
     * Helper para inflar dp sin arrastrar laDensity de todo el archivo.
     */
    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()

    /**
     * El contador sólo aparece cerca del tope: mostrarlo siempre añade ruido
     * en una pantalla que el cliente ve durante segundos.
     */
    private fun updateCounter(counter: TextView?, length: Int, maxLength: Int) {
        counter ?: return
        if (maxLength <= 0 || length < maxLength - COUNTER_VISIBLE_MARGIN) {
            counter.visibility = View.INVISIBLE
            return
        }
        counter.text = context.getString(R.string.field_counter, length, maxLength)
        counter.setTextColor(
            context.getColor(
                if (length >= maxLength) R.color.koaj_state_invalid else R.color.koaj_text_ink_soft
            )
        )
        counter.visibility = View.VISIBLE
    }

    /** Pinta el valor en la fila de lectura (placeholder si está vacío). */
    private fun paintDisplayValue(row: FieldRow, value: String) {
        row.valueView.text = value.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.consent_empty_placeholder)
        row.valueView.alpha = if (value.isBlank()) 0.45f else 1f
    }

    private fun applyCustomer(c: CustomerData) {
        // Mostrar tipo de documento como nombre completo (Cédula de ciudadanía, etc.)
        val tipoCompleto = TipoDocumentoMapper.toFullName(c.fiscalDocumentType)
            ?: c.fiscalDocumentType.orEmpty()
        setValue("customerName", c.customerName)
        setValue("fiscalDocumentType", tipoCompleto)
        setValue("fiscalId", c.fiscalId)
        setValue("email", c.email)
        setValue("phone", c.phone)
        initialCustomer = c

        // Reflejar tipo seleccionado en la zona de captura
        if (tipoCompleto.isNotBlank()) {
            selectedDocType = tipoCompleto
            refreshAwaitingView()
        }
        refreshValidation()
    }

    private fun refreshAwaitingView() {
        awaitingDocTypeValue?.text = selectedDocType
    }

    /**
     * ⚠️ Acá vivía `prefillAwaitingDocument`, que precargaba el documento
     * entrante en la pantalla de captura del cliente. **Se eliminó: era una fuga
     * de datos personales en la pantalla que mira el público.**
     *
     * Su justificación era que "en la práctica casi nunca llega documento —
     * PermodaView reemplaza la pantalla de cliente del POS, así que no hay dónde
     * teclearlo antes— pero si llega, mejor no obligarlo a escribirlo de nuevo".
     *
     * Esa premisa dejó de ser cierta en cuanto el módulo empezó a asignar el
     * cliente de verdad: terminado el flujo, la venta **ya tiene** cliente, así
     * que si el cajero vuelve a pulsar la lupita el `<Document>` llega con el
     * `fiscalId` y la cédula aparecía tecleada en la pantalla de 10".
     *
     * El problema no es la comodidad: es que esa pantalla la mira **quien esté en
     * el mostrador**, que después de cerrar la venta puede ser la persona
     * siguiente. Mostrarle el documento del cliente anterior es exactamente lo
     * que el resto del módulo evita enmascarando documentos en la auditoría.
     *
     * La captura del cliente arranca **siempre en blanco**. El costo —volver a
     * teclear un documento en el caso raro en que el POS traiga uno útil— es
     * mucho menor que el de filtrarlo.
     */

    private fun setValue(key: String, value: String?) {
        // El teléfono va por una rama distinta: NO truncamos el valor recibido
        // con `maxLength`, porque el `maxLength` aplica al campo local (10
        // dígitos), pero el valor entrante puede ser el completo
        // (`+573111234567`). Lo separamos, actualizamos país, y sincronizamos
        // todo desde una sola función.
        if (key == "phone") {
            setPhoneValue(value)
            return
        }
        fields[key]?.let {
            val v = (value ?: "").take(if (it.maxLength > 0) it.maxLength else Int.MAX_VALUE)
            it.currentValue = v
            paintDisplayValue(it, v)
            it.input.setText(v)
            updateCounter(it.counterView, v.length, it.maxLength)
        }
    }

    /**
     * Carga el teléfono desde el CustomerData entrante (e.g. cuando nos llega
     * un cliente ya conocido de HioPosCloud).
     *
     * Detecta el indicativo internacional guardado y selecciona el país
     * correspondiente de [PHONE_COUNTRIES]. Si el indicativo no coincide con
     * ninguno, asume "Extranjeros". Esto hace que el toolbox refleje el dato
     * de origen incluso cuando el POS ya lo tenía.
     */
    private fun setPhoneValue(full: String?) {
        val row = fields["phone"] ?: return
        val (country, local) = splitPhoneCountry(full)
        currentPhoneCountry = country
        row.input.setText(local)
        // El toolbox ya está inflado en este punto (registerField corre
        // antes que cualquier setValue); actualizamos su dial.
        val editRow = row.editRow
        val outerRow = editRow.getChildAt(0) as? LinearLayout ?: return
        val toolbox = outerRow.getChildAt(0) as? LinearLayout ?: return run {
            // Todavía no se montó el toolbox (puede pasar en tests). Pintamos
            // el currentValue y salimos.
            row.currentValue = full.orEmpty()
            paintDisplayValue(row, full.orEmpty())
            return
        }
        (toolbox.getChildAt(0) as? TextView)?.let { dial ->
            dial.text = "+" + currentPhoneCountry.dial
            dial.setCompoundDrawablesRelative(
                scaledFlag(currentPhoneCountry.flagRes, dp(18)), null, null, null
            )
        }
        syncPhoneFieldToShared(row)
    }

    /**
     * Divide un teléfono completo (`+<dial><número>`) en su país y los dígitos
     * locales. Si el prefijo no está vacío pero no calza con ninguno de la
     * lista, devuelve "Extranjeros" con TODO el número como locale (el cajero
     * decide si edita o no).
     */
    private fun splitPhoneCountry(full: String?): Pair<PhoneCountry, String> {
        val raw = full?.trim().orEmpty()
        if (raw.isEmpty()) return DEFAULT_PHONE_COUNTRY to ""
        val noPlus = raw.removePrefix("+").removePrefix(" ")
        // Buscar coincidencia con el dial más largo primero (593 antes que 5931).
        val match = PHONE_COUNTRIES
            .filter { noPlus.startsWith(it.dial) }
            .maxByOrNull { it.dial.length }
        return if (match != null) {
            match to noPlus.removePrefix(match.dial).filter { it.isDigit() }
        } else {
            // No conocemos el país. Asumimos Extranjeros si existe, y el
            // número entero queda en el input (el cajero puede re-editar).
            val extranjeros = PHONE_COUNTRIES.firstOrNull { it.iso2 == "ZZ" }
                ?: DEFAULT_PHONE_COUNTRY
            extranjeros to raw.filter { it.isDigit() }.take(MAX_PHONE)
        }
    }

    private fun enterEdit(key: String) {
        val row = fields[key] ?: return
        // Si el campo es tipo de documento, abrir dialog en vez de EditText
        if (key == "fiscalDocumentType") {
            showDocTypeDialog()
            return
        }
        if (row.editRow.visibility == View.VISIBLE) {
            row.input.requestFocus()
            return
        }
        // Cierra cualquier otro campo abierto: sólo uno a la vez, así el
        // cliente no pierde de vista qué está editando.
        fields.forEach { (otherKey, other) ->
            if (otherKey != key && other.editRow.visibility == View.VISIBLE) exitEdit(otherKey)
        }
        row.displayRow.animate().alpha(0f).setDuration(120).start()
        row.displayRow.postDelayed({
            row.displayRow.visibility = View.GONE
            row.editRow.alpha = 0f
            row.editRow.visibility = View.VISIBLE
            row.editRow.animate().alpha(1f).setDuration(180).start()
            row.input.setSelection(row.input.text?.length ?: 0)
            row.input.requestFocus()
            // Centrar el campo activo entre el header y el teclado
            scrollFieldIntoView(row.input)
        }, 130)
        anyFieldEditing = true
    }

    /**
     * Hace scroll del ScrollView contenedor para que el EditText activo
     * quede centrado verticalmente entre el header y el top del teclado.
     * Espera a que el teclado se haya abierto antes de calcular.
     */
    private fun scrollFieldIntoView(editText: EditText) {
        val scrollView = root.findViewById<ScrollView>(R.id.zone_data) ?: return
        editText.post {
            editText.postDelayed({
                val scrollRect = Rect()
                scrollView.getGlobalVisibleRect(scrollRect)
                val fieldRect = Rect()
                editText.getGlobalVisibleRect(fieldRect)
                // Si el campo ya está visible y centrado, no hacer nada
                if (scrollRect.contains(fieldRect) &&
                    fieldRect.top > scrollRect.top &&
                    fieldRect.bottom < scrollRect.bottom) {
                    // Está dentro del área visible pero podría no estar centrado
                    val fieldCenter = (fieldRect.top + fieldRect.bottom) / 2
                    val visibleCenter = (scrollRect.top + scrollRect.bottom) / 2
                    val delta = fieldCenter - visibleCenter
                    if (Math.abs(delta) > 40) {
                        val target = (scrollView.scrollY + delta).coerceAtLeast(0)
                        scrollView.smoothScrollTo(0, target)
                    }
                    return@postDelayed
                }
                // No está visible: calcular el scroll necesario para centrarlo
                val visibleCenter = (scrollRect.top + scrollRect.bottom) / 2
                val fieldCenter = (fieldRect.top + fieldRect.bottom) / 2
                val delta = fieldCenter - visibleCenter
                val target = (scrollView.scrollY + delta).coerceAtLeast(0)
                scrollView.smoothScrollTo(0, target)
            }, 280)  // esperar a que el teclado termine de abrir
        }
    }

    /**
     * Cierra la edición de un campo.
     *
     * Ya no hay parámetro `save`: el valor se aplica mientras se teclea, así
     * que aquí sólo se normaliza (trim) y se vuelve al modo lectura. No existe
     * la noción de "descartar" porque no hay botón de confirmar que la haga
     * significativa.
     */
    private fun exitEdit(key: String) {
        val row = fields[key] ?: return
        if (row.editRow.visibility != View.VISIBLE) return

        val trimmed = row.input.text?.toString().orEmpty().trim()
        if (trimmed != row.currentValue) {
            row.currentValue = trimmed
            ConsentSharedState.updateCustomer(snapshotCustomer())
        }
        paintDisplayValue(row, trimmed)
        // Sin suppressEmit: reescribir el texto dispararía el TextWatcher y
        // volvería a emitir. Sólo lo tocamos si el trim cambió algo.
        if (row.input.text?.toString() != trimmed) {
            suppressEmit = true
            try { row.input.setText(trimmed) } finally { suppressEmit = false }
        }

        row.editRow.animate().alpha(0f).setDuration(120).start()
        row.editRow.postDelayed({
            row.editRow.visibility = View.GONE
            row.displayRow.alpha = 0f
            row.displayRow.visibility = View.VISIBLE
            row.displayRow.animate().alpha(1f).setDuration(180).start()
            anyFieldEditing = fields.values.any { it.editRow.visibility == View.VISIBLE }
            // El trim puede cambiar la validez del campo.
            refreshValidation()
        }, 130)
    }

    private fun showDocTypeDialog() {
        val picker = zoneDocTypePicker ?: return
        val list = docTypeList ?: return

        // Construir las filas dinámicamente (un botón por tipo)
        list.removeAllViews()
        val inflater = LayoutInflater.from(context)
        documentTypes.forEach { tipo ->
            val row = inflater.inflate(R.layout.widget_doc_type_row, list, false)
            val label = row.findViewById<TextView>(R.id.doc_type_row_label)
            val check = row.findViewById<ImageView>(R.id.doc_type_row_check)
            label.text = tipo
            check.visibility = if (tipo == selectedDocType) View.VISIBLE else View.GONE
            // Listener en CADA fila
            row.setOnClickListener {
                selectedDocType = tipo
                refreshAwaitingView()
                setValue("fiscalDocumentType", selectedDocType)
                ConsentSharedState.updateCustomer(snapshotCustomer())
                refreshValidation()
                hideDocTypeDialog()
            }
            list.addView(row)
        }

        // Listener solo en el FONDO oscuro (no en el card, no en las filas)
        docTypePickerScrim?.setOnClickListener { hideDocTypeDialog() }

        // Mostrar con crossfade
        picker.alpha = 0f
        picker.visibility = View.VISIBLE
        picker.bringToFront()
        picker.animate().alpha(1f).setDuration(200).start()
    }

    private fun hideDocTypeDialog() {
        val picker = zoneDocTypePicker ?: return
        picker.animate()
            .alpha(0f)
            .setDuration(180)
            .withEndAction { picker.visibility = View.GONE }
            .start()
    }

    /**
     * Stub de compatibilidad. El lock bidireccional se eliminó: ahora ambos
     * lados pueden editar siempre (last-write-wins).
     */
    fun applyLockedField(@Suppress("UNUSED_PARAMETER") key: String?) {
        // No-op: ya no hay lock. Ambos lados editan libremente.
    }

    /** Mapeo entre las llaves de [fields] y los campos del validador. */
    private val fieldKeyToValidatorField = mapOf(
        "customerName"       to CustomerValidator.Field.NAME,
        "fiscalDocumentType" to CustomerValidator.Field.DOC_TYPE,
        "fiscalId"           to CustomerValidator.Field.DOC_ID,
        "email"              to CustomerValidator.Field.EMAIL,
        "phone"              to CustomerValidator.Field.PHONE
    )

    /**
     * Recalcula la validación y sincroniza los tres afordances:
     * etiquetas en rojo, hint bajo el botón, y el propio botón Aceptar.
     */
    private fun refreshValidation() {
        lastValidation = CustomerValidator.validate(snapshotCustomer(), validationRules)
        paintFieldErrors()
        updateAcceptEnabled()
    }

    private fun paintFieldErrors() {
        val invalid = lastValidation.fields.toSet()
        fields.forEach { (key, row) ->
            val validatorField = fieldKeyToValidatorField[key]
            val isInvalid = validatorField != null && validatorField in invalid
            row.labelView.setTextColor(
                if (isInvalid) context.getColor(R.color.koaj_state_invalid)
                else row.labelDefaultColor
            )
        }
    }

    /**
     * Aceptar se habilita en cuanto los datos cumplen la completitud mínima.
     *
     * Ya **no** se deshabilita por tener un campo abierto: como el valor se
     * aplica al teclear, no hay nada pendiente de confirmar y hacer que el
     * botón parpadee al abrir cada campo era ruido.
     *
     * El link "No acepto" y el botón de Consumidor Final siguen siempre
     * disponibles: nunca dejamos al cliente sin salida.
     */
    private fun updateAcceptEnabled() {
        val blocked = !lastValidation.isValid
        btnAccept?.isEnabled = !blocked
        btnAccept?.animate()?.alpha(if (blocked) 0.4f else 1f)?.setDuration(180)?.start()

        val hint = validationHint ?: return
        // Mientras el cliente teclea en un campo no lo regañamos por ese
        // campo a medio escribir; el resto de avisos sí se muestran.
        val message = if (anyFieldEditing) "" else lastValidation.summary
        if (message.isBlank()) {
            hint.visibility = View.GONE
        } else {
            hint.text = context.getString(R.string.validation_hint_prefix, message)
            if (hint.visibility != View.VISIBLE) {
                hint.alpha = 0f
                hint.visibility = View.VISIBLE
                hint.animate().alpha(1f).setDuration(180).start()
            }
        }
    }

    private fun wireListeners() {
        btnAccept?.setOnClickListener {
            // Cierra el campo abierto para normalizar el valor (trim) antes
            // de leer el snapshot.
            closeAnyOpenField()
            // Segunda barrera: el botón ya está deshabilitado, pero si algún
            // camino lo dejara habilitado no queremos devolver al POS un
            // cliente incompleto.
            if (!lastValidation.isValid) {
                refreshValidation()
                shakeValidationHint()
                return@setOnClickListener
            }
            scalePress(it) { onAccept?.invoke() }
        }
        linkReject?.setOnClickListener {
            scalePress(it) { onReject?.invoke() }
        }

        // zone_loyalty — la decisión se reporta una sola vez.
        btnKoajAccept?.setOnClickListener {
            scalePress(it) { finishLoyalty(LoyaltyOutcome.ACCEPTED) }
        }
        linkKoajSkip?.setOnClickListener {
            scalePress(it) { finishLoyalty(LoyaltyOutcome.DECLINED) }
        }

        btnCfAccept?.setOnClickListener {
            scalePress(it) { onConsumidorFinalAccept?.invoke() }
        }
        linkCfBack?.setOnClickListener {
            scalePress(it) {
                // Vuelve exactamente al paso donde estaba, no a un estado fijo.
                stateBeforeConsumidorFinal?.let { previous -> render(previous) }
            }
        }
        btnRetry?.setOnClickListener {
            val c = initialCustomer ?: return@setOnClickListener
            applyCustomer(c)
            render(ScreenState.Showing(c))
            onRetry?.invoke()
        }

        // zone_awaiting_document
        awaitingDocType?.setOnClickListener { showDocTypeDialog() }
        btnContinue?.setOnClickListener { handleContinue() }
        awaitingLinkReject?.setOnClickListener {
            scalePress(it) { onReject?.invoke() }
        }
        awaitingDocId?.setOnEditorActionListener { _, _, _ -> handleContinue(); true }
    }

    private fun handleContinue() {
        val numero = awaitingDocId?.text?.toString()?.trim().orEmpty()
        if (numero.length < 4) {
            awaitingDocId?.error = context.getString(R.string.consent_doc_id_too_short)
            return
        }
        // Reflejar selección en el modelo y disparar callback
        setValue("fiscalDocumentType", selectedDocType)
        setValue("fiscalId", numero)
        onDocumentConfirmed?.invoke(selectedDocType, numero)
    }

    /** Cierra el campo que esté abierto, si hay alguno. */
    private fun closeAnyOpenField() {
        fields.forEach { (key, row) ->
            if (row.editRow.visibility == View.VISIBLE) exitEdit(key)
        }
        keyboardHelper?.hideKeyboard()
    }

    /** Sacude el hint para que el cliente note por qué no avanza. */
    private fun shakeValidationHint() {
        val hint = validationHint ?: return
        if (hint.visibility != View.VISIBLE) return
        ObjectAnimator.ofFloat(hint, "translationX", 0f, -12f, 12f, -8f, 8f, 0f).apply {
            duration = 380
            start()
        }
    }

    // ------------------------------------------------------------------
    // Koaj Lovers
    // ------------------------------------------------------------------

    private val loyaltyHandler = Handler(Looper.getMainLooper())
    private var loyaltyDecided = false
    private var loyaltySecondsLeft = 0

    private val loyaltyTick = object : Runnable {
        override fun run() {
            loyaltySecondsLeft--
            if (loyaltySecondsLeft <= 0) {
                finishLoyalty(LoyaltyOutcome.TIMEOUT)
                return
            }
            renderCountdown()
            loyaltyHandler.postDelayed(this, 1_000L)
        }
    }

    /**
     * Arranca el auto-avance. Existe porque mientras esta pantalla está
     * visible el Intent de `SELECT_CUSTOMER` sigue sin resolverse y la caja
     * está esperando: un cliente que se distrae no puede bloquear la venta.
     */
    private fun startLoyaltyTimer() {
        loyaltyDecided = false
        loyaltyHandler.removeCallbacks(loyaltyTick)
        koajCountdown?.visibility = View.INVISIBLE

        if (loyaltyTimeoutMs <= 0L) return
        loyaltySecondsLeft = (loyaltyTimeoutMs / 1_000L).toInt().coerceAtLeast(1)
        renderCountdown()
        loyaltyHandler.postDelayed(loyaltyTick, 1_000L)
    }

    /** La cuenta atrás sólo se muestra al final: antes sería presión gratuita. */
    private fun renderCountdown() {
        val view = koajCountdown ?: return
        if (loyaltySecondsLeft > COUNTDOWN_VISIBLE_FROM_SECONDS) {
            view.visibility = View.INVISIBLE
            return
        }
        view.text = context.getString(R.string.koaj_autocontinue, loyaltySecondsLeft)
        view.visibility = View.VISIBLE
    }

    private fun stopLoyaltyTimer() {
        loyaltyHandler.removeCallbacks(loyaltyTick)
    }

    /** Idempotente: sólo la primera decisión (o el timeout) cuenta. */
    private fun finishLoyalty(outcome: LoyaltyOutcome) {
        if (loyaltyDecided) return
        loyaltyDecided = true
        stopLoyaltyTimer()
        koajCountdown?.visibility = View.INVISIBLE
        onLoyaltyDecision?.invoke(outcome)
    }

    fun snapshotCustomer(): CustomerData {
        val initial = initialCustomer
        return CustomerData(
            customerId         = initial?.customerId,
            customerName       = fields["customerName"]?.currentValue.orEmpty(),
            fiscalDocumentType = TipoDocumentoMapper.toDianCode(
                fields["fiscalDocumentType"]?.currentValue
            ) ?: fields["fiscalDocumentType"]?.currentValue,
            fiscalId           = fields["fiscalId"]?.currentValue,
            email              = fields["email"]?.currentValue,
            phone              = fields["phone"]?.currentValue,
            // No es editable en pantalla, pero hay que arrastrarlo o la
            // respuesta al POS le cambiaría el país al cliente.
            countryIso2        = initial?.countryIso2,
            customerTypeId     = initial?.customerTypeId,
            extraFieldsXml     = initial?.extraFieldsXml.orEmpty()
        )
    }

    /**
     * Aplica datos externos al formulario (típicamente desde el mirror del
     * operador). Usa `suppressEmit` para no disparar el TextWatcher
     * (evita bucle de sincronización).
     */
    fun applyExternal(customer: CustomerData) {
        suppressEmit = true
        try {
            applyCustomer(customer)
        } finally {
            suppressEmit = false
        }
    }

    fun render(state: ScreenState) {
        val target = when (state) {
            is ScreenState.Loading            -> zoneLoading
            is ScreenState.LoadingWithMessage -> zoneLoading
            is ScreenState.WaitingForVendor   -> zoneWaitingVendor
            is ScreenState.AwaitingDocument   -> zoneAwaiting
            is ScreenState.Showing            -> zoneData
            is ScreenState.ConsumidorFinalReview -> zoneConsumidorFinal
            is ScreenState.Loyalty            -> zoneLoyalty
            is ScreenState.Confirmed          -> zoneConfirmed
            is ScreenState.Rejected           -> zoneRejected
            is ScreenState.Error              -> zoneError
            is ScreenState.NoData             -> zoneNoData
        } ?: return

        // Salir de la zona de Koaj cancela su auto-avance.
        if (state !is ScreenState.Loyalty) stopLoyaltyTimer()

        // Recordar de dónde venimos para que "Volver a ingresar mis datos"
        // no adivine el paso anterior.
        if (state !is ScreenState.ConsumidorFinalReview &&
            (state is ScreenState.Showing || state is ScreenState.AwaitingDocument)) {
            stateBeforeConsumidorFinal = state
        }

        allZones.forEach { z ->
            if (z === target) {
                if (z.visibility != View.VISIBLE) {
                    z.alpha = 0f
                    z.translationY = 40f
                    z.visibility = View.VISIBLE
                    z.animate()
                        .alpha(1f)
                        .translationY(0f)
                        .setDuration(360)
                        .setInterpolator(animDecelerate)
                        .start()
                }
            } else if (z.visibility == View.VISIBLE) {
                z.animate()
                    .alpha(0f)
                    .setDuration(200)
                    .withEndAction { z.visibility = View.GONE }
                    .start()
            } else {
                z.visibility = View.GONE
                z.alpha = 0f
            }
        }

        when (state) {
            is ScreenState.Loading -> {
                loadingSubtitle?.setText(R.string.state_loading)
            }
            is ScreenState.LoadingWithMessage -> {
                loadingSubtitle?.text = state.message
            }
            is ScreenState.Showing -> {
                initialCustomer = state.customer
                applyScenarioSubtitle(state.scenario)
                applyCustomer(state.customer)
                runStaggerEntry(state.customer)
            }
            is ScreenState.ConsumidorFinalReview -> {
                applyConsumidorFinal(state.customer)
            }
            is ScreenState.Loyalty -> {
                startLoyaltyTimer()
                runLoyaltyEntry()
            }
            is ScreenState.Confirmed -> {
                if (state.joinedLoyalty) {
                    confirmedTitle?.setText(R.string.koaj_welcome)
                    confirmedSubtitle?.setText(R.string.koaj_welcome_subtitle)
                    confirmedSubtitle?.visibility = View.VISIBLE
                } else {
                    confirmedTitle?.setText(R.string.state_confirmed)
                    confirmedSubtitle?.visibility = View.GONE
                }
                startAvatarPulse(confirmedCheck)
            }
            is ScreenState.Error -> {
                errorMessage?.text = state.message
            }
            else -> Unit
        }
    }

    /**
     * El subtítulo avisa cuando el cliente no está en HioPosCloud, para que
     * el vendedor sepa que la venta va a crear un cliente nuevo.
     */
    private fun applyScenarioSubtitle(scenario: CustomerScenario) {
        consentSubtitle?.setText(
            when (scenario) {
                CustomerScenario.EDIT             -> R.string.subtitle_edit
                CustomerScenario.CREATE_FROM_DIAN -> R.string.subtitle_create_from_dian
                CustomerScenario.CREATE_MANUAL    -> R.string.subtitle_create_manual
                // Ya existe en HioPos: no hay que avisar que "no está creado",
                // sólo pedirle que complete lo que falta.
                CustomerScenario.COMPLETE_EXISTING -> R.string.subtitle_complete_existing
                // No debería pintarse Showing para el CF, pero si pasara no
                // queremos afirmar que falta crearlo en HioPos.
                CustomerScenario.CONSUMIDOR_FINAL -> R.string.subtitle_edit
            }
        )
    }

    /** Pinta los datos genéricos del Consumidor Final en modo lectura. */
    private fun applyConsumidorFinal(customer: CustomerData) {
        cfValueName?.text = customer.customerName
        cfValueDocType?.text = TipoDocumentoMapper.toFullName(customer.fiscalDocumentType)
            ?: customer.fiscalDocumentType.orEmpty()
        cfValueDocId?.text = customer.fiscalId.orEmpty()
        startAvatarPulse(cfIcon)
    }

    /** Entrada escalonada de la pantalla de Koaj Lovers. */
    private fun runLoyaltyEntry() {
        startAvatarPulse(koajHeart)
        animateIn(root.findViewById(R.id.koaj_eyebrow), delay = 40L, distance = 12f)
        animateIn(root.findViewById(R.id.koaj_title), delay = 100L, distance = 22f)
        animateIn(root.findViewById(R.id.koaj_subtitle), delay = 180L, distance = 18f)
        animateIn(root.findViewById(R.id.koaj_perks), delay = 260L, distance = 20f)
        animateIn(btnKoajAccept, delay = 360L, distance = 26f)
        animateIn(root.findViewById(R.id.koaj_free_hint), delay = 440L, distance = 12f)
        if (loyaltyAllowDecline) {
            animateIn(linkKoajSkip, delay = 520L, distance = 10f, endAlpha = SKIP_LINK_ALPHA)
        }
    }

    fun updateData(customer: CustomerData) {
        initialCustomer = customer
        applyCustomer(customer)
    }

    /**
     * No-op a propósito.
     *
     * Los avisos de la DIAN ("no está registrado", "no pudimos validar") son
     * información **operativa del cajero**: al cliente no le dicen nada útil y
     * lo alarman en medio de su compra. Sólo se muestran en la pantalla del
     * vendedor, que es quien puede actuar sobre ellos.
     */
    fun setWarning(@Suppress("UNUSED_PARAMETER") warning: String?) {
        // Intencionalmente vacío. Ver el KDoc.
    }

    private fun runStaggerEntry(@Suppress("UNUSED_PARAMETER") c: CustomerData) {
        val header = root.findViewById<View>(R.id.consent_title)
        val subtitle = root.findViewById<View>(R.id.consent_subtitle)
        animateIn(header, delay = 0L, distance = 16f)
        animateIn(subtitle, delay = 60L, distance = 16f)
        val card = root.findViewById<View>(R.id.field_name)?.parent as? ViewGroup
        animateIn(card, delay = 120L, distance = 24f)
        listOf("customerName", "fiscalDocumentType", "fiscalId", "email", "phone").forEachIndexed { i, key ->
            fields[key]?.displayRow?.let { row ->
                row.alpha = 0f
                row.translationY = 12f
                row.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setStartDelay((220L + i * 60L))
                    .setDuration(280)
                    .setInterpolator(animDecelerate)
                    .start()
            }
        }
        animateIn(btnAccept, delay = 220L + 5 * 60L + 80L, distance = 24f)
        animateIn(linkReject, delay = 220L + 5 * 60L + 140L, distance = 12f)
    }

    /**
     * @param endAlpha alfa final. Se parametriza porque el link "Ahora no" de
     *   Koaj Lovers debe quedar deliberadamente atenuado, no a opacidad plena.
     */
    private fun animateIn(view: View?, delay: Long, distance: Float, endAlpha: Float = 1f) {
        view ?: return
        view.alpha = 0f
        view.translationY = distance
        view.animate()
            .alpha(endAlpha)
            .translationY(0f)
            .setStartDelay(delay)
            .setDuration(320)
            .setInterpolator(animDecelerate)
            .start()
    }

    private fun scalePress(view: View, then: () -> Unit) {
        AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(view, "scaleX", 1f, 0.96f, 1f).apply { duration = 220 },
                ObjectAnimator.ofFloat(view, "scaleY", 1f, 0.96f, 1f).apply { duration = 220 },
                ObjectAnimator.ofFloat(view, "alpha", 1f, 0.7f, 1f).apply { duration = 220 }
            )
            interpolator = animOvershoot
        }.start()
        view.postDelayed(then, 160)
    }

    private var pulseAnim: ObjectAnimator? = null

    private fun startAvatarPulse(view: View?) {
        pulseAnim?.cancel()
        view ?: return
        view.scaleX = 1f
        view.scaleY = 1f
        pulseAnim = ObjectAnimator.ofFloat(view, "scaleX", 1f, 1.06f, 1f).apply {
            duration = 1800
            repeatCount = ObjectAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
        ObjectAnimator.ofFloat(view, "scaleY", 1f, 1.06f, 1f).apply {
            duration = 1800
            repeatCount = ObjectAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun stopPulse() {
        pulseAnim?.cancel()
        pulseAnim = null
    }

    /** Definición estática de cada campo del formulario. */
    private class FieldSpec(
        val rowId: Int,
        val key: String,
        val labelRes: Int,
        val iconRes: Int,
        val inputType: Int,
        /** Tope de caracteres. 0 = sin tope (el tipo de documento es un picker). */
        val maxLength: Int
    )

    private class FieldRow(
        val displayRow: ViewGroup,
        val editRow: ViewGroup,
        val valueView: TextView,
        val input: EditText,
        /** Etiqueta del campo — se tiñe de rojo cuando falta o está mal. */
        val labelView: TextView,
        /** Color original de la etiqueta, para poder restaurarlo. */
        val labelDefaultColor: Int,
        /** Contador "n/max"; sólo visible cerca del tope. */
        val counterView: TextView?,
        val maxLength: Int,
        var currentValue: String
    )

    /**
     * País del teléfono. El orden del toolbar es el mismo que en
     * [PHONE_COUNTRIES]: Colombia primero, luego Ecuador, Costa Rica y
     * finalmente Extranjeros.
     *
     * @param iso2 código ISO-3166-alpha-2 que se persiste aparte en
     *   [com.permoda.facturacion.core.model.CustomerData.countryIso2].
     * @param dial indicativo internacional sin el `+` (lo que va pegado al
     *   número local en [com.permoda.facturacion.core.modify.ModifyDocumentXml]).
     * @param labelRes recurso de cadena con el nombre para mostrar.
     */
    private data class PhoneCountry(
        val iso2: String,
        val dial: String,
        val labelRes: Int,
        /** Bandera recortada y sin fondo. Ver `drawable-xxhdpi/flag_*.png`. */
        val flagRes: Int
    )

    fun dispose() {
        keyboardHelper?.detach()
        stopLoyaltyTimer()
        stopPulse()
    }

    private companion object {
        /** Sólo mostramos la cuenta atrás en los últimos segundos. */
        const val COUNTDOWN_VISIBLE_FROM_SECONDS = 6

        /** El link para declinar existe, pero no compite con el botón. */
        const val SKIP_LINK_ALPHA = 0.65f

        /** El contador aparece cuando faltan estos caracteres para el tope. */
        const val COUNTER_VISIBLE_MARGIN = 10

        // Topes de caracteres. El cliente no es un actor que edita: el vendor
        // teclea y el cliente mira. Por eso permitimos topes generosos — el
        // nombre real y el email del mundo real caben sin problemas.
        const val MAX_NAME = 60
        const val MAX_DOC_ID = 20
        const val MAX_EMAIL = 60
        /** Teléfono local — sin contar el indicativo del país (que va aparte). */
        const val MAX_PHONE = 10

        /**
         * Los 5 campos del formulario. Tenerlos como datos (y no como cinco
         * llamadas con argumentos posicionales) hace que añadir un campo o
         * ajustar un tope sea una línea.
         */
        val FIELD_SPECS = listOf(
            FieldSpec(
                rowId = R.id.field_name,
                key = "customerName",
                labelRes = R.string.consent_label_name,
                iconRes = R.drawable.ic_person,
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS,
                maxLength = MAX_NAME
            ),
            FieldSpec(
                rowId = R.id.field_doc_type,
                key = "fiscalDocumentType",
                labelRes = R.string.consent_label_doc_type,
                iconRes = R.drawable.ic_id,
                inputType = InputType.TYPE_CLASS_TEXT,
                // Se elige de una lista, nunca se teclea.
                maxLength = 0
            ),
            FieldSpec(
                rowId = R.id.field_doc_id,
                key = "fiscalId",
                labelRes = R.string.consent_label_doc_id,
                iconRes = R.drawable.ic_number,
                inputType = InputType.TYPE_CLASS_NUMBER,
                maxLength = MAX_DOC_ID
            ),
            FieldSpec(
                rowId = R.id.field_email,
                key = "email",
                labelRes = R.string.consent_label_email,
                iconRes = R.drawable.ic_email,
                inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                maxLength = MAX_EMAIL
            ),
            FieldSpec(
                rowId = R.id.field_phone,
                key = "phone",
                labelRes = R.string.consent_label_phone,
                iconRes = R.drawable.ic_phone,
                inputType = InputType.TYPE_CLASS_NUMBER,
                maxLength = MAX_PHONE
            )
        )

        /**
         * Países disponibles en el toolbox del teléfono, en el orden que se
         * muestran en el popup (Colombia es el primero porque es la flota).
         * El `iso2` es lo que se persiste en `countryIso2`; el `dial` es el
         * prefijo internacional que se concatena al número local al guardar
         * (`+<dial><número>`).
         */
        val PHONE_COUNTRIES = listOf(
            PhoneCountry("CO", "57",  R.string.phone_country_colombia,  R.drawable.flag_co),
            PhoneCountry("EC", "593", R.string.phone_country_ecuador,   R.drawable.flag_ec),
            PhoneCountry("CR", "506", R.string.phone_country_costarica, R.drawable.flag_cr),
            // "00" es el prefijo genérico para llamadas internacionales salientes
            // desde Colombia. ICG no lo va a guardar como país; lo importante es
            // que el cajero pueda tipear el teléfono del cliente tal cual.
            PhoneCountry("ZZ", "00",  R.string.phone_country_extranjeros, R.drawable.flag_zz)
        )

        /** País del teléfono por defecto. Colombia, primer puesto de la flota. */
        val DEFAULT_PHONE_COUNTRY = PHONE_COUNTRIES.first()
    }

    /**
     * Llamado desde [ConsentPresentation.dispatchTouchEvent]. Si el tap es
     * sobre un área que NO es un EditText con focus, oculta el teclado.
     */
    fun shouldDismissKeyboardOnTouch(rawX: Float, rawY: Float) {
        keyboardHelper?.shouldDismissOnTouch(rawX, rawY)
    }
}
