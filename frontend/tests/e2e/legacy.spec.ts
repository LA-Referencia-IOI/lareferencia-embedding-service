import { test, expect } from '@playwright/test'

test('loads stores above the forms and refreshes the list after creation', async ({ page }) => {
  const stores = [
    { id: 's1', name: 'Store inicial', model: 'model-a', embeddingDimensions: 3, provider: 'ollama' },
  ]
  await page.route('**/api/v2/**', (route) =>
    route.fulfill({
      status: 200,
      json: { items: [], next_cursor: null },
      headers: { 'access-control-allow-origin': '*' },
    }),
  )
  await page.route('**/api/v1/stores', async (route) => {
    if (route.request().method() === 'POST')
      stores.push({
        id: 's2',
        name: 'Store novo',
        model: 'model-b',
        embeddingDimensions: 4,
        provider: 'ollama',
      })
    await route.fulfill({
      status: 200,
      json: route.request().method() === 'POST' ? stores[1] : stores,
      headers: { 'access-control-allow-origin': '*' },
    })
  })
  await page.goto('/legacy')
  await expect(page.getByText('Store inicial', { exact: true })).toBeVisible()
  const list = page.getByRole('heading', { name: 'Stores', exact: true })
  const form = page.getByRole('heading', { name: 'Criar store' })
  expect((await list.boundingBox())!.y).toBeLessThan((await form.boundingBox())!.y)
  const section = page.locator('section').filter({ has: form })
  await section.getByLabel('Nome').fill('Store novo')
  await section.getByLabel('Modelo').fill('model-b')
  await section.getByLabel('Dimensões').fill('4')
  await section.getByRole('button', { name: 'Criar' }).click()
  await expect(page.getByText('Store novo', { exact: true })).toBeVisible()
})

test('generates using the selected store name and dimensions', async ({ page }) => {
  await page.route('**/api/v2/**', (route) =>
    route.fulfill({
      status: 200,
      json: { items: [], next_cursor: null },
      headers: { 'access-control-allow-origin': '*' },
    }),
  )
  await page.route('**/api/v1/stores', (route) =>
    route.fulfill({
      status: 200,
      json: [
        { id: 's1', name: 'Modelo v1', model: 'bge-m3', embeddingDimensions: 3 },
        { id: 's2', name: 'Modelo v2', model: 'outro', embeddingDimensions: 768 },
      ],
      headers: { 'access-control-allow-origin': '*' },
    }),
  )
  let payload: unknown
  await page.route('**/api/v1/embeddings', (route) => {
    payload = route.request().postDataJSON()
    return route.fulfill({ status: 200, json: { data: [] }, headers: { 'access-control-allow-origin': '*' } })
  })
  await page.goto('/legacy')
  const form = page.locator('section').filter({ has: page.getByRole('heading', { name: 'Gerar vetores' }) })
  await form.getByRole('combobox').selectOption('Modelo v1')
  await expect(form.getByLabel('Dimensões')).toHaveValue('3')
  await form.getByRole('combobox').selectOption('Modelo v2')
  await expect(form.getByLabel('Dimensões')).toHaveValue('768')
  await form.getByRole('combobox').selectOption('Modelo v1')
  await expect(form.getByLabel('Dimensões')).toHaveValue('3')
  await form.getByLabel('Texto da consulta').fill('teste')
  await form.getByRole('button', { name: 'Gerar vetores' }).click()
  await expect.poll(() => payload).toEqual({ model: 'Modelo v1', dimensions: 3, input: 'teste', save: false })
  await form.getByLabel('Salvar vetor no banco').check()
  await form.getByRole('button', { name: 'Gerar vetores' }).click()
  await expect.poll(() => payload).toEqual({ model: 'Modelo v1', dimensions: 3, input: 'teste', save: true })
})
