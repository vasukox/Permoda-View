# Product

<!-- impeccable:product-schema 1 -->

## Platform

android

## Users
- **Cajero de tienda Koaj** (primario): opera la caja HioPos en la terminal C9H, en el mostrador y con fila de clientes. Crea o edita la ficha del cliente durante la venta; cada segundo cuenta.
- **Cliente en el mostrador** (secundario): mira la segunda pantalla de la terminal, orientada hacia él. Ve en vivo su documento y sus datos mientras el cajero los escribe, y decide si se inscribe en Koaj Lovers. Es la única pantalla que toca.
- **Soporte técnico**: abre la ficha del módulo desde el ícono de la app para leer versión, `apk_name` y paquete.

## Product Purpose
PermodaView es el módulo de Document API (`custvalidation`, documento 768 de CloudLicense) que HioPosCloud invoca para **crear y editar clientes** (`MANAGE_CUSTOMER`). Al crear, consulta el adquiriente en The Factory HKA (DIAN) para precargar datos; al editar no consulta. Ofrece al cliente la inscripción en **Koaj Lovers** y devuelve la ficha al POS, que la guarda. La búsqueda y la lista de clientes son del POS. Éxito: la ficha queda completa y correcta en HioPos sin frenar la venta.

## Positioning
Es el único punto de captura de clientes de Koaj en caja: une validación DIAN, ficha de HioPos y Koaj Lovers en un flujo de dos pantallas (cajero conduce, cliente ve y decide).

## Operating Context
- Terminal C9H con HioPos 15.14.3; pantalla principal para el cajero y segunda pantalla (Presentation) para el cliente.
- Tres entradas: botón "Nuevo cliente" (recordatorio de buscar primero), "cliente no encontrado → Nuevo" (documento prellenado) y "Editar" (formulario directo, sin HKA).
- Flujo: captura de documento → consulta HKA o captura manual → formulario (nombre, documento, correo, teléfono) → Koaj Lovers → cierre.

## Capabilities and Constraints
- Android Views con XML, AppCompat y una `Presentation` para el display secundario; sin Compose.
- El rediseño es **solo visual**: layouts, drawables, colores, tipografía y estilos. No cambia lógica, flujo, textos ni IDs de vistas que el código usa.
- **La imagen/arte de Koaj Lovers en la pantalla del cliente se conserva tal cual.**
- Alcance del rediseño: pantalla del cajero, pantalla del cliente y ficha del módulo. El driver de QA queda fuera.
- La pantalla del cliente no debe mostrar datos de otra persona (privacidad en el mostrador).
- Tipos de cliente en HioPos: 1 Estándar, 2 VIP, 3 Empleado.

## Brand Commitments
- **Koaj es la marca** de todo el módulo: se respetan su logo y su paleta (tinta negra + dorado). El resto del lenguaje visual se rediseña dentro de esa marca.

## Evidence on Hand
- Arte de marca: `app/src/main/res/drawable/koaj_hero.png` y logos/íconos `ic_koaj_*`.
- Fotos de país en `qa/assets-originales/`.
- No hay capturas de pantalla actuales en el repositorio.

## Product Principles
- La venta no espera: el cajero termina la ficha en el menor número de toques.
- El cajero conduce; el cliente ve y decide solo lo que es suyo (Koaj Lovers).
- Nada de datos ajenos en la pantalla que mira el público.
- Koaj se siente en cada detalle, sin estorbar la tarea.
