package com.permoda.facturacion.entry

import com.permoda.facturacion.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * HU-HIOPOS · Verifica el borde con HioPosCloud leyendo el `AndroidManifest.xml`
 * real del proyecto.
 *
 * Existe porque los bugs de contrato que ya nos pasaron —action de auditoría
 * equivocada, `setPackage` que mataba los broadcasts, el paquete del caller que
 * no existía en las terminales, el `<apk_name>` con el case equivocado— comparten
 * la misma causa: **el contrato se rompió y ningún test lo notó**. Los 162 tests
 * previos cubrían lógica pura y ninguno tocaba el manifest.
 *
 * Y sobre todo: el QA por adb pasaba `-a` junto con `-n` (componente explícito),
 * lo que hace que Android entregue directo a la clase y **nunca consulte los
 * intent-filter**. O sea que la resolución por action —justo el eslabón que
 * falla— nunca se ejercitó. Esto es su reemplazo automatizado.
 *
 * No necesita Robolectric ni instrumentación: parsea el XML con el mismo DOM
 * estándar que ya usa `Xml.kt` y corre en `testDebugUnitTest`.
 */
class ManifestContractTest {

    private val docApiPrefix = "icg.actions.document."

    // ------------------------------------------------------------------
    // Modelo mínimo del manifest
    // ------------------------------------------------------------------

    /** Un `<intent-filter>` con sus actions y categories. */
    private data class FilterDecl(
        val actions: List<String>,
        val categories: List<String>
    )

    private data class ActivityDecl(
        val name: String,
        val exported: Boolean,
        val filters: List<FilterDecl>
    ) {
        val actions: List<String> get() = filters.flatMap { it.actions }

        /** Filtros que atienden la Document API de ICG. */
        val docApiFilters: List<FilterDecl>
            get() = filters.filter { f -> f.actions.any { it.startsWith("icg.actions.document.") } }
    }

