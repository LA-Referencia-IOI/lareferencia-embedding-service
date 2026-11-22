import { test, expect } from '@playwright/test'

test('shows the first document title, external ID, collection name and distance', async ({ page }) => {
  await page.route('**/api/v2/**', async (route) => {
    const url = new URL(route.request().url())
    let data: unknown = { items: [], next_cursor: null }
    if (url.pathname === '/api/v2/collections')
      data = { items: [{ id: 'c1', name: 'Coleção aberta' }], next_cursor: null }
    if (url.pathname === '/api/v2/indexations')
      data = {
        items: [
          {
            id: 'i1',
            slug: 'índice',
            profile_id: 'p1',
            collection_ids: ['c1'],
            capabilities: { approximate: true },
          },
        ],
        next_cursor: null,
      }
    if (url.pathname === '/api/v2/embedding-profiles')
      data = {
        items: [{ id: 'p1', model: 'model', dimensions: 3, execution_mode: 'http' }],
        next_cursor: null,
      }
    if (url.pathname === '/api/v2/indexations/i1/search')
      data = {
        results: [
          {
            collection_id: 'c1',
            document_id: 'd1',
            external_id: 'external-1',
            distance: 0.21,
            unit_id: 'u1',
          },
          { collection_id: 'c1', document_id: 'd2', external_id: 'external-2', distance: 0.3, unit_id: 'u2' },
        ],
      }
    if (url.pathname === '/api/v2/collections/c1/documents/d1')
      data = {
        texts: [
          { knd: 'abstract', cnt: 'Resumo' },
          { knd: 'title', cnt: 'Primeiro título' },
          { knd: 'title', cnt: 'Segundo título' },
        ],
      }
    if (url.pathname === '/api/v2/collections/c1/documents/d2')
      data = { texts: [{ knd: 'abstract', cnt: 'Sem título aqui' }] }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      headers: { 'access-control-allow-origin': '*' },
      body: JSON.stringify(data),
    })
  })
  await page.goto('/search')
  await page.getByRole('combobox', { name: 'Indexação', exact: true }).selectOption('i1')
  await page.getByLabel('Texto da consulta').fill('pesquisa')
  await page.getByRole('button', { name: 'Buscar', exact: true }).click()
  await expect(page.getByText('Primeiro título', { exact: true })).toBeVisible()
  await expect(page.getByText('Segundo título', { exact: true })).toHaveCount(0)
  await expect(
    page.getByText('ID externo: external-1 · Coleção: Coleção aberta · Distância: 0.21'),
  ).toBeVisible()
  await expect(page.getByText('Sem título', { exact: true })).toBeVisible()
})
