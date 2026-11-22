import { useConsole } from '../../state/console'
import { Card } from '../../components/Card'
import { Output } from '../../components/Output'
import { enc } from '../../api'

export function OperationsPage() {
  const { t, base, metricName, setMetricName, apiOutput, setApiOutput, call, perform } = useConsole()
  return (
    <>
      <Card title={t.links}>
        <div className="links">
          {[
            ['Swagger', '/swagger-ui/index.html'],
            ['OpenAPI', '/api-docs'],
            ['Swagger config', '/api-docs/swagger-config'],
            ['Health', '/actuator/health'],
            ['Metrics', '/actuator/metrics'],
          ].map(([name, path]) => (
            <a key={path} href={`${base.replace(/\/$/, '')}${path}`} target="_blank" rel="noreferrer">
              {name} ↗
            </a>
          ))}
        </div>
      </Card>
      <Card title={t.operations}>
        <div className="toolbar">
          <button
            onClick={() => void perform(async () => setApiOutput((await call('/actuator/health')).data))}
          >
            Health
          </button>
          <button
            onClick={() => void perform(async () => setApiOutput((await call('/actuator/metrics')).data))}
          >
            Metrics
          </button>
        </div>
        <form
          className="row"
          onSubmit={(e) => {
            e.preventDefault()
            void perform(async () => setApiOutput((await call(`/actuator/metrics/${enc(metricName)}`)).data))
          }}
        >
          <label>
            {t.endpoint}
            <input value={metricName} onChange={(e) => setMetricName(e.target.value)} required />
          </label>
          <button>{t.find}</button>
        </form>
      </Card>
      <Output data={apiOutput} title={t.result} />
    </>
  )
}
