import { useConsole } from '../../state/console'
import { Card } from '../../components/Card'
import { enc } from '../../api'
import { field, lines } from '../../state/utils'
export function IndexationCreatePage() {
  const { t, collections, profiles, perform, mutate, loadList } = useConsole()
  return (
    <>
      <Card title={t.create + ' ' + t.indexation}>
        <form
          className="stack"
          onSubmit={(e) => {
            e.preventDefault()
            const f = new FormData(e.currentTarget)
            void perform(async () => {
              const c = field(f, 'collection')
              await mutate(c ? `/api/v2/collections/${enc(c)}/indexations` : '/api/v2/indexations', {
                slug: field(f, 'slug'),
                profile_id: field(f, 'profile'),
                selection: {
                  kinds: lines(field(f, 'kinds')),
                  languages: lines(field(f, 'languages')),
                },
                chunking: { mode: 'none' },
                metric: field(f, 'metric'),
                ann_strategy: field(f, 'ann'),
              })
              await loadList('indexations')
            })
          }}
        >
          <label>
            {t.slug}
            <input name="slug" required />
          </label>
          <label>
            {t.profile}
            <select name="profile" required>
              <option value="">…</option>
              {profiles.map((p) => (
                <option key={p.id} value={p.id}>
                  {p.slug}
                </option>
              ))}
            </select>
          </label>
          <label>
            {t.collection} ({t.optional})
            <select name="collection">
              <option value="">—</option>
              {collections.map((c) => (
                <option key={c.id} value={c.id}>
                  {c.name}
                </option>
              ))}
            </select>
          </label>
          <label>
            {t.kind} (comma)
            <input name="kinds" />
          </label>
          <label>
            {t.language} (comma)
            <input name="languages" />
          </label>
          <label>
            Metric
            <select name="metric">
              <option>cosine</option>
              <option>l2</option>
              <option>inner_product</option>
            </select>
          </label>
          <label>
            ANN
            <select name="ann">
              <option>hnsw_vector</option>
              <option>hnsw_halfvec</option>
              <option>none</option>
            </select>
          </label>
          <button>{t.create}</button>
        </form>
      </Card>
    </>
  )
}
