package com.permoda.facturacion.core.loyalty

/**
 * Cómo se cerró la pantalla de comunidad **Koaj Lovers**.
 *
 * Se distingue [DECLINED] de [TIMEOUT] porque para marketing no es lo mismo
 * un "no" explícito que un cliente que se dio la vuelta: ambos resultan en
 * `optIn = false`, pero sólo el primero es una decisión.
 */
enum class LoyaltyOutcome {
    /** El cliente pulsó el botón grande. */
    ACCEPTED,

    /** El cliente pulsó "Ahora no, gracias". */
    DECLINED,

    /** Nadie tocó la pantalla y venció [LoyaltyConfig.timeoutMs]. */
    TIMEOUT;

    val optIn: Boolean get() = this == ACCEPTED
}
