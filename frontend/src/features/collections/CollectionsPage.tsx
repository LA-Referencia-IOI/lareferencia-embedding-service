import { useConsole } from '../../state/console'
import { Card } from '../../components/Card'
import { Select } from '../../components/Select'
import { Items } from '../../components/Items'
import { Pagination } from '../../components/Pagination'
import { Output } from '../../components/Output'
import { enc, query } from '../../api'
export function CollectionsPage() {
  const {
    t,
    lang,
    collections,
    collection,
    setCollection,
    result,
    setResult,
    docs,
    docPage,
    docTotal,
    resetDocs,
    doc,
    setDoc,
    setEtag,
    setExternal,
    lookup,
    setLookup,
    lookupType,
    setLookupType,
    setMeta,
    setTexts,
    listCursor,
    entries,
    call,
    perform,
    loadDoc,
    docsPath,
    listDocs,
    loadList,
    changePage,
  } = useConsole()
  return (
    <>
      <div className="page-actions">
        <button onClick={() => changePage('collection-create')}>
          {t.create} {t.collection}
        </button>
      </div>
      <div>
        <Card title={t.collection}>
          <div className="toolbar">
            <button className="secondary" onClick={() => void perform(() => loadList('collections'))}>
              {t.refresh}
            </button>
            {listCursor && (
              <button onClick={() => void perform(() => loadList('collections', listCursor))}>
                {t.next}
              </button>
            )}
          </div>
          <Items
            t={t}
            items={entries}
            onSelect={(x) => {
              setCollection(x.id)
              setDoc(null)
              resetDocs()
              void perform(async () => {
                const r = await call(`/api/v2/collections/${enc(x.id)}`)
                const links = await call(`/api/v2/collections/${enc(x.id)}/indexations?limit=100`)
                setResult({ ...r.data, indexations: links.data.items })
                await listDocs(1, x.id)
              })
            }}
          />
        </Card>
      </div>
      <Output data={result} title={t.details} />
      {collection && (
        <>
          <Card title={t.documents}>
            <div className="row">
              <Select
                label={t.collection}
                value={collection}
                set={(v) => {
                  setCollection(v)
                  setDoc(null)
                  resetDocs()
                }}
                items={collections}
              />
              <button className="secondary" onClick={() => void perform(() => listDocs())}>
                {t.refresh}
              </button>
            </div>
            <form
              className="row"
              onSubmit={(e) => {
                e.preventDefault()
                void perform(() =>
                  loadDoc(
                    lookupType === 'external'
                      ? `${docsPath}/by-external-id${query({ external_id: lookup })}`
                      : `${docsPath}/${enc(lookup)}`,
                  ),
                )
              }}
            >
              <label>
                {t.find}
                <select value={lookupType} onChange={(e) => setLookupType(e.target.value)}>
                  <option value="external">{t.external}</option>
                  <option value="internal">{t.internal}</option>
                </select>
              </label>
              <label>
                ID
                <input value={lookup} onChange={(e) => setLookup(e.target.value)} required />
              </label>
              <button>{t.find}</button>
            </form>
            {docTotal !== null && (
              <p>
                {t.totalDocuments}: {docTotal.toLocaleString(lang === 'pt' ? 'pt-BR' : 'es')}
              </p>
            )}
            <Items
              t={t}
              items={docs}
              onSelect={(x) => void perform(() => loadDoc(`${docsPath}/${enc(x.id)}`))}
            />
            <Pagination
              total={docTotal}
              page={docPage}
              pageSize={25}
              label={t.documentPagination}
              previousLabel={t.previous}
              nextLabel={t.next}
              pageLabel={t.page}
              onPage={(value) => void perform(() => listDocs(value))}
            />
          </Card>
          <>
            {doc && (
              <>
                <Output data={doc} title={t.document} />
                <button onClick={() => changePage('document-edit')}>
                  {t.edit} {t.document}
                </button>
              </>
            )}
          </>
          <div className="toolbar">
            <button
              onClick={() => {
                setDoc(null)
                setEtag('')
                setExternal('')
                setMeta('{}')
                setTexts([])
                changePage('document-edit')
              }}
            >
              {t.create} {t.document}
            </button>
            <button onClick={() => changePage('imports')}>{t.import}</button>
          </div>
        </>
      )}
    </>
  )
}
