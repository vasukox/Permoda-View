package com.permoda.facturacion.core.loyalty

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoyaltyConfigTest {

    @Test
    fun `sin parametros usa defaults de produccion`() {
        val config = LoyaltyConfig.fromParametersXml(null)
        assertTrue(config.enabled)
        assertTrue(config.isActive)
        assertEquals(LoyaltyConfig.DEFAULT_TIMEOUT_MS, config.timeoutMs)
        assertTrue(config.auditEnabled)
    }

    // ------------------------------------------------------------------
    // Sin auto-avance por defecto, pero CON botón "NO ACEPTO": el mockup
    // aprobado por marca lo incluye (HU-HIOPOS).
    // ------------------------------------------------------------------

    @Test
    fun `por defecto la pantalla espera indefinidamente al Aceptar`() {
        val config = LoyaltyConfig.defaults()
        assertEquals(0L, config.timeoutMs)
        assertFalse(config.hasTimeout)
    }

    @Test
    fun `por defecto se muestra el boton NO ACEPTO`() {
        // Cambió respecto al comportamiento original. Antes iba oculto "por
        // decisión de negocio", pero el mockup aprobado lo incluye — y sin
        // salida, un cliente que se va deja la caja bloqueada.
        assertTrue(
            "el botón de declinar debe estar visible",
            LoyaltyConfig.defaults().allowDecline
        )
    }

    @Test
    fun `operaciones puede ocultar la salida sin liberar version`() {
        val xml = """
            <Parameters>
              <Param Key="KOAJ_ALLOW_DECLINE" Value="false"/>
              <Param Key="KOAJ_TIMEOUT_MS" Value="15000"/>
            </Parameters>
        """.trimIndent()
        val config = LoyaltyConfig.fromParametersXml(xml)
        assertFalse(config.allowDecline)
        assertEquals(15_000L, config.timeoutMs)
        assertTrue(config.hasTimeout)
    }

    @Test
    fun `xml vacio usa defaults`() {
        assertEquals(LoyaltyConfig.defaults(), LoyaltyConfig.fromParametersXml("   "))
    }

    @Test
    fun `operaciones puede apagar la pantalla sin liberar version`() {
        val xml = """<Parameters><Param Key="KOAJ_ENABLED" Value="false"/></Parameters>"""
        val config = LoyaltyConfig.fromParametersXml(xml)
        assertFalse(config.enabled)
        assertFalse(config.isActive)
    }

    @Test
    fun `acepta si y no en espanol`() {
        val si = """<Parameters><Param Key="KOAJ_ENABLED" Value="sí"/></Parameters>"""
        val no = """<Parameters><Param Key="KOAJ_ENABLED" Value="no"/></Parameters>"""
        assertTrue(LoyaltyConfig.fromParametersXml(si).enabled)
        assertFalse(LoyaltyConfig.fromParametersXml(no).enabled)
    }

    @Test
    fun `timeout se respeta dentro del rango`() {
        val xml = """<Parameters><Param Key="KOAJ_TIMEOUT_MS" Value="8000"/></Parameters>"""
        assertEquals(8_000L, LoyaltyConfig.fromParametersXml(xml).timeoutMs)
    }

    @Test
    fun `xml sin la llave KOAJ_ALLOW_DECLINE mantiene el default visible`() {
        val xml = """<Parameters><Param Key="KOAJ_TIMEOUT_MS" Value="8000"/></Parameters>"""
        assertTrue(LoyaltyConfig.fromParametersXml(xml).allowDecline)
    }

    @Test
    fun `timeout se acota para no bloquear la caja`() {
        val enorme = """<Parameters><Param Key="KOAJ_TIMEOUT_MS" Value="999999"/></Parameters>"""
        val config = LoyaltyConfig.fromParametersXml(enorme)
        assertEquals(60_000L, config.timeoutMs)
    }

    @Test
    fun `timeout minusculo se sube al minimo legible`() {
        val diminuto = """<Parameters><Param Key="KOAJ_TIMEOUT_MS" Value="500"/></Parameters>"""
        assertEquals(3_000L, LoyaltyConfig.fromParametersXml(diminuto).timeoutMs)
    }

    @Test
    fun `timeout cero significa esperar indefinidamente`() {
        val xml = """<Parameters><Param Key="KOAJ_TIMEOUT_MS" Value="0"/></Parameters>"""
        val config = LoyaltyConfig.fromParametersXml(xml)
        assertEquals(0L, config.timeoutMs)
        assertFalse(config.hasTimeout)
    }

    @Test
    fun `timeout basura cae al default`() {
        val xml = """<Parameters><Param Key="KOAJ_TIMEOUT_MS" Value="pronto"/></Parameters>"""
        assertEquals(
            LoyaltyConfig.DEFAULT_TIMEOUT_MS,
            LoyaltyConfig.fromParametersXml(xml).timeoutMs
        )
    }

    @Test
    fun `lee el formato con Value como hijo`() {
        val xml = """<Parameters><Param Key="KOAJ_ENABLED">false</Param></Parameters>"""
        assertFalse(LoyaltyConfig.fromParametersXml(xml).enabled)
    }

    @Test
    fun `convive con los parametros de HKA en el mismo xml`() {
        val xml = """
            <Parameters>
              <Param Key="HKA_BASE_URL" Value="https://emision21-api.thefactoryhka.com.co"/>
              <Param Key="HKA_TIMEOUT_MS" Value="5000"/>
              <Param Key="KOAJ_ENABLED" Value="true"/>
              <Param Key="KOAJ_TIMEOUT_MS" Value="10000"/>
              <Param Key="KOAJ_AUDIT_ENABLED" Value="false"/>
            </Parameters>
        """.trimIndent()
        val config = LoyaltyConfig.fromParametersXml(xml)
        assertTrue(config.enabled)
        assertEquals(10_000L, config.timeoutMs)
        assertFalse(config.auditEnabled)
    }

    @Test
    fun `outcome traduce a optIn correctamente`() {
        assertTrue(LoyaltyOutcome.ACCEPTED.optIn)
        assertFalse(LoyaltyOutcome.DECLINED.optIn)
        assertFalse(LoyaltyOutcome.TIMEOUT.optIn)
    }
}
