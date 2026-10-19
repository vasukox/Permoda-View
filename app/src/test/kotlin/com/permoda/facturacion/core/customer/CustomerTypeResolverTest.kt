package com.permoda.facturacion.core.customer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La tabla de reglas de negocio del tipo de cliente, ejecutable.
 *
 * Existe porque la regla no se lee del flujo: está repartida entre la decisión
 * de Koaj Lovers (al final), el tipo que trajo el POS (al principio) y una
 * elección del cajero que sólo aparece para empleados. Cada vez que se movió
 * alguna de las tres, algo se rompió en silencio — el `customerTypeId` que se
 * perdía en el round-trip vivió semanas sin que nadie lo notara.
 */
class CustomerTypeResolverTest {

    private val VIP = CustomerType.VIP.id           // 1
    private val EMPLEADO = CustomerType.EMPLEADO.id // 2

    // ------------------------------------------------------------------
    // Cliente nuevo / sin tipo: la base de todo
    // ------------------------------------------------------------------

    @Test
    fun `cliente nuevo que acepta Koaj queda VIP`() {
        assertEquals(
            CustomerType.VIP,
            CustomerTypeResolver.resolve(
                incomingTypeId = null,
                cashierEmployeeChoice = null,
                loyaltyOptIn = true,
                isConsumidorFinal = false
            )
        )
    }

    @Test
    fun `cliente nuevo que NO acepta queda Estandar`() {
        // `false` (declinó explícito) y `null` (no se le preguntó, p.ej. Koaj
        // apagado desde CloudLicense) dan lo mismo para el tipo. La diferencia
        // sólo importa para la auditoría.
        listOf(false, null).forEach { optIn ->
            assertEquals(
                "optIn=$optIn debe dejarlo Estándar",
                CustomerType.ESTANDAR,
                CustomerTypeResolver.resolve(null, null, optIn, isConsumidorFinal = false)
            )
        }
    }

    @Test
    fun `un timeout no promueve a VIP`() {
        // `LoyaltyOutcome.TIMEOUT.optIn` es false: el cliente que se dio la
        // vuelta no queda inscrito. La membresía tiene que ser un acto.
        assertEquals(
            CustomerType.ESTANDAR,
            CustomerTypeResolver.resolve(
                null,
                null,
                loyaltyOptIn = com.permoda.facturacion.core.loyalty.LoyaltyOutcome.TIMEOUT.optIn,
                isConsumidorFinal = false
            )
        )
    }

    // ------------------------------------------------------------------
    // VIP: vía de un solo sentido
    // ------------------------------------------------------------------

    @Test
    fun `un VIP que declina NO baja a Estandar`() {
        // Es la regla que protege al Koaj Lover que vuelve: se le muestra la
        // pantalla otra vez, pero no tocar el botón no le quita la membresía.
        listOf(true, false, null).forEach { optIn ->
            assertEquals(
                "un VIP con optIn=$optIn sigue VIP",
                CustomerType.VIP,
                CustomerTypeResolver.resolve(VIP, null, optIn, isConsumidorFinal = false)
            )
        }
    }

    @Test
    fun `al VIP si se le ofrece Koaj Lovers`() {
        // Antes se saltaba, cuando VIP era "cliente especial de la casa". Hoy
        // significa "ya es Koaj Lover" y la pantalla se le muestra igual.
        assertTrue(CustomerTypeResolver.offersKoajLovers(VIP, null, isConsumidorFinal = false))
    }

    // ------------------------------------------------------------------
    // Empleado
    // ------------------------------------------------------------------

    @Test
    fun `a un empleado jamas se le ofrece Koaj Lovers`() {
        assertFalse(
            CustomerTypeResolver.offersKoajLovers(EMPLEADO, null, isConsumidorFinal = false)
        )
        assertFalse(
            CustomerTypeResolver.offersKoajLovers(
                EMPLEADO,
                CustomerType.EMPLEADO,
                isConsumidorFinal = false
            )
        )
    }

    @Test
    fun `un empleado sin decision del cajero sigue siendo empleado`() {
        // Default seguro: degradar a Estándar tiene que ser un acto explícito,
        // porque cambia el tipo en el maestro y además le habilita Koaj.
        assertEquals(
            CustomerType.EMPLEADO,
            CustomerTypeResolver.resolve(EMPLEADO, null, loyaltyOptIn = null, isConsumidorFinal = false)
        )
    }

