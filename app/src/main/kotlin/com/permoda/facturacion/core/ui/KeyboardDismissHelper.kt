package com.permoda.facturacion.core.ui

import android.content.Context
import android.graphics.Rect
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.view.ViewTreeObserver
import android.widget.EditText

/**
 * Helper para mostrar/ocultar un botón flotante "▼ Cerrar teclado"
 * cuando el teclado virtual está abierto en una pantalla.
 *
 * También detecta taps sobre áreas NO-editables y cierra el teclado, y
 * **reporta la altura del teclado** vía [onHeightChanged].
 *
 * ## Por qué la altura se mide acá y no con `WindowInsets`
 *
 * `CustomerSelectionActivity` levanta `FLAG_LAYOUT_NO_LIMITS` para que la barra
 * de navegación no quede pegada al borde en las terminales pre-API 30. Ese flag
 * tiene un efecto lateral: **Android desactiva `adjustResize`**. La ventana sigue
 * midiendo la pantalla completa y el teclado se dibuja encima, así que un
 * `ScrollView` nunca se entera de que perdió la mitad inferior — el cajero
 * escribía a ciegas en los campos de abajo.
 *
 * `getWindowVisibleDisplayFrame` sí refleja el teclado incluso con ese flag
 * puesto, porque el IME es otra ventana y reduce el frame visible real. Es la
 * técnica pre-insets, y acá es la correcta justamente porque no exige renunciar
 * al fullscreen.
 *
 * Esta clase ya calculaba `heightDiff` para decidir si el teclado estaba
 * abierto — y tiraba el número. Ahora lo publica.
 *
 * @param onHeightChanged alto del teclado en px, `0` cuando está cerrado. Se
 *   invoca sólo cuando el valor cambia.
 */
class KeyboardDismissHelper(
    private val button: View,
    private val rootView: View,
    private val onHeightChanged: (Int) -> Unit = {}
) : ViewTreeObserver.OnGlobalLayoutListener {

    private var keyboardOpen = false

    /** Último alto reportado, para no repetir el callback en cada layout. */
    private var lastReportedHeight = -1

    init {
        button.setOnClickListener { hideKeyboard() }
        rootView.viewTreeObserver.addOnGlobalLayoutListener(this)
    }

    /** Llamar en `onDestroy` para limpiar el listener. */
    fun detach() {
        runCatching { rootView.viewTreeObserver.removeOnGlobalLayoutListener(this) }
    }

    override fun onGlobalLayout() {
        val rect = Rect()
        rootView.getWindowVisibleDisplayFrame(rect)
        val rootHeight = rootView.height
        if (rootHeight <= 0) return
        val heightDiff = rootHeight - rect.height()
        val isOpen = heightDiff > rootHeight * KEYBOARD_THRESHOLD
        if (isOpen != keyboardOpen) {
            keyboardOpen = isOpen
            button.visibility = if (isOpen) View.VISIBLE else View.GONE
        }
        // El alto se reporta aunque `isOpen` no haya cambiado: el teclado cambia
        // de tamaño al alternar entre el layout numérico y el alfabético, y con
        // el padding viejo el campo enfocado volvía a quedar tapado.
        val height = if (isOpen) heightDiff else 0
        if (height != lastReportedHeight) {
            lastReportedHeight = height
            onHeightChanged(height)
        }
    }

    /**
     * Llamado desde el Presentation/Activity para detectar taps fuera
     * de campos editables y cerrar el teclado.
     *
     * Hace hit-test contra TODOS los EditText visibles del rootView
     * (no solo el que tiene focus). Esto es importante para campos
     * numéricos, donde `findFocus()` puede no retornar el EditText esperado.
     *
     * Devuelve `true` si consumió el evento (no se debe propagar al hijo),
     * `false` en caso contrario.
     */
    fun shouldDismissOnTouch(rawX: Float, rawY: Float): Boolean {
        if (isTouchOverAnyEditText(rootView, rawX.toInt(), rawY.toInt())) return false
        hideKeyboard()
        val focus = rootView.findFocus()
        if (focus is EditText) focus.clearFocus()
        return true
    }

    /**
     * Hit-test recursivo: ¿el punto (x, y) está sobre algún EditText visible
     * dentro del view? Recorre hijos en orden inverso para que el último
     * hijo pintado (encima) gane.
     */
    private fun isTouchOverAnyEditText(view: View, x: Int, y: Int): Boolean {
        if (view.visibility != View.VISIBLE) return false
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        val left = loc[0]
        val top = loc[1]
        val right = left + view.width
        val bottom = top + view.height
        if (x < left || x > right || y < top || y > bottom) return false

        if (view is EditText) return true

        if (view is android.view.ViewGroup) {
            // Recorrer del último hijo al primero (Z-order inverso)
            for (i in view.childCount - 1 downTo 0) {
                val child = view.getChildAt(i)
                if (isTouchOverAnyEditText(child, x, y)) return true
            }
        }
        return false
    }

    fun hideKeyboard() {
        val imm = rootView.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val token = (rootView.context as? android.app.Activity)?.currentFocus?.windowToken
            ?: rootView.windowToken
        if (token != null) {
            imm.hideSoftInputFromWindow(token, 0)
        }
        val focus = rootView.findFocus()
        if (focus is android.widget.EditText) focus.clearFocus()
    }

    private companion object {
        /** 15% de la altura del view ≈ suficiente para detectar teclado abierto. */
        const val KEYBOARD_THRESHOLD = 0.15
    }
}
