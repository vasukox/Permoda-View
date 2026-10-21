package com.permoda.facturacion.core.consent

import android.content.Context
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import com.permoda.facturacion.R
import com.permoda.facturacion.core.hka.TipoDocumentoMapper
import com.permoda.facturacion.core.model.CustomerData

/**
 * Espejo editable de los datos del cliente para la pantalla del vendedor.
 *
 * Sincroniza en **ambos sentidos** contra [ConsentSharedState]:
 *  - el vendedor teclea → se emite al estado compartido → la pantalla del
 *    cliente lo refleja
 *  - el cliente teclea → llega por el flow → se pinta aquí
 *
 * Sin locks por campo, *last-write-wins*: es lo que evita conflictos cuando
 * los dos escriben a la vez. `suppressEmit` corta el bucle de realimentación
 * (pintar un valor entrante dispararía el TextWatcher y lo volvería a emitir).
 *
 * Extraído porque la lógica vivía duplicada en el driver de QA; ahora el
 * driver y la pantalla real del vendedor comparten exactamente el mismo
 * comportamiento.
 */
class CustomerMirror(
    private val name: EditText,
    private val docType: EditText,
    private val docId: EditText,
    private val email: EditText,
    private val phone: EditText,
    /** Se invoca al tocar el tipo de documento: no se teclea, se elige. */
    private val onDocTypeClick: () -> Unit
) {

    private var suppressEmit = false

    /** True mientras el vendedor puede editar (ver [ConsentStage.allowsVendorEdit]). */
    private var editable = true

    /**
     * País seleccionado en el toolbox del teléfono. Lo editamos como un
     * `currentPhoneCountry` que se sincroniza con el del `ConsentScreen` vía
     * `ConsentSharedState` para que cambiar aquí (vendor) o allí (cliente)
     * mantenga la coherencia.
     */
    private var currentPhoneCountry: PhoneCountry = DEFAULT_PHONE_COUNTRY

    /** View del toolbox (chip "+57" clickable). Se setea en el init. */
    private var phoneToolbox: TextView? = null

    private val watcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) {
            if (suppressEmit || !editable) return
            ConsentSharedState.updateCustomer(snapshot())
        }
    }

    init {
        // Mismos topes que la pantalla del cliente: si el vendedor pudiera
        // escribir más, el valor se truncaría luego en silencio.
        name.filters = arrayOf(InputFilter.LengthFilter(MAX_NAME))
        docId.filters = arrayOf(InputFilter.LengthFilter(MAX_DOC_ID))
        email.filters = arrayOf(InputFilter.LengthFilter(MAX_EMAIL))
        phone.filters = arrayOf(InputFilter.LengthFilter(MAX_PHONE))

        // Toolbox de país a la izquierda del campo de teléfono del vendor.
        // Mismo patrón que [ConsentScreen.mountPhoneCountryToolbox]: el
        // input sigue mostrando sólo dígitos locales (sin el prefijo) para
        // no romper la longitud máxima; el prefijo se concatena al guardar
        // vía [buildPhoneFullNumber] en [snapshot].
        mountPhoneCountryToolbox()

        val keys = mapOf(name to "customerName", docId to "fiscalId", email to "email", phone to "phone")
        keys.forEach { (field, key) ->
            field.addTextChangedListener(watcher)
            // El teclado se pide explícitamente al ganar foco. Dejarlo al
            // comportamiento por defecto obligaba al cajero a acertarle justo a
            // la línea de texto; con esto basta tocar cualquier parte del campo.
            // El foco se publica para que la pantalla del cliente resalte ese campo.
            field.setOnFocusChangeListener { view, hasFocus ->
                if (hasFocus) {
                    showKeyboard(view)
                    ConsentSharedState.setActiveField(key)
                } else if (ConsentSharedState.current.activeField == key) {
                    ConsentSharedState.setActiveField(null)
                }
            }
            field.setOnClickListener { showKeyboard(it) }
        }

        // El tipo de documento se elige de una lista, nunca se teclea.
        docType.isFocusable = false
        docType.isFocusableInTouchMode = false
        docType.setOnClickListener {
            if (editable) {
                ConsentSharedState.setActiveField("fiscalDocumentType")
                onDocTypeClick()
            }
        }
    }

    /**
     * Inserta el toolbox selector de país delante del `phone` EditText en el
     * LinearLayout padre. El vendor phone ya está en su row; sólo lo
     * envolvemos en un LinearLayout horizontal igual que en la pantalla del
     * cliente.
     */
    private fun mountPhoneCountryToolbox() {
        val editRow = phone.parent as? ViewGroup ?: return
        val parent = editRow.parent as? ViewGroup ?: return
        val inputIndex = editRow.indexOfChild(phone)
        editRow.removeView(phone)

        val toolbox = TextView(phone.context).apply {
            text = "+" + currentPhoneCountry.dial
            textSize = 16f
            setTextColor(phone.context.getColor(R.color.caja_text))
            typeface = androidx.core.content.res.ResourcesCompat.getFont(phone.context, R.font.plex_sans_semibold)
            fontFeatureSettings = "tnum"
            setBackgroundResource(R.drawable.bg_caja_phone_prefix)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(10), 0)
            compoundDrawablePadding = dp(8)
            isClickable = true
            isFocusable = true
            contentDescription = phone.context.getString(R.string.phone_country_picker_title)
        }
        applyToolboxFlag(toolbox, currentPhoneCountry)
        toolbox.setOnClickListener { showCountryPicker(toolbox) }

        val row = LinearLayout(phone.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(
            toolbox,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        phone.setPadding(dp(14), 0, dp(14), 0)
        phone.background = phone.context.getDrawable(R.drawable.bg_caja_phone_input)
        row.addView(
            phone,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        )
        editRow.addView(row, inputIndex)
        phoneToolbox = toolbox
    }

    /**
     * Popup con los cuatro países, en el mismo orden que la pantalla del
     * cliente. Al elegir uno, repinta el toolbox y reconstruye el currentValue
     * del `ConsentSharedState` para que `CustomerValidator` y el serializador
     * XML vean el número completo.
     */
    private fun showCountryPicker(anchor: View) {
        val popup = PopupMenu(phone.context, anchor)
        PHONE_COUNTRIES.forEachIndexed { index, country ->
            popup.menu.add(0, country.iso2.hashCode(), index, countryMenuTitle(country))
        }
        popup.setOnMenuItemClickListener { item ->
            PHONE_COUNTRIES.getOrNull(item.order)?.let { country ->
                currentPhoneCountry = country
                phoneToolbox?.text = "+" + country.dial
                phoneToolbox?.let { applyToolboxFlag(it, country) }
                // Re-emitimos el valor para que el prefijo nuevo se concatene.
                if (editable) ConsentSharedState.updateCustomer(snapshot())
            }
            true
        }
        popup.show()
    }

    /**
     * Bandera + nombre + indicativo en cada fila del selector.
     *
     * Va como `ImageSpan` dentro del título y no como `MenuItem.setIcon`, porque
     * `PopupMenu` **no muestra iconos**: `setForceShowIcon` existe sólo desde
     * API 29 y por debajo hay que recurrir a reflexión sobre `MenuPopupHelper`.
     * Un span se renderiza igual en todas las versiones, sin trucos y sin
     * depender de APIs privadas.
     */
    private fun countryMenuTitle(country: PhoneCountry): CharSequence {
        val ctx = phone.context
        val label = ctx.getString(country.labelRes)
        val texto = "   $label  ·  +${country.dial}"
        val flag = scaledFlag(country.flagRes, dp(20)) ?: return texto

        return android.text.SpannableString(texto).apply {
            setSpan(
                android.text.style.ImageSpan(flag, android.text.style.DynamicDrawableSpan.ALIGN_CENTER),
                0, 1,
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    /** Pinta la bandera del país a la izquierda del "+dial" del toolbox. */
    private fun applyToolboxFlag(toolbox: TextView, country: PhoneCountry) {
        val flag = scaledFlag(country.flagRes, dp(18))
        // El chevron dice "esto se elige", igual que el selector de tipo de documento.
        val chevron = toolbox.context.getDrawable(R.drawable.ic_caja_chevron_down)
            ?.apply { setBounds(0, 0, dp(16), dp(16)) }
        toolbox.setCompoundDrawablesRelative(flag, null, chevron, null)
    }

    /**
     * Carga la bandera y le fija los bounds al tamaño pedido.
     *
     * Los PNG son de 96 px y hay que acotarlos a mano: un `Drawable` sin bounds
     * explícitos se pinta a su tamaño intrínseco, y una bandera de 96 px al lado
     * de un texto de 16sp rompe la fila. `bounds` cuadrado con el alto pedido
     * mantiene la proporción del recuadro sin deformar la bandera — la de
     * Ecuador es apaisada y se centra sola dentro del cuadro.
     */
    private fun scaledFlag(resId: Int, size: Int): android.graphics.drawable.Drawable? =
        phone.context.getDrawable(resId)?.apply { setBounds(0, 0, size, size) }

    /** Construye el cliente a partir de lo que hay en los campos. */
    fun snapshot(): CustomerData {
        val current = ConsentSharedState.current.customer
        val rawLocal = phone.text?.toString().orEmpty()
        val fullPhone = buildPhoneFullNumber(rawLocal)
        return current.copy(
            customerName = name.text?.toString().orEmpty(),
            // El campo muestra el nombre completo; al POS va el código DIAN.
            fiscalDocumentType = TipoDocumentoMapper.toDianCode(
                docType.text?.toString()
            ) ?: current.fiscalDocumentType,
            fiscalId = docId.text?.toString().orEmpty(),
            email = email.text?.toString().orEmpty(),
            phone = fullPhone.orEmpty()
        )
    }

    /**
     * Pinta datos entrantes sin re-emitirlos.
     *
     * Compara antes de escribir para no mover el cursor del vendedor mientras
     * teclea: `setText` reposiciona la selección al final.
     */
    fun apply(customer: CustomerData) {
        suppressEmit = true
        try {
            val tipo = TipoDocumentoMapper.toFullName(customer.fiscalDocumentType)
                ?: customer.fiscalDocumentType.orEmpty()
            setIfChanged(name, customer.customerName)
            setIfChanged(docType, tipo)
            setIfChanged(docId, customer.fiscalId.orEmpty())
            setIfChanged(email, customer.email.orEmpty())
            // El teléfono en `customer.phone` viene con prefijo. Mostramos al
            // cajero solo los dígitos locales — el prefijo vive en el toolbox.
            setIfChanged(phone, extractLocalPhoneDigits(customer.phone))
        } finally {
            suppressEmit = false
        }
    }

    /** Habilita o bloquea la edición según el punto del flujo. */
    fun setEditable(value: Boolean) {
        editable = value
        listOf(name, email, phone).forEach {
            it.isEnabled = value
            it.alpha = if (value) 1f else DISABLED_ALPHA
        }
        docType.isEnabled = value
        docType.alpha = if (value) 1f else DISABLED_ALPHA
        phoneToolbox?.isEnabled = value
        // `docId` siempre es sólo-lectura: la captura es el único momento
        // donde el cajero teclea el documento (en [VendorScreen.setupCapture]).
        // Si se equivocó, vuelve con BackToDocument — en el formulario, el
        // número ya viene del lupita y no se puede corregir.
        docId.isEnabled = false
        docId.alpha = LOCKED_ALPHA
    }

    /** Aplica el tipo elegido en el selector y lo propaga. */
    fun applyDocType(fullName: String) {
        suppressEmit = true
        try { docType.setText(fullName) } finally { suppressEmit = false }
        ConsentSharedState.updateCustomer(snapshot())
        ConsentSharedState.setActiveField(null)
    }

    fun dispose() {
        listOf(name, docId, email, phone).forEach { it.removeTextChangedListener(watcher) }
        ConsentSharedState.setActiveField(null)
    }

    private fun setIfChanged(field: EditText, value: String) {
        if (field.text?.toString() != value) field.setText(value)
    }

    /**
     * Devuelve solo los dígitos del número completo. Si el `full` trae
     * `+57 3111234567`, retorna `3111234567` para que el EditText del
     * vendor respete el `MAX_PHONE = 10`.
     */
    private fun extractLocalPhoneDigits(full: String?): String {
        if (full.isNullOrBlank()) return ""
        val noPlus = full.removePrefix("+").removePrefix(" ")
        val match = PHONE_COUNTRIES
            .filter { noPlus.startsWith(it.dial) && it.dial.isNotEmpty() }
            .maxByOrNull { it.dial.length }
        return if (match != null) {
            noPlus.removePrefix(match.dial).filter { it.isDigit() }
        } else {
            full.filter { it.isDigit() }.take(MAX_PHONE)
        }
    }

    /**
     * Compone el número completo (`+<dial><dígitos locales>`) que va al
     * `CustomerData.phone`. Si no hay dígitos, devuelve `null` para que el
     * teléfono quede opcional.
     */
    private fun buildPhoneFullNumber(localDigits: String): String? {
        val digits = localDigits.filter { it.isDigit() }
        if (digits.isBlank()) return null
        return "+" + currentPhoneCountry.dial + digits
    }

    private fun showKeyboard(view: View) {
        if (!editable) return
        val imm = view.context
            .getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun dp(value: Int): Int =
        (value * phone.resources.displayMetrics.density + 0.5f).toInt()

    private companion object {
        // HU-HIOPOS · Mismos topes que ConsentScreen. El cliente no edita
        // (sólo mira), así que el vendor puede teclear nombres y correos del
        // mundo real sin truncar.
        const val MAX_NAME = 60
        const val MAX_DOC_ID = 20
        const val MAX_EMAIL = 60
        /** Teléfono local — sin contar el indicativo del país (que va aparte). */
        const val MAX_PHONE = 10
        // El fondo gris del estado deshabilitado ya dice "bloqueado"; el alfa solo lo acompaña.
        const val DISABLED_ALPHA = 0.7f
        // El docId es el único campo sólo-lectura permanente: el cajero lo
        // teclea UNA vez en la captura; en el formulario ya viene del lupita
        // y corregirlo requiere volver al paso de captura.
        const val LOCKED_ALPHA = 0.7f
    }
}

/**
 * País del teléfono. Mismo modelo que [ConsentScreen.PhoneCountry] — vive
 * en una copia porque `core.consent` no debe depender de `presentation.consent`.
 * Si el flujo sigue bifurcándose, lo correcto es mover el modelo a un
 * `core.phone` compartido.
 */
internal data class PhoneCountry(
    val iso2: String,
    val dial: String,
    val labelRes: Int,
    /**
     * Bandera del país, ya recortada y sin fondo.
     *
     * Los originales eran badges de 2048×2048 sobre un damero **pintado** (no
     * transparencia) y pesaban 5.4 MB entre los cuatro. Se recortaron al badge,
     * se reconstruyó el alfa de las esquinas y se reescalaron a 96 px: 36 KB en
     * total. Ver `drawable-xxhdpi/flag_*.png`.
     */
    val flagRes: Int
)

/** Mismo orden que en la pantalla del cliente: Colombia primero. */
internal val PHONE_COUNTRIES: List<PhoneCountry> = listOf(
    PhoneCountry("CO", "57",  R.string.phone_country_colombia,    R.drawable.flag_co),
    PhoneCountry("EC", "593", R.string.phone_country_ecuador,     R.drawable.flag_ec),
    PhoneCountry("CR", "506", R.string.phone_country_costarica,   R.drawable.flag_cr),
    PhoneCountry("ZZ", "00",  R.string.phone_country_extranjeros, R.drawable.flag_zz)
)

internal val DEFAULT_PHONE_COUNTRY: PhoneCountry = PHONE_COUNTRIES.first()
