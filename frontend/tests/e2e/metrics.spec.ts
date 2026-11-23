import { expect, test } from '@playwright/test'

test('queries a metric with and without repeated Actuator tag filters', async ({ page }) => {
  await page.route('**/actuator/metrics/**', async (route) => {
    const url = new URL(route.request().url())
    await route.fulfill({ json: { name: url.pathname.split('/').pop(), tags: url.searchParams.getAll('tag') } })
  })
  await page.goto('/operations')
  const metric = page.getByLabel('Métrica')
  await metric.fill('dvp.search.stage.duration')
  await page.getByRole('button', { name: 'Consultar' }).click()
  await expect(page.getByText('"tags": []', { exact: false })).toBeVisible()

  await metric.fill('dvp.search.stage.duration?tag=stage:database&tag=mode:exact')
  await page.getByRole('button', { name: 'Consultar' }).click()
  await expect(page.getByText('"stage:database"', { exact: false })).toBeVisible()
  await expect(page.getByText('"mode:exact"', { exact: false })).toBeVisible()
})
