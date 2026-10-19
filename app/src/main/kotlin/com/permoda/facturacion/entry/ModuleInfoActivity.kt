package com.permoda.facturacion.entry

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.permoda.facturacion.BuildConfig
import com.permoda.facturacion.R

/**
 * Llaves de extras que, si vienen en el Intent, gatillan el auto-reenvío al
 * driver en builds debug. Mantener sincronizado con los extras que lee
 * [TestDriverActivity].
 */
private val QA_EXTRA_KEYS = setOf(
    "mode", "customerTypeId", "customerTypeName",
    "customerId", "customerName", "email", "fiscalId", "phone"
)

/**
 * Lo que ve quien toca el ícono del módulo en la terminal.
 *
 * ## Por qué existe
 *
 * Hasta el 19-ago-2026 el ícono del launcher abría [TestDriverActivity], el
 * driver de QA. En una terminal en producción eso significa que **cualquiera
 * puede disparar un ciclo completo sin venta detrás**: el formulario aparece en
 * la pantalla del cliente, se puede confirmar, y nada de eso corresponde a una
 * factura. El driver es la herramienta correcta para QA y la equivocada para el
 * cajón de aplicaciones de una caja.
 *
 * La alternativa era quitar el ícono. Se eligió esto porque un ícono que no hace
 * nada invita a volver a tocarlo, y porque el toque accidental es la única
 * ocasión en que alguien de tienda va a tener los datos del módulo en pantalla:
 * versión, nombre del módulo y paquete. Justo los tres que soporte pide y que
 * costaron semanas de diagnóstico cuando el POS "no activaba" el módulo.
 *
 * O sea: no es un cartel de error, es la ficha del módulo.
 *
 * ## El driver sigue existiendo, pero sólo en debug
 *
 * [TestDriverActivity] pasó a `exported="false"` y perdió el filtro de launcher.
 * Se abre desde acá con un botón que **sólo aparece en debug**. Al lanzarse desde
 * la propia app no hace falta exportarla, así que ninguna otra aplicación del
 * dispositivo puede invocarla — antes sí podía.
 */
class ModuleInfoActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_module_info)

        // QA: si llegan extras de test en el Intent, reenviarlos directo a
        // TestDriverActivity y cerrar. Sin esto hay que pulsar el botón después
        // de cada `adb shell am start`, lo que un script no puede hacer.
        // Sólo en debug: en release el botón de driver sigue oculto y la
        // activity del driver queda inalcanzable desde afuera (exported=false).
        if (BuildConfig.DEBUG && hasQaExtras(intent)) {
            val forward = Intent(this, TestDriverActivity::class.java).apply {
                intent.extras?.let { putExtras(it) }
            }
            startActivity(forward)
            finish()
            return
        }

        // Ficha técnica. El `apk_name` es el dato que define el namespace de los
        // Intents y el que hay que comparar con CloudLicense cuando el POS no
        // invoca el módulo; tenerlo a un toque ahorra una sesión de logcat.
        findViewById<TextView>(R.id.info_support)?.text = getString(
            R.string.module_info_support,
            BuildConfig.VERSION_NAME,
            ApiActions.APK_NAME,
            BuildConfig.APPLICATION_ID
        )

        findViewById<Button>(R.id.info_close)?.setOnClickListener { finish() }

        // Acceso al driver de QA. En release el botón no existe: no se oculta,
        // no se deshabilita — se deja en GONE y además la activity no está
        // exportada, así que no hay forma de llegar a ella desde afuera.
        findViewById<Button>(R.id.info_open_driver)?.apply {
            if (BuildConfig.DEBUG) {
                visibility = View.VISIBLE
                setOnClickListener {
                    startActivity(Intent(this@ModuleInfoActivity, TestDriverActivity::class.java))
                }
            } else {
                visibility = View.GONE
            }
        }
    }

    private fun hasQaExtras(intent: Intent?): Boolean {
        val extras = intent?.extras ?: return false
        return QA_EXTRA_KEYS.any { extras.containsKey(it) }
    }
}
