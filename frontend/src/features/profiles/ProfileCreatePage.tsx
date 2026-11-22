import { useConsole } from '../../state/console'
import { Card } from '../../components/Card'
import { field } from '../../state/utils'
export function ProfileCreatePage() {
  const { t, profile, setProfile, perform, mutate, loadList } = useConsole()
  return (
    <>
      <Card title={t.create + ' ' + t.profile}>
        <form
          className="stack"
          onSubmit={(e) => {
            e.preventDefault()
            const f = new FormData(e.currentTarget)
            void perform(async () => {
              await mutate('/api/v2/embedding-profiles', {
                slug: field(f, 'slug'),
                model: field(f, 'model'),
                model_revision: field(f, 'revision'),
                dimensions: Number(field(f, 'dimensions')),
                execution_mode: field(f, 'execution'),
                ...(field(f, 'execution') === 'http' ? { provider_ref: field(f, 'provider') } : {}),
                preparation: {
                  version: field(f, 'preparation'),
                  document_prefix: field(f, 'document_prefix'),
                  query_prefix: field(f, 'query_prefix'),
                },
              })
              await loadList('profiles')
            })
          }}
        >
          <label>
            {t.slug}
            <input name="slug" required />
          </label>
          <label>
            {t.model}
            <input name="model" required />
          </label>
          <label>
            {t.revision}
            <input name="revision" required />
          </label>
          <label>
            {t.dimensions}
            <input name="dimensions" type="number" min="1" required />
          </label>
          <label>
            {t.mode}
            <select name="execution" onChange={(e) => setProfile(e.target.value)}>
              <option value="http">HTTP</option>
              <option value="import_only">import_only</option>
            </select>
          </label>
          {profile !== 'import_only' && (
            <label>
              {t.provider}
              <input name="provider" required />
            </label>
          )}
          <label>
            Preparation
            <select name="preparation">
              <option>identity-v1</option>
              <option>legacy-normalizer-v1</option>
            </select>
          </label>
          <label>
            Document prefix
            <input name="document_prefix" />
          </label>
          <label>
            Query prefix
            <input name="query_prefix" />
          </label>
          <button>{t.create}</button>
        </form>
      </Card>
    </>
  )
}
