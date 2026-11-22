import { useConsole } from '../../state/console'
import { Card } from '../../components/Card'
import { Select } from '../../components/Select'
import { ApiError, enc, query, type Json } from '../../api'
export function DocumentEditPage() {
  const {
    lang,
    t,
    setNotice,
    collections,
    collection,
    setCollection,
    doc,
    setDoc,
    etag,
    setEtag,
    external,
    setExternal,
    meta,
    setMeta,
    texts,
    setTexts,
    conflict,
    setConflict,
    call,
    perform,
    loadDoc,
    docsPath,
    listDocs,
  } = useConsole()
  return (
    <>
      <Select
        label={t.collection}
        value={collection}
        set={(value) => {
          if (value !== collection && doc && !window.confirm(t.changeCollection)) return
          setCollection(value)
          setDoc(null)
          setEtag('')
          setExternal('')
          setMeta('{}')
          setTexts([])
          setConflict(false)
        }}
        items={collections}
      />
      <Card title={t.document}>
        <form
          className="stack"
          onSubmit={(e) => {
            e.preventDefault()
            const action = (e.nativeEvent as SubmitEvent).submitter as HTMLButtonElement
            const method = action.value
            if (method === 'PUT' && !window.confirm(t.confirmReplace)) return
            void perform(async () => {
              let metadata
              try {
                metadata = JSON.parse(meta)
              } catch {
                throw new Error(t.jsonInvalid)
              }
              const path = `${docsPath}/by-external-id${query({ external_id: external })}`
              if (doc && !etag)
                throw new Error(
                  lang === 'pt'
                    ? 'ETag indisponível. Verifique a configuração CORS antes de editar.'
                    : 'ETag no disponible. Verifique CORS antes de editar.',
                )
              if (!doc) {
                try {
                  await call(path)
                  throw new Error(
                    lang === 'pt'
                      ? 'Documento existente: consulte-o antes de editar.'
                      : 'Documento existente: consúltelo antes de editar.',
                  )
                } catch (e) {
                  if (!(e instanceof ApiError && e.status === 404)) throw e
                }
              }
              const res = await call(path, {
                method,
                body: { metadata, texts: texts.map((x) => ({ ...x, lng: x.lng || null })) },
                etag: etag || undefined,
              })
              setDoc(res.data)
              setEtag(res.etag)
              setConflict(false)
              setNotice(t.save + ' ✓')
              await listDocs()
            })
          }}
        >
          <label>
            {t.external}
            <input
              value={external}
              onChange={(e) => {
                setExternal(e.target.value)
                if (doc?.external_id !== e.target.value) {
                  setDoc(null)
                  setEtag('')
                }
              }}
              required
            />
          </label>
          <label>
            {t.metadata}
            <textarea rows={4} value={meta} onChange={(e) => setMeta(e.target.value)} />
          </label>
          <strong>{t.texts}</strong>
          {texts.map((x, i) => (
            <div className="card stack" key={i}>
              <div className="row">
                <label>
                  {t.kind}
                  <input
                    value={x.knd}
                    onChange={(e) =>
                      setTexts(texts.map((v, j) => (j === i ? { ...v, knd: e.target.value } : v)))
                    }
                    required
                  />
                </label>
                <label>
                  {t.language}
                  <input
                    value={x.lng}
                    onChange={(e) =>
                      setTexts(texts.map((v, j) => (j === i ? { ...v, lng: e.target.value } : v)))
                    }
                  />
                </label>
              </div>
              <label>
                {t.content}
                <textarea
                  value={x.cnt}
                  onChange={(e) =>
                    setTexts(texts.map((v, j) => (j === i ? { ...v, cnt: e.target.value } : v)))
                  }
                  required
                />
              </label>
              <div className="toolbar">
                <button
                  type="button"
                  className="secondary"
                  onClick={() => setTexts(texts.filter((_, j) => j !== i))}
                >
                  {t.remove}
                </button>
                {doc && (
                  <button
                    type="button"
                    className="danger"
                    disabled={
                      !etag ||
                      JSON.stringify(texts) !==
                        JSON.stringify(
                          (doc.texts || []).map((v: Json) => ({
                            knd: v.knd,
                            lng: v.lng || '',
                            cnt: v.cnt,
                          })),
                        )
                    }
                    onClick={() => {
                      if (!window.confirm(`${t.confirm} ${t.textWarning}`)) return
                      const ordinal = texts
                        .slice(0, i)
                        .filter((v) => v.knd === x.knd && v.lng === x.lng).length
                      void perform(async () => {
                        await call(
                          `${docsPath}/${enc(doc.id)}/texts${query({ knd: x.knd, lng: x.lng, ordinal })}`,
                          { method: 'DELETE', etag },
                        )
                        await loadDoc(`${docsPath}/${enc(doc.id)}`)
                      })
                    }}
                  >
                    {t.delete}
                  </button>
                )}
              </div>
            </div>
          ))}
          <button
            type="button"
            className="secondary"
            onClick={() => setTexts([...texts, { knd: '', lng: '', cnt: '' }])}
          >
            {t.add} +
          </button>
          <div className="toolbar">
            <button type="submit" value="PATCH" disabled={!collection}>
              PATCH · {t.save}
            </button>
            <button type="submit" value="PUT" className="secondary" disabled={!collection}>
              PUT · {t.save}
            </button>
            {doc && (
              <button
                type="button"
                className="danger"
                disabled={!etag}
                onClick={() => {
                  if (window.confirm(t.confirm))
                    void perform(async () => {
                      await call(`${docsPath}/${enc(doc.id)}`, { method: 'DELETE', etag })
                      setDoc(null)
                      setEtag('')
                      setTexts([])
                      await listDocs()
                    })
                }}
              >
                {t.delete}
              </button>
            )}
            {conflict && doc && (
              <button
                type="button"
                className="secondary"
                onClick={() => void perform(() => loadDoc(`${docsPath}/${enc(doc.id)}`))}
              >
                {t.reload}
              </button>
            )}
          </div>
        </form>
      </Card>
    </>
  )
}
