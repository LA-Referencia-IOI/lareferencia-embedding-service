import { useConsole } from '../../state/console'
import { Card } from '../../components/Card'
import { Select } from '../../components/Select'
import { enc } from '../../api'
import { lines } from '../../state/utils'
export function IndexationActionsPage() {
  const {
    t,
    setNotice,
    collections,
    indexes,
    collection,
    setCollection,
    indexation,
    setIndexation,
    docIds,
    setDocIds,
    operation,
    setOperation,
    call,
    loadReference,
    perform,
    mutate,
    attached,
  } = useConsole()
  return (
    <>
      <Card title={t.attach}>
        <div className="row">
          <Select label={t.indexation} value={indexation} set={setIndexation} items={indexes} />
          <Select label={t.collection} value={collection} set={setCollection} items={collections} />
          <button
            disabled={!collection || !indexation}
            onClick={() =>
              void perform(async () => {
                await call(`/api/v2/indexations/${enc(indexation)}/collections/${enc(collection)}`, {
                  method: 'PUT',
                })
                setNotice(t.save + ' ✓')
                await loadReference()
              })
            }
          >
            {t.attach}
          </button>
        </div>
      </Card>
      <Card title={t.generation + ' / ' + t.maintenance}>
        <div className="stack">
          <div className="row">
            <Select label={t.indexation} value={indexation} set={setIndexation} items={indexes} />
            <Select label={t.collection} value={collection} set={setCollection} items={attached} />
          </div>
          <label>
            {t.internal} (comma)
            <textarea
              rows={2}
              value={docIds}
              onChange={(e) => setDocIds(e.target.value)}
              placeholder={t.allDocs}
            />
          </label>
          <div className="toolbar">
            <button
              disabled={!collection || !indexation}
              onClick={() =>
                void perform(() =>
                  mutate(
                    `/api/v2/collections/${enc(collection)}/indexations/${enc(indexation)}/generation-jobs`,
                    { document_ids: lines(docIds) },
                  ).then(() => {}),
                )
              }
            >
              {t.generation}
            </button>
            <select
              aria-label={t.maintenance}
              value={operation}
              onChange={(e) => setOperation(e.target.value)}
              style={{ width: 'auto' }}
            >
              <option>build_ann</option>
              <option>reconcile</option>
            </select>
            <button
              disabled={!collection || !indexation}
              className="secondary"
              onClick={() =>
                void perform(() =>
                  mutate(
                    `/api/v2/collections/${enc(collection)}/indexations/${enc(indexation)}/maintenance-jobs`,
                    { operation },
                  ).then(() => {}),
                )
              }
            >
              {t.maintenance}
            </button>
          </div>
        </div>
      </Card>
    </>
  )
}
