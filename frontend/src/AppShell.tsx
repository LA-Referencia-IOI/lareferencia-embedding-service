import { useConsole, type Language } from './state/console'
import { CollectionsPage } from './features/collections/CollectionsPage'
import { ProfilesPage } from './features/profiles/ProfilesPage'
import { IndexationsPage } from './features/indexations/IndexationsPage'
import { SearchPage } from './features/search/SearchPage'
import { VectorsPage } from './features/vectors/VectorsPage'
import { JobsPage } from './features/jobs/JobsPage'
import { LegacyPage } from './features/legacy/LegacyPage'
import { OperationsPage } from './features/operations/OperationsPage'
import { SettingsPage } from './features/settings/SettingsPage'
import { CollectionCreatePage } from './features/collections/CollectionCreatePage'
import { DocumentEditPage } from './features/documents/DocumentEditPage'
import { ImportPage } from './features/imports/ImportPage'
import { ProfileCreatePage } from './features/profiles/ProfileCreatePage'
import { IndexationCreatePage } from './features/indexations/IndexationCreatePage'
import { IndexationActionsPage } from './features/indexations/IndexationActionsPage'

export function AppShell() {
  const { lang, setLang, t, page, menu, setMenu, error, notice, busy, jobId, nav, changePage, title } =
    useConsole()
  return (
    <div className="shell">
      <aside className={`sidebar ${menu ? 'open' : ''}`}>
        <div className="brand">
          LA Referencia<small>EMBEDDING SERVICE</small>
        </div>
        <nav className="nav" aria-label="Navigation">
          {nav.map((n) => (
            <button key={n} className={page === n ? 'active' : ''} onClick={() => changePage(n)}>
              {t[n]}
            </button>
          ))}
        </nav>
      </aside>
      <div className="body">
        <header className="topbar">
          <button className="menu secondary" onClick={() => setMenu(!menu)} aria-expanded={menu}>
            {t.menu}
          </button>
          <label>
            {t.languageLabel}
            <select value={lang} onChange={(e) => setLang(e.target.value as Language)}>
              <option value="pt">Português</option>
              <option value="es">Español</option>
            </select>
          </label>
        </header>
        <main className="content">
          <div className="heading">
            <div>
              <h1>{title}</h1>
              <p>{t.description}</p>
            </div>
            {busy && <span role="status">{t.loading}</span>}
          </div>
          {error && (
            <div className="error" role="alert">
              {error}
            </div>
          )}
          {notice && (
            <div className="notice" role="status">
              {notice}{' '}
              {jobId && (
                <button className="secondary" onClick={() => changePage('jobs')}>
                  {t.jobs}: {jobId}
                </button>
              )}
            </div>
          )}
          {page === 'collections' && <CollectionsPage />}
          {page === 'collection-create' && <CollectionCreatePage />}
          {page === 'document-edit' && <DocumentEditPage />}
          {page === 'imports' && <ImportPage />}
          {page === 'profile-create' && <ProfileCreatePage />}
          {page === 'indexation-create' && <IndexationCreatePage />}
          {page === 'indexation-actions' && <IndexationActionsPage />}
          {page === 'profiles' && <ProfilesPage />}
          {page === 'indexations' && <IndexationsPage />}
          {page === 'search' && <SearchPage />}
          {page === 'vectors' && <VectorsPage />}
          {page === 'jobs' && <JobsPage />}
          {page === 'legacy' && <LegacyPage />}
          {page === 'operations' && <OperationsPage />}
          {page === 'settings' && <SettingsPage />}
        </main>
      </div>
    </div>
  )
}