    private fun manifestFile(): File {
        // El working dir de los unit tests de AGP es el directorio del módulo,
        // pero se aceptan ambas raíces para que el test no dependa de eso.
        val candidates = listOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml")
        )
        return candidates.firstOrNull { it.exists() }
            ?: fail("No se encontró AndroidManifest.xml. cwd=${File(".").absolutePath}")
                .let { error("unreachable") }
    }

    private fun activities(): List<ActivityDecl> {
        // El manifest trae placeholders que AGP interpola en tiempo de build. Acá
        // se resuelven con los mismos valores que quedaron en BuildConfig, para
        // comparar contra lo que realmente se empaqueta.
        val raw = manifestFile().readText(Charsets.UTF_8)
            .replace("\${icgApkName}", ApiActions.APK_NAME)
            .replace("\${applicationId}", BuildConfig.APPLICATION_ID)

        assertTrue(
            "Quedó un manifestPlaceholder sin resolver en el manifest: " +
                Regex("""\$\{[^}]*}""").find(raw)?.value + ". " +
                "Si es uno nuevo, agregá su reemplazo acá; si es un typo, corregí el manifest.",
            !raw.contains("\${")
        )

        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isValidating = false
        }
        val root = factory.newDocumentBuilder()
            .parse(raw.byteInputStream(Charsets.UTF_8))
            .documentElement

        val application = root.children().first { it.nodeName == "application" }

        return application.children()
            .filter { it.nodeName == "activity" }
            .map { activity ->
                ActivityDecl(
                    name = activity.getAttribute("android:name"),
                    exported = activity.getAttribute("android:exported") == "true",
                    filters = activity.children()
                        .filter { it.nodeName == "intent-filter" }
                        .map { f ->
                            FilterDecl(
                                actions = f.children()
                                    .filter { it.nodeName == "action" }
                                    .map { it.getAttribute("android:name") },
                                categories = f.children()
                                    .filter { it.nodeName == "category" }
                                    .map { it.getAttribute("android:name") }
                            )
                        }
                )
            }
    }

    // ------------------------------------------------------------------
    // Las actions del contrato
    // ------------------------------------------------------------------

    @Test
    fun `MODIFY_DOCUMENT tiene que estar declarada`() {
        // Es por donde HioPosCloud entra a la selección de cliente (la lupita).
        // Se probó quitarla para esquivar un bug de guardado de HioPos y rompió
        // el módulo: el POS muestra "action not found in the external module" y
        // la venta queda sin cliente. NO cae de vuelta a SELECT_CUSTOMER.
        //
        // Este test existe para que nadie la vuelva a quitar buscando ese atajo.
        val declaradas = activities().flatMap { it.actions }
        assertTrue(
            "MODIFY_DOCUMENT no está declarada. Sin ella HioPosCloud muestra " +
                "\"action not found in the external module\" al pulsar la lupita.",
            ApiActions.MODIFY_DOCUMENT in declaradas
        )
    }

    @Test
    fun `las actions del contrato estan declaradas exactamente una vez`() {
        val all = activities()

        ApiActions.DECLARED_ACTIONS.forEach { action ->
            val dueñas = all.filter { action in it.actions }
            assertEquals(
                "La action '$action' debe estar declarada en exactamente una " +
                    "activity. HioPosCloud la resuelve por action, no por " +
                    "componente: si falta, el módulo nunca se levanta y no hay " +
                    "error visible en logcat — HioPos atrapa el fallo y ofrece " +
                    "instalar el módulo. Encontradas: " +
                    dueñas.joinToString { it.name }.ifBlank { "ninguna" },
                1,
                dueñas.size
            )
        }
    }

    @Test
    fun `cada intent-filter de la API declara exactamente una action`() {
        // HU-HIOPOS · Requisito estructural que ICG señaló al comparar contra su
        // módulo de referencia. Android admite varias <action> por filtro y el
        // match es OR, pero su ejemplo usa una y no vale discutirlo cuando el
        // suyo funciona. Tener VARIOS <intent-filter> por activity sí es válido
        // y es como se declaran los nombres candidatos de la sonda.
        activities().forEach { decl ->
            decl.docApiFilters.forEachIndexed { i, filtro ->
                assertEquals(
                    "${decl.name}, intent-filter #${i + 1}: debe declarar UNA sola " +
                        "action (declara ${filtro.actions.size}: ${filtro.actions}). " +
                        "Para varios nombres usá varios <intent-filter>, no varias " +
                        "<action> en el mismo.",
                    1,
                    filtro.actions.size
                )
            }
        }
    }

    @Test
    fun `las activities de la API estan exportadas`() {
        // Sin `exported=true` HioPosCloud no puede lanzarlas: es otro proceso.
        activities()
            .filter { it.docApiFilters.isNotEmpty() }
            .forEach { decl ->
                assertTrue(
                    "La activity ${decl.name} filtra actions de la Document API " +
                        "pero no está exportada; HioPosCloud no podría invocarla.",
                    decl.exported
                )
            }
    }

    @Test
    fun `cada intent-filter de la API declara category DEFAULT`() {
        // El contrato (HPCL.025) la exige en todos los intent-filter, y sin ella
        // un Intent implícito no hace match.
        activities().forEach { decl ->
            decl.docApiFilters.forEach { filtro ->
                assertTrue(
                    "${decl.name}: filtro de ${filtro.actions} sin " +
                        "android.intent.category.DEFAULT.",
                    "android.intent.category.DEFAULT" in filtro.categories
                )
            }
        }
    }

    @Test
    fun `no hay actions de la Document API con sufijo desconocido`() {
        // Cazador de typos, sobre todo en las actions candidatas de la sonda:
        // una mal escrita queda muerta y no se nota nunca.
        val sufijosValidos = ApiActions.ALL_ACTIONS
            .map { it.substringAfterLast('.') }
            .toSet()

        activities()
            .flatMap { it.actions }
            .filter { it.startsWith(docApiPrefix) }
            .forEach { action ->
                val sufijo = action.substringAfterLast('.')
                assertTrue(
                    "La action '$action' no termina en ninguno de los sufijos del " +
                        "contrato ($sufijosValidos). ¿Typo?",
                    sufijo in sufijosValidos
                )
            }
    }

    @Test
    fun `la sonda cubre las mismas acciones en todos los nombres candidatos`() {
        // HU-HIOPOS · Mientras la sonda esté activa, cada <apk_name> candidato
        // tiene que declarar TODAS las acciones. Si a un candidato le falta
        // GET_VERSION, HioPosCloud no lo resuelve, concluye "no instalado" y
        // ofrece instalar el módulo — el mismo síntoma que estamos cazando, pero
        // ahora por un descuido nuestro.
        val porNombre = activities()
            .flatMap { it.actions }
            .filter { it.startsWith(docApiPrefix) }
            .groupBy { it.removePrefix(docApiPrefix).substringBeforeLast('.') }
            .mapValues { (_, acciones) -> acciones.map { it.substringAfterLast('.') }.toSet() }

        val esperadas = ApiActions.DECLARED_ACTIONS.map { it.substringAfterLast('.') }.toSet()

        porNombre.forEach { (nombre, sufijos) ->
            assertEquals(
                "Al candidato '$nombre' le faltan acciones: ${esperadas - sufijos}",
                esperadas,
                sufijos
            )
        }
    }

    @Test
    fun `el namespace se arma con el apk_name de BuildConfig`() {
        // Blindaje de la fuente única: si alguien vuelve a escribir el nombre a
        // mano en ApiActions o en el manifest, esto se cae.
        assertTrue(
            "APK_NAME no puede venir vacío: es el <apk_name> del namespace.",
            ApiActions.APK_NAME.isNotBlank()
        )
        assertEquals(
            "$docApiPrefix${ApiActions.APK_NAME}.SELECT_CUSTOMER",
            ApiActions.SELECT_CUSTOMER
        )
    }

    @Test
    fun `el driver de QA no filtra actions de la API`() {
        // TestDriverActivity es el launcher de pruebas. Si alguna vez filtrara
        // una action del contrato, HioPosCloud podría resolver contra ella y
        // abrir el simulador en producción.
        val driver = activities().firstOrNull { it.name.endsWith("TestDriverActivity") }
            ?: return
        assertTrue(
            "TestDriverActivity no debe filtrar actions de la Document API.",
            driver.docApiFilters.isEmpty()
        )
    }

    @Test
    fun `solo ManageCustomer resuelve a la activity de gestion`() {
        val all = activities()
        val managed = all.single { ApiActions.MANAGE_CUSTOMER in it.actions }
        assertTrue(managed.name.endsWith("ManageCustomerActivity"))
        assertTrue(ApiActions.SELECT_CUSTOMER !in managed.actions)
        assertTrue(ApiActions.MODIFY_DOCUMENT !in managed.actions)
        assertEquals(false, ApiActions.CUSTOMER_BEHAVIOUR["CallOnCustomerSelection"])
        assertEquals(true, ApiActions.CUSTOMER_BEHAVIOUR["ManageCustomer"])
        assertEquals(false, ApiActions.CUSTOMER_BEHAVIOUR["CreateUpdateCustomer"])
    }

    // ------------------------------------------------------------------
    // Helpers DOM
    // ------------------------------------------------------------------

    private fun Element.children(): List<Element> {
        val out = mutableListOf<Element>()
        val nodes = childNodes
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            if (node.nodeType == Node.ELEMENT_NODE) out += node as Element
        }
        return out
    }
}
