# Del cosechador a una colección vectorial en v2

Este flujo toma un export de metadata de LA Referencia, calcula los
embeddings con EmbeddingGemma 2 y carga una colección nueva en PostgreSQL.
El cálculo y la importación se pueden ejecutar en máquinas distintas.
Para importar un paquete de vectores ya calculado basta con Python y acceso
a PostgreSQL.

## 1. Exportar la metadata

Para Brasil (`BR`) y Argentina (`AR`) usamos
`classification-embeddings-v1.json`, con versión declarada **2.0**.
El nombre del archivo conserva el sufijo `v1`. Comprueba que el
`mapping.json` incluido en el paquete indique `"version": "2.0"`.

| Campo en `data` | Selector XOAI |
|---|---|
| `titles` | `dc.title.*` |
| `descriptions` | `dc.description.*` |
| `keywords` | `dc.subject.*` |

Cada ocurrencia conserva su valor y, cuando están disponibles, el idioma
y el calificador. `descriptions` incluye todos los tipos de descripción.
El calculador distingue los abstracts por su calificador.

En el shell del cosechador, adapta la ruta de configuración y exporta
cada red con `--variant original`.

```text
export-mapping-validate --mapping /config/exports/classification-embeddings-v1.json
export-metadata-preview --network BR --variant original --mapping /config/exports/classification-embeddings-v1.json --limit 5
export-metadata --network BR --variant original --format jsonl --mapping /config/exports/classification-embeddings-v1.json --partition-by network --compression gzip --output /exports
export-metadata --network AR --variant original --format jsonl --mapping /config/exports/classification-embeddings-v1.json --partition-by network --compression gzip --output /exports
```

Para otra red, sustituye el acrónimo y comprueba con la vista previa que
el perfil conserva los títulos y resúmenes esperados.

El resultado es una carpeta `exp-…` con estado `COMPLETE` en
`manifest.json`. Conserva la carpeta completa, incluidas las partes
JSONL, `mapping.json` y `records.sqlite`. SQLite identifica el registro y
su parte y número de línea. El calculador y el importador lo abren en modo
lectura.

## 2. Calcular los vectores

Ejecuta los comandos siguientes desde la raíz de este repositorio en la rama
`v2`. Para el cálculo necesitas Python y el entorno descrito en la
[guía de scripts](../scripts/python/README.md).

```bash
META=/ruta/exp-del-cosechador
VECTORS=/ruta/embeddings-del-export
REVISION=f9972ac22e696a105c482202646979e33e4b5a1e

python scripts/python/compute_metadata_embeddings.py "$META" "$VECTORS" \
  --revision "$REVISION"
```

La salida debe ser una carpeta nueva. Esta receta toma el primer título
no vacío y une las ocurrencias de `data.descriptions` cuyo primer
componente del qualifier es `abstract`. Si no hay resumen, usa el título
también como texto. Cuando el perfil exporta los resúmenes a un campo
dedicado llamado `abstracts`, añade
`--abstract-field abstracts --abstract-qualifier ''`.

La configuración predeterminada usa CUDA y BF16. Para CPU, añade
`--device cpu --dtype float32`. Si la revisión ya está disponible en la
caché local, puedes usar `--offline`.

El resultado contiene un `manifest.json` y una parte JSONL comprimida de
vectores por cada parte de metadata. Por ejemplo, la línea 42 de
`BR/part-000001.jsonl.gz` corresponde a la línea 42 de
`BR/part-000001.embeddings.jsonl.gz`. Cada línea contiene un array de
vectores. El calculador actual escribe un vector de 768 dimensiones en la
posición 0, que el manifiesto identifica como `title-abstract`.

## 3. Validar e importar

Para las ediciones generadas por metadata tools con `--format sqlite`, seguí
la [guía de importación directa de SQLite](../scripts/python/README.md#import-a-sqlite-vector-edition-into-v2).
Los comandos de esta sección usan el export del cosechador y su paquete de
vectores JSONL.

Prepara un entorno Python 3.11 o posterior. Si cambias de máquina, vuelve
a definir `META` y `VECTORS` con las rutas de ambas carpetas.

```bash
python -m pip install -r scripts/python/requirements-import.txt

python scripts/python/import_metadata_embeddings.py "$META" "$VECTORS" \
  --collection-slug mi-coleccion --collection-name "Mi colección" \
  --dry-run
```

`--dry-run` valida los hashes, el orden de las líneas, los textos y los
vectores sin conectarse a PostgreSQL.

Para cargar, usa una base PostgreSQL con pgvector y el esquema v2 ya
migrado. Configura `DATABASE_URL` con el DSN de esa base y los permisos
indicados en la [guía del importador](../scripts/python/README.md#importar-metadata-y-vectores-directamente-a-postgresql-v2).

```bash
python scripts/python/import_metadata_embeddings.py "$META" "$VECTORS" \
  --collection-slug mi-coleccion --collection-name "Mi colección" \
  --report /ruta/mi-coleccion-import.json
```

Usa un *slug* y una ruta de informe nuevos. El script carga cada documento
y sus textos una vez, crea o reutiliza una indexación por receta del manifiesto y
confirma toda la carga en una transacción. Por defecto, las indexaciones
usan búsqueda exacta por coseno. `--ann hnsw_vector` construye un índice
aproximado cuando la dimensión lo permite.

Each recipe uses a global indexation slug. If an existing ready indexation has
the same profile and configuration, the importer attaches the new collection
and reuses its vector table. A different profile or indexation configuration
causes a conflict and rolls back the import. Reused HNSW indexes must be ready.
The report records whether each indexation was reused.

## 4. Comprobar el resultado

Conserva el informe de importación junto a los dos manifiestos. El
informe registra los UUID de colección e indexaciones, los conteos y los
hashes de origen. Comprueba que el total de documentos coincide con el
export y que cada indexación tiene un vector por documento. Puedes
recuperar un registro en la API v2 por su `external_id`, que corresponde
al `record_key` del SQLite.

El perfil importado queda en modo `import_only`. Para buscar, genera el
vector de consulta con la revisión y la plantilla de consulta de la
receta y envíalo a v2 como `query.vector`. Consulta los
[flujos de la API v2](WORKFLOWS.md) para los detalles de búsqueda y
recuperación.
