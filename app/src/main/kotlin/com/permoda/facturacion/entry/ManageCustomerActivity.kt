package com.permoda.facturacion.entry

import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.permoda.facturacion.BuildConfig
import com.permoda.facturacion.PermodaViewApp
import com.permoda.facturacion.core.consent.ConsentSharedState
import com.permoda.facturacion.core.hka.CustomerMerger
import com.permoda.facturacion.core.hka.HkaAuditor
import com.permoda.facturacion.core.hka.HkaClient
import com.permoda.facturacion.core.hka.HkaConfig
import com.permoda.facturacion.core.hka.HkaResponse
import com.permoda.facturacion.core.hka.TipoDocumentoMapper
import com.permoda.facturacion.core.loyalty.LoyaltyAuditor
import com.permoda.facturacion.core.loyalty.LoyaltyConfig
import com.permoda.facturacion.core.manage.ManageCustomerConfig
import com.permoda.facturacion.core.manage.ManagedCustomerSession
import com.permoda.facturacion.core.manage.ManageCustomerTransport
import com.permoda.facturacion.presentation.consent.ConsentResult
import com.permoda.facturacion.presentation.display.ConsentPresentation
import com.permoda.facturacion.presentation.vendor.VendorScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/** Única entrada del POS que abre el formulario propio. */
class ManageCustomerActivity : AppCompatActivity() {
    private var vendor: VendorScreen? = null
    private var completed = false
    private var ownsFlow = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Conservar la ventana del vendedor y el manejo de teclado de VendorScreen.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = cancelFlow()
        })
        lifecycleScope.launch {
            try {
                assertFromTrustedCaller()
                require(intent.action == ApiActions.MANAGE_CUSTOMER) { "Acción de gestión de cliente inválida" }
                logIncomingExtras()
                val config = (application as PermodaViewApp).configStore.snapshot()
                val profile = ManageCustomerConfig.fromParametersXml(config.parametersXml)
                val session = if (intent.hasExtra(profile.inputExtra) || intent.hasExtra(profile.inputUriExtra)) {
                    val xml = withContext(Dispatchers.IO) { readCustomerXml(profile) }
                    val rawId = if (intent.hasExtra(profile.identityExtra)) {
                        @Suppress("DEPRECATION")
                        val id = intent.extras?.get(profile.identityExtra)
                        require(id is Int || id is Long || id is String) { "Tipo de identidad de cliente inválido" }
                        id.toString()
                    } else null
                    ManagedCustomerSession.parse(xml, profile, rawId)
                } else {
                    // Contrato real de HioPos 15.14.3: campos sueltos, sin XML ni customerId.
                    val extras = intent.extras
                    @Suppress("DEPRECATION")
                    val map = extras?.keySet()?.associateWith { extras.get(it) }.orEmpty()
                    ManagedCustomerSession.fromPosExtras(map, profile)
                }
                android.util.Log.i(TAG, "MANAGE_CUSTOMER operación=${session.operation}")
                require(active.compareAndSet(false, true)) { "Ya hay una gestión de cliente en curso" }
                ownsFlow = true
                val container = FrameLayout(this@ManageCustomerActivity)
                setContentView(container)
                vendor = VendorScreen(container).also { it.bind(onCancel = ::cancelFlow) }
                lifecycleScope.launch { ConsentSharedState.state.collect { vendor?.render(it) } }
                val hkaConfig = HkaConfig.fromParametersXml(config.parametersXml)
                val hka = HkaClient(hkaConfig)
                val loyalty = LoyaltyConfig.fromParametersXml(config.parametersXml)
                val result = ConsentPresentation.request(
                    context = this@ManageCustomerActivity,
                    parsed = session.parsed,
                    managedOperation = session.operation,
                    loyaltyConfig = loyalty,
                    onDocumentConfirmed = { type, number, controller ->
                        // El guard está en el dominio: ni corregir el documento ni
                        // un comando tardío pueden habilitar HKA en una edición.
                        val dianType = TipoDocumentoMapper.toDianCode(type)
                        val base = session.forDocument(dianType ?: type, number)
                        val response = if (dianType == null) HkaResponse.BadRequest("Tipo de documento no soportado")
                            else hka.consultaAdquiriente(dianType, number)
                        val merge = CustomerMerger.merge(
                            base, response as? HkaResponse.Success, hkaConfig.fallbackMode,
                            response.takeUnless { it is HkaResponse.Success }
                        )
                        HkaAuditor.emitConsulta(
                            this@ManageCustomerActivity, hkaConfig.auditEnabled, config.token,
                            dianType ?: "sin-tipo", HkaAuditor.maskDocumento(number), response, merge.source
                        )
                        controller.showCustomer(merge.customer, merge.warning, merge.dianStatus)
                    },
                    onLoyaltyOutcome = { outcome ->
                        LoyaltyAuditor.emit(this@ManageCustomerActivity, loyalty.auditEnabled, config.token,
                            outcome, ConsentSharedState.current.customer.fiscalId)
                    }
                )
                if (!completed) {
                    completed = true
                    when (result) {
                        is ConsentResult.Accepted -> {
                            val xml = session.resultXml(result.customer, result.loyaltyOptIn)
                            if (BuildConfig.DEBUG) android.util.Log.i(TAG, "CustomerResult → ${maskedSummary(xml)}")
                            finishOk(xml)
                        }
                        is ConsentResult.Rejected -> finishCancelled(result.reason)
                        is ConsentResult.Error -> finishError(result.message)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SecurityException) {
                fail("Emisor no autorizado")
            } catch (e: IllegalArgumentException) {
                fail(e.message ?: "Entrada de gestión de cliente inválida")
            } catch (e: Exception) {
                // Nunca incluir XML, datos personales o mensajes de servicios en logs.
                fail("No se pudo completar la gestión del cliente")
            }
        }
    }

    /** Solo nombres y tipos: el contrato de entrada no está documentado y los valores son datos personales. */
    private fun logIncomingExtras() {
        val extras = intent.extras
        @Suppress("DEPRECATION")
        val summary = extras?.keySet()?.sorted()?.joinToString { "$it:${extras.get(it)?.javaClass?.simpleName ?: "null"}" }
        android.util.Log.i(TAG, "MANAGE_CUSTOMER ← caller=${callingPackage ?: "desconocido"} extras=[${summary.orEmpty()}]")
    }

    /** Solo debug: claves completas; valores personales reducidos a longitud y dos caracteres. */
    private fun maskedSummary(xml: String): String {
        val plain = setOf("fiscalDocumentType", "contactTypeId", "customerTypeId", "countryIso2", "allowPromos", "searchField")
        return Regex("Key=\"([^\"]+)\">([^<]*)<").findAll(xml).joinToString { m ->
            val (key, value) = m.destructured
            if (key in plain || value.isEmpty()) "$key=$value" else "$key=${value.take(2)}…(${value.length})"
        }
    }

    private fun readCustomerXml(profile: ManageCustomerConfig): String {
        val inline = intent.getStringExtra(profile.inputExtra)
        @Suppress("DEPRECATION")
        val uri = intent.getParcelableExtra<Uri>(profile.inputUriExtra)
        return ManageCustomerTransport.read(
            inline, uri?.let { ManageCustomerTransport.Resource(it.scheme, it.authority) }, callingPackage
        ) { uri?.let { contentResolver.openInputStream(it) } }
    }

    private fun fail(message: String) {
        if (isFinishing) return
        completed = true
        finishError(message)
    }

    private fun cancelFlow() {
        if (completed) return
        completed = true
        finishCancelled("Gestión de cliente cancelada")
    }

    override fun onDestroy() {
        vendor?.dispose()
        super.onDestroy()
        if (ownsFlow) active.set(false)
    }

    private companion object {
        const val TAG = "PvManage"
        val active = AtomicBoolean(false)
    }
}
