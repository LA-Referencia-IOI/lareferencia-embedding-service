import { useConsole } from '../../state/console'
import { Card } from '../../components/Card'
import { Items } from '../../components/Items'
import { Output } from '../../components/Output'
import { enc, query } from '../../api'

export function JobsPage() {
  const {
    t,
    jobId,
    setJobId,
    job,
    setJob,
    jobErrors,
    setJobErrors,
    jobErrorCursor,
    setJobErrorCursor,
    listCursor,
    entries,
    call,
    perform,
    mutate,
    loadList,
  } = useConsole()
  return (
    <>
      <div className="grid">
        <Card title={t.jobs}>
          <button className="secondary" onClick={() => void perform(() => loadList('jobs'))}>
            {t.refresh}
          </button>
          <Items
            t={t}
            items={entries}
            onSelect={(x) => {
              setJobId(x.id)
              void perform(async () => {
                setJob((await call(`/api/v2/jobs/${enc(x.id)}`)).data)
                setJobErrors(null)
              })
            }}
          />
          {listCursor && (
            <button onClick={() => void perform(() => loadList('jobs', listCursor))}>{t.next}</button>
          )}
        </Card>
        <Card title={t.details}>
          <div className="row">
            <label>
              Job ID
              <input value={jobId} onChange={(e) => setJobId(e.target.value)} />
            </label>
            <button
              disabled={!jobId}
              onClick={() =>
                void perform(async () => setJob((await call(`/api/v2/jobs/${enc(jobId)}`)).data))
              }
            >
              {t.find}
            </button>
          </div>
          <div className="toolbar">
            <button
              className="secondary"
              disabled={!jobId}
              onClick={() =>
                void perform(async () => {
                  const r = await call(`/api/v2/jobs/${enc(jobId)}/errors?limit=100`)
                  setJobErrors(r.data)
                  setJobErrorCursor(r.data.next_cursor || '')
                })
              }
            >
              {t.errors}
            </button>
            <button
              disabled={!jobId || !job || ['queued', 'running', 'succeeded'].includes(job.status)}
              onClick={() =>
                void perform(async () => {
                  await mutate(`/api/v2/jobs/${enc(jobId)}/retries`, undefined)
                  await loadList('jobs')
                })
              }
            >
              {t.retry}
            </button>
          </div>
          <Output data={job} title={t.status} />
          <Output data={jobErrors} title={t.errors} />
          {jobErrorCursor && (
            <button
              onClick={() =>
                void perform(async () => {
                  const r = await call(
                    `/api/v2/jobs/${enc(jobId)}/errors${query({ limit: 100, cursor: jobErrorCursor })}`,
                  )
                  setJobErrors(r.data)
                  setJobErrorCursor(r.data.next_cursor || '')
                })
              }
            >
              {t.next}
            </button>
          )}
        </Card>
      </div>
    </>
  )
}