    @Test
    fun `un empleado conserva su categoria con cualquier eleccion o respuesta`() {
        listOf(null, CustomerType.ESTANDAR, CustomerType.EMPLEADO, CustomerType.VIP).forEach { choice ->
            listOf(null, true, false).forEach { answer ->
                assertFalse(CustomerTypeResolver.offersKoajLovers(EMPLEADO, choice, false))
                assertEquals(CustomerType.EMPLEADO, CustomerTypeResolver.resolve(EMPLEADO, choice, answer, false))
            }
        }
    }

    @Test
    fun `la eleccion del cajero se IGNORA si el cliente no era empleado`() {
        // Blindaje: si la elección se filtrara desde la pantalla de captura para
        // un cliente que no es empleado, no debe poder degradar a un VIP ni
        // convertir en empleado a quien no lo es.
        assertEquals(
            "un VIP no se degrada por la elección del cajero",
            CustomerType.VIP,
            CustomerTypeResolver.resolve(VIP, CustomerType.ESTANDAR, false, isConsumidorFinal = false)
        )
        assertEquals(
            "nadie se vuelve empleado sin que el POS lo diga",
            CustomerType.VIP,
            CustomerTypeResolver.resolve(null, CustomerType.EMPLEADO, true, isConsumidorFinal = false)
        )
    }

    @Test
    fun `el cajero no puede cambiar la categoria de empleado`() {
        assertFalse(CustomerTypeResolver.offersEmployeeChoice(EMPLEADO))
        listOf(null, 0, VIP, 3, 4, 5, 99).forEach { id ->
            assertFalse("id=$id no debe abrir la elección", CustomerTypeResolver.offersEmployeeChoice(id))
        }
    }

    // ------------------------------------------------------------------
    // Consumidor Final
    // ------------------------------------------------------------------

    @Test
    fun `el Consumidor Final siempre es Estandar y nunca ve Koaj`() {
        // Gana sobre todo lo demás: no tiene sentido inscribir en una comunidad
        // de descuentos a un cliente sin identidad, ni marcarlo VIP.
        assertEquals(
            CustomerType.ESTANDAR,
            CustomerTypeResolver.resolve(VIP, CustomerType.EMPLEADO, true, isConsumidorFinal = true)
        )
        assertFalse(
            CustomerTypeResolver.offersKoajLovers(VIP, null, isConsumidorFinal = true)
        )
    }

    // ------------------------------------------------------------------
    // Consecuencia aceptada del enum de 3 tipos
    // ------------------------------------------------------------------

    @Test
    fun `los tipos 3 4 y 5 del maestro se normalizan`() {
        // Decisión de negocio del 19-ago-2026 documentada en `CustomerType`:
        // `Atención especial`, `CLIENTE TIENDA` y `CLIENTE ECOMMERCE` no están en
        // el enum, así que se leen como "sin tipo" y se sobrescriben. Este test
        // NO defiende la regla, la fija: si algún día hay que preservarlos, va a
        // fallar y ese es el aviso de que hay que agregarlos al enum.
        listOf(3, 4, 5).forEach { id ->
            assertEquals(
                "id=$id acepta Koaj ⇒ VIP",
                CustomerType.VIP,
                CustomerTypeResolver.resolve(id, null, true, isConsumidorFinal = false)
            )
            assertEquals(
                "id=$id declina ⇒ Estándar",
                CustomerType.ESTANDAR,
                CustomerTypeResolver.resolve(id, null, false, isConsumidorFinal = false)
            )
        }
    }

    @Test
    fun `el enum tiene exactamente los tres tipos que usa Permoda`() {
        assertEquals(
            listOf(CustomerType.ESTANDAR, CustomerType.VIP, CustomerType.EMPLEADO),
            CustomerType.supportedTypes
        )
        assertEquals(listOf(0, 1, 2), CustomerType.supportedTypes.map { it.id })
    }

    // ------------------------------------------------------------------
    // Proyección: lo que el cajero ve en el formulario
    // ------------------------------------------------------------------

    private fun proyectar(
        incoming: Int? = null,
        eleccion: CustomerType? = null,
        optIn: Boolean? = null,
        koajActive: Boolean = true,
        koajDecided: Boolean = false,
        cf: Boolean = false
    ) = CustomerTypeResolver.project(incoming, eleccion, optIn, koajActive, koajDecided, cf)

