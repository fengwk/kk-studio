import { expect, test } from './fixture'
import { FIXTURE_MODELS } from './model-browser-fixtures'

test('renders the shared model and grouped capability pickers across their host forms', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 })
  await page.route('**/api/platform/models', (route) => route.fulfill({ json: FIXTURE_MODELS }))
  await page.goto('/browser-tests/pickers-harness.html')

  await expect(page.getByRole('region', { name: 'Agent configuration fixture' })).toBeVisible()
  await expect(page.getByRole('region', { name: 'Compression model setting fixture' })).toBeVisible()
  await expect(page.getByRole('region', { name: 'Model group edit fixture' })).toBeVisible()
  await expect(page.getByRole('searchbox')).toHaveCount(2)
  await expect(page.getByText('BUILTIN', { exact: true })).toBeVisible()
  await expect(page.getByText('MCP', { exact: true })).toBeVisible()
  await expect(page.getByText('engineering', { exact: true })).toBeVisible()
  await page.screenshot({ path: test.info().outputPath('pickers-desktop.png'), fullPage: true })

  await page.setViewportSize({ width: 390, height: 844 })
  await expect(page.getByRole('region', { name: 'Agent configuration fixture' })).toBeVisible()
  await expect(page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).resolves.toBe(true)
  await page.screenshot({ path: test.info().outputPath('pickers-mobile.png'), fullPage: true })
})
