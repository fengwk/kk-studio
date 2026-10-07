import { expect, test } from './fixture'
import type { Locator, Page } from './fixture'

/**
 * M1 表格复制的真实浏览器回归。
 *
 * jsdom 里没有布局、滚动与真实剪贴板，因此这里的契约只能在浏览器验证：
 * 预留的复制位是否真的避开表头/单元格、宽表横滚后按钮是否仍在原位可见、
 * 外层（md-root / 消息外壳）是否把按钮裁掉、hover 与键盘显隐、
 * 剪贴板拿到的确实是该表格的原始 Markdown、流式追加后已渲染段落不被重挂、
 * 以及剪贴板与兜底同时失败时绝不显示「已复制」。
 */

const HARNESS = '/browser-tests/markdown-table-harness.html'

// 需要读回实际复制的文本；写入权限同时覆盖真实 click 路径。
test.use({ permissions: ['clipboard-read', 'clipboard-write'] })

type HarnessApi = {
  appendRow(): void
  narrowTableSource(appendedRows?: number): string
  wideTableSource(): string
}

type CopyFallbackWindow = Window & { __copyFallbackCalls?: number }

const shells = (page: Page): Locator => page.locator('.md-table-shell')
const table = (shell: Locator): Locator => shell.locator('table')
const copyButton = (shell: Locator): Locator => shell.locator('.md-table-copy')
const assistantCopy = (page: Page): Locator => page.locator('.thread-assistant-copy')

/** 在浏览器里读权威原文：与应用内「按 offset 切片」是两条独立路径。 */
function expectedSource(page: Page, kind: 'narrow' | 'wide', appendedRows = 0): Promise<string> {
  return page.evaluate(
    ([name, rows]) => {
      const api = (window as unknown as { markdownTableHarness: HarnessApi }).markdownTableHarness
      return name === 'narrow' ? api.narrowTableSource(rows as number) : api.wideTableSource()
    },
    [kind, appendedRows] as const,
  )
}

function clipboardText(page: Page): Promise<string> {
  return page.evaluate(() => navigator.clipboard.readText())
}

/** 按钮与表格/单元格的矩形是否相交：相交即代表按钮压在表头上。 */
function overlapsTableCells(shell: Locator): Promise<boolean> {
  return shell.evaluate((element) => {
    const button = element.querySelector('.md-table-copy')
    const tableElement = element.querySelector('table')
    if (!button || !tableElement) {
      return true
    }
    const buttonRect = button.getBoundingClientRect()
    const tableRect = tableElement.getBoundingClientRect()
    const intersects = (a: DOMRect, b: DOMRect): boolean =>
      !(a.right <= b.left || b.right <= a.left || a.bottom <= b.top || b.bottom <= a.top)
    if (intersects(buttonRect, tableRect)) {
      return true
    }
    return Array.from(tableElement.querySelectorAll('th, td')).some((cell) =>
      intersects(buttonRect, cell.getBoundingClientRect()),
    )
  })
}

/** 按钮中心的最上层元素：被祖先裁掉或被其它层盖住时不会命中按钮本身。 */
async function hitsButton(shell: Locator): Promise<boolean> {
  return shell.evaluate((element) => {
    const button = element.querySelector('.md-table-copy')
    if (!button) {
      return false
    }
    const rect = button.getBoundingClientRect()
    const hit = document.elementFromPoint(rect.left + rect.width / 2, rect.top + rect.height / 2)
    return hit !== null && (hit === button || button.contains(hit))
  })
}

const box = (target: Locator): Promise<{ x: number; y: number; width: number; height: number }> =>
  target.boundingBox().then((value) => value ?? { x: NaN, y: NaN, width: NaN, height: NaN })

