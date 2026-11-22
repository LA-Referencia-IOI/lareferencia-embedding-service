import { useConsole } from '../../state/console'
import { Card } from '../../components/Card'
import { Select } from '../../components/Select'
import { enc, query, type Json } from '../../api'
import { pretty } from '../../state/utils'

export function SearchPage() {
  const {
    t,
    busy,
    collections,
    indexes,
    collection,
    setCollection,
    indexation,
    setIndexation,
    result,
    setResult,
    queryText,
    setQueryText,
    queryProfile,
    setQueryProfile,
    mode,
    setMode,
    top,
    setTop,
    group,
    setGroup,
    includeTexts,
    setIncludeTexts,
    includeMeta,
    setIncludeMeta,
    call,
    perform,
    activeIndex,
    indexProfile,
    candidates,
    attached,
  } = useConsole()
  return (
    <>
      <Card title={t.search}>
        <form
          className="stack"
          onSubmit={(e) => {
            e.preventDefault()
            void perform(async () => {
              const r = await call(
                `/api/v2/indexations/${enc(indexation)}/search${query({ collection_id: collection })}`,
                {
                  method: 'POST',
                  body: {
                    query: { text: queryText, ...(queryProfile ? { profile_id: queryProfile } : {}) },
                    mode,
                    top_n: top,
                    result_unit: group,
                    include: [...(includeTexts ? ['texts'] : []), ...(includeMeta ? ['metadata'] : [])],
                  },
                },
              )
              const hits: Json[] = r.data.results || []
              const documents = new Map<string, Json>()
              const collectionNames = new Map<string, string>(collections.map((c) => [c.id, c.name]))
              const uniqueDocuments = [
                ...new Map(hits.map((hit) => [`${hit.collection_id}/${hit.document_id}`, hit])).values(),
              ]
              for (let offset = 0; offset < uniqueDocuments.length; offset += 10) {
                const batch = uniqueDocuments.slice(offset, offset + 10)
                await Promise.all(
                  batch.map(async (hit) => {
                    try {
                      const document = await call(
                        `/api/v2/collections/${enc(hit.collection_id)}/documents/${enc(hit.document_id)}`,
                      )
                      documents.set(`${hit.collection_id}/${hit.document_id}`, document.data)
                    } catch {
                      /* Keep the search result visible if a document cannot be retrieved. */
                    }
                  }),
                )
              }
              const missingCollections = [...new Set(hits.map((hit) => hit.collection_id as string))].filter(
                (id) => !collectionNames.has(id),
              )
              for (let offset = 0; offset < missingCollections.length; offset += 10) {
                await Promise.all(
                  missingCollections.slice(offset, offset + 10).map(async (id) => {
                    try {
                      const response = await call(`/api/v2/collections/${enc(id)}`)
                      if (response.data?.name) collectionNames.set(id, response.data.name)
                    } catch {
                      /* Fall back to the collection ID. */
                    }
                  }),
                )
              }
              setResult({
                ...r.data,
                results: hits.map((hit) => ({
                  ...hit,
                  titleUnavailable: !documents.has(`${hit.collection_id}/${hit.document_id}`),
                  displayTitle: documents
                    .get(`${hit.collection_id}/${hit.document_id}`)
                    ?.texts?.find(
                      (text: Json) => text.knd === 'title' && typeof text.cnt === 'string' && text.cnt.trim(),
                    )?.cnt,
                  collectionName: collectionNames.get(hit.collection_id) || hit.collection_id,
                })),
              })
            })
          }}
        >
          <div className="row">
            <Select
              label={t.indexation}
              value={indexation}
              set={(v) => {
                setIndexation(v)
                setMode('exact')
                setQueryProfile('')
                setCollection('')
              }}
              items={indexes}
            />
            <Select
              label={`${t.collection} (${t.optional})`}
              value={collection}
              set={setCollection}
              items={attached}
              optional
            />
          </div>
          <label>
            {t.query}
            <textarea rows={4} value={queryText} onChange={(e) => setQueryText(e.target.value)} required />
          </label>
          {indexProfile?.execution_mode === 'import_only' && (
            <>
              <p className="notice">{t.importOnly}</p>
              {candidates.length === 0 ? (
                <p role="alert" className="error">
                  {t.missingProfile}
                </p>
              ) : (
                <Select label={t.profile} value={queryProfile} set={setQueryProfile} items={candidates} />
              )}
            </>
          )}
          <details>
            <summary>{t.advanced}</summary>
            <div className="grid">
              <label>
                {t.mode}
                <select value={mode} onChange={(e) => setMode(e.target.value)}>
                  <option value="exact">{t.exact}</option>
                  <option value="approximate" disabled={!activeIndex?.capabilities?.approximate}>
                    {t.approximate}
                  </option>
                </select>
              </label>
              <label>
                {t.count}
                <input
                  type="number"
                  min="1"
                  max="100"
                  value={top}
                  onChange={(e) => setTop(Number(e.target.value))}
                />
              </label>
              <label>
                {t.group}
                <select value={group} onChange={(e) => setGroup(e.target.value)}>
                  <option value="document">{t.document}</option>
                  <option value="unit">Unit</option>
                </select>
              </label>
            </div>
            <div className="toolbar">
              <label>
                <input
                  type="checkbox"
                  checked={includeTexts}
                  onChange={(e) => setIncludeTexts(e.target.checked)}
                />{' '}
                {t.texts}
              </label>
              <label>
                <input
                  type="checkbox"
                  checked={includeMeta}
                  onChange={(e) => setIncludeMeta(e.target.checked)}
                />{' '}
                {t.metadata}
              </label>
            </div>
          </details>
          <button
            disabled={
              busy || !indexation || (indexProfile?.execution_mode === 'import_only' && !queryProfile)
            }
          >
            {t.searchButton}
          </button>
        </form>
      </Card>
      {result && (
        <Card title={t.results}>
          {(result.results || []).length === 0 && <p>{t.empty}</p>}
          {(result.results || []).map((x: Json, i: number) => (
            <div className="list-line" key={x.unit_id || i}>
              <strong>{x.displayTitle || (x.titleUnavailable ? t.titleUnavailable : t.untitled)}</strong>
              <p className="muted">
                {t.external}: {x.external_id || x.document_id} · {t.collection}: {x.collectionName} ·{' '}
                {t.distance}: {x.distance}
              </p>
              {x.text && <p>{x.text}</p>}
              {x.metadata && <pre>{pretty(x.metadata)}</pre>}
            </div>
          ))}
        </Card>
      )}
    </>
  )
}
