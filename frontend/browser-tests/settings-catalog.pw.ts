import { test, expect } from './fixture'

// 使用真实页面与本地权威 fixture，不访问上游目录。
test('retry backoff strategy options save and clear dirty state', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 })
  await page.route('**/api/**', (route) => route.fulfill({ json: { items: [] } }))
  await page.goto('/browser-tests/settings-catalog-harness.html')
  await page.getByRole('tab', { name: 'AI 运行时' }).click()
  const select = page.getByLabel('退避策略')
  await expect(select).toContainText('指数')
  await select.click()
  await expect(page.getByRole('option', { name: '指数', exact: true })).toBeVisible()
  await page.screenshot({ path: '../reports/layout/settings-catalog-options.png' })
  await page.getByRole('option', { name: '固定', exact: true }).click()
  await expect(page.getByText('有未保存的更改')).toBeVisible()
  await page.getByRole('button', { name: '保存', exact: true }).click()
  await expect(page.locator('body')).toHaveAttribute('data-saved-strategy', 'FIXED')
  await expect(page.getByRole('button', { name: '保存', exact: true })).toBeDisabled()
  await expect(page.getByText('已是最新')).toBeVisible()
  await page.screenshot({ path: '../reports/layout/settings-catalog-saved.png' })
})
