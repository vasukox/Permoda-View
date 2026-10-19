package com.permoda.facturacion.core.modify

import com.permoda.facturacion.core.hka.TipoDocumentoMapper
import com.permoda.facturacion.core.model.CustomerData
import com.permoda.facturacion.core.scenario.CustomerScenario
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

/** Codec histórico para regresiones de XML. No abre UI ni persiste categorías. */
object ModifyDocumentXml {

    private const val MAX_BYTES = 2 * 1024 * 1024

    private val builder by lazy {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isValidating = false
            isExpandEntityReferences = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }
        factory.newDocumentBuilder()
    }

    fun parseDocumentCustomer(documentXml: String): CustomerData {
        require(documentXml.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) {
            "El XML del documento excede el tamaño máximo"
        }

        val root = builder
            .parse(ByteArrayInputStream(documentXml.toByteArray(Charsets.UTF_8)))
            .documentElement

        val customer = root.descendantByName("Customer")
            ?: return CustomerData(customerName = "")

        val fields = customer.childrenByName("CustomerField")
            .associate { it.getAttribute("Key").trim() to it.text() }
        fun value(vararg keys: String): String? = keys
            .firstNotNullOfOrNull { fields[it]?.takeIf { v -> v.isNotBlank() } }

        return CustomerData(
            customerId = value("customerId", "CustomerId")
                ?.takeIf { (it.toLongOrNull() ?: 0) > 0 },
            customerName = value("customerName", "Name").orEmpty(),
            fiscalDocumentType = value("fiscalDocumentType", "FiscalIdDocType")
                ?.let { raw -> TipoDocumentoMapper.fromHioPosCode(raw) ?: raw },
            fiscalId = value("fiscalId", "FiscalId"),
            email = value("email", "Email"),
            phone = value("phone", "Phone"),
            countryIso2 = value("countryIso2", "CountryIso2"),
            customerTypeId = value("customerTypeId", "CustomerTypeId")
                ?.toIntOrNull()
                ?.takeIf { it in com.permoda.facturacion.core.customer.CustomerType.supportedTypes.map { t -> t.id } }
        )
    }

    const val TRIGGER_CUSTOMER_SELECTION = "CUSTOMER_SELECTION"

    fun rawCustomerFragment(documentXml: String): String? {
        val start = documentXml.indexOf("<Customer>")
        if (start < 0) return null
        val end = documentXml.indexOf("</Customer>", start)
        if (end < 0) return null
        return documentXml.substring(start, end + "</Customer>".length)
    }

    fun writeModifyDocumentResult(
        data: CustomerData,
        invoicing: Boolean = true,
        scenario: CustomerScenario? = null
    ): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        append("<ModifyDocumentResult>")

        val id = data.customerId?.trim()?.takeIf { (it.toLongOrNull() ?: 0) > 0 }
        if (id != null) {
            append("<Header>")
            append("<HeaderField Key=\"CustomerId\">").append(escape(id)).append("</HeaderField>")
            append("</Header>")
            appendCustomerData(data)
        } else {
            append("<Header></Header>")
            appendCustomerData(data)
        }

        append("</ModifyDocumentResult>")
    }

    fun StringBuilder.appendCustomerData(data: CustomerData) {
        append("<Customer>")
        writeCustomerField("CustomerName", data.customerName)
        writeCustomerField("CustomerFiscalId", data.fiscalId)
        val hioPosDocType = com.permoda.facturacion.core.hka.TipoDocumentoMapper
            .toHioPosCode(data.fiscalDocumentType)
        if (hioPosDocType != null) {
            writeCustomerField("CustomerFiscalIdDocType", hioPosDocType)
        }
        writeCustomerField("CustomerEmail", data.email)
        writeCustomerField("CustomerPhone", data.phone)
        writeCustomerField("CustomerCountryIso2", data.countryIso2 ?: DEFAULT_COUNTRY)
        writeCustomerField("GenerateInvoice", "true")
        writeCustomerField("GenerateElectronicInvoice", "true")
        append("</Customer>")
    }

    val CUSTOMER_KEYS: Set<String> = setOf(
        "CustomerName", "CustomerFiscalId", "CustomerFiscalIdDocType",
        "CustomerEmail", "CustomerPhone",
        "CustomerCountryIso2", "CustomerAddress", "CustomerPostalCode",
        "CustomerCity", "CustomerState", "CustomerExternalId",
        "GenerateInvoice", "GenerateElectronicInvoice", "SendDocumentByEmail",
        "GivenName1", "GivenName2", "GivenSurname1", "GivenSurname2",

    )

    const val DEFAULT_COUNTRY = "CO"

    private fun StringBuilder.writeCustomerField(key: String, value: String?) {
        val limpio = value?.trim().orEmpty()
        if (limpio.isEmpty()) return
        append("<CustomerField Key=\"").append(escape(key)).append("\">")
        append(escape(limpio))
        append("</CustomerField>")
    }

    private fun escape(value: String) = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}

private fun Element.text(): String {
    val sb = StringBuilder()
    val children = childNodes
    for (i in 0 until children.length) {
        val node = children.item(i)
        if (node.nodeType == Node.TEXT_NODE || node.nodeType == Node.CDATA_SECTION_NODE) {
            sb.append(node.nodeValue.orEmpty())
        }
    }
    return sb.toString().trim()
}

private fun Element.childrenByName(name: String): List<Element> {
    val out = mutableListOf<Element>()
    val children = childNodes
    for (i in 0 until children.length) {
        val node = children.item(i)
        if (node.nodeType == Node.ELEMENT_NODE && node.nodeName == name) out += node as Element
    }
    return out
}

private fun Element.descendantByName(name: String): Element? {
    if (nodeName == name) return this
    val children = childNodes
    for (i in 0 until children.length) {
        val node = children.item(i)
        if (node.nodeType == Node.ELEMENT_NODE) {
            (node as Element).descendantByName(name)?.let { return it }
        }
    }
    return null
}
