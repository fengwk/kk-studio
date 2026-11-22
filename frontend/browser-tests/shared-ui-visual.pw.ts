import { expect, test, type Locator, type Page } from './fixture'
import {
  routeApplicationEvents,
  shoot,
  spacingMetrics,
  tabIconTextGaps,
  wordRectCounts,
} from './visual-shots'
import type { SpacingMetrics } from './visual-shots'
import type { IssueDetailDTO } from '@/shared/api/generated/contracts'

/**
 * 共享 UI 基元与全局页面的真实浏览器视觉回归：Select 下伸部、弹窗底栏间距、
 * Tabs 图标间距、资源卡元信息标签、Markdown 代码表面。
 *
 * 夹具全部是真实组件（layout-foundation / catalog-editor / project-workflow ?issue /
 * resource-card / markdown-table），在 1440×900 与 390×844 下分别以 zh-CN、en-US 渲染。
 * 截图只在设置 `KK_SHARED_UI_SHOTS_DIR` 时落盘，几何与样式断言始终执行。
 */

const VIEWPORTS = [
  { name: '1440x900', width: 1440, height: 900 },
  { name: '390x844', width: 390, height: 844 },
] as const

const LOCALES = ['zh-CN', 'en-US'] as const

const PROJECT_ID = 'a0000000-0000-0000-0000-000000000001'
const ISSUE_ID = 'a0000000-0000-0000-0000-000000000002'
const STAMP = Date.UTC(2026, 9, 1)

/** 真实 IssueDetailModal 的最小快照：六个页签各拿到一份空数据。 */
function issueDetail(): IssueDetailDTO {
  return {
    issue: {
      id: ISSUE_ID,
      projectId: PROJECT_ID,
      number: '12',
      title: 'Tab geometry issue',
      description: 'Issue detail tabs at shared icon gap',
      state: 'WORK',
      blockedFromState: null,
      blockReason: null,
      pauseReason: null,
      pauseDetail: null,
      version: '1',
      createdAt: STAMP,
      updatedAt: STAMP,
    },
    activities: [],
    nextActivityCursor: null,
    agentThreads: [],
    runs: [],
    stageBudgets: [],
  }
}

/** Issue 详情与全局壳层需要的离线传输：事件通道 + Issue 快照 + 空待处理计数。 */
async function issueTransport(page: Page) {
  await routeApplicationEvents(page)
  await page.route('**/api/**', async (route) => {
    const path = new URL(route.request().url()).pathname
    if (path === `/api/project/issues/${ISSUE_ID}`) {
      await route.fulfill({ json: issueDetail() })
      return
    }
    if (path === `/api/project/issues/${ISSUE_ID}/evidence`) {
      await route.fulfill({ json: [] })
      return
    }
    if (path === '/api/harness/interactions') {
      await route.fulfill({ json: { items: [], total: 0 } })
      return
    }
    await route.fulfill({
      status: 404,
      contentType: 'application/problem+json',
      json: { type: 'about:blank', status: 404, code: 'not_found', title: 'Not found' },
    })
  })
}

/** 弹窗底栏的关联动作间距：8–12px，使用共享间距 token，且按钮不重叠。 */
function expectActionGap(metrics: SpacingMetrics, label: string) {
  expect(metrics.columnGap, `${label}: configured action gap`).toBeGreaterThanOrEqual(8)
  expect(metrics.columnGap).toBeLessThanOrEqual(12)
  if (!Number.isNaN(metrics.minHorizontalGap)) {
    expect(metrics.minHorizontalGap, `${label}: adjacent buttons on the same row`).toBeGreaterThanOrEqual(8)
  }
}

/** 读取元素自身表面（背景/边框/内边距/圆角/滚动）的真实计算样式。 */
function surface(locator: Locator) {
  return locator.first().evaluate((element) => {
    const style = getComputedStyle(element)
    return {
      background: style.backgroundColor,
      borderWidth: Number.parseFloat(style.borderTopWidth),
      paddingTop: Number.parseFloat(style.paddingTop),
      paddingLeft: Number.parseFloat(style.paddingLeft),
      radius: Number.parseFloat(style.borderTopLeftRadius),
      overflowX: style.overflowX,
      scrollWidth: element.scrollWidth,
      clientWidth: element.clientWidth,
    }
  })
}

