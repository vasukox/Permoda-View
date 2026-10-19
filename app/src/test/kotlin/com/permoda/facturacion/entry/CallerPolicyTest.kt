package com.permoda.facturacion.entry

import com.permoda.facturacion.core.config.CallerPolicy
import org.junit.Assert.*
import org.junit.Test

class CallerPolicyTest {
    @Test fun `release exige llamador del sistema permitido`() {
        assertTrue(CallerPolicy.isTrusted("icg.android.start", false))
        assertFalse(CallerPolicy.isTrusted(null, false))
        assertFalse(CallerPolicy.isTrusted("attacker.app", false))
        assertFalse(CallerPolicy.isTrusted("icg.android.start.attacker", false))
    }
}
