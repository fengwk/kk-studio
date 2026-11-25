import { test, expect, type Page } from './fixture'
import type { PluginDTO } from '@/shared/api/generated/contracts'

// 使用真实页面与本地权威 fixture，不访问上游目录。
test('retry backoff strategy options save and clear dirty state', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 })
  // 只读目录端点（如 /api/platform/models）直接返回数组；模型选择字段按数组消费。
  await page.route('**/api/**', (route) => route.fulfill({ json: [] }))
  await page.goto('/browser-tests/settings-catalog-harness.html')
  await page.getByRole('tab', { name: 'AI 运行时' }).click()
  const select = page.getByRole('button', { name: '退避策略', exact: true })
  await expect(select).toContainText('指数')
  await select.click()
  await expect(page.getByRole('option', { name: '指数', exact: true })).toBeVisible()
  await page.screenshot({ path: '../reports/layout/settings-catalog-options.png' })
  await page.getByRole('option', { name: '固定', exact: true }).click()
  await expect(page.getByText('有未保存的更改')).toBeVisible()
  await page.getByRole('button', { name: '保存', exact: true }).click()
  await expect(page.locator('body')).toHaveAttribute('data-saved-strategy', 'FIXED')
  await expect(page.getByRole('button', { name: '保存', exact: true })).toBeDisabled()
  await expect(page.getByText('所有更改已保存')).toBeVisible()
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

/**
 * 插件卡夹具：三枚徽标（版本 / 已生效 / 未连接）同时出现的最宽形态。
 * 文案与徽标数量取自真实 Plugins 页签，名称和 id 是卡片唯一可识别的文本。
 */
function pluginFixture(pluginId: string, name: string): PluginDTO {
  return {
    pluginId,
    name,
    pluginVersion: '1.0.0',
    authKind: { type: 'DEEP_LINK', regionCandidates: ['CN', 'EN'] },
    status: 'NOT_CONNECTED',
    region: null,
    expiresAt: null,
    nextRefreshAt: null,
    lastRefreshedAt: null,
    lastRefreshError: null,
    configuration: {
      version: '1',
      valuesJson: '{}',
      valuesSchema: { type: 'object', properties: {}, additionalProperties: false },
      defaults: {},
      secrets: {},
      credentialContextFields: [],
    },
    appliedVersion: '1',
    connectionCheckSupported: false,
    harnessToolNames: ['mavis_asr', 'mavis_extract_web'],
    canvasFunctionNames: [],
    applicationError: null,
  }
}

const PLUGINS_TAB_LABEL = { 'zh-CN': '插件', 'en-US': 'Plugins' } as const

async function openPluginsTab(
  page: Page,
  locale: keyof typeof PLUGINS_TAB_LABEL,
  viewport: { width: number; height: number },
) {
  await page.setViewportSize(viewport)
  const plugins = [
    pluginFixture('comfyui', 'ComfyUI'),
    pluginFixture('minimax-mavis', 'MiniMax Mavis'),
  ]
  // 兜底只读端点返回空数组；插件列表返回真实形状的卡片数据。
  await page.route('**/api/**', (route) => route.fulfill({ json: [] }))
  await page.route('**/api/platform/plugins', (route) => route.fulfill({ json: plugins }))
  await page.goto(
    `/browser-tests/settings-catalog-harness.html${locale === 'en-US' ? '?locale=en-US' : ''}`,
  )
  await page.getByRole('tab', { name: PLUGINS_TAB_LABEL[locale], exact: true }).click()
  await expect(page.locator('.plugin-card')).toHaveCount(plugins.length, { timeout: 15000 })
  return page.locator('.plugin-card').first()
}

for (const locale of ['zh-CN', 'en-US'] as const) {
  for (const viewport of [WIDE, NARROW]) {
    // 窄卡片的英文徽标最宽：整组徽标必须换到标题下方，名称与 id 不能再被挤成单个字符。
    test(`plugin card badges keep the name and id readable in ${locale} at ${viewport.width}px`, async ({
      page,
    }, info) => {
      const card = await openPluginsTab(page, locale, viewport)
      const headText = (await card.locator('.resource-card-head-text').boundingBox())!
      const title = (await card.locator('.resource-card-title').boundingBox())!
      const subtitle = (await card.locator('.resource-card-subtitle').boundingBox())!
      const badges = (await card.locator('.resource-card-badge').boundingBox())!

      // 标题与 id 各自占满整列，而不是被徽标压到只剩一个字符的宽度。
      expect(headText.width).toBeGreaterThan(200)
      expect(Math.abs(title.width - headText.width)).toBeLessThan(2)
      expect(Math.abs(subtitle.width - headText.width)).toBeLessThan(2)

      if (viewport.width === NARROW.width) {
        // 窄卡片：徽标整组落在标题与 id 之下，并与文字列左对齐（不回到图标下方）。
        expect(badges.y).toBeGreaterThanOrEqual(subtitle.y + subtitle.height - 1)
        expect(Math.round(badges.x)).toBe(Math.round(headText.x))
      } else {
        // 宽卡片保持既有的一行头部：徽标在文字列右侧、与标题同一行。
        expect(badges.x).toBeGreaterThanOrEqual(headText.x + headText.width - 1)
        expect(badges.y).toBeLessThan(title.y + title.height)
      }

      expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(
        viewport.width,
      )
      await page.screenshot({
        path: info.outputPath(`settings-plugins-badges-${locale}-${viewport.width}.png`),
      })
    })
  }
}
