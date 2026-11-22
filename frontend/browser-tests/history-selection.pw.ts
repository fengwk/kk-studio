import { expect, test, type Locator, type Page } from './fixture'
import {
  captureSearchHitShot,
  expectFirstMarkVisible,
  expectNoLeadingClipping,
  readSearchHitText,
  recordSearchHitMetrics,
} from './search-hit-geometry'
import {
  DEEP_HIT,
  DEEP_PREVIEW,
  DEEP_PREVIEW_HIT,
  DEEP_TITLE,
  HISTORY_TEXT,
  LONG_HIT,
  SHORT_HIT,
  SHORT_TITLE,
} from './search-hit-fixtures'

const LOCALES = [
  {
    locale: 'zh-CN',
    historyTree: '历史树',
    /** 历史树搜索框：ai.chat.history.search。 */
    historySearch: '搜索记录',
    /** 选择面板搜索框：ai.chat.selection.search，与历史树不是同一文案。 */
    listSearch: '搜索',
    selected: '已选',
    sessionPanel: '选择会话',
    threadPanel: '选择 Thread',
  },
  {
    locale: 'en-US',
    historyTree: 'History tree',
    historySearch: 'Search records',
    listSearch: 'Search',
    selected: 'Selected',
    sessionPanel: 'Select Session',
    threadPanel: 'Select Thread',
  },
] as const

const VIEWPORTS = [
  { width: 390, height: 844 },
  { width: 1440, height: 900 },
] as const

