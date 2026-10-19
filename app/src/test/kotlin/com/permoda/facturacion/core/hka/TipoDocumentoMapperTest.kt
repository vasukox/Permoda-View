package com.permoda.facturacion.core.hka

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TipoDocumentoMapperTest {

    @Test
    fun `mapea CC a codigo DIAN 13`() {
        assertEquals("13", TipoDocumentoMapper.toDianCode("CC"))
    }

    @Test
    fun `mapea NIT a codigo DIAN 31`() {
        assertEquals("31", TipoDocumentoMapper.toDianCode("NIT"))
    }

    @Test
    fun `mapea CE a codigo DIAN 22`() {
        assertEquals("22", TipoDocumentoMapper.toDianCode("CE"))
    }

    @Test
    fun `mapea TI a codigo DIAN 12`() {
        assertEquals("12", TipoDocumentoMapper.toDianCode("TI"))
    }

    @Test
    fun `mapea PS y PAS ambos a codigo 41`() {
        assertEquals("41", TipoDocumentoMapper.toDianCode("PS"))
        assertEquals("41", TipoDocumentoMapper.toDianCode("PAS"))
    }

    @Test
    fun `mapea case-insensitive`() {
        assertEquals("13", TipoDocumentoMapper.toDianCode("cc"))
        assertEquals("31", TipoDocumentoMapper.toDianCode("nit"))
    }

    @Test
    fun `acepta codigo DIAN numerico ya conocido`() {
        assertEquals("13", TipoDocumentoMapper.toDianCode("13"))
        assertEquals("31", TipoDocumentoMapper.toDianCode("31"))
    }

    @Test
    fun `pasa codigos numericos desconocidos tal cual`() {
        // Si HioPosCloud ya manda un código numérico, se acepta aunque no esté en la lista
        assertEquals("99", TipoDocumentoMapper.toDianCode("99"))
    }

    @Test
    fun `devuelve null para entrada null o vacia`() {
        assertNull(TipoDocumentoMapper.toDianCode(null))
        assertNull(TipoDocumentoMapper.toDianCode(""))
        assertNull(TipoDocumentoMapper.toDianCode("   "))
    }

    @Test
    fun `devuelve null y no lanza para tipo desconocido`() {
        assertNull(TipoDocumentoMapper.toDianCode("XYZ"))
        assertNull(TipoDocumentoMapper.toDianCode("DNI_EXTRAÑO"))
    }

    @Test
    fun `tolera espacios al inicio y final`() {
        assertEquals("13", TipoDocumentoMapper.toDianCode("  CC  "))
    }

    @Test
    fun `isValidDianCode acepta codigos conocidos`() {
        assertTrue(TipoDocumentoMapper.isValidDianCode("13"))
        assertTrue(TipoDocumentoMapper.isValidDianCode("31"))
        assertTrue(TipoDocumentoMapper.isValidDianCode("91"))
    }

    @Test
    fun `isValidDianCode rechaza codigos desconocidos`() {
        assertEquals(false, TipoDocumentoMapper.isValidDianCode("99"))
        assertEquals(false, TipoDocumentoMapper.isValidDianCode(null))
    }

    @Test
    fun `supportedCodes incluye los 12 codigos estandar`() {
        val codes = TipoDocumentoMapper.supportedCodes()
        assertEquals(12, codes.size) // 12 códigos DIAN únicos (PS y PAS colisionan en 41)
        assertTrue("11" in codes)
        assertTrue("91" in codes)
    }

    // ---------------------------------------------------------------------
    // SONDA FRENTE 3 — código HioPos (no DIAN). Confirmado en terminal
    // C9H el 13-08-2026. Si en el futuro se confirma con ICG, este mapeo
    // se vuelve "la fuente de verdad" y `toDianCode` se reemplaza.
    // ---------------------------------------------------------------------

    @Test
    fun `mapea CC a codigo HioPos 9`() {
        assertEquals("9", TipoDocumentoMapper.toHioPosCode("CC"))
        assertEquals("9", TipoDocumentoMapper.toHioPosCode("Cédula de ciudadanía"))
    }

    @Test
    fun `mapea CE a codigo HioPos 11`() {
        assertEquals("11", TipoDocumentoMapper.toHioPosCode("CE"))
        assertEquals("11", TipoDocumentoMapper.toHioPosCode("Cédula de extranjería"))
    }

    @Test
    fun `mapea NIT a codigo HioPos 12`() {
        assertEquals("12", TipoDocumentoMapper.toHioPosCode("NIT"))
    }

    @Test
    fun `mapea TI a codigo HioPos 8`() {
        assertEquals("8", TipoDocumentoMapper.toHioPosCode("TI"))
        assertEquals("8", TipoDocumentoMapper.toHioPosCode("Tarjeta de identidad"))
    }

    @Test
    fun `mapea RC a codigo HioPos 7`() {
        assertEquals("7", TipoDocumentoMapper.toHioPosCode("RC"))
    }

    @Test
    fun `mapea PAS y PS a codigo HioPos 65`() {
        assertEquals("65", TipoDocumentoMapper.toHioPosCode("PAS"))
        assertEquals("65", TipoDocumentoMapper.toHioPosCode("PS"))
        assertEquals("65", TipoDocumentoMapper.toHioPosCode("Pasaporte"))
    }

    @Test
    fun `mapea PEP a codigo HioPos 47`() {
        assertEquals("47", TipoDocumentoMapper.toHioPosCode("PEP"))
    }

    @Test
    fun `mapea PTP y PPT a codigo HioPos 64`() {
        // El usuario renombró el nombre canónico en HioPos a "PTP"
        // (no "PPT"). Aceptamos ambos alias por compatibilidad.
        assertEquals("64", TipoDocumentoMapper.toHioPosCode("PTP"))
        assertEquals("64", TipoDocumentoMapper.toHioPosCode("PPT"))
    }

    @Test
    fun `toFullName devuelve PTP canónico`() {
        // El nombre que se muestra al usuario es el canónico de HioPos.
        assertEquals("PTP", TipoDocumentoMapper.toFullName("PPT"))
        assertEquals("PTP", TipoDocumentoMapper.toFullName("PTP"))
        assertEquals("PTP", TipoDocumentoMapper.toFullName("48")) // DIAN code
    }

    @Test
    fun `acepta codigo HioPos numerico via nombre o alias`() {
        // Si el caller quiere explícitamente un código HioPos que colisiona
        // con uno DIAN, tiene que pasar el nombre o el alias — porque
        // pasar el código de 2 dígitos se interpreta como DIAN (prioridad).
        // Ejemplo: "12" es HioPos NIT pero también DIAN TI, así que sin
        // nombre se va por DIAN.
        assertEquals("12", TipoDocumentoMapper.toHioPosCode("NIT"))    // HioPos NIT
        assertEquals("9",  TipoDocumentoMapper.toHioPosCode("CC"))     // HioPos CC
        assertEquals("65", TipoDocumentoMapper.toHioPosCode("Pasaporte")) // HioPos Pasaporte
    }

    @Test
    fun `acepta codigo DIAN numerico y lo convierte a HioPos`() {
        // Atajo: si llega un código DIAN (2 dígitos) lo traduce por nombre
        // y luego a código HioPos. Va ANTES del chequeo directo de HioPos
        // para resolver el conflicto "13" (DIAN 13=CC, HioPos 13=DE).
        assertEquals("9", TipoDocumentoMapper.toHioPosCode("13"))   // CC   (DIAN 13 → 9 HioPos)
        assertEquals("11", TipoDocumentoMapper.toHioPosCode("22"))  // CE   (DIAN 22 → 11 HioPos)
        assertEquals("12", TipoDocumentoMapper.toHioPosCode("31"))  // NIT  (DIAN 31 → 12 HioPos)
        assertEquals("8", TipoDocumentoMapper.toHioPosCode("12"))   // TI   (DIAN 12 → 8 HioPos)
        assertEquals("7", TipoDocumentoMapper.toHioPosCode("11"))   // RC   (DIAN 11 → 7 HioPos)
        assertEquals("65", TipoDocumentoMapper.toHioPosCode("41"))  // PAS  (DIAN 41 → 65 HioPos)
        assertEquals("47", TipoDocumentoMapper.toHioPosCode("47"))  // PEP  (DIAN 47 = HioPos 47, mismo)
        assertEquals("64", TipoDocumentoMapper.toHioPosCode("48"))  // PTP  (DIAN 48 → 64 HioPos)
    }

    @Test
    fun `acepta codigo HioPos numerico directamente`() {
        // Si el caller ya tiene el código HioPos confirmado, se devuelve tal cual.
        assertEquals("9", TipoDocumentoMapper.toHioPosCode("9"))     // CC HioPos
        assertEquals("65", TipoDocumentoMapper.toHioPosCode("65"))   // Pasaporte HioPos
    }

    @Test
    fun `los tipos sin confirmar ya no devuelven un numero inventado`() {
        // ⚠️ Este test antes exigía `DE → 13`, y ese 13 era **adivinado**. Peor:
        // 13 es el código DIAN de la cédula de ciudadanía, así que un documento
        // extranjero podía terminar tipificado como cédula colombiana.
        //
        // Operaciones confirmó 8 tipos contra el terminal (13-08-2026) y `DE` no
        // está entre ellos, igual que `TE`, `NE` y `NUIP`. Ahora devuelven null:
        // no mandar el tipo es recuperable —el cajero lo corrige en HioPos—,
        // mandarlo mal no, porque nadie se entera.
        //
        // Cuando ICG confirme sus números se agregan a `shortToHioPos` y este
        // test hay que actualizarlo con los valores reales.
        assertNull(TipoDocumentoMapper.toHioPosCode("DE"))
        assertNull(TipoDocumentoMapper.toHioPosCode("Documento de identificación extranjero"))
        assertNull(TipoDocumentoMapper.toHioPosCode("TE"))
        assertNull(TipoDocumentoMapper.toHioPosCode("NE"))
        assertNull(TipoDocumentoMapper.toHioPosCode("NUIP"))
    }

    @Test
    fun `toHioPosCode devuelve null para entrada null o vacia`() {
        assertNull(TipoDocumentoMapper.toHioPosCode(null))
        assertNull(TipoDocumentoMapper.toHioPosCode(""))
        assertNull(TipoDocumentoMapper.toHioPosCode("   "))
    }
}
