# Phase 4: documents and source texts

Enable v2 with `DVP_ENABLED=true`. These endpoints use the existing deployment access boundary; a collection is not a security tenant. Create a collection first as described in [Phases 1–3 operations](phases-1-3-operations.md). No UI or inference call is included in document ingestion.

All document routes are under `/api/v2/collections/{collection_id}/documents`. External IDs and text IDs are case-sensitive and preserved exactly; URL-encode query values, including `/` and `:`. IDs are scoped to the collection and text keys to the document.

- `PATCH /by-external-id?external_id=...` creates a document or partially updates it. When present, `metadata` replaces the whole object; supplied text keys are upserted and omitted keys remain. At least one field is required.
- `PUT /by-external-id?external_id=...` creates or replaces a snapshot. Both `metadata` and `texts` are required; omitted existing text keys are removed. An empty array removes all texts.
- `GET /by-external-id?external_id=...` and `GET /{document_id}` return the document, metadata, source texts and revision. Creation returns 201 and `Location`; updates return 200. Both return an `ETag`.
- `GET` on the base path lists summaries without source bodies or metadata. Use `include=metadata` to include metadata; `limit` is 1–1000 (default 100). Follow `next_cursor` with the same collection and projection. Pages do not share one snapshot.
- `DELETE /{document_id}` deletes a document and its representations; `DELETE /{document_id}/texts?text_id=...` deletes one source text and its representations. Both return 204; text deletion returns the new document `ETag`.

Example, substituting a collection UUID:

```bash
curl -X PATCH 'http://localhost:8989/api/v2/collections/COLLECTION_UUID/documents/by-external-id?external_id=doi%3A10.1234%2Fexample' \
  -H 'Content-Type: application/json' \
  -d '{"metadata":{"title":"Example"},"texts":[{"text_id":"abstract","kind":"abstract","language":"en","content":"Original text."}]}'
```

Mutations accept an optional `If-Match: "revision"` and optional `Idempotency-Key`. A stale precondition, including a missing document in an existing collection, returns 412. Repeating the same operation and key replays the original response; changing its body, query or precondition returns 409. Use a fresh key for a different operation. Without a precondition, deleting a missing resource returns 404. Idempotency records can contain response bodies, including source text, and follow the existing retention policy; restrict database access accordingly.

The source hash is SHA-256 of the exact UTF-8 content; v1 text normalization is not applied. Identical text keys/content and metadata do not advance revisions or timestamps. Actual source content, kind or language changes advance the source and document revisions and remove the source's vector units and dependent vectors across indexations. Metadata-only changes preserve representations. Removing a source or document cascades its representations. Regeneration or import is needed to restore removed vectors once those features are available; ingestion does not generate them.

The default request and combined document limits are each 16 MiB (`app.dvp.max-request-bytes`, `app.dvp.max-document-bytes`). The combined limit measures compact UTF-8 JSON containing the final metadata and texts, so repeated PATCH requests cannot bypass it. Metadata defaults to 256 KiB (`app.dvp.max-metadata-bytes`); source count defaults to 1000 (`app.dvp.max-texts`) and content to 2,000,000 Unicode code points (`app.dvp.max-text-code-points`). External IDs allow 512 code points/2048 UTF-8 bytes and text keys 200/800. NUL and invalid Unicode are rejected. Request limits apply independently of the combined limit.

The existing V3 schema supplies the document, source and cascade tables; this phase adds no migration and does not reindex legacy v1 stores. Upgrades use the existing Flyway baseline procedure. To roll back the application, disable v2 or restore the previous application version while retaining the migrated schema and data; do not drop tables or vectors as a rollback. Actual source edits/deletes are intentional data changes and require a consistent backup for recovery. Do not enable JDBC bind-value tracing in deployments that handle private content.

