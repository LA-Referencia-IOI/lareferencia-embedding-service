import { expect, test } from '@playwright/test'

test('sends a locally generated vector using a compatible browser model', async ({ page }) => {
  await page.addInitScript(() => {
    Object.defineProperty(navigator, 'gpu', { value: {}, configurable: true })
    class FakeWorker {
      listeners: Record<string, ((event: any) => void)[]> = {}
      addEventListener(name: string, listener: (event: any) => void) {
        ;(this.listeners[name] ??= []).push(listener)
      }
      removeEventListener(name: string, listener: (event: any) => void) {
        this.listeners[name] = (this.listeners[name] || []).filter((x) => x !== listener)
      }
      postMessage(payload: { text: string; dimensions: number }) {
        ;(window as any).__browserInput = payload
        setTimeout(
          () =>
            this.listeners.message?.forEach((listener) =>
              listener({ data: { type: 'result', vector: Array(payload.dimensions).fill(0.1) } }),
            ),
          0,
        )
      }
      terminate() {}
    }
    ;(window as any).Worker = FakeWorker
  })
  let request: any
  await page.route('**/api/v2/**', async (route) => {
    const url = new URL(route.request().url())
    const profile = {
      id: 'p1',
      slug: 'local',
      model: 'google/embeddinggemma-2',
      model_revision: 'r1',
      dimensions: 512,
      execution_mode: 'import_only',
      preparation: { version: 'identity-v1', query_prefix: 'task: search result | query: ' },
    }
    let data: unknown = { items: [], next_cursor: null }
    if (url.pathname === '/api/v2/embedding-profiles')
      data = { items: [profile, { ...profile, id: 'wrong', slug: 'wrong', dimensions: 768 }] }
    if (url.pathname === '/api/v2/indexations')
      data = {
        items: [
          {
            id: 'i1',
            slug: 'test-index',
            profile_id: 'p1',
            capabilities: { exact: true, approximate: false },
            collection_ids: [],
          },
        ],
      }
    if (url.pathname === '/api/v2/indexations/i1/search') {
      request = route.request().postDataJSON()
      data = { results: [] }
    }
    await route.fulfill({ status: 200, json: data })
  })
  await page.goto('/search')
  await page.getByRole('combobox', { name: 'Indexação', exact: true }).selectOption('i1')
  await page.getByLabel('Texto da consulta').fill('ciência aberta')
  await page.getByLabel('Onde gerar o vetor da consulta').selectOption('browser')
  const models = page.getByRole('combobox', { name: 'Modelo disponível no navegador', exact: true })
  await expect(models.locator('option')).toHaveCount(2)
  await expect(models.locator('option').nth(1)).toHaveText('google/embeddinggemma-2 · 512 Dimensões')
  await expect(page.getByRole('combobox', { name: 'Perfil', exact: true })).toHaveCount(0)
  await models.selectOption('embeddinggemma-2-512')
  await page.getByRole('button', { name: 'Buscar', exact: true }).click()
  await expect.poll(() => request?.query?.vector?.length).toBe(512)
  expect(request.query).toEqual({ vector: Array(512).fill(0.1) })
  expect(await page.evaluate(() => (window as any).__browserInput)).toEqual({
    text: 'task: search result | query: ciência aberta',
    dimensions: 512,
  })
})

test('explains when WebGPU is unavailable', async ({ page }) => {
  await page.addInitScript(() =>
    Object.defineProperty(navigator, 'gpu', { value: undefined, configurable: true }),
  )
  await page.route('**/api/v2/**', (route) => route.fulfill({ status: 200, json: { items: [] } }))
  await page.goto('/search')
  await page.getByLabel('Onde gerar o vetor da consulta').selectOption('browser')
  await expect(page.getByText(/WebGPU indisponível/)).toBeVisible()
})

test('asks for server inference when the indexation model has no compatible browser model', async ({
  page,
}) => {
  await page.addInitScript(() => Object.defineProperty(navigator, 'gpu', { value: {}, configurable: true }))
  await page.route('**/api/v2/**', async (route) => {
    const path = new URL(route.request().url()).pathname
    let data: unknown = { items: [] }
    if (path === '/api/v2/embedding-profiles')
      data = {
        items: [
          {
            id: 'p1',
            model: 'bge-m3',
            dimensions: 1024,
            preparation: { version: 'identity-v1', query_prefix: '' },
          },
        ],
      }
    if (path === '/api/v2/indexations')
      data = { items: [{ id: 'i1', slug: 'index', profile_id: 'p1', collection_ids: [] }] }
    await route.fulfill({ status: 200, json: data })
  })
  await page.goto('/search')
  await page.getByRole('combobox', { name: 'Indexação', exact: true }).selectOption('i1')
  await page.getByLabel('Onde gerar o vetor da consulta').selectOption('browser')
  await expect(
    page.getByText(
      'Nenhum modelo do navegador é compatível com esta indexação. Escolha gerar o vetor no servidor.',
    ),
  ).toBeVisible()
  await expect(page.getByRole('combobox', { name: 'Modelo disponível no navegador' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: 'Buscar', exact: true })).toBeDisabled()
})
