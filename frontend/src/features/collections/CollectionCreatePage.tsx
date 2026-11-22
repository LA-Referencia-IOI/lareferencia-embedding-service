import { useConsole } from '../../state/console'
import { Card } from '../../components/Card'
import { field } from '../../state/utils'
export function CollectionCreatePage() {
  const { t, busy, perform, mutate, loadList } = useConsole()
  return (
    <>
      <Card title={t.create + ' ' + t.collection}>
        <form
          className="stack"
          onSubmit={(e) => {
            e.preventDefault()
            const f = new FormData(e.currentTarget)
            void perform(async () => {
              await mutate('/api/v2/collections', {
                slug: field(f, 'slug'),
                name: field(f, 'name'),
              })
              await loadList('collections')
            })
          }}
        >
          <label>
            {t.slug}
            <input name="slug" required />
          </label>
          <label>
            {t.name}
            <input name="name" required />
          </label>
          <button disabled={busy}>{t.create}</button>
        </form>
      </Card>
    </>
  )
}
