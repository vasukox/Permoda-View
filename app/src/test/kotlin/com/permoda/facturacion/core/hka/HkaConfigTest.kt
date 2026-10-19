package com.permoda.facturacion.core.hka

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HkaConfigTest {

    @Test
    fun `defaults apuntan a produccion y todo habilitado`() {
        val c = HkaConfig.defaults()
        assertEquals(HkaConfig.URL_PRODUCCION, c.baseUrl)
        // ⚠️ 14-08-2026: timeout default subió de 5 s a 15 s. Si la DIAN no
        // responde en 15 s, el flujo cae al formulario manual sin mostrar
        // pantalla de "validando" al cliente (cambió en el mismo turno).
        assertEquals(15000, c.timeoutMs)
        assertEquals(HkaConfig.FallbackMode.HIBRIDO, c.fallbackMode)
        assertTrue(c.enabled)
        assertEquals(0, c.cacheMinutes)
        assertTrue(c.auditEnabled)
        assertTrue(c.isActive)
    }

    @Test
    fun `XML vacio devuelve defaults`() {
        val c = HkaConfig.fromParametersXml("")
        assertEquals(HkaConfig.defaults().baseUrl, c.baseUrl)
        assertEquals(HkaConfig.defaults().timeoutMs, c.timeoutMs)
        assertEquals(HkaConfig.defaults().fallbackMode, c.fallbackMode)
    }

    @Test
    fun `XML null devuelve defaults`() {
        val c = HkaConfig.fromParametersXml(null)
        assertEquals(HkaConfig.defaults(), c)
    }

    @Test
    fun `parsea XML con todos los parametros`() {
        val xml = """
            <Configuration>
              <Parameters>
                <Param Key="HKA_BASE_URL">https://demoemision21-api.thefactoryhka.com.co</Param>
                <Param Key="HKA_TIMEOUT_MS">3000</Param>
                <Param Key="HKA_ENABLED">FALSE</Param>
                <Param Key="HKA_FALLBACK_MODE">CONSERVADOR</Param>
                <Param Key="HKA_CACHE_MINUTES">30</Param>
                <Param Key="HKA_AUDIT_ENABLED">FALSE</Param>
              </Parameters>
            </Configuration>
        """.trimIndent()
        val c = HkaConfig.fromParametersXml(xml)
        assertEquals("https://demoemision21-api.thefactoryhka.com.co", c.baseUrl)
        assertEquals(3000, c.timeoutMs)
        assertFalse(c.enabled)
        assertEquals(HkaConfig.FallbackMode.CONSERVADOR, c.fallbackMode)
        assertEquals(30, c.cacheMinutes)
        assertFalse(c.auditEnabled)
    }

    @Test
    fun `fallback mode PERMISIVO`() {
        val xml = """<Config><Param Key="HKA_FALLBACK_MODE" Value="PERMISIVO"/></Config>"""
        assertEquals(HkaConfig.FallbackMode.PERMISIVO, HkaConfig.fromParametersXml(xml).fallbackMode)
    }

    @Test
    fun `fallback mode HIBRIDO acepta tilde`() {
        val xml = """<Config><Param Key="HKA_FALLBACK_MODE" Value="HÍBRIDO"/></Config>"""
        assertEquals(HkaConfig.FallbackMode.HIBRIDO, HkaConfig.fromParametersXml(xml).fallbackMode)
    }

    @Test
    fun `fallback mode desconocido cae a default`() {
        val xml = """<Config><Param Key="HKA_FALLBACK_MODE" Value="XYZ"/></Config>"""
        assertEquals(HkaConfig.FallbackMode.HIBRIDO, HkaConfig.fromParametersXml(xml).fallbackMode)
    }

    @Test
    fun `enabled FALSE hace isActive false`() {
        val xml = """<Config><Param Key="HKA_ENABLED" Value="FALSE"/></Config>"""
        assertFalse(HkaConfig.fromParametersXml(xml).isActive)
    }

    @Test
    fun `timeout se coerce a minimo 1000 ms`() {
        val xml = """<Config><Param Key="HKA_TIMEOUT_MS" Value="100"/></Config>"""
        assertEquals(1000, HkaConfig.fromParametersXml(xml).timeoutMs)
    }

    @Test
    fun `timeout se coerce a maximo 30000 ms`() {
        val xml = """<Config><Param Key="HKA_TIMEOUT_MS" Value="99999"/></Config>"""
        assertEquals(30000, HkaConfig.fromParametersXml(xml).timeoutMs)
    }

    @Test
    fun `timeout malformado cae a default`() {
        val xml = """<Config><Param Key="HKA_TIMEOUT_MS" Value="abc"/></Config>"""
        // Desde el 14-08-2026 el default es 15000.
        assertEquals(15000, HkaConfig.fromParametersXml(xml).timeoutMs)
    }

    @Test
    fun `cacheMinutes negativo se coerce a 0`() {
        val xml = """<Config><Param Key="HKA_CACHE_MINUTES" Value="-5"/></Config>"""
        assertEquals(0, HkaConfig.fromParametersXml(xml).cacheMinutes)
    }

    @Test
    fun `isActive rechaza baseUrl vacia aunque enabled sea true`() {
        val c = HkaConfig(
            baseUrl = "",
            timeoutMs = 5000,
            fallbackMode = HkaConfig.FallbackMode.HIBRIDO,
            enabled = true,
            cacheMinutes = 0,
            auditEnabled = true
        )
        assertFalse(c.isActive)
    }

    @Test
    fun `isActive rechaza HTTP para no enviar datos en claro`() {
        val c = HkaConfig.defaults().copy(baseUrl = "http://10.0.0.1:8080")
        assertFalse(c.isActive)
    }
}