for (const viewport of VIEWPORTS) {
  for (const locale of LOCALES) {
    const suffix = `${viewport.name}-${locale}`

    test.describe(`shared UI primitives ${suffix}`, () => {
      test.use({ viewport: { width: viewport.width, height: viewport.height } })

      test('Select triggers keep descenders inside normal and compact heights', async ({ page }, testInfo) => {
        await page.goto(`/browser-tests/layout-foundation-harness.html?locale=${locale}`)
        await expect(page.locator('#open-foundation-dialog')).toBeVisible()
        if (locale === 'zh-CN') {
          // Linux 上未安装微软雅黑时按系统无衬线字体回退。
          await page.addStyleTag({ content: 'body { font-family: "Microsoft YaHei", sans-serif; }' })
        }
        await page.evaluate(() => document.fonts.ready.then(() => undefined))

        const metrics = await page.evaluate(() => {
          const read = (selector: string) => {
            const trigger = document.querySelector<HTMLElement>(selector)
            const value = trigger?.querySelector<HTMLElement>('.ui-select-value')
            if (!trigger || !value) {
              return null
            }
            const valueStyle = getComputedStyle(value)
            const valueRect = value.getBoundingClientRect()
            const triggerRect = trigger.getBoundingClientRect()
            const fontSize = Number.parseFloat(valueStyle.fontSize)
            return {
              triggerHeight: triggerRect.height,
              fontSize,
              lineHeight: valueStyle.lineHeight === 'normal'
                ? valueRect.height
                : Number.parseFloat(valueStyle.lineHeight),
              overflowY: valueStyle.overflowY,
              // 值文本必须整体落在触发器内，且不被水平截断。
              valueInsideTrigger: valueRect.top >= triggerRect.top - 0.5
                && valueRect.bottom <= triggerRect.bottom + 0.5,
              valueTruncated: value.scrollWidth > value.clientWidth + 1,
              text: value.textContent ?? '',
            }
          }
          return {
            standard: read('#standard-model-select'),
            compact: read('#compact-model-select'),
          }
        })

        const { standard, compact } = metrics
        expect(standard, 'standard Select trigger must exist').not.toBeNull()
        expect(compact, 'compact Select trigger must exist').not.toBeNull()
        if (standard === null || compact === null) {
          return
        }

        // 夹具值必须真的带下伸部，否则用例就退化成普通截断检查。
        expect(standard.text).toMatch(/[gpqyj]/)
        expect(compact.text).toMatch(/[gpqyj]/)

        expect(standard.triggerHeight).toBe(32)
        expect(compact.triggerHeight).toBe(28)
        for (const [kind, value] of [['standard', standard], ['compact', compact]] as const) {
          expect(value.valueInsideTrigger, `${kind}: value must stay inside the trigger`).toBe(true)
          expect(value.valueTruncated, `${kind}: value must not be clipped horizontally`).toBe(false)
          // 行盒高度不足 1.15em 时字体下伸部会被 overflow 裁掉；允许显式放开的溢出。
          const fitsLineBox = value.lineHeight >= value.fontSize * 1.15
          expect(
            fitsLineBox || value.overflowY === 'visible',
            `${kind}: descenders need a tall enough line box or visible overflow`,
          ).toBe(true)
        }

        await shoot(page, testInfo, `select-${suffix}`)
      })

      test('modal footers space adjacent actions with the shared gap token', async ({ page }, testInfo) => {
        await page.goto(`/browser-tests/layout-foundation-harness.html?locale=${locale}`)

        // 确认弹窗（移动端由 .modal-touch-target 提供触区与换行）。
        await page.locator('#open-confirm-dialog').click()
        const confirm = page.getByRole('alertdialog')
        await expect(confirm).toBeVisible()
        expectActionGap(await spacingMetrics(confirm.locator('.modal-footer')), 'confirm footer')
        await shoot(page, testInfo, `confirm-modal-${suffix}`)
        await page.keyboard.press('Escape')
        await expect(confirm).toHaveCount(0)

        // 普通 Dialog 底栏（两个动作）。
        await page.locator('#open-foundation-dialog').click()
        const dialog = page.getByRole('dialog')
        await expect(dialog).toBeVisible()
        expectActionGap(await spacingMetrics(dialog.locator('.modal-footer')), 'dialog footer')
        await shoot(page, testInfo, `foundation-dialog-${suffix}`)

        // 资源编辑弹窗（catalog editor 真实 ResourceEditorModal）。
        await page.goto(`/browser-tests/catalog-editor-harness.html?edit&locale=${locale}`)
        const editor = page.getByRole('dialog')
        await expect(editor).toBeVisible()
        expectActionGap(await spacingMetrics(editor.locator('.modal-footer')), 'resource editor footer')
        await shoot(page, testInfo, `resource-editor-modal-${suffix}`)
      })

      test('issue detail tabs give every icon the same shared text gap', async ({ page }, testInfo) => {
        await issueTransport(page)
        await page.goto(`/browser-tests/project-workflow-harness.html?issue&locale=${locale}`)

        const tablist = page.locator('.issue-detail-tabs [role="tablist"]')
        await expect(tablist).toBeVisible()
        const tabs = tablist.getByRole('tab')
        await expect(tabs).toHaveCount(6)

        const gaps = await tabIconTextGaps(tablist)
        expect(gaps).toHaveLength(6)
        for (const gap of gaps) {
          expect(Number.isNaN(gap), 'every tab must render an icon followed by text').toBe(false)
          expect(gap).toBeGreaterThanOrEqual(3.5)
          expect(gap).toBeLessThanOrEqual(8)
        }
        // 共享 .ui-tab 提供统一间距：各页签之间不得出现肉眼可见差异。
        expect(Math.max(...gaps) - Math.min(...gaps)).toBeLessThanOrEqual(1)
        await shoot(page, testInfo, `issue-detail-tabs-${suffix}`)

        // 页签栏放不下时横向滚动：用键盘逐个切换，选中页签每次都完整位于页签栏可视区内。
        await tabs.first().focus()
        for (let index = 0; index < 6; index += 1) {
          if (index > 0) {
            await page.keyboard.press('ArrowRight')
          }
          const selected = tabs.nth(index)
          await expect(selected).toHaveAttribute('aria-selected', 'true')
          await expect.poll(() => selected.evaluate((tab) => {
            const list = tab.parentElement!.getBoundingClientRect()
            const rect = tab.getBoundingClientRect()
            return rect.left >= list.left - 0.5 && rect.right <= list.right + 0.5
          })).toBe(true)
        }
      })

      test('resource card meta labels keep words intact and columns aligned', async ({ page }, testInfo) => {
        await page.goto(`/browser-tests/resource-card-harness.html?locale=${locale}`)
        const card = page.locator('article.resource-card.harness-labels')
        await expect(card).toBeVisible()
        const labels = card.locator('.resource-card-meta-label')
        const rowCount = await labels.count()
        expect(rowCount).toBe(4)

        // 标签按词换行：任何单词都不能被拆成多行（en-US 的 "Environmen/t" 即由此暴露）。
        for (let index = 0; index < rowCount; index += 1) {
          const counts = await wordRectCounts(labels.nth(index))
          expect(counts.length, `label ${index} must keep its text`).toBeGreaterThan(0)
          for (const count of counts) {
            expect(count, `label ${index} must not break inside a word`).toBe(1)
          }
        }

        const geometry = await card.evaluate((element) => {
          const rows = Array.from(element.querySelectorAll('.resource-card-meta-row'))
          const measure = (node: Element | null) => {
            if (node === null) {
              return null
            }
            const rect = node.getBoundingClientRect()
            return {
              left: rect.left,
              right: rect.right,
              scrollWidth: node.scrollWidth,
              clientWidth: node.clientWidth,
            }
          }
          return {
            labelRects: rows.map((row) => measure(row.querySelector('.resource-card-meta-label'))),
            valueRects: rows.map((row) => measure(row.querySelector('.resource-card-meta-value'))),
            overflow: element.scrollWidth - element.clientWidth,
          }
        })

        const labelLefts = geometry.labelRects.map((rect) => {
          expect(rect).not.toBeNull()
          return rect?.left ?? Number.NaN
        })
        const valueLefts = geometry.valueRects.map((rect) => {
          expect(rect).not.toBeNull()
          return rect?.left ?? Number.NaN
        })

        // 同一卡片内标签列与值列各自对齐（subgrid/共享 min-content 列）。
        expect(Math.max(...labelLefts) - Math.min(...labelLefts)).toBeLessThanOrEqual(0.5)
        expect(Math.max(...valueLefts) - Math.min(...valueLefts)).toBeLessThanOrEqual(0.5)

        for (let index = 0; index < rowCount; index += 1) {
          const label = geometry.labelRects[index]
          const value = geometry.valueRects[index]
          expect(label, `label ${index} rect`).not.toBeNull()
          expect(value, `value ${index} rect`).not.toBeNull()
          if (label === null || value === null) {
            continue
          }
          // 标签完整显示（不省略、不溢出到值列）。
          expect(label.scrollWidth, `label ${index} must not be clipped`).toBeLessThanOrEqual(label.clientWidth + 1)
          expect(label.right, `label ${index} must not overlap the value column`).toBeLessThanOrEqual(value.left + 0.5)
        }
        expect(geometry.overflow, 'card must not overflow horizontally').toBeLessThanOrEqual(1)

        await card.scrollIntoViewIfNeeded()
        await shoot(page, testInfo, `resource-card-labels-${suffix}`)
      })

      test('markdown code carries its own surface, border, padding and scroll', async ({ page }, testInfo) => {
        await page.goto(`/browser-tests/markdown-table-harness.html?code&locale=${locale}`)
        await expect(page.locator('#markdown-code-fixture')).toBeVisible()
        await expect(page.locator('#markdown-muted-tone .md-tone-muted')).toBeVisible()

        await page.locator('#markdown-tool-result button[aria-expanded="false"]').click()
        const pageBackground = await page.evaluate(() => getComputedStyle(document.body).backgroundColor)
        const surfaces = [
          ['inline', page.locator('#markdown-code-fixture .md-inline-code')],
          ['block', page.locator('#markdown-code-fixture .md-code-block')],
          ['muted inline', page.locator('#markdown-muted-tone .md-inline-code')],
          ['muted block', page.locator('#markdown-muted-tone .md-code-block')],
          ['tool inline', page.locator('#markdown-tool-result .md-inline-code')],
          ['tool block', page.locator('#markdown-tool-result .md-code-block')],
        ] as const

        for (const [name, locator] of surfaces) {
          await expect(locator.first(), `${name} code must be rendered`).toBeVisible()
          const style = await surface(locator)
          expect(style.background, `${name}: code needs its own surface`).not.toBe('rgba(0, 0, 0, 0)')
          expect(style.background, `${name}: code surface must differ from the page`).not.toBe(pageBackground)
          expect(style.borderWidth, `${name}: code needs a visible border`).toBeGreaterThanOrEqual(1)
          expect(style.paddingTop + style.paddingLeft, `${name}: code needs padding`).toBeGreaterThan(0)
          expect(style.radius, `${name}: code needs rounded corners`).toBeGreaterThanOrEqual(2)
        }

        // 代码块横向滚动，不把长行挤进正文宽度。
        for (const name of ['block', 'muted block'] as const) {
          const style = await surface(page.locator(name === 'block'
            ? '#markdown-code-fixture .md-code-block'
            : '#markdown-muted-tone .md-code-block'))
          expect(style.overflowX, `${name}: code block must scroll horizontally`).toBe('auto')
          if (name === 'block') {
            expect(style.scrollWidth, `${name}: long line must overflow its box`).toBeGreaterThan(style.clientWidth)
          }
        }

        const block = page.locator('#markdown-code-fixture .md-code-block').first()
        await block.evaluate((element) => { element.scrollLeft = element.scrollWidth })
        await expect.poll(() => block.evaluate((element) => element.scrollLeft)).toBeGreaterThan(0)
        await block.evaluate((element) => { element.scrollLeft = 0 })
        await page.locator('#markdown-tool-result button[aria-expanded="true"]').click()
        await block.scrollIntoViewIfNeeded()
        await shoot(page, testInfo, `markdown-code-${suffix}`)
      })
    })
  }
}
