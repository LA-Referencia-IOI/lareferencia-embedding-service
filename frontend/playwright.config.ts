import { defineConfig } from '@playwright/test'
export default defineConfig({
  testDir: './tests/e2e',
  use: { baseURL: 'http://127.0.0.1:4173', browserName: 'chromium' },
  webServer: { command: 'pnpm preview --host 127.0.0.1 --port 4173', port: 4173, reuseExistingServer: true },
})
