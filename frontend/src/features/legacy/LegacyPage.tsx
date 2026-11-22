import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { request, type Json } from '../../api'
import { useConsole } from '../../state/console'
import { Card } from '../../components/Card'
import { Output } from '../../components/Output'
import { field } from '../../state/utils'

export function LegacyPage() {
  const { t, base, apiOutput, setApiOutput, call, perform } = useConsole()
  const [dimensions, setDimensions] = useState('')
  const stores = useQuery({
    queryKey: ['v1-stores', base],
    queryFn: async ({ signal }) => (await request(base, '/api/v1/stores', { signal })).data as Json[],
  })
  return (
    <>
      <Card title="Stores">
        <div className="toolbar">
          <button className="secondary" onClick={() => void stores.refetch()}>
            {t.refresh}
          </button>
        </div>
        {stores.isLoading && <p role="status">{t.loading}</p>}
        {stores.isError && (
          <p role="alert" className="error">
            {stores.error instanceof Error ? stores.error.message : t.noApi}
          </p>
        )}
        {stores.data && stores.data.length === 0 && <p className="muted">{t.empty}</p>}
        {stores.data && (
          <div className="items">
            {stores.data.map((store) => (
              <div className="item" key={store.id}>
                <div>
                  <strong>{store.name}</strong>
                  <small>
                    {t.model}: {store.model} · {t.dimensions}: {store.embeddingDimensions} · {t.provider}:{' '}
                    {store.provider}
                  </small>
                </div>
              </div>
            ))}
          </div>
        )}
      </Card>
      <div className="grid">
        <Card title={t.createStore}>
          <form
            className="stack"
            onSubmit={(e) => {
              e.preventDefault()
              const f = new FormData(e.currentTarget)
              void perform(async () => {
                setApiOutput(
                  (
                    await call('/api/v1/stores', {
                      method: 'POST',
                      body: {
                        name: field(f, 'name'),
                        model: field(f, 'model'),
                        dimensions: Number(field(f, 'dimensions')),
                        createVectorIndex: f.has('ann'),
                      },
                    })
                  ).data,
                )
                await stores.refetch()
              })
            }}
          >
            <label>
              {t.name}
              <input name="name" required />
            </label>
            <label>
              {t.model}
              <input name="model" required />
            </label>
            <label>
              {t.dimensions}
              <input name="dimensions" type="number" min="1" required />
            </label>
            <label>
              <input type="checkbox" name="ann" /> ANN
            </label>
            <button>{t.create}</button>
          </form>
        </Card>
        <Card title={t.generation}>
          <form
            className="stack"
            onSubmit={(e) => {
              e.preventDefault()
              const f = new FormData(e.currentTarget)
              void perform(async () =>
                setApiOutput(
                  (
                    await call('/api/v1/embeddings', {
                      method: 'POST',
                      body: {
                        model: field(f, 'store_name'),
                        save: f.has('save'),
                        dimensions: Number(field(f, 'dimensions')),
                        input: field(f, 'input'),
                      },
                    })
                  ).data,
                ),
              )
            }}
          >
            <label>
              Store
              <select
                name="store_name"
                required
                defaultValue=""
                onChange={(event) => {
                  const selected = stores.data?.find((store) => store.name === event.target.value)
                  setDimensions(
                    selected?.embeddingDimensions == null ? '' : String(selected.embeddingDimensions),
                  )
                }}
              >
                <option value="">…</option>
                {(stores.data || []).map((store) => (
                  <option key={store.id} value={store.name}>
                    {store.name} · {store.model} · {store.embeddingDimensions}
                  </option>
                ))}
              </select>
            </label>
            <label>
              {t.dimensions}
              <input
                name="dimensions"
                type="number"
                min="1"
                value={dimensions}
                onChange={(event) => setDimensions(event.target.value)}
                required
              />
            </label>
            <label>
              {t.query}
              <textarea name="input" required />
            </label>
            <label>
              <input type="checkbox" name="save" /> {t.saveVector}
            </label>
            <button>{t.generation}</button>
          </form>
        </Card>
      </div>
      <Output data={apiOutput} title={t.result} />
    </>
  )
}
