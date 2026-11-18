# Scripts Python

Herramientas independientes para preparar datos de intercambio para el servicio de embeddings.

## Calcular embeddings desde una exportación del cosechador

`compute_metadata_embeddings.py` recibe una exportación JSONL con estado
`COMPLETE` y genera un paquete separado de vectores con EmbeddingGemma 2.

Usa Python 3.11 o posterior y un entorno con PyTorch, NumPy, Hugging Face Hub,
Transformers y Sentence Transformers. La prueba de UY usó Python 3.12,
PyTorch 2.11.0+cu128, Transformers 5.23.0 y Sentence Transformers 6.3.0.
Para CUDA, usa una distribución de PyTorch compatible con la GPU y el driver.

```bash
python scripts/python/compute_metadata_embeddings.py \
  /ruta/exp-del-cosechador \
  /ruta/emb-uy-title-abstract \
  --revision f9972ac22e696a105c482202646979e33e4b5a1e \
  --offline
```

`--revision` fija el commit exacto del modelo. Con `--offline` debe estar en
la caché local. La configuración predeterminada usa CUDA y BF16. Para
ejecutar en CPU, indica `--device cpu --dtype float32`.

La receta toma el primer título no vacío de `data.titles` y concatena, en
orden, las ocurrencias no vacías de `data.descriptions` cuyo primer
componente del qualifier es `abstract`. Si falta el abstract, usa el título
también como texto. El modelo recibe `title: {title} | text: {text}` y devuelve
un vector de 768 dimensiones normalizado en FP32. El límite de entrada es
8.192 tokens y una secuencia que lo supera provoca un error. Para un perfil
que exporta los resúmenes a un campo dedicado, usa
`--abstract-field abstracts --abstract-qualifier ''`.

### Paquete de salida

```text
emb-uy-title-abstract/
  manifest.json
  UY/part-000001.embeddings.jsonl.gz
  UY/part-000002.embeddings.jsonl.gz
```

Cada línea contiene únicamente un array de vectores. La posición de cada
vector corresponde a la misma posición en `embeddings` del manifiesto.
Este script genera una sola posición, `title-abstract`.

```json
[[0.0312, -0.0187, 0.0048]]
```

El ejemplo abrevia el vector. La línea 42 de una parte de vectores corresponde
a la línea 42 de la parte de metadata indicada en `parts[].sourceFile`.
El manifiesto registra la identidad de la exportación, el modelo y su
revisión, los parámetros, la receta, los conteos y los SHA-256 del origen
y de las partes de salida.

El script coteja las líneas con `records.sqlite` en modo lectura y verifica
la identidad, el hash de metadata y la posición de cada registro. El índice
permanece en el paquete original. La carpeta final se publica después de
completar todas las partes y debe ser nueva. Un error aborta la ejecución
y permite relanzarla desde el comienzo.

Este paquete es el formato de intercambio. Para enviarlo a la API de
importación v2, se deben combinar las partes de metadata y vectores en los
objetos que acepta el servicio. Consulta la
[guía de importación](../../docs/phase-10-imports.md).

## Importar metadata y vectores directamente a PostgreSQL v2

`import_metadata_embeddings.py` crea una colección nueva a partir del export
JSONL original y su paquete separado de embeddings. Requiere Python 3.11+,
PostgreSQL con pgvector y el esquema `dvp` ya migrado por el servicio v2.
Para la carga, la cuenta necesita permisos de lectura e inserción en las
tablas de v2, permiso para actualizar el estado de las indexaciones,
acceso a sus secuencias y permiso para crear tablas e índices en `dvp`. El script no instala extensiones ni ejecuta migraciones.

```bash
python -m pip install -r scripts/python/requirements-import.txt

python scripts/python/import_metadata_embeddings.py \
  /ruta/exp-del-cosechador /ruta/emb-uy-title-abstract \
  --collection-slug uy-gemma2 --collection-name "UY · EmbeddingGemma 2" \
  --dry-run

# Configura DATABASE_URL en el entorno con el DSN de PostgreSQL.
python scripts/python/import_metadata_embeddings.py \
  /ruta/exp-del-cosechador /ruta/emb-uy-title-abstract \
  --collection-slug uy-gemma2 --collection-name "UY · EmbeddingGemma 2" \
  --report /ruta/uy-gemma2-import.json
```

| Parámetro | Comportamiento |
|---|---|
| `source` | Carpeta del export JSONL v2 `COMPLETE`, con `manifest.json` y `records.sqlite`. |
| `embeddings` | Carpeta del paquete `metadata-embeddings` v1 `COMPLETE`, con partes JSONL GZIP. |
| `--collection-slug` | Identificador nuevo de hasta 100 caracteres, en minúsculas, números, guiones o guiones bajos. |
| `--collection-name` | Nombre visible de hasta 200 caracteres. |
| `--ann` | `none` por defecto, con búsqueda exacta. `hnsw_vector` crea HNSW con coseno al terminar la carga y admite hasta 2.000 dimensiones. |
| `--batch-size` | Documentos por lote COPY, de 1 a 1.000. Por defecto 256. Todos los lotes quedan en la misma transacción. |
| `--dsn-env` | Nombre de la variable con la conexión. Por defecto `DATABASE_URL`. |
| `--dry-run` | Verifica manifiestos, hashes, alineación, textos y dimensiones. No abre PostgreSQL. |
| `--report` | Ruta nueva del informe JSON, obligatoria para la carga. Su directorio debe existir. |

### Modelo relacional y trazabilidad

- Cada documento y su colección de textos se escriben una vez. El
  `external_id` es el `record_key` de SQLite. La metadata del documento
  conserva `network`, `catalogId`, `identifier`, `metadataHash` y todo el
  objeto `data` del export.
- Se proyectan `titles`, `descriptions`, `abstracts` y `keywords` en
  `source_texts` como `title`, `description`, `abstract` y `keyword`.
  En `descriptions`, el primer componente de qualifier `abstract` cambia
  el tipo a `abstract`. Se conserva el idioma explícito cuando existe.
  Las ocurrencias en blanco quedan en `data`. Las demás se escriben sin
  normalización y con las mismas claves y hashes que espera v2.
- Cada posición de `embeddings` crea su propia indexación, identificada
  por el ID de la receta, con coseno y sin fragmentación. Cada documento
  tiene una unidad con `embedding_key` igual al ID de la receta por
  indexación. Se importa un vector `float32` finito y no nulo por posición,
  con la dimensión declarada. El perfil `import_only` se crea o se
  reutiliza por el fingerprint que usa v2. La búsqueda requiere
  `query.vector` del modelo y la receta de consulta correspondientes.
- El informe conserva el UUID de colección, el perfil e indexación por
  receta, sus parámetros, los hashes de los manifiestos, el exportId, la
  variante, los snapshots y los conteos. Conserva ese informe junto con
  ambos manifiestos. La procedencia compartida queda fuera de la
  metadata repetida de los documentos.

La carga comprueba `records.sqlite` en modo lectura. Una discrepancia,
un vector inválido o un error de PostgreSQL revierte la colección y las
tablas creadas en esa transacción. Un slug existente se rechaza. El informe
se publica después del commit. Si el commit se confirma y falla la
publicación del informe, el programa indica la ruta temporal para
recuperarlo.
