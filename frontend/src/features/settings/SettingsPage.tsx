import { useConsole } from '../../state/console'
import { Card } from '../../components/Card'

export function SettingsPage() {
  const { t } = useConsole()
  return <Card title={t.connection}><p>{window.location.origin}</p></Card>
}
