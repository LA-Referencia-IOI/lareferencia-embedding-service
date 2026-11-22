import { test, expect } from '@playwright/test'
test.beforeEach(async ({ page }) => {
  await page.route('**/api/v2/**', async (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      headers: { 'access-control-allow-origin': '*' },
      body: JSON.stringify({ items: [], next_cursor: null }),
    }),
  )
})
test('direct search route and language selection', async ({ page }) => {
  await page.goto('/search')
  await expect(page.getByLabel('Texto da consulta')).toBeVisible()
  await page.getByLabel('Idioma da interface').selectOption('es')
  await expect(page.getByRole('heading', { name: 'Búsqueda semántica', level: 1 })).toBeVisible()
  await expect(page.locator('html')).toHaveAttribute('lang', 'es')
})
test('mobile navigation opens', async ({ page }) => {
  await page.setViewportSize({ width: 375, height: 800 })
  await page.goto('/')
  await page.getByRole('button', { name: 'Menu' }).click()
  await expect(page.getByRole('button', { name: 'Busca semântica' })).toBeVisible()
})
