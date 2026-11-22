import { useConsole } from '../../state/console'
import { Card } from '../../components/Card'
import { Items } from '../../components/Items'
import { Output } from '../../components/Output'
export function ProfilesPage() {
  const { t, result, setResult, listCursor, entries, perform, loadList, changePage } = useConsole()
  return (
    <>
      <div className="page-actions">
        <button onClick={() => changePage('profile-create')}>
          {t.create} {t.profile}
        </button>
      </div>
      <Card title={t.profiles}>
        <button className="secondary" onClick={() => void perform(() => loadList('profiles'))}>
          {t.refresh}
        </button>
        <Items t={t} items={entries} onSelect={(x) => setResult(x)} />
        {listCursor && (
          <button onClick={() => void perform(() => loadList('profiles', listCursor))}>{t.next}</button>
        )}
        <Output data={result} title={t.details} />
      </Card>
    </>
  )
}
