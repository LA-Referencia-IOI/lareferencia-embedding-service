import { useConsole } from '../../state/console'
import { Card } from '../../components/Card'
import { Select } from '../../components/Select'
import { Items } from '../../components/Items'
import { Output } from '../../components/Output'
import { enc } from '../../api'
export function IndexationsPage() {
  const {
    t,
    collections,
    setIndexation,
    result,
    setResult,
    apiOutput,
    setApiOutput,
    listCursor,
    entries,
    filterCollection,
    setFilterCollection,
    call,
    perform,
    loadList,
    changePage,
  } = useConsole()
  return (
    <>
      <div className="page-actions">
        <button onClick={() => changePage('indexation-create')}>
          {t.create} {t.indexation}
        </button>
      </div>
      <div>
        <Card title={t.indexations}>
          <Select
            label={t.collection}
            value={filterCollection}
            set={setFilterCollection}
            items={collections}
            optional
          />
          <button className="secondary" onClick={() => void perform(() => loadList('indexations'))}>
            {t.refresh}
          </button>
          <Items
            t={t}
            items={entries}
            onSelect={(x) => {
              setIndexation(x.id)
              setResult(x)
              void perform(async () => {
                const r = await call(`/api/v2/indexations/${enc(x.id)}/collections`)
                setApiOutput(r.data)
              })
            }}
          />
          {listCursor && (
            <button onClick={() => void perform(() => loadList('indexations', listCursor))}>{t.next}</button>
          )}
          <Output data={result} title={t.details} />
          <Output data={apiOutput} title={t.collections} />
        </Card>
      </div>
      <button onClick={() => changePage('indexation-actions')}>{t.actions}</button>
    </>
  )
}
