# Correcciones pendientes

Fecha de revisión: 2 de noviembre de 2026.

Este documento recoge los problemas identificados en la revisión del código y las
correcciones propuestas. No implica que los cambios estén implementados. Salvo el
fallo de pruebas indicado al final, los hallazgos proceden del análisis estático;
sus escenarios deben cubrirse durante la implementación.

## 1. Condición de carrera en los bloqueos por clave

**Prioridad:** alta. **Estado:** pendiente.

**Código:** `src/main/java/org/lareferencia/embedding/util/PerKeyLockManager.java`,
métodos `lockAll` y `LockSet.close`.

Al cerrar un conjunto de bloqueos se ejecuta `unlock()` y después se elimina el
bloqueo del mapa si `hasQueuedThreads()` es falso. Un segundo hilo puede haber
adquirido ya ese mismo bloqueo sin que queden hilos en espera. Si se elimina la
entrada, un tercer hilo puede crear otro bloqueo para la misma clave y entrar
simultáneamente en la sección protegida. También hay una ventana entre obtener
la referencia del mapa y comenzar a esperar por el bloqueo.

**Impacto:** llamadas duplicadas al proveedor para el mismo texto y pérdida de la
garantía de exclusión mutua dentro de una instancia.

**Corrección propuesta:** gestionar atómicamente las referencias de propietarios
y solicitantes antes de retirar una entrada, o utilizar un conjunto fijo de
bloqueos. Mantener el orden global de adquisición para evitar interbloqueos.
Los bloqueos locales no coordinan distintas réplicas del servicio.

**Verificación:** añadir una prueba concurrente que fuerce adquisiciones durante
la liberación y compruebe que nunca hay dos secciones protegidas activas para la
misma clave. Cubrir también lotes con claves solapadas.

## 2. Configuración ausente en una imagen Docker construida desde un checkout limpio

**Prioridad:** alta. **Estado:** pendiente.

**Código:** `Dockerfile`, `docker-compose.yml` y
`src/main/resources/application.example.yml`.

Spring Boot no carga automáticamente `application.example.yml`. El Dockerfile no
lo convierte en configuración activa. Variables de Compose como `DB_URL`,
`DB_USER` o `EMBEDDING_PROVIDER` dependen de los placeholders de ese archivo y no
equivalen por sí mismas a las propiedades de Spring correspondientes.

**Impacto:** el arranque documentado puede fallar por falta de configuración del
datasource o ignorar la selección de proveedor. El resultado puede depender de
que exista un `application.yml` local, que está ignorado por Git.

**Corrección propuesta:** incorporar una configuración base activa sin secretos,
cargar explícitamente el archivo de configuración, o cambiar Compose para usar
variables directamente vinculables, como `SPRING_DATASOURCE_URL`,
`SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD` y
`APP_EMBEDDING_PROVIDER_TYPE`. Revisar todas las variables publicadas en el README
y dejar vacía la clave de API por defecto.

**Verificación:** construir y arrancar desde un checkout sin `application.yml`,
con PostgreSQL disponible y la extensión vector habilitada. Comprobar conexión,
puerto efectivo y selección del proveedor mediante variables de entorno.

## 3. Posible mezcla de proveedores en un mismo store

**Prioridad:** alta. **Estado:** pendiente.

**Código:** `EmbeddingService.generateEmbeddings`, `StoreService` y
`EmbeddingProviderService`.

El store persiste un proveedor, pero la generación utiliza el proveedor global
configurado. No se valida que ambos coincidan antes de reutilizar o añadir
vectores. Tras cambiar el proveedor y reiniciar, un mismo store podría contener
vectores de procedencias distintas para el mismo modelo y dimensiones.

**Impacto:** incoherencia del espacio vectorial y respuestas cuyo proveedor
declarado puede no corresponder al de los vectores recuperados de caché.

**Corrección propuesta:** definir explícitamente la política. Si solo se admite
un proveedor activo, rechazar el uso de stores incompatibles. Si se admiten varios,
resolver el cliente a partir del proveedor persistido y revisar la identidad y
unicidad del store para incluirlo. Un cambio de proveedor debe requerir un store
compatible o una migración explícita.

**Verificación:** crear un store con un proveedor, cambiar la configuración y
comprobar el comportamiento tanto para aciertos de caché como para textos nuevos.
Ningún caso debe introducir vectores de otro proveedor silenciosamente.

## 4. Caché de stores desalineada con las transacciones y las réplicas

**Prioridad:** media. **Estado:** pendiente.

**Código:** `StoreService.createStore`, `loadStoresIntoCache` y
`EmbeddingRepository.ensureStoreTableExists`.

`createStore` publica el store en memoria antes de que finalice el commit. Si este
falla, la entrada puede permanecer aunque no exista en la base de datos. El
repositorio también marca tablas como inicializadas antes del resultado final de
la transacción que puede contener su creación. Además, los stores se cargan al
arrancar y los creados por otra instancia no se incorporan a la caché local.

