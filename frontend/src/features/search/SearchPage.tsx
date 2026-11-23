import { useState } from 'react'
import { compatibleBrowserModels, prepareBrowserQuery } from './browser/embedding'
import { browserEmbedding } from './browser/workerClient'
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
  const [embeddingSource, setEmbeddingSource] = useState<'server' | 'browser'>('server')
  const [browserModelId, setBrowserModelId] = useState('')
  const [browserStatus, setBrowserStatus] = useState('')
  const [modeOverride, setModeOverride] = useState<{ indexation: string; mode: 'exact' | 'approximate' } | null>(null)
  const approximateAvailable = activeIndex?.capabilities?.approximate === true
  const preferredMode = modeOverride?.indexation === indexation ? modeOverride.mode : 'approximate'
  const mode = preferredMode === 'approximate' && !approximateAvailable ? 'exact' : preferredMode
  const browserModels = compatibleBrowserModels(indexProfile)
  const browserModel = browserModels.find((model) => model.id === browserModelId)
  const browserAvailable =
    typeof window !== 'undefined' && window.isSecureContext && 'gpu' in navigator && !!navigator.gpu
  return (
    <>
      <Card title={t.search}>
        <form
          className="stack"
          onSubmit={(e) => {
            e.preventDefault()
            void perform(async () => {
              let searchQuery: { text: string; profile_id?: string } | { vector: number[] }
              if (embeddingSource === 'browser') {
                if (
                  !browserModel ||
                  !indexProfile ||
                  !compatibleBrowserModels(indexProfile).some((model) => model.id === browserModel.id)
                )
                  throw new Error(t.browserIncompatible)
                if (!browserAvailable) throw new Error(t.browserUnavailable)
                setBrowserStatus(t.browserLoading)
                try {
                  const prepared = prepareBrowserQuery(indexProfile, queryText)
                  const vector = await browserEmbedding(prepared, browserModel.dimensions)
                  searchQuery = { vector }
                } finally {
                  setBrowserStatus('')
                }
              } else {
                searchQuery = { text: queryText, ...(queryProfile ? { profile_id: queryProfile } : {}) }
              }
              const r = await call(
                `/api/v2/indexations/${enc(indexation)}/search${query({ collection_id: collection })}`,
                {
                  method: 'POST',
                  body: {
                    query: searchQuery,
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
                setModeOverride(null)
                setQueryProfile('')
                setBrowserModelId('')
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
          <label>
            {t.embeddingSource}
            <select
              value={embeddingSource}
              onChange={(event) => {
                setEmbeddingSource(event.target.value as 'server' | 'browser')
                setQueryProfile('')
                setBrowserModelId('')
              }}
            >
              <option value="server">{t.embeddingServer}</option>
              <option value="browser">{t.embeddingBrowser}</option>
            </select>
          </label>
          {embeddingSource === 'browser' && (
            <>
              <p className="notice">{t.browserModelNotice}</p>
              {!browserAvailable && (
                <p className="error" role="alert">
                  {t.browserUnavailable}
                </p>
              )}
              {browserModels.length === 0 ? (
                <p className="error" role="alert">
                  {t.browserIncompatible}
                </p>
              ) : (
                <label>
                  {t.browserModel}
                  <select
                    value={browserModelId}
                    onChange={(event) => setBrowserModelId(event.target.value)}
                    required
                  >
                    <option value="">…</option>
                    {browserModels.map((model) => (
                      <option key={model.id} value={model.id}>
                        {model.model} · {model.dimensions} {t.dimensions}
                      </option>
                    ))}
                  </select>
                </label>
              )}
              {browserStatus && <p role="status">{browserStatus}</p>}
            </>
          )}
          {embeddingSource === 'server' && indexProfile?.execution_mode === 'import_only' && (
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
                <select value={mode} onChange={(e) => setModeOverride({ indexation, mode: e.target.value as 'exact' | 'approximate' })}>
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
              busy ||
              !indexation ||
              (embeddingSource === 'server' &&
                indexProfile?.execution_mode === 'import_only' &&
                !queryProfile) ||
              (embeddingSource === 'browser' && (!browserAvailable || !browserModel))
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
