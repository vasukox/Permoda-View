package com.permoda.facturacion.core.config

/** El llamador procede de Android; nunca de un extra controlado por la otra app. */
object CallerPolicy {
    val packages = setOf("icg.android.start", "com.icg.hiopos")
    fun isTrusted(systemCaller: String?, debug: Boolean): Boolean = debug || systemCaller in packages
}