For isolated real-database verification, configure the disposable pgvector test database as described in [Phases 1–3 operations](phases-1-3-operations.md#reproducible-verification), then run:

```bash
DVP_TEST_DB_URL=jdbc:postgresql://127.0.0.1:55439/dvp_test mvn -o clean verify
```

## Request and response examples

Examples use `http://localhost:8989`; substitute the UUIDs returned by your server. Response IDs, timestamps and hashes are illustrative. `If-Match` uses the current quoted ETag and is optional. Requests that create or change a document can optionally include an `Idempotency-Key`.

### PATCH: create or partially update by external ID

```http
PATCH /api/v2/collections/11111111-1111-4111-8111-111111111111/documents/by-external-id?external_id=doi%3A10.1234%2Fexample
Content-Type: application/json

{"metadata":{"title":"Example"},"texts":[{"text_id":"abstract","kind":"abstract","language":"en","content":"Example text."}]}
```

```http
HTTP/1.1 201 Created
Location: /api/v2/collections/11111111-1111-4111-8111-111111111111/documents/22222222-2222-4222-8222-222222222222
ETag: "1"
Content-Type: application/json

{"id":"22222222-2222-4222-8222-222222222222","external_id":"doi:10.1234/example","collection_id":"11111111-1111-4111-8111-111111111111","revision":1,"metadata":{"title":"Example"},"texts":[{"text_id":"abstract","kind":"abstract","language":"en","content":"Example text.","revision":1,"content_hash":"14a30c9b8fabb27151dfbf709bc2f5bf32743b1f02813e140deb02f54b1ca70c","updated_at":"2026-11-10T12:00:00Z"}],"created_at":"2026-11-10T12:00:00Z","updated_at":"2026-11-10T12:00:00Z"}
```

An existing document returns 200 with its current representation and ETag.

### PUT: replace a snapshot

```http
PUT /api/v2/collections/11111111-1111-4111-8111-111111111111/documents/by-external-id?external_id=doi%3A10.1234%2Fexample
Content-Type: application/json
If-Match: "1"

{"metadata":{"title":"Revised"},"texts":[]}
```

```http
HTTP/1.1 200 OK
ETag: "2"
Content-Type: application/json

{"id":"22222222-2222-4222-8222-222222222222","external_id":"doi:10.1234/example","collection_id":"11111111-1111-4111-8111-111111111111","revision":2,"metadata":{"title":"Revised"},"texts":[],"created_at":"2026-11-10T12:00:00Z","updated_at":"2026-11-10T12:01:00Z"}
```

A new snapshot returns 201 and a Location. An outdated `If-Match` returns 412.

### GET: retrieve by external ID or document UUID

```http
GET /api/v2/collections/11111111-1111-4111-8111-111111111111/documents/by-external-id?external_id=doi%3A10.1234%2Fexample
```

```http
GET /api/v2/collections/11111111-1111-4111-8111-111111111111/documents/22222222-2222-4222-8222-222222222222
```

Both return the same full representation:

```http
HTTP/1.1 200 OK
ETag: "2"
Content-Type: application/json

{"id":"22222222-2222-4222-8222-222222222222","external_id":"doi:10.1234/example","collection_id":"11111111-1111-4111-8111-111111111111","revision":2,"metadata":{"title":"Revised"},"texts":[],"created_at":"2026-11-10T12:00:00Z","updated_at":"2026-11-10T12:01:00Z"}
```

### GET: list documents

```http
GET /api/v2/collections/11111111-1111-4111-8111-111111111111/documents?limit=100&include=metadata
```

```http
HTTP/1.1 200 OK
Content-Type: application/json

{"items":[{"id":"22222222-2222-4222-8222-222222222222","external_id":"doi:10.1234/example","collection_id":"11111111-1111-4111-8111-111111111111","revision":2,"metadata":{"title":"Revised"},"created_at":"2026-11-10T12:00:00Z","updated_at":"2026-11-10T12:01:00Z"}],"next_cursor":null}
```

Without `include=metadata`, the summary omits metadata. Pass a returned non-null `next_cursor` as `cursor` to fetch the next page.

### DELETE: one text or the complete document

These are independent examples; the text must exist before deleting it.

```http
DELETE /api/v2/collections/11111111-1111-4111-8111-111111111111/documents/22222222-2222-4222-8222-222222222222/texts?text_id=abstract
If-Match: "1"
```

```http
HTTP/1.1 204 No Content
ETag: "2"
```

```http
DELETE /api/v2/collections/11111111-1111-4111-8111-111111111111/documents/22222222-2222-4222-8222-222222222222
If-Match: "2"
```

```http
HTTP/1.1 204 No Content
```

A 204 response has no body.