test.describe('Markdown table copy', () => {
  test('reserves header space and stays visible while a wide table scrolls', async ({ page }, testInfo) => {
    await page.goto(HARNESS)
    await expect(shells(page)).toHaveCount(2)

    const narrow = shells(page).nth(0)
    const wide = shells(page).nth(1)

    // 预留位不属于表格的横向滚动容器：横滚发生在 table 自身。
    const narrowTable = await table(narrow).evaluate((element) => ({
      overflowX: getComputedStyle(element).overflowX,
      scrollWidth: element.scrollWidth,
      clientWidth: element.clientWidth,
    }))
    expect(narrowTable.overflowX).toBe('auto')
    const wideTable = await table(wide).evaluate((element) => ({
      scrollWidth: element.scrollWidth,
      clientWidth: element.clientWidth,
    }))
    expect(wideTable.scrollWidth).toBeGreaterThan(wideTable.clientWidth)

    // 悬停外壳即显形；按钮位于预留带内，与表头、单元格都不相交。
    await narrow.hover()
    await expect(copyButton(narrow)).toHaveCSS('opacity', '1')
    expect(await overlapsTableCells(narrow)).toBe(false)
    expect(await hitsButton(narrow)).toBe(true)

    const buttonBox = await box(copyButton(narrow))
    const tableBox = await box(table(narrow))
    expect(buttonBox.y + buttonBox.height).toBeLessThanOrEqual(tableBox.y + 1)
    await expect(page.locator('.md-root')).toHaveCSS('overflow', 'visible')
    await page.screenshot({ path: testInfo.outputPath('markdown-table-narrow.png') })

    // 宽表横向滚动：按钮位置与可见性都不随之移动或消失。
    await wide.hover()
    await expect(copyButton(wide)).toHaveCSS('opacity', '1')
    const wideButtonBefore = await box(copyButton(wide))
    await table(wide).evaluate((element) => {
      element.scrollLeft = 600
    })
    await expect.poll(() => table(wide).evaluate((element) => element.scrollLeft)).toBe(600)
    expect(await box(copyButton(wide))).toEqual(wideButtonBefore)
    expect(await overlapsTableCells(wide)).toBe(false)
    expect(await hitsButton(wide)).toBe(true)
    await page.screenshot({ path: testInfo.outputPath('markdown-table-wide-scrolled.png') })
  })

  test('reveals on keyboard focus and copies the raw Markdown of that table', async ({ page }) => {
    await page.goto(HARNESS)
    await expect(shells(page)).toHaveCount(2)

    const narrow = shells(page).nth(0)
    const wide = shells(page).nth(1)
    const narrowSource = await expectedSource(page, 'narrow')
    const wideSource = await expectedSource(page, 'wide')

    // 默认两枚按钮都隐藏；外层「复制全文」与表格按钮的冲突由 :has() 规则处理。
    await expect(copyButton(narrow)).toHaveCSS('opacity', '0')
    await expect(assistantCopy(page)).toHaveCSS('opacity', '0')

    // 键盘路径：焦点从「复制全文」前进到窄表按钮即显形，回车复制。
    await assistantCopy(page).focus()
    await page.keyboard.press('Tab')
    await expect(copyButton(narrow)).toBeFocused()
    await expect(copyButton(narrow)).toHaveCSS('opacity', '1')
    await expect(assistantCopy(page)).toHaveCSS('opacity', '0')
    await page.keyboard.press('Enter')
    await expect.poll(() => clipboardText(page)).toBe(narrowSource)

    // 复制的是原始 Markdown：转义竖线、对齐行、链接/加粗语法都还在，且不含整条消息的其它段落。
    const copiedNarrow = await clipboardText(page)
    expect(copiedNarrow).toContain('| :--- | ---: | :---: |')
    expect(copiedNarrow).toContain('`a\\|b`')
    expect(copiedNarrow).toContain('**粗体**')
    expect(copiedNarrow).toContain('[链接](https://example.com/x)')
    expect(copiedNarrow).not.toContain('结尾段落')

    // 鼠标路径：悬停表格外壳 → 按钮显形、外层「复制全文」让位；点第二张表复制的是它自己的原文。
    await wide.hover()
    await expect(copyButton(wide)).toHaveCSS('opacity', '1')
    await expect(assistantCopy(page)).toHaveCSS('opacity', '0')
    await copyButton(wide).click()
    await expect.poll(() => clipboardText(page)).toBe(wideSource)
  })

  test('copies the grown table after a streamed append without remounting later segments', async ({ page }) => {
    await page.goto(HARNESS)
    await expect(shells(page)).toHaveCount(2)

    const narrow = shells(page).nth(0)
    const wide = shells(page).nth(1)
    // Mermaid 之后的宽表段在追加过程中 content 不变：重挂会丢掉这个标记。
    await wide.evaluate((element) => {
      ;(element as HTMLElement).dataset.probe = 'mounted'
    })

    await page.evaluate(() => {
      ;(window as unknown as { markdownTableHarness: HarnessApi }).markdownTableHarness.appendRow()
    })
    await expect(narrow.locator('tbody tr')).toHaveCount(2)
    await expect(wide).toHaveAttribute('data-probe', 'mounted')

    await narrow.hover()
    await copyButton(narrow).click()
    await expect.poll(() => clipboardText(page)).toBe(await expectedSource(page, 'narrow', 1))
  })

  test('never shows the copied state when the clipboard and the fallback both fail', async ({ page }) => {
    await page.addInitScript(() => {
      Object.defineProperty(navigator, 'clipboard', {
        configurable: true,
        value: { writeText: () => Promise.reject(new Error('denied')) },
      })
      Object.defineProperty(document, 'execCommand', {
        configurable: true,
        value: () => {
          const target = window as CopyFallbackWindow
          target.__copyFallbackCalls = (target.__copyFallbackCalls ?? 0) + 1
          return false
        },
      })
    })
    await page.goto(HARNESS)

    const narrow = shells(page).nth(0)
    await narrow.hover()
    await copyButton(narrow).click()

    // 兜底路径确实被尝试过，且失败后按钮保持空闲态，没有误导性的「已复制」。
    await expect
      .poll(() => page.evaluate(() => (window as CopyFallbackWindow).__copyFallbackCalls ?? 0))
      .toBe(1)
    await expect(copyButton(narrow)).toHaveAttribute('aria-label', '复制表格')
    await expect(page.getByRole('button', { name: '已复制' })).toHaveCount(0)
    await expect(page.locator('body textarea')).toHaveCount(0)
  })
})
