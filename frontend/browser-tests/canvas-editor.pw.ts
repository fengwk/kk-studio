import fs from 'node:fs'
import path from 'node:path'
import { expect, test, type Locator, type Page } from './fixture'

/**
 * Canvas 编辑器的真实浏览器回归：真实 CanvasPage + React Flow 舞台 + 共享按钮/菜单，
 * 只在传输层替身后端（见 canvas-editor-harness.tsx）。
 *
 * 断言的是用户可见事实：头部只有标题与保存状态、空画布引导不遮挡舞台交互、
 * 引导与左侧 `+` 打开同一个添加菜单、加入第一个节点后引导消失。
 */

const HARNESS_URL = '/browser-tests/canvas-editor-harness.html'

const COPY = {
  'zh-CN': {
    saved: '已保存',
    title: '先添加资源或 Function 开始',
    description: '选中 Function 节点即可运行',
    addEntry: '添加资源或 Function',
    imageFunction: '图片生成',
  },
  'en-US': {
    saved: 'Saved',
    title: 'Add a resource or Function to start',
    description: 'Select a Function node to run it.',
    addEntry: 'Add resource or Function',
    imageFunction: 'Image generation',
  },
} as const

type SliceLocale = keyof typeof COPY

/** 截图路径：显式给 KK_SLICE_SHOTS 时写到该目录，否则走 Playwright 输出目录。 */
function shotPath(name: string): string {
  const dir = process.env.KK_SLICE_SHOTS
  if (!dir) {
    return test.info().outputPath(name)
  }
  fs.mkdirSync(dir, { recursive: true })
  return path.join(dir, name)
}

type Box = { x: number; y: number; width: number; height: number }

function overlaps(a: Box, b: Box): boolean {
  return a.x < b.x + b.width && b.x < a.x + a.width
    && a.y < b.y + b.height && b.y < a.y + a.height
}

async function box(locator: Locator): Promise<Box> {
  const value = await locator.boundingBox()
  expect(value, '元素必须已完成布局').not.toBeNull()
  return value!
}

function headerText(page: Page): Promise<string> {
  return page.locator('.editor-header').innerText().then((text) => text.replace(/\s+/g, ' ').trim())
}

test.describe('Canvas editor empty canvas', () => {
  test('the header shows only the title and save state, and the guidance never blocks the stage', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 })
    await page.goto(`${HARNESS_URL}?locale=zh-CN`)

    const header = page.locator('.editor-header')
    await expect(header).toContainText('Board')
    // 版本号留在同步协议与诊断信息：头部只有标题与保存状态，没有 `v0` 胶囊。
    await expect.poll(() => headerText(page)).toBe(`Board ${COPY['zh-CN'].saved}`)
    await expect(page.locator('.version-pill')).toHaveCount(0)

    const guidance = page.locator('.canvas-empty-guidance')
    await expect(guidance).toBeVisible()
    await expect(guidance).toContainText(COPY['zh-CN'].title)
    await expect(guidance).toContainText(COPY['zh-CN'].description)

    // 引导层本身不接收指针事件，只有添加按钮可点：舞台仍能平移、框选与拖放。
    expect(await guidance.evaluate((element) => getComputedStyle(element).pointerEvents)).toBe('none')
    const body = page.locator('.canvas-empty-guidance-body')
    expect(await body.evaluate((element) => getComputedStyle(element).pointerEvents)).toBe('none')
    const action = guidance.getByRole('button', { name: COPY['zh-CN'].addEntry })
    expect(await action.evaluate((element) => getComputedStyle(element).pointerEvents)).toBe('auto')

    const bodyBox = await box(body)
    const hit = await page.evaluate(({ x, y }) => {
      const element = document.elementFromPoint(x, y)
      return {
        insideGuidance: Boolean(element?.closest('.canvas-empty-guidance')),
        insideStage: Boolean(element?.closest('.react-flow')),
      }
    }, { x: bodyBox.x + 8, y: bodyBox.y + 8 })
    expect(hit.insideGuidance).toBe(false)
    expect(hit.insideStage).toBe(true)

    // 与左侧 `+` 相同的入口：点击引导按钮打开同一个添加菜单。
    await expect(action).toHaveAttribute('aria-expanded', 'false')
    await action.click()
    const menu = page.getByRole('menu', { name: COPY['zh-CN'].addEntry })
    await expect(menu).toBeVisible()
    await expect(action).toHaveAttribute('aria-expanded', 'true')
    await expect(page.locator('.canvas-tool-rail .dock-add')).toHaveAttribute('aria-expanded', 'true')

    // 关闭菜单后引导仍在：引导只在画布真正有节点后消失。
    await page.keyboard.press('Escape')
    await expect(menu).toBeHidden()
    await expect(guidance).toBeVisible()
  })

  test('adding the first node removes the guidance', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 })
    await page.goto(`${HARNESS_URL}?locale=zh-CN`)

    const guidance = page.locator('.canvas-empty-guidance')
    await expect(guidance).toBeVisible()
    await expect(page.locator('html')).toHaveAttribute('data-function-catalog', 'ready')
    await guidance.getByRole('button', { name: COPY['zh-CN'].addEntry }).click()
    await page.getByRole('menuitem', { name: COPY['zh-CN'].imageFunction }).click()

    await expect(page.locator('.react-flow__node')).toHaveCount(1)
    await expect(guidance).toHaveCount(0)
    // 头部仍不出现版本号，即使 revision 已经前进。
    await expect.poll(() => headerText(page)).toBe(`Board ${COPY['zh-CN'].saved}`)
  })

  for (const locale of ['zh-CN', 'en-US'] as const) {
    for (const viewport of [
      { width: 1440, height: 900 },
      { width: 390, height: 844 },
    ] as const) {
      test(`empty canvas guidance holds for ${locale} at ${viewport.width}x${viewport.height}`, async ({
        page,
      }) => {
        await page.setViewportSize(viewport)
        await page.goto(`${HARNESS_URL}?locale=${locale}`)

        const copy = COPY[locale as SliceLocale]
        const guidance = page.locator('.canvas-empty-guidance')
        const action = guidance.getByRole('button', { name: copy.addEntry })
        await expect(guidance).toBeVisible()
        await expect(guidance).toContainText(copy.title)
        await expect(guidance).toContainText(copy.description)
        await expect(action).toBeVisible()

        // 引导不与左侧添加入口重叠，窄屏也不产生横向溢出。
        expect(overlaps(await box(page.locator('.canvas-empty-guidance-body')),
          await box(page.locator('.canvas-tool-rail .dock-add')))).toBe(false)
        const overflow = await page.evaluate(() => ({
          scrollWidth: document.documentElement.scrollWidth,
          clientWidth: document.documentElement.clientWidth,
        }))
        expect(overflow.scrollWidth).toBeLessThanOrEqual(overflow.clientWidth + 1)

        const actionBox = await box(action)
        expect(actionBox.height).toBe(32)
        expect(actionBox.x).toBeGreaterThanOrEqual(0)
        expect(actionBox.x + actionBox.width).toBeLessThanOrEqual(viewport.width)

        await page.screenshot({
          path: shotPath(`canvas-empty-${locale}-${viewport.width}x${viewport.height}.png`),
        })
      })
    }
  }
})
