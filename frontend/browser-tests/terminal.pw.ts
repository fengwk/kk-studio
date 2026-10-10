/**
 * 全局终端面板离线回归（Playwright Chromium，无 Backend）。
 *
 * 复用真实 `TerminalViewport` 与生产 `.app-frame` / `.stage` / `.terminal-panel` 几何：
 * - 底部面板与 stage 共享高度、整页不溢出、prompt 跟随底部可见；
 * - 提权控制时的显式 fit：尺寸被 clamp 并去重，滚动重绘不重复 ACK，换版本才再次 ACK；
 * - Ctrl+C 走唯一原生复制路径（本地槽选择），Ctrl+V 保留浏览器默认粘贴；
 * - IME：compositionend 提交一次，忽略 Chromium 补发的最终 input；
 * - wheel：活动屏行按 VT 上报，历史行/Shift 走本地滚动。
 */
import { expect, test } from './fixture'
import type { Page } from './fixture'

const HARNESS = '/browser-tests/terminal-harness.html'
const STREAM = 'bbbbbbbb-0000-4000-8000-000000000001'

const scrollBox = async (page: Page) => {
  const box = await page.locator('.terminal-viewport__scroll').boundingBox()
  if (box === null) {
    throw new Error('terminal viewport scroll box is missing')
  }
  return box
}

const scrollState = (page: Page) =>
  page.evaluate(() => {
    const element = document.querySelector('.terminal-viewport__scroll') as HTMLElement
    return {
      top: element.scrollTop,
      left: element.scrollLeft,
      max: element.scrollHeight - element.clientHeight,
    }
  })

/** Shift 覆盖 VT 上报，选中 COPY_ME 行（data-line 98）的前 7 列。 */
const selectCopyLine = async (page: Page) => {
  const line = page.locator('.terminal-grid__line[data-line="98"]')
  await expect(line).toBeVisible()
  const box = await line.boundingBox()
  if (box === null) {
    throw new Error('copy line box is missing')
  }
  await page.keyboard.down('Shift')
  await page.mouse.move(box.x + 2, box.y + box.height / 2)
  await page.mouse.down()
  await page.mouse.move(box.x + 7 * 8 + 4, box.y + box.height / 2, { steps: 4 })
  await page.mouse.up()
  await page.keyboard.up('Shift')
  await expect(page.locator('.terminal-grid__selection').first()).toBeVisible()
}

