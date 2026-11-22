import { test, expect } from '@playwright/test'

test.beforeEach(async ({ page }) => {
  await page.route('**/api/v2/**', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      headers: { 'access-control-allow-origin': '*' },
      body: JSON.stringify({ items: [], next_cursor: null }),
    }),
  )
})

for (const [path, heading] of [
  ['collections', 'Coleções e documentos'],
  ['profiles', 'Perfis de embedding'],
  ['indexations', 'Indexações'],
  ['search', 'Busca semântica'],
  ['vectors', 'Vetores armazenados'],
  ['jobs', 'Jobs'],
  ['legacy', 'API v1'],
  ['operations', 'Operações'],
  ['settings', 'Configurações'],
]) {
  test(`opens ${path} directly`, async ({ page }) => {
    const errors: string[] = []
    page.on('pageerror', (error) => errors.push(error.message))
    await page.goto(`/${path}`)
    await expect(page.getByRole('heading', { level: 1, name: heading, exact: true })).toBeVisible()
    expect(errors).toEqual([])
  })
}
