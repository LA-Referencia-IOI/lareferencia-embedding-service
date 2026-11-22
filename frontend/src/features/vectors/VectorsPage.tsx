import { useConsole } from '../../state/console'
import { Card } from '../../components/Card'
import { Select } from '../../components/Select'
import { enc, query, type Json } from '../../api'
import { pretty } from '../../state/utils'

export function VectorsPage() {
  const {
    t,
    indexes,
    collection,
    setCollection,
    indexation,
    setIndexation,
    vectorId,
    setVectorId,
    vectorType,
    setVectorType,
    vectorCursor,
    setVectorCursor,
    vectorData,
    setVectorData,
    call,
    perform,
    attached,
  } = useConsole()
  return (
    <>
      <Card title={t.vectors}>
        <form
          className="stack"
          onSubmit={(e) => {
            e.preventDefault()
            setVectorCursor('')
            void perform(async () => {
              const path =
                vectorType === 'external'
                  ? `/api/v2/indexations/${enc(indexation)}/vectors${query({ external_id: vectorId, collection_id: collection, limit: 25 })}`
                  : `/api/v2/indexations/${enc(indexation)}/documents/${enc(vectorId)}/vectors${query({ collection_id: collection, limit: 25 })}`
              const r = await call(path)
              setVectorData(r.data)
              setVectorCursor(r.data?.next_cursor || '')
            })
          }}
        >
          <div className="row">
            <Select
              label={t.indexation}
              value={indexation}
              set={(v) => {
                setIndexation(v)
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
            {t.find}
            <select value={vectorType} onChange={(e) => setVectorType(e.target.value)}>
              <option value="external">{t.external}</option>
              <option value="internal">{t.internal}</option>
            </select>
          </label>
          <label>
            ID
            <input value={vectorId} onChange={(e) => setVectorId(e.target.value)} required />
          </label>
          <button disabled={!indexation}>{t.find}</button>
        </form>
      </Card>
      {vectorData && (
        <Card title={`${t.vectors} · ${vectorData.model || ''}`}>
          <div>
            {(vectorData.data || []).map((x: Json, i: number) => (
              <div key={i} className="list-line">
                <strong>Embedding {x.index}</strong>
                <p className="vector">
                  [{(x.embedding || []).slice(0, 8).join(', ')}
                  {(x.embedding || []).length > 8 ? ', …' : ''}]
                </p>
              </div>
            ))}
          </div>
          <div className="toolbar">
            <button className="secondary" onClick={() => navigator.clipboard.writeText(pretty(vectorData))}>
              {t.copy}
            </button>
            {vectorCursor && (
              <button
                onClick={() =>
                  void perform(async () => {
                    const path =
                      vectorType === 'external'
                        ? `/api/v2/indexations/${enc(indexation)}/vectors${query({ external_id: vectorId, collection_id: collection, limit: 25, cursor: vectorCursor })}`
                        : `/api/v2/indexations/${enc(indexation)}/documents/${enc(vectorId)}/vectors${query({ collection_id: collection, limit: 25, cursor: vectorCursor })}`
                    const r = await call(path)
                    setVectorData(r.data)
                    setVectorCursor(r.data.next_cursor || '')
                  })
                }
              >
                {t.next}
              </button>
            )}
          </div>
        </Card>
      )}
    </>
  )
}
