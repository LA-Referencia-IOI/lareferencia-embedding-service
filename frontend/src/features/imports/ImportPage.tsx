import { useConsole } from '../../state/console'
import { Card } from '../../components/Card'
import { Select } from '../../components/Select'
import { enc } from '../../api'
export function ImportPage() {
  const {
    t,
    collections,
    collection,
    setCollection,
    upload,
    setUpload,
    uploadFormat,
    setUploadFormat,
    uploadMode,
    setUploadMode,
    perform,
    mutate,
  } = useConsole()
  return (
    <>
      <Select label={t.collection} value={collection} set={setCollection} items={collections} />
      <Card title={t.import}>
        <form
          className="stack"
          onSubmit={(e) => {
            e.preventDefault()
            if (!upload) return
            void perform(async () => {
              const f = new FormData()
              f.append(
                'options',
                new Blob([JSON.stringify({ format: uploadFormat, mode: uploadMode })], {
                  type: 'application/json',
                }),
              )
              f.append('file', upload)
              await mutate(`/api/v2/collections/${enc(collection)}/imports`, f)
            })
          }}
        >
          <label>
            {t.format}
            <select value={uploadFormat} onChange={(e) => setUploadFormat(e.target.value)}>
              <option>jsonl</option>
              <option>json</option>
            </select>
          </label>
          <label>
            {t.mode}
            <select value={uploadMode} onChange={(e) => setUploadMode(e.target.value)}>
              <option>merge</option>
              <option>replace</option>
            </select>
          </label>
          <label>
            JSON / JSONL
            <input
              type="file"
              accept=".json,.jsonl,application/json"
              onChange={(e) => setUpload(e.target.files?.[0] || null)}
              required
            />
          </label>
          <button disabled={!collection}>{t.import}</button>
        </form>
      </Card>
    </>
  )
}