    @Test
    fun `mientras el formulario esta abierto el tipo NO esta decidido`() {
        // Es la razón de existir de `pendingKoajDecision`: mostrarle "Estándar" a
        // secas al cajero sería mentir por omisión — a ese cliente le falta un
        // paso para ser VIP y el cajero tiene que poder verlo.
        val p = proyectar()
        assertEquals(CustomerType.ESTANDAR, p.type)
        assertTrue("todavía puede subir a VIP", p.pendingKoajDecision)
    }

    @Test
    fun `una vez decidido Koaj el tipo es definitivo`() {
        val acepto = proyectar(optIn = true, koajDecided = true)
        assertEquals(CustomerType.VIP, acepto.type)
        assertFalse(acepto.pendingKoajDecision)

        val declino = proyectar(optIn = false, koajDecided = true)
        assertEquals(CustomerType.ESTANDAR, declino.type)
        assertFalse("ya decidió: no puede subir", declino.pendingKoajDecision)
    }

    @Test
    fun `un VIP entrante nunca aparece como pendiente`() {
        // No "puede subir": ya está arriba, y la vía de un solo sentido garantiza
        // que tampoco baja. Para el cajero es un tipo cerrado desde el principio.
        assertEquals(
            CustomerType.VIP,
            proyectar(incoming = VIP).type
        )
        assertFalse(proyectar(incoming = VIP).pendingKoajDecision)
    }

    @Test
    fun `un empleado nunca aparece como pendiente`() {
        val p = proyectar(incoming = EMPLEADO)
        assertEquals(CustomerType.EMPLEADO, p.type)
        assertFalse("no va a ver la pantalla, no hay nada pendiente", p.pendingKoajDecision)
    }

    @Test
    fun `el empleado nunca queda pendiente de cambiar a VIP`() {
        val p = proyectar(incoming = EMPLEADO, eleccion = CustomerType.ESTANDAR)
        assertEquals(CustomerType.EMPLEADO, p.type)
        assertFalse(p.pendingKoajDecision)
    }

    @Test
    fun `con Koaj apagado nada queda pendiente`() {
        // `KOAJ_ENABLED=false` desde CloudLicense: no hay decisión que esperar, así
        // que el cajero tiene que ver Estándar firme y no una promesa que nunca
        // va a cumplirse.
        val p = proyectar(koajActive = false)
        assertEquals(CustomerType.ESTANDAR, p.type)
        assertFalse(p.pendingKoajDecision)
    }

    @Test
    fun `el Consumidor Final no queda pendiente`() {
        val p = proyectar(incoming = VIP, cf = true)
        assertEquals(CustomerType.ESTANDAR, p.type)
        assertFalse(p.pendingKoajDecision)
    }

    @Test
    fun `la proyeccion nunca discrepa de lo que se envia`() {
        // El invariante que sostiene el apartado del formulario: lo que el cajero
        // ve tiene que ser exactamente lo que viaja en el ModifyDocumentResult.
        // Se recorre el producto de todas las combinaciones posibles.
        val incomings = listOf(null, 0, VIP, EMPLEADO, 3, 99)
        val elecciones = listOf(null, CustomerType.ESTANDAR, CustomerType.EMPLEADO)
        val optIns = listOf(null, true, false)

        incomings.forEach { incoming ->
            elecciones.forEach { eleccion ->
                optIns.forEach { optIn ->
                    listOf(true, false).forEach { cf ->
                        val proyectado = CustomerTypeResolver.project(
                            incoming, eleccion, optIn,
                            koajActive = true, koajDecided = true, isConsumidorFinal = cf
                        )
                        val enviado = CustomerTypeResolver.resolve(incoming, eleccion, optIn, cf)
                        assertEquals(
                            "incoming=$incoming elección=$eleccion optIn=$optIn cf=$cf",
                            enviado,
                            proyectado.type
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `solo el Empleado salta Koaj Lovers`() {
        // Las banderas están invertidas respecto a la versión anterior del enum
        // (antes saltaba el VIP). Se fija acá para que un `git revert` distraído
        // no las devuelva sin que nadie se entere.
        assertEquals(
            listOf(CustomerType.EMPLEADO),
            CustomerType.supportedTypes.filter { it.skipKoajLovers }
        )
    }
}
