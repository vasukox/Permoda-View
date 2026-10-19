package com.permoda.facturacion.core.model

/**
 * Snapshot inmutable de la configuración persistida del módulo.
 * KISS: un único data class evita exponer [ConfigStore] por doquier.
 */
data class ModuleConfig(
    val token: String,
    val parametersXml: String,
    val shopXml: String,
    /** Tipo de licencia HioPos (Basic, Retail, …). Informativo. */
    val license: String = ""
) {
    val isInitialized: Boolean get() = token.isNotBlank() && parametersXml.isNotBlank()
}
