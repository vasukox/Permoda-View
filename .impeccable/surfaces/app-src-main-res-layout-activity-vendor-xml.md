---
version: 1
slug: "app-src-main-res-layout-activity-vendor-xml"
primary_target: "app/src/main/res/layout/activity_vendor.xml"
related_targets: ["app/src/main/res/layout/presentation_consent.xml","app/src/main/res/layout/activity_module_info.xml"]
---

# Flujo de cliente PermodaView (cajero + cliente + ficha)

Mode: Operate. Superficies: pantalla del cajero (activity_vendor.xml + overlays), pantalla del cliente (presentation_consent.xml, excepto el arte de Koaj Lovers, intocable) y ficha del módulo (activity_module_info.xml). Solo visual: sin cambios de lógica, flujo, textos ni IDs.

Audiencia y tarea: cajero en caja C9H con fila; crea/edita la ficha en segundos. El cliente al otro lado del mostrador ve su documento y datos en vivo y decide Koaj Lovers.

## Direction contract

THESIS: La caja habla como la vitrina de Koaj: un rótulo de vinilo en tinta y dorado nombra el paso en curso. Rechaza el formulario gris en tarjeta blanca con sombra y botón azul corporativo.

OWN-WORLD: Tinta #141312 en banda superior a sangre y botones primarios; dorado #C9A227 solo para la cinta de paso, el foco y la marca; blanco puro de trabajo y gris hueso #E8E5E0 para filetes; verde #1F8A5B y rojo #C8372D solo para estados. Campos planos separados por filetes de 1 dp, sin tarjetas con sombra ni degradados. Tipografía condensada pesada en mayúsculas para el rótulo; sans de sistema para los campos. Retícula de 8 dp.

STORY: El cajero ve de un vistazo en qué paso va, llena una sola columna y confirma con un botón inconfundible. El cliente ve su documento crecer en grande y reconoce Koaj sin leer nada.

FIRST VIEWPORT: Banda de tinta arriba (≈20 % de alto) con el título del paso en mayúsculas condensadas grandes y la cinta dorada de pasos Documento · Datos · Koaj debajo; columna única centrada de campos con placa de rótulo fija; botón primario de tinta a todo el ancho abajo; cancelar como botón contorno discreto.

FORM: Vitrina Koaj (rótulos de vinilo de corte de vitrina), candidato 7 de 7 de la lista propia; seed key 9c3b5134. Raises: color por regiones enteras (mapa), retícula 8 dp (Crouwel), jerarquía solo por escala (espécimen variable), filetes 1 dp (densidad japonesa), placa de rótulo fija (catálogo de personajes).

FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance
