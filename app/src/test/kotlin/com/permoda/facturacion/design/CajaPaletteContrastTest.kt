package com.permoda.facturacion.design

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.pow

/**
 * Guardián de contraste del sistema «Caja» (pantalla del cajero y ficha del
 * módulo). Mismo criterio que [PaletteContrastTest], sobre `colors_caja.xml`:
 * los pares que de verdad se pintan juntos.
 */
class CajaPaletteContrastTest {

    private val MIN_TEXTO = 4.5
    private val MIN_COMPONENTE = 3.0

    private val colores: Map<String, String> by lazy {
        val f = File("src/main/res/values/colors_caja.xml")
        assertTrue("no se encontró colors_caja.xml en ${f.absolutePath}", f.exists())
        Regex("""<color\s+name="([^"]+)"\s*>\s*(#[0-9A-Fa-f]{6,8})\s*</color>""")
            .findAll(f.readText())
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    @Test
    fun `los textos de la banda se leen sobre el marino`() {
        exigir("caja_on_navy", "caja_navy", MIN_TEXTO)
        exigir("caja_on_navy_soft", "caja_navy", MIN_TEXTO)
        exigir("caja_on_navy_dim", "caja_navy", MIN_TEXTO)
        exigir("caja_on_navy", "caja_navy_raised", MIN_TEXTO)
    }

    @Test
    fun `el paso actual se distingue sobre la banda`() {
        exigir("caja_accent_on_navy", "caja_navy", MIN_COMPONENTE)
    }

    @Test
    fun `la accion primaria se lee y se separa del fondo`() {
        exigir("caja_on_navy", "caja_primary", MIN_TEXTO)
        exigir("caja_on_navy", "caja_primary_pressed", MIN_TEXTO)
        exigir("caja_primary", "caja_ground", MIN_COMPONENTE)
    }

    @Test
    fun `el boton deshabilitado sigue siendo legible`() {
        exigir("caja_text_muted", "caja_disabled", MIN_TEXTO)
    }

    @Test
    fun `los textos de trabajo se leen sobre blanco y sobre gris`() {
        listOf("caja_text", "caja_text_soft", "caja_text_muted").forEach { texto ->
            exigir(texto, "caja_surface", MIN_TEXTO)
            exigir(texto, "caja_ground", MIN_TEXTO)
        }
        exigir("caja_primary", "caja_ground", MIN_TEXTO)
        exigir("caja_primary", "caja_primary_tint", MIN_TEXTO)
    }

    @Test
    fun `el borde del campo cumple como componente de UI`() {
        exigir("caja_field_border", "caja_surface", MIN_COMPONENTE)
    }

    @Test
    fun `los estados se leen sobre su tinte`() {
        exigir("caja_error", "caja_error_tint", MIN_TEXTO)
        exigir("caja_success", "caja_success_tint", MIN_TEXTO)
        exigir("caja_warning_text", "caja_warning_tint", MIN_TEXTO)
    }

    private fun exigir(frente: String, fondo: String, minimo: Double) {
        val ratio = contraste(hex(frente), hex(fondo))
        assertTrue(
            "$frente sobre $fondo da ${"%.2f".format(ratio)}:1 y el mínimo es $minimo:1",
            ratio >= minimo
        )
    }

    private fun hex(nombre: String): String =
        colores[nombre] ?: error("el token '$nombre' no existe en colors_caja.xml")

    private fun contraste(a: String, b: String): Double {
        val la = luminancia(a)
        val lb = luminancia(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private fun luminancia(hex: String): Double {
        val rgb = hex.removePrefix("#").let { if (it.length == 8) it.substring(2) else it }
        val canales = (0..2).map { i ->
            val v = rgb.substring(i * 2, i * 2 + 2).toInt(16) / 255.0
            if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * canales[0] + 0.7152 * canales[1] + 0.0722 * canales[2]
    }
}
