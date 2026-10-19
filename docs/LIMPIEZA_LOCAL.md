# Limpieza local recuperable

Se guardó una copia previa de 191 archivos de código, configuración, documentación y herramientas históricas en:

`C:/Users/andresdb/.codex/visualizations/2026/10/15/01a13feb-fbf7-78f3-80cb-db4742082eaf/implementation-baseline/permodaview-before-implementation.zip`

El inventario SHA-256 previo y cleanup-manifest.json están junto a ese archivo. No se inicializó Git.

Se retiraron dos copias del APK de HioPos idénticas por SHA-256: qa/hiopos-fresh.zip y qa/hiopos-pull/base.apk. Se conserva qa/hiopos-fresh.apk, suficiente para recuperar ambas copias byte a byte.

Se retiraron, después de contrastarlos con el respaldo, los scripts temporales de modificación/inspección de XML y los simuladores del contrato antiguo:

- qa/test_minimal.py
- qa/normalize_xml.py
- qa/fix_encoding2.py
- qa/fix_encoding.ps1
- qa/remove_zone_cf.ps1
- qa/find_zone_cf.ps1
- qa/check_xml.ps1
- qa/Pv.ps1
- qa/Simular-Escenarios.ps1
- qa/presentation_consent.xml.bak

Se sustituyen por qa/Verificar-Local.ps1, los fixtures del contrato nuevo y el plan de validación posterior. El script local no usa un dispositivo ni hace llamadas de negocio.

Total retirado de estos 12 archivos: **198.050.367 bytes** (aproximadamente 188,88 MiB). El escritor Android ModifyDocumentResultFile.kt, ya sin consumidores, también se retiró como parte del cambio de código; su original está en el respaldo.

Se conservaron los logs, PDF, APK histórico de la raíz, assets originales y extracciones útiles. Al comparar las dos extracciones se detectó una diferencia en libSerialPort.so; por ello no se borró ese árbol como si fuera enteramente redundante. Los logs históricos pueden contener datos personales y tokens; no se publicaron ni se consideraron basura.

Las exclusiones de .gitignore cubren esos artefactos y configuraciones locales. No constituyen saneamiento retroactivo ni revocación de credenciales históricas.

La revisión automática rechazó la eliminación recursiva de `.gradle/configuration-cache` con el motivo genérico «blocked by policy». Esa caché se conserva intacta; `org.gradle.configuration-cache=false` evita nuevas serializaciones de la configuración de firma.
