package com.permoda.facturacion.core.hka

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TipoDocumentoMapperFullNameTest {

    @Test
    fun `toDianCode traduce nombres completos a codigos DIAN`() {
        assertEquals("13", TipoDocumentoMapper.toDianCode("Cédula de ciudadanía"))
        assertEquals("12", TipoDocumentoMapper.toDianCode("Tarjeta de identidad"))
        assertEquals("31", TipoDocumentoMapper.toDianCode("NIT"))
        assertEquals("22", TipoDocumentoMapper.toDianCode("Cédula de extranjería"))
        assertEquals("41", TipoDocumentoMapper.toDianCode("Pasaporte"))
        assertEquals("11", TipoDocumentoMapper.toDianCode("Registro civil"))
    }

    @Test
    fun `toDianCode acepta nombres cortos legacy`() {
        assertEquals("13", TipoDocumentoMapper.toDianCode("CC"))
        assertEquals("31", TipoDocumentoMapper.toDianCode("NIT"))
        assertEquals("22", TipoDocumentoMapper.toDianCode("CE"))
    }

    @Test
    fun `toDianCode acepta codigos numericos`() {
        assertEquals("13", TipoDocumentoMapper.toDianCode("13"))
        assertEquals("31", TipoDocumentoMapper.toDianCode("31"))
    }

    @Test
    fun `toDianCode devuelve null para entrada null o vacia`() {
        assertNull(TipoDocumentoMapper.toDianCode(null))
        assertNull(TipoDocumentoMapper.toDianCode(""))
        assertNull(TipoDocumentoMapper.toDianCode("   "))
    }

    @Test
    fun `toDianCode devuelve null para tipo desconocido`() {
        assertNull(TipoDocumentoMapper.toDianCode("Tipo inventado"))
        assertNull(TipoDocumentoMapper.toDianCode("XYZ"))
    }

    @Test
    fun `toFullName traduce codigos DIAN a nombres completos`() {
        assertEquals("Cédula de ciudadanía", TipoDocumentoMapper.toFullName("13"))
        assertEquals("Tarjeta de identidad", TipoDocumentoMapper.toFullName("12"))
        assertEquals("NIT", TipoDocumentoMapper.toFullName("31"))
        assertEquals("Cédula de extranjería", TipoDocumentoMapper.toFullName("22"))
        assertEquals("Pasaporte", TipoDocumentoMapper.toFullName("41"))
    }

    @Test
    fun `toFullName devuelve null para entrada null o vacia`() {
        assertNull(TipoDocumentoMapper.toFullName(null))
        assertNull(TipoDocumentoMapper.toFullName(""))
        assertNull(TipoDocumentoMapper.toFullName("   "))
    }

    @Test
    fun `toFullName devuelve null para codigo desconocido`() {
        assertNull(TipoDocumentoMapper.toFullName("99"))
    }

    @Test
    fun `supportedFullNames contiene los tipos principales de Colombia`() {
        val names = TipoDocumentoMapper.supportedFullNames()
        assertTrue("Debe incluir Cédula de ciudadanía", "Cédula de ciudadanía" in names)
        assertTrue("Debe incluir Tarjeta de identidad", "Tarjeta de identidad" in names)
        assertTrue("Debe incluir NIT", "NIT" in names)
        assertTrue("Debe incluir Cédula de extranjería", "Cédula de extranjería" in names)
        assertTrue("Debe incluir Pasaporte", "Pasaporte" in names)
    }

    @Test
    fun `supportedFullNames empieza con Cédula de ciudadanía porque es lo mas comun`() {
        val names = TipoDocumentoMapper.supportedFullNames()
        assertEquals("Cédula de ciudadanía", names.first())
    }

    @Test
    fun `roundtrip fullName hacia codigo DIAN funciona`() {
        assertEquals("13", TipoDocumentoMapper.toDianCode(
            TipoDocumentoMapper.toFullName("13")
        ))
        assertEquals("31", TipoDocumentoMapper.toDianCode(
            TipoDocumentoMapper.toFullName("31")
        ))
    }

    @Test
    fun `roundtrip shortName legacy hacia codigo DIAN funciona`() {
        // CC (legacy) -> 13 -> Cédula de ciudadanía -> 13
        assertEquals("13", TipoDocumentoMapper.toDianCode(
            TipoDocumentoMapper.toFullName(
                TipoDocumentoMapper.toDianCode("CC") ?: error("")
            )
        ))
    }
}