/** 1440 宽下文字放得下：从开头完整显示，不能被前导省略裁掉。 */
const WIDE = 1440

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

    test(`${copy.locale} ${size}: history row keeps the original preview for an in-preview hit`, async ({ page }, testInfo) => {
      await openHarness(page, copy.locale, viewport)
      const region = page.getByRole('region', { name: copy.historyTree })
      await expect(region).toBeVisible()
      await region.getByRole('searchbox', { name: copy.historySearch }).fill(DEEP_HIT)

      const row = region.getByRole('button').filter({ hasText: DEEP_HIT }).first()
      const preview = row.locator('.history-tree-entry-preview')
      await expect(preview.locator('mark')).toHaveText(DEEP_HIT)
      // 无障碍标签仍带完整预览：视觉上省略的标题前缀仍出现在标签里。
      expect(await row.getAttribute('aria-label')).toContain(HISTORY_TEXT)
      const geometry = await readSearchHitText(preview)
      expect(geometry.text).toBe(HISTORY_TEXT)
      expectFirstMarkVisible(geometry, `${copy.locale} ${size} 历史预览内深层命中`)
      if (viewport.width >= WIDE) {
        expectNoLeadingClipping(geometry, `${copy.locale} ${size} 历史预览内深层命中`)
      }

      await region.getByRole('searchbox', { name: copy.historySearch }).fill('停止接收新请求')
      await expect(preview.locator('mark')).toHaveText('停止接收新请求')
      expectFirstMarkVisible(await readSearchHitText(preview), `${copy.locale} ${size} 历史浅命中`)
      await captureSearchHitShot(region, testInfo, `tree-shallow-hit-${copy.locale}-${size}`)
    })

    test(`${copy.locale} ${size}: a long hit beyond the preview limit is shown inside the row`, async ({ page }, testInfo) => {
      await openHarness(page, copy.locale, viewport)
      const region = page.getByRole('region', { name: copy.historyTree })
      await region.getByRole('searchbox', { name: copy.historySearch }).fill(LONG_HIT)

      // 搜索预览从全文命中处截取：长命中词在窄行里也完整可见，预览以字面量 … 开头。
      const mark = page.locator('mark', { hasText: LONG_HIT })
      const preview = region.locator('.history-tree-entry-preview').filter({ has: mark })
      await expect(preview).toHaveCount(1)
      await expect(preview).toHaveText(/^…/)
      const row = region.getByRole('button').filter({ has: mark })
      await expect(row).toHaveCount(1)
      await expect(row).toHaveAttribute('aria-pressed', 'false')

      // R7 场景之一：行未被选中时，长命中词同样完整可见。
      const unselected = await readSearchHitText(preview)
      expectFirstMarkVisible(unselected, `${copy.locale} ${size} R7 未选中行`)
      if (viewport.width >= WIDE) {
        expectNoLeadingClipping(unselected, `${copy.locale} ${size} R7 未选中行`)
      }
      recordSearchHitMetrics(testInfo, `tree-r7-unselected-${copy.locale}-${size}`, unselected)

      // R7 场景之二：选中同一行（选中只改变底色与光标字形），可用宽度与命中可见性不变。
      await row.click()
      await expect(row).toHaveAttribute('aria-pressed', 'true')
      const selected = await readSearchHitText(preview)
      expectFirstMarkVisible(selected, `${copy.locale} ${size} R7 选中行`)
      if (viewport.width >= WIDE) {
        expectNoLeadingClipping(selected, `${copy.locale} ${size} R7 选中行`)
      }
      expect(selected.availableWidth, '选中前后文字框可用宽度').toBeCloseTo(unselected.availableWidth, 0)
      recordSearchHitMetrics(testInfo, `tree-r7-selected-${copy.locale}-${size}`, selected)
      await captureSearchHitShot(region, testInfo, `tree-r7-selected-${copy.locale}-${size}`)
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

    test(`${copy.locale} ${size}: session list keeps deep title and subtitle hits fully visible`, async ({ page }, testInfo) => {
      await openHarness(page, copy.locale, viewport)
      const region = page.getByTestId('bound-sessions').getByRole('region', { name: copy.sessionPanel })
      const search = region.getByRole('searchbox', { name: copy.listSearch })

      // 标题深处的命中：长标题在窄行里放不下，命中词必须完整可见。
      await search.fill(DEEP_HIT)
      const titleRow = region.getByRole('option').filter({ has: page.locator('mark', { hasText: DEEP_HIT }) })
      await expect(titleRow).toHaveCount(1)
      const title = await readSearchHitText(titleRow.locator('.thread-selection-item-title'))
      expect(title.text, 'DOM 保留完整标题').toBe(DEEP_TITLE)
      expectFirstMarkVisible(title, `${copy.locale} ${size} 会话标题深层命中`)
      if (viewport.width >= WIDE) {
        expectNoLeadingClipping(title, `${copy.locale} ${size} 会话标题深层命中`)
      }
      recordSearchHitMetrics(testInfo, `list-title-deep-hit-${copy.locale}-${size}`, title)
      await captureSearchHitShot(region, testInfo, `list-title-deep-hit-${copy.locale}-${size}`)

      // 副标题深处的命中：命中词在预览 40 多字之后。
      await search.fill(DEEP_PREVIEW_HIT)
      const subtitleRow = region.getByRole('option').filter({ has: page.locator('mark', { hasText: DEEP_PREVIEW_HIT }) })
      await expect(subtitleRow).toHaveCount(1)
      const subtitle = await readSearchHitText(subtitleRow.locator('.thread-selection-item-subtitle'))
      expect(subtitle.text.endsWith(DEEP_PREVIEW), 'DOM 保留完整副标题预览').toBe(true)
      expectFirstMarkVisible(subtitle, `${copy.locale} ${size} 会话副标题深层命中`)
      if (viewport.width >= WIDE) {
        expectNoLeadingClipping(subtitle, `${copy.locale} ${size} 会话副标题深层命中`)
      }
      recordSearchHitMetrics(testInfo, `list-subtitle-deep-hit-${copy.locale}-${size}`, subtitle)
      await captureSearchHitShot(region, testInfo, `list-subtitle-deep-hit-${copy.locale}-${size}`)
    })

    test(`${copy.locale} ${size}: session list keeps a short hit at the row start`, async ({ page }, testInfo) => {
      await openHarness(page, copy.locale, viewport)
      const region = page.getByTestId('bound-sessions').getByRole('region', { name: copy.sessionPanel })
      await region.getByRole('searchbox', { name: copy.listSearch }).fill(SHORT_HIT)

      const row = region.getByRole('option').filter({ has: page.locator('mark', { hasText: SHORT_HIT }) })
      await expect(row).toHaveCount(1)
      const title = await readSearchHitText(row.locator('.thread-selection-item-title'))
      expect(title.text, 'DOM 保留完整标题').toBe(SHORT_TITLE)
      // 命中就在开头：没有可收缩的前文，标题从内容区起点开始显示。
      expect(title.prefixWidth, '短命中标题没有前文框').toBeNull()
      expectFirstMarkVisible(title, `${copy.locale} ${size} 会话短命中`)
      expectNoLeadingClipping(title, `${copy.locale} ${size} 会话短命中`)
      recordSearchHitMetrics(testInfo, `list-short-hit-${copy.locale}-${size}`, title)
      await captureSearchHitShot(region, testInfo, `list-short-hit-${copy.locale}-${size}`)
    })

    test(`${copy.locale} ${size}: thread list keeps deep title and subtitle hits fully visible`, async ({ page }, testInfo) => {
      await openHarness(page, copy.locale, viewport)
      const region = page.getByTestId('bound-threads').getByRole('region', { name: copy.threadPanel })
      const search = region.getByRole('searchbox', { name: copy.listSearch })

      await search.fill(DEEP_HIT)
      const titleRow = region.getByRole('option').filter({ has: page.locator('mark', { hasText: DEEP_HIT }) })
      await expect(titleRow).toHaveCount(1)
      const title = await readSearchHitText(titleRow.locator('.thread-selection-item-title'))
      expect(title.text, 'DOM 保留完整标题').toBe(DEEP_TITLE)
      expectFirstMarkVisible(title, `${copy.locale} ${size} Thread 标题深层命中`)
      if (viewport.width >= WIDE) {
        expectNoLeadingClipping(title, `${copy.locale} ${size} Thread 标题深层命中`)
      }
      recordSearchHitMetrics(testInfo, `thread-title-deep-hit-${copy.locale}-${size}`, title)

      await search.fill(DEEP_PREVIEW_HIT)
      const subtitleRow = region.getByRole('option').filter({ has: page.locator('mark', { hasText: DEEP_PREVIEW_HIT }) })
      await expect(subtitleRow).toHaveCount(1)
      const subtitle = await readSearchHitText(subtitleRow.locator('.thread-selection-item-subtitle'))
      expect(subtitle.text.endsWith(DEEP_PREVIEW), 'DOM 保留完整副标题预览').toBe(true)
      expectFirstMarkVisible(subtitle, `${copy.locale} ${size} Thread 副标题深层命中`)
      if (viewport.width >= WIDE) {
        expectNoLeadingClipping(subtitle, `${copy.locale} ${size} Thread 副标题深层命中`)
      }
      recordSearchHitMetrics(testInfo, `thread-subtitle-deep-hit-${copy.locale}-${size}`, subtitle)
    })
  }
}