**Impacto:** stores fantasma tras un rollback y respuestas 404 en réplicas que aún
no conocen un store existente. Una tabla revertida podría seguir marcada como
inicializada en memoria.

**Corrección propuesta:** publicar e invalidar el estado en memoria en función del
resultado de la transacción. Añadir una estrategia de coherencia entre instancias,
por ejemplo consulta a la base de datos ante un fallo de caché y actualización
controlada del listado, o invalidación compartida.

**Verificación:** provocar un fallo al confirmar la transacción y comprobar que
no quedan entradas fantasma ni marcas de tablas inexistentes. Con dos instancias,
crear un store en una y verificar su consulta y uso desde la otra.

## 5. Unicidad distinta en memoria y PostgreSQL

**Prioridad:** media. **Estado:** pendiente.

**Código:** `StoreService.createStore`, `StoreEntity` y `GlobalExceptionHandler`.

La comprobación previa usa `equalsIgnoreCase`, mientras que las restricciones
declaradas sobre `name` y sobre `(model, embedding_dimension)` no expresan esa
misma regla. La consulta previa tampoco es atómica con la inserción. Dos
solicitudes concurrentes pueden superar la validación; las variantes de
mayúsculas pueden producir stores ambiguos, y una violación de unicidad de la
base de datos puede terminar como HTTP 500.

**Impacto:** reglas inconsistentes, búsquedas ambiguas y errores de servidor para
conflictos que deberían devolver HTTP 409.

**Corrección propuesta:** definir la sensibilidad a mayúsculas de cada identificador
y aplicar la misma regla en las búsquedas y en restricciones de PostgreSQL. Si se
mantiene la comparación actual, usar claves normalizadas o índices únicos sobre
`lower(...)`. Traducir las violaciones de las restricciones pertinentes a 409,
sin convertir otros errores de integridad en conflictos de duplicados.

**Verificación:** ejecutar creaciones concurrentes con nombres y pares
modelo/dimensiones iguales y con variantes de mayúsculas. Debe persistirse una
sola combinación según la política elegida y devolverse 409 para el duplicado.

## 6. Asociación de embeddings basada en la posición de la respuesta

**Prioridad:** media. **Estado:** pendiente.

**Código:** `EmbeddingProviderService.toEmbeddingList` y la asociación por hash
en `EmbeddingService.generateEmbeddings`.

El parser recorre `data` en su orden de llegada e ignora el campo `index`. Si un
proveedor devuelve los elementos desordenados con índices correctos, los vectores
se asociarán a textos distintos y esa asociación quedará persistida en la caché.

**Impacto:** respuestas y caché incorrectas sin un error visible de dimensiones
o cantidad de resultados.

**Corrección propuesta:** reconstruir el orden a partir de `index`, comprobando
su tipo, rango, unicidad y completitud. Definir explícitamente el comportamiento
para proveedores que no lo incluyan.

**Verificación:** simular respuestas desordenadas, índices duplicados, ausentes
y fuera de rango. Comprobar la asociación texto/vector tanto en la respuesta
como en la escritura de caché; rechazar payloads ambiguos como error del proveedor.

## 7. Formato de tamaños dependiente del locale

**Prioridad:** baja. **Estado:** pendiente; reproducido en las pruebas.

**Código:** `StatusService.formatBytes` y
`StatusServiceTest.combinesSystemAndDatabaseStatus`.

El formateo mediante `"%.2f %s".formatted(...)` utiliza el locale por defecto.
En el entorno de revisión devuelve `4,00 KiB`, mientras que la prueba espera
`4.00 KiB`.

**Corrección propuesta:** usar un locale explícito, por ejemplo
`String.format(Locale.ROOT, "%.2f %s", value, units[unitIndex])`, para mantener
estable el formato de la API.

**Verificación:** comprobar el resultado con locales español e inglés,
restaurando el locale original si la prueba modifica el estado global.

## Resultado de la validación inicial

Se ejecutó `mvn test` con Java 21: **29 pruebas, 28 correctas y 1 fallida**, sin
errores ni pruebas omitidas. El fallo corresponde al formato de tamaños descrito
en el punto 7.

La cobertura revisada es principalmente unitaria. Para cerrar los puntos
anteriores hacen falta pruebas específicas de concurrencia e integración con
PostgreSQL/pgvector, además de comprobar el arranque de Docker desde un checkout
limpio. No se validaron llamadas contra proveedores reales.

Orden sugerido de trabajo: configuración de Docker, exclusión mutua, identidad
del proveedor, coherencia transaccional y entre réplicas, unicidad, orden de
resultados y formato de tamaños. Cada corrección debe incluir su validación antes
de marcarse como completada.
