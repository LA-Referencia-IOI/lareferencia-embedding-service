import { expect, test } from '@playwright/test'

test('defaults to approximate when available and retains the exact option', async ({ page }) => {
  const modes: string[] = []
  await page.route('**/api/v2/**', async (route) => {
    const path = new URL(route.request().url()).pathname
    let data: unknown = { items: [], next_cursor: null }
    if (path === '/api/v2/indexations') data = { items: [
      { id: 'i1', slug: 'ann', profile_id: 'p1', capabilities: { approximate: true } },
      { id: 'i2', slug: 'exact', profile_id: 'p1', capabilities: { approximate: false } },
    ] }
    if (path === '/api/v2/embedding-profiles') data = { items: [{ id: 'p1', execution_mode: 'http' }] }
    if (path.endsWith('/search')) {
      modes.push(route.request().postDataJSON().mode)
      data = { results: [] }
    }
    await route.fulfill({ json: data })
  })
  await page.goto('/search')
  await expect(page.locator('nav button').first()).toHaveText('Busca semântica')
  await page.getByRole('combobox', { name: 'Indexação', exact: true }).selectOption('i1')
  await page.getByLabel('Texto da consulta').fill('pesquisa')
  await page.getByRole('button', { name: 'Buscar', exact: true }).click()
  await expect.poll(() => modes[0]).toBe('approximate')
  await page.getByText('Opções avançadas').click()
  await expect(page.getByRole('combobox', { name: 'Modo', exact: true })).toHaveValue('approximate')
  await page.getByRole('combobox', { name: 'Modo', exact: true }).selectOption('exact')
  await page.getByRole('button', { name: 'Buscar', exact: true }).click()
  await expect.poll(() => modes[1]).toBe('exact')
  await page.getByRole('combobox', { name: 'Indexação', exact: true }).selectOption('i2')
  await expect(page.getByRole('combobox', { name: 'Modo', exact: true })).toHaveValue('exact')
  await expect(page.getByRole('option', { name: 'Aproximada' })).toBeDisabled()
  await page.getByRole('combobox', { name: 'Indexação', exact: true }).selectOption('i1')
  await expect(page.getByRole('combobox', { name: 'Modo', exact: true })).toHaveValue('approximate')
})
