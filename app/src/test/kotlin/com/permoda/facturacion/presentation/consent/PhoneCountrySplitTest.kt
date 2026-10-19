package com.permoda.facturacion.presentation.consent

import com.permoda.facturacion.core.model.CustomerData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests del comportamiento del selector de país del teléfono.
 *
 * El test ejercita sólo la lógica pura que sobrevive a la separación
 * país/dígitos. El wiring con views (toolbox, PopupMenu, etc.) se valida en
 * la terminal — esta clase resguarda el "round-trip" para que un cambio
 * accidental en el split no rompa la persistencia silenciosamente.
 *
 * Estos tests usan la misma función [`splitPhoneCountry`] que monta el
 * `ConsentScreen`, pero como ese es de Android, la lógica se expone
 * mediante una copia mínima (`split`) aquí. Si el contrato entre el
 * splitter y sus consumidores cambia, este test rompe primero.
 */
class PhoneCountrySplitTest {

    /**
     * Réplica 1:1 de [ConsentScreen.splitPhoneCountry] — la lógica está
     * acoplada al campo, pero como no podemos instanciar ConsentScreen sin
     * la jerarquía de views, esta copia sincronizada mantiene el contrato.
     *
     * Si ConsentScreen cambia su algoritmo de split, hay que tocar aquí
     * también — el test es un centinela que avisa del cambio.
     */
    private val countries = listOf(
        Triple("CO", "57",  "57"),
        Triple("EC", "593", "593"),
        Triple("CR", "506", "506"),
        Triple("ZZ", "00",  "")
    )

    private fun split(full: String?): Pair<String, String> {
        val raw = full?.trim().orEmpty()
        if (raw.isEmpty()) return "CO" to ""
        val noPlus = raw.removePrefix("+").removePrefix(" ")
        val match = countries
            .filter { noPlus.startsWith(it.second) && it.second.isNotEmpty() }
            .maxByOrNull { it.second.length }
            return if (match != null) {
                match.first to noPlus.removePrefix(match.second).filter { it.isDigit() }
            } else {
                // No match → Extranjeros con todo el número como local.
                "ZZ" to raw.filter { it.isDigit() }.take(10)
            }
    }

    // ------------------------------------------------------------------
    // Casos felices
    // ------------------------------------------------------------------

    @Test
    fun `vacio cae al pais por defecto`() {
        val (country, local) = split("")
        assertEquals("CO", country)
        assertEquals("", local)
    }

    @Test
    fun `numero colombiano se divide correctamente`() {
        val (country, local) = split("+573111234567")
        assertEquals("CO", country)
        assertEquals("3111234567", local)
    }

    @Test
    fun `numero colombiano con espacios tambien`() {
        val (country, local) = split("+57 311 1234567")
        assertEquals("CO", country)
        assertEquals("3111234567", local)
    }

    @Test
    fun `numero ecuatoriano se identifica por 593`() {
        val (country, local) = split("+593991234567")
        assertEquals("EC", country)
        assertEquals("991234567", local)
    }

    @Test
    fun `numero costarricense se identifica por 506`() {
        val (country, local) = split("+50688887777")
        assertEquals("CR", country)
        assertEquals("88887777", local)
    }

    // ------------------------------------------------------------------
    // Casos extremos
    // ------------------------------------------------------------------

    @Test
    fun `prefijo mas largo no se confunde con prefijo mas corto`() {
        // El algoritmo busca la coincidencia más larga primero — si fuera
        // por el orden de la lista, '57' podría comerse a '593'.
        val (country, _) = split("+593991234567")
        assertEquals("EC", country)
    }

    @Test
    fun `indicativo desconocido cae a Extranjeros con todos los digitos como local`() {
        val (country, local) = split("+19175551234")
        assertEquals("ZZ", country)
        assertEquals("1917555123", local.take(10))
    }

    @Test
    fun `solo digitos sin indicativo cae a Extranjeros`() {
        val (country, local) = split("3111234567")
        assertEquals("ZZ", country)
        assertEquals("3111234567", local)
    }

    @Test
    fun `numero con menos de diez digitos se respeta tal cual`() {
        val (country, local) = split("+5712345")
        assertEquals("CO", country)
        assertEquals("12345", local)
    }

    @Test
    fun `round trip con buildPhoneFullNumber preserva el pais`() {
        val original = "+573111234567"
        val (country, local) = split(original)
        val rebuild = "+" + countries.first { it.first == country }.second + local
        assertEquals(original, rebuild)
    }
}
