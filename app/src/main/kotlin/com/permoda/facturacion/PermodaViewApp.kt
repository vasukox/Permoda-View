package com.permoda.facturacion

import android.app.Application
import com.permoda.facturacion.core.config.ConfigStore

/**
 * Composition root (KISS). Expone las dependencias singleton
 * del proyecto, instanciadas de forma perezosa.
 */
class PermodaViewApp : Application() {

    val configStore: ConfigStore by lazy { ConfigStore(this) }
}
