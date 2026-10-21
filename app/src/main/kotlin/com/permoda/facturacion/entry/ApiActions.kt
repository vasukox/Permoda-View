package com.permoda.facturacion.entry

import android.app.Activity
import android.content.Intent
import android.util.Log
import com.permoda.facturacion.BuildConfig
import android.app.Activity.RESULT_OK

/** Contrato central de acciones. Las rutas antiguas se conservan sin UI. */
object ApiActions {

    val APK_NAME: String = BuildConfig.ICG_APK_NAME

    private val NS = "icg.actions.document.$APK_NAME."

    val INITIALIZE         = NS + "INITIALIZE"
    val GET_BEHAVIOR       = NS + "GET_BEHAVIOR"
    val GET_VERSION        = NS + "GET_VERSION"
    val SELECT_CUSTOMER    = NS + "SELECT_CUSTOMER"
    val FINALIZE           = NS + "FINALIZE"

    val SHOW_SETUP_SCREEN  = NS + "SHOW_SETUP_SCREEN"
    val MODIFY_DOCUMENT    = NS + "MODIFY_DOCUMENT"
    val MANAGE_CUSTOMER    = NS + "MANAGE_CUSTOMER"

    val DECLARED_ACTIONS: List<String> get() = listOf(
        INITIALIZE, SHOW_SETUP_SCREEN, GET_BEHAVIOR, GET_VERSION, FINALIZE,
        MODIFY_DOCUMENT, SELECT_CUSTOMER, MANAGE_CUSTOMER
    )

    val ALL_ACTIONS: List<String> get() = DECLARED_ACTIONS

    val CUSTOMER_BEHAVIOUR: Map<String, Boolean> get() = mapOf(
        Behaviour.CALL_ON_CUSTOMER_SELECTION to false,
        Behaviour.MANAGE_CUSTOMER to true,
        Behaviour.CREATE_UPDATE_CUSTOMER to false
    )

    /**
     * Validación del cliente al totalizar (TotalizeValidationActivity).
     * `CanContinue=false` es lo que convierte el RESULT_CANCELED en un bloqueo:
     * con `true` HioPos 15.14.3 registra "Continue totalization (API behavior
     * with continue after fail)" y cobra igual.
     */
    val TOTALIZE_BEHAVIOUR: Map<String, Boolean> get() = mapOf(
        Behaviour.CALL_BEFORE_TOTALIZE_SALE to true,
        Behaviour.CAN_CONTINUE to false
    )
    const val EXTRA_TOKEN          = "Token"
    const val EXTRA_PARAMETERS     = "Parameters"   // String XML de configuración persistida en CloudLicense.
    const val EXTRA_SHOP           = "Shop"         // String XML con info de la tienda.
    const val EXTRA_LICENSE        = "License"      // Tipo de licencia HioPos (Basic, Retail, …).
    const val EXTRA_CUSTOMER_RESULT = "CustomerResult" // String XML con datos del cliente (SELECT_CUSTOMER).
    const val EXTRA_LANGUAGE_ISO   = "LanguageISO"  // Idioma del terminal, ISO 639-1.
    const val EXTRA_INVOICING      = "Invoicing"    // Boolean: la selección es para una factura (SELECT_CUSTOMER).

    const val EXTRA_IS_INVOICE     = "IsInvoice"
    const val EXTRA_CUSTOMER_MODE  = "CustomerMode" // Solo driver histórico; no es una señal contractual.
    const val EXTRA_DOCUMENT        = "Document"       // String XML del <Document> con <Customer> adentro.
    const val EXTRA_TRIGGER_EVENT   = "TriggerEvent"   // "TOTALIZE_SALE", "CUSTOMER_SELECTION", etc.
    const val EXTRA_DOCUMENT_URI    = "DocumentUri"    // content:// de icg.android.start.provider.
    const val EXTRA_DOCUMENT_PATH   = "DocumentPath"   // Ruta en Documents/APIExternalTraffic.
    const val RESULT_MODIFY_DOCUMENT_RESULT = "ModifyDocumentResult" // String XML; 15.14.3 también acepta …Uri/…Path.
    const val RESULT_STATUS        = "STATUS"
    const val RESULT_VERSION       = "Version"
    const val RESULT_CUSTOMER_RESULT = "CustomerResult"
    const val RESULT_ERROR_MESSAGE = "ErrorMessage"

