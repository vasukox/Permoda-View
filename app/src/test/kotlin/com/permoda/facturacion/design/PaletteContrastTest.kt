package com.permoda.facturacion.design

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.pow

/**
 * Guardián de contraste del sistema visual.
 *
 * ## Por qué existe
 *
 * El sistema oro/cream llegó a producción con dos pares por debajo del mínimo
 * legible y **nadie lo notó durante semanas**: el CTA daba 2.2:1 contra el fondo
 * y las etiquetas de los campos 2.4:1 sobre tarjeta blanca. Se descubrió porque a
 * un usuario le pareció "feo", no porque algo fallara — que es exactamente el
 * tipo de defecto que un test tiene que atrapar.
 *
 * Este proyecto ya lleva dos re-skins (magenta → oro → navy) y va a haber un
 * tercero. Sin este test, el tercero puede volver a elegir por vibra.
 *
 * ## Qué verifica
 *
 * Parsea `res/values/colors.xml` de verdad —igual que `ManifestContractTest`
 * parsea el manifest real— y aplica la fórmula de contraste de WCAG 2.1 a los
 * pares que de hecho se pintan juntos en la app.
 *
 * ## Qué NO verifica
 *
 * La paleta de marca KOAJ (`koaj_gold*`, `koaj_black`, `koaj_ink*`…) queda fuera
 * a propósito: es la pantalla de Koaj Lovers, está aprobada por marca y no se
 * toca. Meterla acá haría fallar el build por una decisión que no es nuestra.
 */
class PaletteContrastTest {

    /** Mínimo de WCAG AA para texto normal. Las etiquetas son de 11sp. */
    private val MIN_TEXTO = 4.5

    /**
     * Mínimo de separación entre una superficie y el fondo sobre el que se
     * apoya. WCAG lo pide para componentes de UI; es el número que el oro sobre
     * cream reventaba (2.2:1).
     */
    private val MIN_SUPERFICIE = 3.0

    private val colores: Map<String, String> by lazy {
        val f = File("src/main/res/values/colors.xml")
        assertTrue("no se encontró colors.xml en ${f.absolutePath}", f.exists())
        Regex("""<color\s+name="([^"]+)"\s*>\s*(#[0-9A-Fa-f]{6,8})\s*</color>""")
            .findAll(f.readText())
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    // ------------------------------------------------------------------
    // Los pares que de verdad se pintan juntos
    // ------------------------------------------------------------------

    @Test
    fun `el texto del CTA primario se lee sobre su relleno`() {
        exigirTexto("text_on_primary", "accent_primary")
    }

    @Test
    fun `el CTA primario se separa del fondo de la pantalla`() {
        // El par que estaba en 2.2:1 con el oro. Es la razón por la que el
        // relleno del primario va oscuro y no un azul medio "premium".
        exigirSuperficie("accent_primary", "bg_app")
    }

    @Test
    fun `las etiquetas de los campos se leen sobre la tarjeta`() {
        // `VendorFieldLabel` pinta `accent_secondary` a 11sp sobre `bg_surface`.
        // Con el oro esto daba 2.4:1 — texto ilegible en producción.
        exigirTexto("accent_secondary", "bg_surface")
    }

    @Test
    fun `el texto principal se lee sobre el fondo y sobre las tarjetas`() {
        exigirTexto("text_primary", "bg_app")
        exigirTexto("text_primary", "bg_surface")
    }

    @Test
    fun `el texto secundario se lee sobre las tarjetas`() {
        exigirTexto("text_secondary", "bg_surface")
    }

    @Test
    fun `la etiqueta del CTA deshabilitado sigue siendo legible`() {
        // El Aceptar pasa la mayor parte del tiempo deshabilitado —mientras
        // faltan campos— y su etiqueta es justo la que el cajero necesita leer
        // para entender por qué no puede confirmar. Con texto blanco sobre el
        // gris del estado daba 1.9:1.
        exigirTexto("text_primary", "accent_primary_disabled")
    }

    @Test
    fun `el CTA del pod se separa del fondo oscuro`() {
        // El pod del cajero es oscuro, así que la regla se invierte: el relleno
        // va claro. Se compara contra el negro de marca porque ese es el fondo
        // real sobre el que se pinta.
        exigirSuperficie("bg_surface", "koaj_black")
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    private fun exigirTexto(frente: String, fondo: String) =
        exigir(frente, fondo, MIN_TEXTO, "texto")

    private fun exigirSuperficie(frente: String, fondo: String) =
        exigir(frente, fondo, MIN_SUPERFICIE, "superficie")

    private fun exigir(frente: String, fondo: String, minimo: Double, tipo: String) {
        val ratio = contraste(hex(frente), hex(fondo))
        assertTrue(
            "$tipo: $frente sobre $fondo da ${"%.2f".format(ratio)}:1 " +
                "y el mínimo es $minimo:1. Si acabás de cambiar la paleta, el " +
                "problema es la paleta, no el test.",
            ratio >= minimo
        )
    }

    private fun hex(nombre: String): String =
        colores[nombre] ?: error("el token '$nombre' no existe en colors.xml")

    /** WCAG 2.1: (L_claro + 0.05) / (L_oscuro + 0.05). */
    private fun contraste(a: String, b: String): Double {
        val la = luminancia(a)
        val lb = luminancia(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /**
     * Luminancia relativa. Ignora el canal alfa cuando viene (`#AARRGGBB`):
     * comparar un color translúcido exige saber qué hay debajo, y ninguno de los
     * pares de arriba es translúcido.
     */
    private fun luminancia(hex: String): Double {
        val rgb = hex.removePrefix("#").let { if (it.length == 8) it.substring(2) else it }
        val canales = (0..2).map { i ->
            val v = rgb.substring(i * 2, i * 2 + 2).toInt(16) / 255.0
            if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * canales[0] + 0.7152 * canales[1] + 0.0722 * canales[2]
    }
}
