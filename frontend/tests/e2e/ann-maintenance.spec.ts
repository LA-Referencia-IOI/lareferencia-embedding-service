import { expect, test } from '@playwright/test'

test('enables ANN on an existing indexation and opens its maintenance job', async ({ page }) => {
  const requests: unknown[] = []
  await page.route('**/api/v2/**', async (route) => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    let data: unknown = { items: [], next_cursor: null }
    let status = 200
    if (path === '/api/v2/collections') data = { items: [{ id: 'c1', name: 'Coleção' }] }
    if (path === '/api/v2/indexations')
      data = { items: [
        { id: 'i1', slug: 'sem-ann', state: 'ready', ann_strategy: 'none', profile: { dimensions: 768 }, collection_ids: ['c1'] },
        { id: 'i2', slug: 'com-ann', state: 'ready', ann_strategy: 'hnsw_vector', profile: { dimensions: 768 }, collection_ids: ['c1'] },
        { id: 'i3', slug: 'grande', state: 'ready', ann_strategy: 'none', profile: { dimensions: 3000 }, collection_ids: ['c1'] },
      ] }
    if (path.endsWith('/maintenance-jobs')) {
      requests.push(request.postDataJSON())
      status = 202
      data = { id: 'job-1', kind: 'maintenance', status: 'queued' }
    }
    if (path === '/api/v2/jobs/job-1') data = { id: 'job-1', status: 'succeeded' }
    await route.fulfill({ status, json: data })
  })
  await page.goto('/indexation-actions')
  const indexes = page.getByLabel('Indexação')
  const collections = page.getByLabel('Coleção')
  await indexes.last().selectOption('i1')
  await collections.last().selectOption('c1')
  await page.getByLabel('Estratégia ANN').selectOption('hnsw_halfvec')
  await page.getByRole('button', { name: 'Manutenção', exact: true }).click()
  await expect.poll(() => requests[0]).toEqual({ operation: 'build_ann', ann_strategy: 'hnsw_halfvec' })
  await page.getByRole('button', { name: 'Acompanhar job de manutenção' }).click()
  await expect(page).toHaveURL(/\/jobs$/)
  await expect(page.getByLabel('Job ID')).toHaveValue('job-1')
  await expect(page.getByText('"succeeded"', { exact: false })).toBeVisible()

  await page.goto('/indexation-actions')
  await indexes.last().selectOption('i2')
  await collections.last().selectOption('c1')
  await expect(page.getByLabel('Estratégia ANN')).toHaveCount(0)
  await page.getByRole('button', { name: 'Manutenção', exact: true }).click()
  await expect.poll(() => requests[1]).toEqual({ operation: 'build_ann' })

  await indexes.last().selectOption('i3')
  await page.getByLabel('Estratégia ANN').selectOption('hnsw_vector')
  await expect(page.getByRole('alert').filter({ hasText: 'não suporta a dimensão' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Manutenção', exact: true })).toBeDisabled()
  await page.getByLabel('Estratégia ANN').selectOption('hnsw_halfvec')
  await expect(page.getByRole('button', { name: 'Manutenção', exact: true })).toBeEnabled()
  await page.getByLabel('Idioma da interface').selectOption('es')
  await expect(page.getByLabel('Estrategia ANN')).toHaveValue('hnsw_halfvec')
  await expect(page.getByText('El índice se construirá sobre los vectores existentes', { exact: false })).toBeVisible()
})