    object Status {
        const val OK = "OK"
        const val CANCELLED = "CANCELLED"
        const val ERROR = "ERROR"
    }

    object Behaviour {
        
        const val CAN_AUDIT                              = "canAudit"
        const val REMOVE_CUSTOMER                        = "RemoveCustomer"
        const val CALL_BEFORE_TOTALIZE_SALE              = "CallBeforeTotalizeSale"
        const val CALL_BEFORE_TOTALIZE_PURCHASE          = "CallBeforeTotalizePurchase"
        const val CALL_BEFORE_TOTALIZE_ORDER             = "CallBeforeTotalizeOrder"
        const val CALL_BEFORE_FINALIZE_STOCK_ADJUSTMENT = "CallBeforeFinalizeStockAdjustment"
        const val CALL_BEFORE_FINALIZE_TRANSFER         = "CallBeforeFinalizeTransfer"
        const val CALL_BEFORE_SET_ON_HOLD               = "CallBeforeSetOnHold"
        const val IF_PRODUCT_NOT_EXISTS                  = "IfProductNotExists"
        const val CALL_ON_TOTALIZATION_CANCELED         = "CallOnTotalizationCanceled"
        const val CALL_ON_DOCUMENT_CANCELED              = "CallOnDocumentCanceled"
        const val CALL_BEFORE_PRINT                      = "CallBeforePrint"
        const val CAN_CONTINUE                          = "CanContinue"
        const val CREATE_UPDATE_CUSTOMER                 = "CreateUpdateCustomer"
        const val CALL_ON_CUSTOMER_SELECTION             = "CallOnCustomerSelection"
        const val MANAGE_CUSTOMER = "ManageCustomer"
        const val FIX_PRICELIST                         = "FixPricelist"
        const val CAN_SHOW_AS_SALE_HEADER_OPTION         = "CanShowAsSaleHeaderOption"
        const val CALL_ON_TOTALIZE_SALE_AFTER_MIX_MATCH  = "CallOnTotalizeSaleAfterMixAndMatch"
        const val CALL_AFTER_CREATE                     = "CallAfterCreate"
        const val CALL_VOID_DOCUMENT                    = "CallVoidDocument"
        const val CALL_ON_SUBTOTAL                      = "CallOnSubtotal"
    }

    val TRUSTED_POS_PACKAGES = com.permoda.facturacion.core.config.CallerPolicy.packages

    const val TRUSTED_HIOPOS_PACKAGE = "icg.android.start"
}

fun Activity.assertFromTrustedCaller() {
    val caller = callingPackageSafe()
    val trusted = com.permoda.facturacion.core.config.CallerPolicy.isTrusted(caller, BuildConfig.DEBUG)
    if (!trusted) {
        Log.w("PermodaView", "Emisor no autorizado: $caller")
        throw SecurityException("Emisor no autorizado: $caller")
    }
}

private fun Activity.callingPackageSafe(): String? = callingPackage

fun Activity.finishWithStatus(
    status: String,
    errorMessage: String? = null,
    customerResult: String? = null
) {
    val intent = Intent().apply {
        action = this@finishWithStatus.intent.action
        putExtra(ApiActions.RESULT_STATUS, status)
        errorMessage?.takeIf { it.isNotBlank() }?.let { putExtra(ApiActions.RESULT_ERROR_MESSAGE, it) }
        customerResult?.takeIf { it.isNotBlank() }?.let { putExtra(ApiActions.RESULT_CUSTOMER_RESULT, it) }
    }
    val resultCode = if (status == ApiActions.Status.OK) RESULT_OK else Activity.RESULT_CANCELED
    setResult(resultCode, intent)
    finish()
}

fun Activity.finishOk(customerResult: String? = null) =
    finishWithStatus(ApiActions.Status.OK, customerResult = customerResult)

fun Activity.finishCancelled(errorMessage: String) =
    finishWithStatus(ApiActions.Status.CANCELLED, errorMessage = errorMessage)

fun Activity.finishError(message: String) =
    finishWithStatus(ApiActions.Status.ERROR, errorMessage = message)
