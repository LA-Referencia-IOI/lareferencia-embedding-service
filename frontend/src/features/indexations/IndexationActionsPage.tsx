import { useConsole } from '../../state/console'
import { Card } from '../../components/Card'
import { Select } from '../../components/Select'
import { enc } from '../../api'
import { lines } from '../../state/utils'
export function IndexationActionsPage() {
  const [annStrategy, setAnnStrategy] = useState('hnsw_vector')
  const [maintenanceJob, setMaintenanceJob] = useState('')
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
    busy,
    setJob,
    setJobId,
    changePage,
  } = useConsole()
  const selectedIndex = indexes.find((item) => item.id === indexation)
  const needsStrategy = operation === 'build_ann' && selectedIndex?.ann_strategy === 'none'
  const dimensions = selectedIndex?.profile?.dimensions
  const strategyTooLarge =
    needsStrategy && typeof dimensions === 'number' && dimensions > (annStrategy === 'hnsw_vector' ? 2000 : 4000)
  const indexNotReady = operation === 'build_ann' && selectedIndex && selectedIndex.state !== 'ready'
  const canMaintain = Boolean(selectedIndex && attached.some((item) => item.id === collection))
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
            {needsStrategy && (
              <label>
                {t.annStrategy}
                <select value={annStrategy} onChange={(e) => setAnnStrategy(e.target.value)}>
                  <option value="hnsw_vector">HNSW vector (≤ 2000)</option>
                  <option value="hnsw_halfvec">HNSW halfvec (≤ 4000)</option>
                </select>
              </label>
            )}
            <button
              disabled={!canMaintain || busy || strategyTooLarge || Boolean(indexNotReady)}
              className="secondary"
              onClick={() =>
                void perform(async () => {
                  setMaintenanceJob('')
                  const result = await mutate(
                    `/api/v2/collections/${enc(collection)}/indexations/${enc(indexation)}/maintenance-jobs`,
                    needsStrategy ? { operation, ann_strategy: annStrategy } : { operation },
                  )
                  if (result.status === 202 && result.data?.id) setMaintenanceJob(result.data.id)
                })
              }
            >
              {t.maintenance}
            </button>
          </div>
          {needsStrategy && <p>{t.annEnableNotice}</p>}
          {strategyTooLarge && <p role="alert">{t.annDimensionsUnsupported}</p>}
          {indexNotReady && <p role="alert">{t.annIndexNotReady}</p>}
          {maintenanceJob && (
            <button
              className="secondary"
              onClick={() => {
                setJob(null)
                setJobId(maintenanceJob)
                changePage('jobs')
              }}
            >
              {t.viewMaintenanceJob}
            </button>
          )}
        </div>
      </Card>
    </>
  )
}
import { useState } from 'react'
