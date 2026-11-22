import { ConsoleProvider } from './state/console'
import { AppShell } from './AppShell'

export default function App() {
  return (
    <ConsoleProvider>
      <AppShell />
    </ConsoleProvider>
  )
}
