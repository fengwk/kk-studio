import { test, expect, type Page } from './fixture'

// 使用真实页面与本地权威 fixture，不访问上游目录。
test('retry backoff strategy options save and clear dirty state', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 })
  // 只读目录端点（如 /api/platform/models）直接返回数组；模型选择字段按数组消费。
  await page.route('**/api/**', (route) => route.fulfill({ json: [] }))
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

/** en-US 页签文案最长，作为页签栏宽度的回归基准。 */
const EN_TAB_LABELS = [
  'General',
  'Plugins',
  'AI Runtime',
  'Tools & Permissions',
  'Environment',
  'Backend Network',
  'Storage & Media',
  'Advanced',
  'Sync',
]

const EN_SETTINGS_URL = '/browser-tests/settings-catalog-harness.html?locale=en-US'
const WIDE = { width: 1440, height: 900 }
const NARROW = { width: 390, height: 844 }

async function openEnSettingsTabs(page: Page, viewport: { width: number; height: number }) {
  await page.setViewportSize(viewport)
  // 只读目录端点（如 /api/platform/models）直接返回数组；模型选择字段按数组消费。
  await page.route('**/api/**', (route) => route.fulfill({ json: [] }))
  await page.goto(EN_SETTINGS_URL)
  // server 页签来自 settings schema，等全部页签渲染后再量宽度。
  await expect(page.getByRole('tab')).toHaveCount(EN_TAB_LABELS.length)
  return page.getByRole('tablist')
}

/** 页签自身的横向内容不溢出，即文案既没被省略号截断也没被折行裁切。 */
async function expectTabLabelsIntact(page: Page) {
  const clipped = await page
    .getByRole('tab')
    .evaluateAll((tabs) =>
      tabs.filter((tab) => tab.scrollWidth > tab.clientWidth + 1).map((tab) => tab.textContent),
    )
  expect(clipped).toEqual([])
}

test('en-US settings tabs show without horizontal scrolling at 1440', async ({ page }) => {
  const tablist = await openEnSettingsTabs(page, WIDE)

  // 宽屏下每个页签都完整落在视口内，页签栏本身不产生横向溢出。
  for (const label of EN_TAB_LABELS) {
    await expect(page.getByRole('tab', { name: label, exact: true })).toBeInViewport({ ratio: 1 })
  }
  expect(await tablist.evaluate((el) => el.scrollWidth <= el.clientWidth + 1)).toBe(true)
  await expectTabLabelsIntact(page)
  await page.screenshot({ path: '../reports/layout/settings-tabs-1440-en.png' })
})

test('en-US settings tabs scroll horizontally with intact labels at 390', async ({ page }) => {
  const tablist = await openEnSettingsTabs(page, NARROW)

  // 窄屏靠页签栏自身横向滚动：内容溢出，容器仍停在视口内。
  expect(await tablist.evaluate((el) => el.scrollWidth > el.clientWidth)).toBe(true)
  const box = await tablist.boundingBox()
  expect(box).not.toBeNull()
  expect(box!.x + box!.width).toBeLessThanOrEqual(NARROW.width + 1)
  await expectTabLabelsIntact(page)

  // 滚到末尾能看到最后一个页签的完整文案。scrollIntoView 只能停在整像素，滚动量末位会有
  // 亚像素残差（实测 tab 右缘 374.44、容器右缘 374），因此按 1px 容差校验可见范围，
  // 文字本身是否被裁由 expectTabLabelsIntact 断言。
  const lastTab = page.getByRole('tab', { name: 'Sync', exact: true })
  await lastTab.scrollIntoViewIfNeeded()
  const lastTabBox = await lastTab.boundingBox()
  expect(lastTabBox).not.toBeNull()
  expect(lastTabBox!.x).toBeGreaterThanOrEqual(0)
  expect(lastTabBox!.x + lastTabBox!.width).toBeLessThanOrEqual(NARROW.width + 1)
  await page.screenshot({ path: '../reports/layout/settings-tabs-390-en.png' })
})
