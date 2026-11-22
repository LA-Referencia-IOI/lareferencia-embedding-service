import { test, expect } from '@playwright/test'

test.beforeEach(async ({ page }) => {
  await page.route('**/api/v2/**', async (route) => {
    const url = new URL(route.request().url())
    const headers = {
      'access-control-allow-origin': '*',
      'access-control-expose-headers': 'ETag',
      ETag: '"1"',
    }
    let data: unknown = { items: [], next_cursor: null }
    if (url.pathname === '/api/v2/collections')
      data = { items: [{ id: 'c1', name: 'Coleção de teste' }], next_cursor: null }
    if (url.pathname === '/api/v2/collections/c1') data = { id: 'c1', name: 'Coleção de teste' }
    if (url.pathname === '/api/v2/collections/c1/documents')
      data = { items: [{ id: 'd1', external_id: 'doc-1' }], next_cursor: null }
    if (url.pathname === '/api/v2/collections/c1/documents/d1')
      data = {
        id: 'd1',
        external_id: 'doc-1',
        metadata: {},
        texts: [{ knd: 'title', lng: 'pt', cnt: 'Exemplo' }],
      }
    await route.fulfill({ status: 200, contentType: 'application/json', headers, body: JSON.stringify(data) })
  })
})

test('separates listing, creating and editing documents', async ({ page }) => {
  await page.goto('/collections')
  await expect(page.getByLabel('Identificador (slug)')).toHaveCount(0)
  await page.getByRole('button', { name: 'Criar Coleção' }).click()
  await expect(page).toHaveURL(/\/collection-create$/)
  await expect(page.getByLabel('Identificador (slug)')).toBeVisible()
  await page.getByRole('button', { name: 'Coleções e documentos' }).click()
  await page.getByRole('button', { name: 'Detalhes' }).first().click()
  await expect(page.getByText('doc-1')).toBeVisible()
  await page.getByRole('button', { name: 'Detalhes' }).last().click()
  await page.getByRole('button', { name: 'Editar Documento' }).click()
  await expect(page).toHaveURL(/\/document-edit$/)
  await expect(page.getByLabel('Conteúdo')).toHaveValue('Exemplo')
})

test('separates profile and index creation from listings', async ({ page }) => {
  for (const [path, action, target] of [
    ['profiles', 'Criar Perfil', 'profile-create'],
    ['indexations', 'Criar Indexação', 'indexation-create'],
  ]) {
    await page.goto(`/${path}`)
    await expect(page.getByLabel('Identificador (slug)')).toHaveCount(0)
    await page.getByRole('button', { name: action }).click()
    await expect(page).toHaveURL(new RegExp(`/${target}$`))
    await expect(page.getByLabel('Identificador (slug)')).toBeVisible()
  }
})

test('imports have their own page and require a collection', async ({ page }) => {
  await page.goto('/imports')
  await expect(page.getByRole('heading', { level: 1, name: 'Importação de arquivos' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Importar arquivo' })).toBeDisabled()
  await page.getByLabel('Coleção').selectOption('c1')
  await expect(page.getByRole('button', { name: 'Importar arquivo' })).toBeEnabled()
})
