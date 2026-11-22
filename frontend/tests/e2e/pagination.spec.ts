import { test, expect } from '@playwright/test'

test('navigates numbered document pages and resets when the collection changes', async ({ page }) => {
  const cursors: string[] = []
  await page.route('**/api/v2/**', async (route) => {
    const url = new URL(route.request().url())
    let data: unknown = { items: [], next_cursor: null }
    if (url.pathname === '/api/v2/collections')
      data = {
        items: [
          { id: 'c1', name: 'Coleção 1' },
          { id: 'c2', name: 'Coleção 2' },
        ],
        next_cursor: null,
      }
    if (url.pathname === '/api/v2/collections/c1')
      data = { id: 'c1', counts: { documents: 53, indexations: 0 } }
    if (url.pathname === '/api/v2/collections/c2')
      data = { id: 'c2', counts: { documents: 1, indexations: 0 } }
    if (url.pathname === '/api/v2/collections/c1/documents') {
      const cursor = url.searchParams.get('cursor') || ''
      cursors.push(cursor)
      const n = cursor === '' ? 1 : cursor === 'p2' ? 2 : 3
      data = {
        items: [{ id: `d${n}`, external_id: `doc-página-${n}` }],
        next_cursor: n < 3 ? `p${n + 1}` : null,
      }
    }
    if (url.pathname === '/api/v2/collections/c2/documents')
      data = { items: [{ id: 'other', external_id: 'outro-documento' }], next_cursor: null }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      headers: { 'access-control-allow-origin': '*' },
      body: JSON.stringify(data),
    })
  })
  await page.goto('/collections')
  await page.getByRole('button', { name: 'Detalhes' }).first().click()
  const nav = page.getByRole('navigation', { name: 'Paginação de documentos' })
  await expect(page.getByText('Total de documentos: 53')).toBeVisible()
  await expect(nav).toContainText('Página 1 / 3')
  await nav.getByRole('button', { name: 'Página 3' }).click()
  await expect(page.getByText('doc-página-3')).toBeVisible()
  await expect(nav.getByRole('button', { name: 'Página 3' })).toHaveAttribute('aria-current', 'page')
  expect(cursors.slice(0, 3)).toEqual(['', 'p2', 'p3'])
  await nav.getByRole('button', { name: 'Página anterior' }).click()
  await expect(page.getByText('doc-página-2')).toBeVisible()
  await page.getByLabel('Coleção').selectOption('c2')
  await page.getByRole('button', { name: 'Atualizar' }).last().click()
  await expect(page.getByText('outro-documento')).toBeVisible()
  await expect(page.getByText('Total de documentos: 1', { exact: true })).toBeVisible()
  await expect(nav).toContainText('Página 1 / 1')
})