test.describe('Global terminal panel (offline harness)', () => {
  test('shares height with the stage, keeps the page bounded and shows the prompt', async ({ page }) => {
    await page.goto(HARNESS)

    const viewport = page.viewportSize()
    if (viewport === null) {
      throw new Error('viewport size is unavailable')
    }
    const panel = await page.getByTestId('harness-panel').boundingBox()
    const stage = await page.getByTestId('harness-stage').boundingBox()
    if (panel === null || stage === null) {
      throw new Error('harness layout boxes are missing')
    }

    // 面板贴底，stage 恰好占据其上方剩余空间且高度为正：两者共享同一列高度。
    expect(Math.abs(panel.y + panel.height - viewport.height)).toBeLessThanOrEqual(1)
    expect(Math.abs(stage.y + stage.height - panel.y)).toBeLessThanOrEqual(1)
    expect(stage.height).toBeGreaterThan(0)

    // 面板不把整页撑出视口；stage 自身内部滚动。
    const pageOverflow = await page.evaluate(() => ({
      scrollHeight: document.documentElement.scrollHeight,
      innerHeight: window.innerHeight,
      stageOverflows: (() => {
        const element = document.querySelector('[data-testid="harness-stage"]') as HTMLElement
        return element.scrollHeight > element.clientHeight
      })(),
    }))
    expect(pageOverflow.scrollHeight).toBeLessThanOrEqual(pageOverflow.innerHeight + 1)
    expect(pageOverflow.stageOverflows).toBe(true)

    // 默认跟随底部：末行 prompt 可见，且滚动位置已到最大。
    const prompt = page.locator('.terminal-grid__line[data-line="99"]')
    await expect(prompt).toBeVisible()
    const promptBox = await prompt.boundingBox()
    const scroll = await scrollBox(page)
    if (promptBox === null) {
      throw new Error('prompt glyph box is missing')
    }
    expect(promptBox.y).toBeGreaterThanOrEqual(scroll.y - 1)
    expect(promptBox.y + promptBox.height).toBeLessThanOrEqual(scroll.y + scroll.height + 1)

    const state = await scrollState(page)
    expect(Math.abs(state.top - state.max)).toBeLessThanOrEqual(1)
  })

  test('fits the controlled terminal once, dedupes identical sizes and ACKs each version once', async ({
    page,
  }) => {
    await page.goto(HARNESS)

    await expect
      .poll(() => page.evaluate(() => window.__terminalHarness.resize.length))
      .toBe(1)
    await expect
      .poll(() => page.evaluate(() => window.__terminalHarness.applied.length))
      .toBe(1)

    const scroll = await scrollBox(page)
    const expectedCols = Math.min(Math.max(Math.floor(scroll.width / 8), 5), 300)
    const expectedRows = Math.min(Math.max(Math.floor(scroll.height / 18), 2), 100)
    expect(await page.evaluate(() => window.__terminalHarness.resize[0])).toEqual({
      cols: expectedCols,
      rows: expectedRows,
    })
    expect(await page.evaluate(() => window.__terminalHarness.applied[0])).toEqual({
      streamId: STREAM,
      version: 1,
    })

    // 仅滚动重绘同一版本，不重复 ACK。
    await page.mouse.move(scroll.x + 40, scroll.y + 40)
    await page.mouse.wheel(0, -400)
    expect(await page.evaluate(() => window.__terminalHarness.applied.length)).toBe(1)

    // 新版本才再次 ACK。
    await page.evaluate(() => window.__terminalHarness.patchView({ version: 2 }))
    await expect
      .poll(() => page.evaluate(() => window.__terminalHarness.applied.at(-1)?.version))
      .toBe(2)

    // 视口尺寸变化才提出新的合法尺寸。
    await page.setViewportSize({ width: 900, height: 720 })
    await expect
      .poll(() => page.evaluate(() => window.__terminalHarness.resize.length))
      .toBeGreaterThan(1)
    const latest = await page.evaluate(() => window.__terminalHarness.resize.at(-1))
    const resizedScroll = await scrollBox(page)
    expect(latest).toEqual({
      cols: Math.min(Math.max(Math.floor(resizedScroll.width / 8), 5), 300),
      rows: Math.min(Math.max(Math.floor(resizedScroll.height / 18), 2), 100),
    })
  })

  test('copies a local selection through the native clipboard path', async ({ page, context }) => {
    await context.grantPermissions(['clipboard-read', 'clipboard-write'])
    await page.goto(HARNESS)

    await selectCopyLine(page)
    await page.locator('.terminal-viewport__input').focus()
    await page.keyboard.press('Control+c')

    const copied = await page.evaluate(() => navigator.clipboard.readText())
    expect(copied).toContain('COPY_ME')
  })

  test('copies via the copy event only, without the async clipboard API', async ({ page }) => {
    // 移除 navigator.clipboard，证明复制只用原生 copy 事件，不依赖仅安全上下文可用的异步 API。
    await page.addInitScript(() => {
      Object.defineProperty(navigator, 'clipboard', { configurable: true, get: () => undefined })
    })
    await page.goto(HARNESS)
    expect(await page.evaluate(() => navigator.clipboard === undefined)).toBe(true)

    await page.evaluate(() => window.__terminalHarness.watchCopy())
    await selectCopyLine(page)
    await page.locator('.terminal-viewport__input').focus()
    await page.keyboard.press('Control+c')

    await expect
      .poll(() => page.evaluate(() => window.__terminalHarness.lastCopy))
      .toContain('COPY_ME')
  })

  test('keeps the browser paste path and encodes clipboard text', async ({ page, context }) => {
    await context.grantPermissions(['clipboard-read', 'clipboard-write'])
    await page.goto(HARNESS)

    await page.evaluate(() => navigator.clipboard.writeText('PASTE_ME'))
    await page.evaluate(() => window.__terminalHarness.clearLog())
    await page.locator('.terminal-viewport__input').focus()
    await page.keyboard.press('Control+v')

    await expect
      .poll(() => page.evaluate(() => window.__terminalHarness.sendInput.join('')))
      .toContain('PASTE_ME')
  })

  test('commits an IME composition exactly once despite the trailing input', async ({ page }) => {
    await page.goto(HARNESS)
    await page.evaluate(() => window.__terminalHarness.clearLog())
    await page.evaluate(() => window.__terminalHarness.compose('你好'))

    await expect
      .poll(() => page.evaluate(() => window.__terminalHarness.sendInput.join('')))
      .toBe('你好')
    expect(await page.evaluate(() => window.__terminalHarness.sendInput.length)).toBe(1)
  })

  test('reports active-screen wheel to the terminal and scrolls history locally', async ({ page }) => {
    await page.goto(HARNESS)
    const scroll = await scrollBox(page)
    const pointerX = scroll.x + 40
    const pointerY = scroll.y + 30

    // 活动屏行：鼠标上报模式下手轮上报 VT，不滚动视口。
    await page.evaluate(() => window.__terminalHarness.setMouseMode('NORMAL'))
    await page.evaluate(() => window.__terminalHarness.clearLog())
    const beforeScreen = await scrollState(page)
    await page.mouse.move(pointerX, pointerY)
    await page.mouse.wheel(0, -120)
    await expect
      .poll(() => page.evaluate(() => window.__terminalHarness.sendInput.length))
      .toBe(1)
    expect(await page.evaluate(() => window.__terminalHarness.sendInput[0])).toContain('\u001b[M')
    expect((await scrollState(page)).top).toBe(beforeScreen.top)

    // 先回到历史顶部，关闭上报后滚动。
    await page.evaluate(() => window.__terminalHarness.setMouseMode('NONE'))
    await page.mouse.wheel(0, -5000)
    expect((await scrollState(page)).top).toBe(0)

    // 历史区行：不向 VT 报告，只本地滚动。
    await page.evaluate(() => window.__terminalHarness.setMouseMode('NORMAL'))
    await page.evaluate(() => window.__terminalHarness.clearLog())
    await page.mouse.move(pointerX, pointerY)
    await page.mouse.wheel(0, 200)
    expect(await page.evaluate(() => window.__terminalHarness.sendInput.length)).toBe(0)
    expect((await scrollState(page)).top).toBeGreaterThan(0)

    // Shift 覆盖上报，改为水平滚动（内容宽于容器）。
    await page.evaluate(() => window.__terminalHarness.clearLog())
    const beforeHorizontal = await scrollState(page)
    await page.keyboard.down('Shift')
    await page.mouse.move(pointerX, pointerY)
    await page.mouse.wheel(0, 200)
    await page.keyboard.up('Shift')
    expect((await scrollState(page)).left).toBeGreaterThan(beforeHorizontal.left)
    expect(await page.evaluate(() => window.__terminalHarness.sendInput.length)).toBe(0)
  })
})
