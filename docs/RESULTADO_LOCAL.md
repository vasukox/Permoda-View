# Resultado de la implementación local

Actualizado el 15 de octubre de 2026. La entrega vigente tiene versión fija 1 y APK dev/release firmados. Se completaron los cambios locales y la verificación funcional previa. No se instaló en un terminal, no se publicó en CloudLicense y no se consultó la API de negocio durante esta validación.

## Comportamiento implementado

- La lupa, la consulta y la selección de clientes permanecen en HioPosCloud: `CallOnCustomerSelection=false`, `ManageCustomer=true`, `CreateUpdateCustomer=false`.
- La entrada nueva `MANAGE_CUSTOMER` abre el formulario existente y conserva la pantalla del vendedor. Las acciones históricas SELECT_CUSTOMER/MODIFY_DOCUMENT tienen un manejador inerte que cancela.
- El alta consulta HKA después de tener tipo y número; usa el documento precargado sin repetir la pregunta nativa. Una respuesta fallida permite captura manual.
- La edición conserva el ID original y nunca consulta HKA, aunque se corrija el documento. La operación no se deduce del nombre ni de la cédula.
- El resultado incluye `CustomerResult` con `CustomerSearch` anidado sobre `FiscalId` y el documento final. El guardado y los duplicados pertenecen al POS.
- Los IDs comerciales remotos se configuran mediante un mapa obligatorio. Normal que acepta pasa a VIP; VIP conserva su categoría; empleado conserva empleado. Continuar sin inscripción desde el cajero no simula aceptación del cliente.
- Se preservan naturaleza fiscal, permisos comerciales y campos originales no editados. `customerTypeId`, `contactTypeId` y los dominios de códigos HioPos/DIAN se tratan por separado.
- Se acotan XML y respuestas HTTP, se restringe la URI de entrada, se valida el llamador Android y se cancela la llamada HTTP al cerrar. Se retiraron logs de datos personales y el fallback de emisor basado en un extra manipulable.

## Verificación funcional previa

Antes del ajuste de versión y firma debug se ejecutaron las tareas JVM, lint debug, assembleDebug y release: **357 pruebas en 28 clases**, sin fallos, errores ni omitidas; lint debug con **0 errores y 150 advertencias**. La línea base tenía 332 pruebas y 6 errores de lint.

Esas pruebas cubren perfil de entrada, identidad, categorías remotas, fiscalidad, XML, transporte, fixtures, cancelación HTTP, respuestas inválidas, política del llamador y manifiesto. Los informes permanecen en `app/build/reports/tests/testDebugUnitTest/index.html` y `app/build/reports/lint-results-debug.html`. No sustituyen una prueba de integración con el terminal.

## Empaquetado vigente: versión fija 1

El ajuste solicitado solo cambia configuración de versión/firma, tareas de entrega y documentación. No se modificó lógica de negocio ni se repitieron pruebas extensas.

```powershell
.\gradlew.bat :app:apkDevParaAdb :app:apkParaCloudLicense --offline --console=plain
```

Resultado: **BUILD SUCCESSFUL**, 2 min 42 s. Se compilaron ambas variantes y pasó lint vital de release. Se verificaron manifiestos empaquetados, constantes BuildConfig, firmas y hashes.

| Propiedad | Dev para ADB | Release CloudLicense |
|---|---|---|
| APK | app/build/outputs/dev/custvalidation-dev.apk | app/build/outputs/cloudlicense/custvalidation.apk |
| Android versionName / versionCode | 1 / 1 | 1 / 1 |
| ICG_MODULE_VERSION | 1 | 1 |
| debuggable | true | false |
| testOnly | Ausente/falso | Ausente/falso |
| Firma | v1 y v2 válidas | v1 y v2 válidas |
| Tamaño | 8,451,496 bytes | 3,180,036 bytes |

Ambos usan el paquete `com.permoda.facturacion`, namespace `custvalidation` y el certificado habitual `a964ceae301f56bf82f5fcbc4dc1c109a296951bc78d8558e91ab6463dea9b3f`. No hay incrementos automáticos; los tres overrides de versión solo admiten 1 y fallan si se solicita otro valor. Las advertencias de empaquetado/firma se detallan en `ENTREGA_VERSION_1.md`.

SHA-256 dev, contrastado con `custvalidation-dev.apk.sha256`:

```text
537e9c6a86cc97d2e8ff6631e489c0230975f86e1ba77d6874afcef5d17b7827
```

SHA-256 release, contrastado con `custvalidation.apk.sha256`:

```text
88d76d15dd368e59ae99ab16bf8563d3d0ba2291e4c7221e4b4fdb72d1089dc0
```

El dev actual comparte certificado con la release histórica y la release local previa. El antiguo debug estándar usaba otra clave; esa diferencia se corrigió para esta entrega. La compatibilidad con una instalación real depende de comprobar su firma y versión.

Ver `ENTREGA_VERSION_1.md` e `INSTALACION_ADB.md`. Si estuviera instalada una release no depurable de código 2, bajar a 1 no se resuelve universalmente con `-d`. No se ha conectado un terminal ni se presume esa instalación.

El respaldo específico previo a este ajuste está en `C:\Users\andresdb\.codex\visualizations\2026\10\15\01a13feb-fbf7-78f3-80cb-db4742082eaf\version-fixed-1-baseline\before-fixed-version-1.zip`. Incluye nueve archivos y los APK anteriores; el inventario SHA-256 y las comprobaciones nuevas están a su lado.

## Respaldo y limpieza

El respaldo previo está fuera del proyecto:

`C:/Users/andresdb/.codex/visualizations/2026/10/15/01a13feb-fbf7-78f3-80cb-db4742082eaf/implementation-baseline/permodaview-before-implementation.zip`

Incluye 191 archivos originales. A su lado están `sha256-before.json`, `cleanup-manifest.json` e `implementation-changes.json`. No hay un repositorio Git inicializado en esta carpeta.

Se retiraron 12 archivos recuperables por 198.050.367 bytes: dos APK idénticos a una copia conservada y diez scripts/fixtures temporales del contrato antiguo verificados contra el respaldo. El escritor Android sin consumidores también quedó respaldado y se retiró. Se conservaron logs históricos, PDF, APK original de la raíz, assets y las extracciones cuyo contenido difiere. Ver `LIMPIEZA_LOCAL.md`.

La revisión automática rechazó la eliminación recursiva de `.gradle/configuration-cache` con el motivo genérico «blocked by policy». La caché se conserva intacta. Su uso futuro está deshabilitado para evitar nuevas serializaciones de la configuración de firma; esto no elimina credenciales históricas ni revoca tokens existentes en evidencias antiguas.

## Validación posterior en el POS

La documentación 0.6.4 no especifica todos los literales del transporte de entrada. El APK de referencia local 14.20.0.0 es anterior a ManageCustomer. Por ello el adaptador aísla un perfil configurable, exige identidad explícita y rechaza entradas ambiguas.

Antes de la instalación se deben obtener los IDs reales normal/VIP/empleado y contrastar los nombres de extras, el carrier XML/URI, la identidad y los códigos documentales con la versión efectiva del POS. Los IDs 10/47/81 de `qa/fixtures` son ficticios, con HKA deshabilitado. No son configuración de producción.

La matriz de la fase posterior está en `PLAN_VALIDACION_POS.md`: consulta nativa, alta, edición, cambio de documento, categorías, duplicados, cancelación y guardado/recarga. La ausencia de terminal no impidió completar esta fase local; la persistencia real y la apariencia en las dos pantallas siguen pendientes de esa comprobación.
