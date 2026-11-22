import { expect, test, type Locator, type Page } from './fixture'

const LOCALES = [
  { locale: 'zh-CN', historyTree: '历史树', search: '搜索记录', selected: '已选', sessionPanel: '选择会话', threadPanel: '选择 Thread' },
  { locale: 'en-US', historyTree: 'History tree', search: 'Search records', selected: 'Selected', sessionPanel: 'Select Session', threadPanel: 'Select Thread' },
] as const

const VIEWPORTS = [
  { width: 390, height: 844 },
  { width: 1440, height: 900 },
] as const

/** 历史树搜索词：只命中助手正文第 40 个字符处，单行省略默认会把它挤出可见区。 */
const HIT = '停机耗时'

/** 行内命中片段必须完整落在单行预览的可见框内，不能被省略裁掉。 */
async function expectMarkVisibleInsideRow(preview: Locator) {
  const previewBox = await preview.boundingBox()
  const markBox = await preview.locator('mark').boundingBox()
  expect(previewBox, 'preview box').not.toBeNull()
  expect(markBox, 'mark box').not.toBeNull()
  expect(markBox!.x).toBeGreaterThanOrEqual(previewBox!.x - 0.5)
  expect(markBox!.x + markBox!.width).toBeLessThanOrEqual(previewBox!.x + previewBox!.width + 0.5)
}

/** 绑定行的已选标记：aria-current 与行内“已选”文案同时存在。 */
async function expectCurrentRow(panel: Locator, title: RegExp, selectedLabel: string) {
  const current = panel.getByRole('option').filter({ hasText: title })
  await expect(current).toHaveCount(1)
  await expect(current).toHaveAttribute('aria-current', 'true')
  await expect(current.locator('em')).toHaveText(selectedLabel)
  const active = panel.getByRole('option').filter({ hasNotText: title })
  await expect(active.first()).not.toHaveAttribute('aria-current')
}

async function expectNoCurrentRow(panel: Locator, selectedLabel: string) {
  const options = panel.getByRole('option')
  expect(await options.count()).toBeGreaterThan(0)
  for (let index = 0; index < await options.count(); index += 1) {
    const option = options.nth(index)
    await expect(option).not.toHaveAttribute('aria-current')
    await expect(option.locator('em')).toHaveCount(0)
    await expect(option).not.toContainText(selectedLabel)
  }
}

async function openHarness(page: Page, locale: string, viewport: { width: number; height: number }) {
  await page.setViewportSize(viewport)
  await page.goto(`/browser-tests/history-selection-harness.html?locale=${locale}`)
}

for (const copy of LOCALES) {
  for (const viewport of VIEWPORTS) {
    const size = `${viewport.width}x${viewport.height}`

    test(`${copy.locale} ${size}: history row preview starts at the deep search hit`, async ({ page }, testInfo) => {
      await openHarness(page, copy.locale, viewport)
      const region = page.getByRole('region', { name: copy.historyTree })
      await expect(region).toBeVisible()
      await region.getByRole('searchbox', { name: copy.search }).fill(HIT)

      const row = region.getByRole('button').filter({ hasText: HIT }).first()
      const preview = row.locator('.history-tree-entry-preview')
      await expect(preview).toContainText(`…`)
      await expect(preview.locator('mark')).toHaveText(HIT)
      // 无障碍标签仍带完整预览：摘录省掉的标题前缀仍出现在标签里。
      await expect(row).toHaveAttribute('aria-label', /## 结论 服务停机时先停止接收新请求/)
      await expectMarkVisibleInsideRow(preview)
      await page.screenshot({ path: testInfo.outputPath(`history-${copy.locale}-${size}.png`), fullPage: true })
    })

    test(`${copy.locale} ${size}: bound Thread and its Session are the current selection`, async ({ page }, testInfo) => {
      await openHarness(page, copy.locale, viewport)
      const sessions = page.getByTestId('bound-sessions')
      const threads = page.getByTestId('bound-threads')
      await expectCurrentRow(
        sessions.getByRole('region', { name: copy.sessionPanel }),
        /第二个会话/,
        copy.selected,
      )
      await expectCurrentRow(
        threads.getByRole('region', { name: copy.threadPanel }),
        /第二个 Thread/,
        copy.selected,
      )
      await page.screenshot({ path: testInfo.outputPath(`bound-${copy.locale}-${size}.png`), fullPage: true })
    })

    test(`${copy.locale} ${size}: unbound draft panes mark no Session or Thread`, async ({ page }, testInfo) => {
      await openHarness(page, copy.locale, viewport)
      await expectNoCurrentRow(
        page.getByTestId('unbound-sessions').getByRole('region', { name: copy.sessionPanel }),
        copy.selected,
      )
      await expectNoCurrentRow(
        page.getByTestId('unbound-threads').getByRole('region', { name: copy.threadPanel }),
        copy.selected,
      )
      expect(
        await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth),
      ).toBe(true)
      await page.screenshot({ path: testInfo.outputPath(`unbound-${copy.locale}-${size}.png`), fullPage: true })
    })
  }
}
