package com.permoda.facturacion.core.hka

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `toFullName` sólo resolvía el código DIAN numérico. Cuando HioPosCloud
 * enviaba el alias corto ("NIT", "CE"), la pantalla mostraba el alias en
 * crudo y el selector caía al primer tipo de la lista — un NIT se pintaba
 * como "Cédula de ciudadanía".
 */
class TipoDocumentoMapperToFullNameTest {

    @Test
    fun `resuelve el codigo DIAN numerico`() {
        assertEquals("Cédula de ciudadanía", TipoDocumentoMapper.toFullName("13"))
        assertEquals("NIT", TipoDocumentoMapper.toFullName("31"))
        assertEquals("Pasaporte", TipoDocumentoMapper.toFullName("41"))
    }

    @Test
    fun `resuelve el alias corto de HioPosCloud`() {
        assertEquals("Cédula de ciudadanía", TipoDocumentoMapper.toFullName("CC"))
        assertEquals("NIT", TipoDocumentoMapper.toFullName("NIT"))
        assertEquals("Cédula de extranjería", TipoDocumentoMapper.toFullName("CE"))
        assertEquals("Tarjeta de identidad", TipoDocumentoMapper.toFullName("TI"))
    }

    @Test
    fun `el alias corto es insensible a mayusculas y espacios`() {
        assertEquals("Cédula de ciudadanía", TipoDocumentoMapper.toFullName(" cc "))
        assertEquals("NIT", TipoDocumentoMapper.toFullName("nit"))
    }

    @Test
    fun `un nombre completo ya resuelto se devuelve tal cual`() {
        assertEquals(
            "Cédula de ciudadanía",
            TipoDocumentoMapper.toFullName("Cédula de ciudadanía")
        )
    }

    @Test
    fun `un NIT no se confunde con cedula de ciudadania`() {
        // Regresión directa del bug: el fallback del selector era
        // supportedFullNames().first(), es decir cédula de ciudadanía.
        val resuelto = TipoDocumentoMapper.toFullName("NIT")
        assertEquals("NIT", resuelto)
        assertEquals("31", TipoDocumentoMapper.toDianCode(resuelto))
    }

    @Test
    fun `devuelve null para valores desconocidos o vacios`() {
        assertNull(TipoDocumentoMapper.toFullName(null))
        assertNull(TipoDocumentoMapper.toFullName(""))
        assertNull(TipoDocumentoMapper.toFullName("   "))
        assertNull(TipoDocumentoMapper.toFullName("ZZ"))
    }

    @Test
    fun `roundtrip nombre corto a completo a codigo DIAN`() {
        mapOf(
            "RC" to "11", "TI" to "12", "CC" to "13", "TE" to "21",
            "CE" to "22", "NIT" to "31", "PAS" to "41", "DE" to "42",
            "PEP" to "47", "PPT" to "48", "NE" to "50", "NUIP" to "91"
        ).forEach { (corto, dian) ->
            val completo = TipoDocumentoMapper.toFullName(corto)
            assertEquals("no resolvió '$corto'", dian, TipoDocumentoMapper.toDianCode(completo))
        }
    }
}
