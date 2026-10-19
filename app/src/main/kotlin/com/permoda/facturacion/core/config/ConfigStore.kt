package com.permoda.facturacion.core.config

import android.content.Context
import android.content.SharedPreferences
import com.permoda.facturacion.core.model.ModuleConfig

/**
 * Almacén tipado de configuración persistida.
 *
 * DRY: única responsable de hablar con [SharedPreferences].
 * El resto del código consume siempre [ModuleConfig] (snapshot inmutable).
 */
class ConfigStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val token: String
        get() = prefs.getString(KEY_TOKEN, "").orEmpty()

    val parametersXml: String
        get() = prefs.getString(KEY_PARAMETERS, "").orEmpty()

    val shopXml: String
        get() = prefs.getString(KEY_SHOP, "").orEmpty()

    /** Tipo de licencia HioPos (Basic, Retail, …). Informativo. */
    val license: String
        get() = prefs.getString(KEY_LICENSE, "").orEmpty()

    fun setToken(value: String) =
        prefs.edit().putString(KEY_TOKEN, value).apply()

    fun setParameters(value: String) =
        prefs.edit().putString(KEY_PARAMETERS, value).apply()

    fun setShop(value: String) =
        prefs.edit().putString(KEY_SHOP, value).apply()

    fun setLicense(value: String) =
        prefs.edit().putString(KEY_LICENSE, value).apply()

    fun clear() = prefs.edit().clear().apply()

    fun snapshot(): ModuleConfig = ModuleConfig(
        token = token,
        parametersXml = parametersXml,
        shopXml = shopXml,
        license = license
    )

    private companion object {
        const val PREFS_NAME = "permodaview"
        const val KEY_TOKEN = "token"
        const val KEY_PARAMETERS = "parameters_xml"
        const val KEY_SHOP = "shop_xml"
        const val KEY_LICENSE = "license"
    }
}
