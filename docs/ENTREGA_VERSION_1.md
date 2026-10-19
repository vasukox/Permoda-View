# Entrega vigente: versión fija 1

Verificada localmente el 15 de octubre de 2026. No se conectó un terminal, no se ejecutó ADB, no se instaló ni se publicó ningún APK.

## Regla de versión

`versionName="1"`, `versionCode=1` e `ICG_MODULE_VERSION=1` son constantes para debug y release. No hay incremento automático ni sufijo. Los overrides `PERMODA_VERSION_CODE`, `PERMODA_VERSION_NAME` y `PERMODA_ICG_MODULE_VERSION` rechazan cualquier valor diferente de `1`, incluso cuando proviene de una propiedad Gradle antigua. El protocolo ICG devuelve un Int; Android conserva una cadena para el nombre visible. Ambos valores quedan en 1 por solicitud del usuario.

## Artefactos

| Propiedad | Desarrollo para ADB | Release CloudLicense |
|---|---|---|
| Ruta | `C:\Proyectos\PermodaView\app\build\outputs\dev\custvalidation-dev.apk` | `C:\Proyectos\PermodaView\app\build\outputs\cloudlicense\custvalidation.apk` |
| Tamaño | 8,451,496 bytes | 3,180,036 bytes |
| versionName / versionCode | 1 / 1 | 1 / 1 |
| ICG_MODULE_VERSION | 1 | 1 |
| debuggable | true | false |
| testOnly | Ausente/falso | Ausente/falso |
| Firma | v1 y v2 válidas | v1 y v2 válidas |
| Paquete | com.permoda.facturacion | com.permoda.facturacion |

Cada APK tiene su archivo lateral `.apk.sha256`, contrastado con un cálculo independiente sobre el binario final.

SHA-256 dev:

```text
537e9c6a86cc97d2e8ff6631e489c0230975f86e1ba77d6874afcef5d17b7827
```

SHA-256 release:

```text
88d76d15dd368e59ae99ab16bf8563d3d0ba2291e4c7221e4b4fdb72d1089dc0
```

Ambos certificados tienen SHA-256:

```text
a964ceae301f56bf82f5fcbc4dc1c109a296951bc78d8558e91ab6463dea9b3f
```

## Compatibilidad de actualización

El certificado coincide con `RELEASE-custvalidation-v1.0.apk` y con la release local anterior de código 2. Se mantiene el mismo paquete, sin `applicationIdSuffix`; dev y release reemplazan una misma instalación y comparten datos.

El debug estándar anterior, respaldado antes del cambio, usaba el certificado `103075dabd64b2c0bcf485243a1943c15b29f76ad27f5300627b0a514c5c12b5`. Esta entrega dev corrige esa diferencia usando la firma de la aplicación. No es compatible como actualización directa de una instalación que realmente use aquella clave debug diferente. No se presume qué APK está instalado en un terminal.

Con código instalado 1 y firma compatible, ADB puede solicitar un reemplazo con `install -r`. Si estuviera instalada una release no depurable de código 2, bajar a 1 requiere un mecanismo autorizado por el sistema: añadir `-d` o hacer depurable el APK entrante no garantiza permitirlo. Los comandos escritos, sus condiciones y las fuentes oficiales están en `INSTALACION_ADB.md`. No se propone desinstalar ni borrar datos.

Dev conserva el driver y la política debug de llamador para QA; release conserva la política de llamadores del POS. La firma compartida permite alternar ambas con código 1 cuando el terminal autoriza la instalación. Con versión fija, una distribución que detecte solo números superiores puede necesitar reemplazo manual.

## Verificación realizada

```powershell
.\gradlew.bat :app:apkDevParaAdb :app:apkParaCloudLicense --offline --console=plain
```

**BUILD SUCCESSFUL**, 2 min 42 s. Incluye compilación de ambas variantes, reducción R8 de release y lint vital de release. No se modificó lógica de negocio ni se repitió la batería extensa; las 357 pruebas sin fallos y lint debug con 0 errores/150 advertencias corresponden a la verificación funcional previa.

Se comprobaron ambos binarios con `aapt dump badging`, `aapt dump xmltree ... AndroidManifest.xml`, `apksigner verify --min-sdk-version 23 --verbose --print-certs` y SHA-256 independiente. Se verificaron también las constantes generadas de BuildConfig para ambas variantes. MANAGE_CUSTOMER está presente en ambos manifiestos.

`apksigner` finaliza con código 0: v1 y v2 válidas. Advierte sobre entradas META-INF de la firma JAR: 53 en dev y 54 en release. No se afirma ausencia de advertencias.

Las tareas de entrega exigen las credenciales locales de firma habituales; no las imprimen ni las pasan como argumentos al firmador. No se incluyeron claves privadas en el respaldo. Debug utiliza esa firma cuando está configurada. Si falta, `assembleDebug` podría usar la clave debug estándar, pero la tarea de entrega `apkDevParaAdb` falla en lugar de entregar ese APK como compatible.

## Recuperación y evidencia

Antes de modificar versiones se respaldaron nueve archivos, incluidos configuración, documentos y APK previos, en:

`C:\Users\andresdb\.codex\visualizations\2026\10\15\01a13feb-fbf7-78f3-80cb-db4742082eaf\version-fixed-1-baseline\before-fixed-version-1.zip`

En la misma carpeta están `sha256-before.json`, `signatures-before.json`, `verified-version-1.json` y `verified-version-guards.json`. El respaldo permite recuperar la configuración previa y los APK anteriores. La entrega vigente son los dos APK de versión 1 indicados arriba; no se eliminaron datos ni artefactos históricos en este ajuste.

## Paso posterior

Consultar `INSTALACION_ADB.md` para instalar cuando se autorice conectar un terminal. Después seguir `PLAN_VALIDACION_POS.md`: confirmar el perfil de entrada, catálogo real, guardado y recarga. Este empaquetado no certifica la integración con un terminal.
