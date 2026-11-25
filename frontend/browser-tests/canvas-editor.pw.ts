import { once } from 'node:events'
import fs from 'node:fs'
import { createServer } from 'node:http'
import path from 'node:path'
import { expect, test, type Locator, type Page } from './fixture'

/**
 * Canvas 编辑器的真实浏览器回归：真实 CanvasPage + React Flow 舞台 + 共享按钮/菜单，
 * 只在传输层替身后端（见 canvas-editor-harness.tsx）。
 *
 * 断言的是用户可见事实：头部只有标题与保存状态、空画布引导不遮挡舞台交互、
 * 引导与左侧 `+` 打开同一个添加菜单、加入第一个节点后引导消失、零节点画布不渲染缩略图。
 */

const HARNESS_URL = '/browser-tests/canvas-editor-harness.html'

const COPY = {
  'zh-CN': {
    saved: '已保存',
    title: '先添加资源或 Function 开始',
    description: '选中 Function 节点即可运行',
    addEntry: '添加资源或 Function',
    imageFunction: '图片生成',
    catalogLoading: '正在读取函数目录…',
    catalogFailed: '函数目录读取失败',
    retry: '重试',
    functionMissing: '目录中没有此函数',
    panelAria: 'Function 生成面板',
    conflictStale: '节点在远端已被修改，请对比后决定。',
    conflictRetryLatest: '使用最新版本重试',
    conflictSaveAsNew: '另存为新节点',
    conflictDiscard: '放弃草稿',
    toastServerUpdated: '部分修改未能保存，请在冲突提示中处理。',
    toastOperationFailed: '画布操作失败，请重试。',
    zoomGroup: '画布缩放控制',
    zoomIn: '放大',
    resetZoom: (percentage: string) => `${percentage}，重置缩放`,
  },
  'en-US': {
    saved: 'Saved',
    title: 'Add a resource or Function to start',
    description: 'Select a Function node to run it.',
    addEntry: 'Add resource or Function',
    imageFunction: 'Image generation',
    catalogLoading: 'Loading function catalog…',
    catalogFailed: 'Could not load the function catalog',
    retry: 'Retry',
    functionMissing: 'This function is not in the catalog',
    panelAria: 'Function generation panel',
    conflictStale: 'The node changed remotely. Compare the versions before deciding',
    conflictRetryLatest: 'Retry with the latest version',
    conflictSaveAsNew: 'Save as a new node',
    conflictDiscard: 'Discard draft',
    toastServerUpdated: 'Some changes were not saved. Handle them in the conflict banner.',
    toastOperationFailed: 'The canvas action failed. Try again.',
    zoomGroup: 'Canvas zoom controls',
    zoomIn: 'Zoom in',
    resetZoom: (percentage: string) => `${percentage}, reset zoom`,
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

function saveStateText(page: Page): Promise<string> {
  return page.locator('.editor-view [aria-live="polite"]').innerText().then((text) => text.replace(/\s+/g, ' ').trim())
}

const VIEWPORTS = [
  { width: 1440, height: 900 },
  { width: 390, height: 844 },
] as const

const LOCALES = ['zh-CN', 'en-US'] as const

/** 文案表与查询参数的组合矩阵：清单在这里，用例体只描述行为。 */
function localeMatrix(): Array<{ locale: SliceLocale; viewport: { width: number; height: number } }> {
  return LOCALES.flatMap((locale) => VIEWPORTS.map((viewport) => ({ locale, viewport })))
}

test('downloads a cross-origin Canvas attachment without navigating the workspace', async ({ page }) => {
  const name = '画布 sample.png'
  const bytes = 'canvas attachment fixture'
  // Use a real second origin without CORS: the browser must handle the attachment response itself.
  const server = createServer((_request, response) => {
    response.writeHead(200, {
      'Content-Type': 'image/png',
      'Content-Disposition': `attachment; filename*=UTF-8''${encodeURIComponent(name)}`,
    }).end(bytes)
  }).listen(0, '127.0.0.1')
  try {
    await once(server, 'listening')
    const address = server.address()
    if (!address || typeof address === 'string') {
      throw new Error('Missing attachment server address')
    }
    const attachmentUrl = `http://127.0.0.1:${address.port}/original`
    await page.goto(`${HARNESS_URL}?${new URLSearchParams({ locale: 'en-US', attachmentUrl })}`)
    const canvasUrl = page.url()
    expect(new URL(canvasUrl).origin).not.toBe(new URL(attachmentUrl).origin)
    const navigations: string[] = []
    page.on('framenavigated', (frame) => {
      if (frame === page.mainFrame()) {
        navigations.push(frame.url())
      }
    })
    await page.locator('.react-flow__node').click({ button: 'right' })
    const [download] = await Promise.all([
      page.waitForEvent('download'),
      page.getByRole('menuitem', { name: 'Download', exact: true }).click(),
    ])
    expect(download.url()).toBe(attachmentUrl)
    expect(download.suggestedFilename()).toBe(name)
    expect(fs.readFileSync(await download.path(), 'utf8')).toBe(bytes)
    expect(page.url()).toBe(canvasUrl)
    expect(navigations).toEqual([])
    expect(page.context().pages()).toEqual([page])
  } finally {
    await new Promise<void>((resolve, reject) => server.close((error) => error ? reject(error) : resolve()))
  }
})

test.describe('Canvas editor canvas-only surface regressions', () => {
  for (const { locale, viewport } of localeMatrix()) {
    const suffix = `${locale}-${viewport.width}x${viewport.height}`

    test(`the add menu reports a catalog failure and recovers on retry ${suffix}`, async ({ page }) => {
      const copy = COPY[locale]
      await page.setViewportSize(viewport)
      // 第一次目录请求 500，之后的请求（用户点“重试”触发的 refetch）返回真实目录。
      await page.goto(`${HARNESS_URL}?locale=${locale}&catalog=fail`)
      await expect(page.locator('html')).toHaveAttribute('data-function-catalog', 'failed')

      await page.locator('.canvas-tool-rail .dock-add').click()
      await expect(page.getByRole('menu', { name: copy.addEntry })).toBeVisible()

      const notice = page.locator('.add-menu-catalog')
      // 失败说成失败：不是“没有可用的图片模型”，也不是空态。
      await expect(notice.getByRole('alert')).toHaveText(copy.catalogFailed)
      const retry = notice.getByRole('button', { name: copy.retry, exact: true })
      await expect(retry).toBeInViewport({ ratio: 1 })
      const imageFunction = page.getByRole('menuitem', { name: copy.imageFunction })
      await expect(imageFunction).toHaveAttribute('aria-disabled', 'true')
      await expect(page.locator('.react-flow__node')).toHaveCount(0)
      await page.screenshot({ path: shotPath(`canvas-catalog-failed-${suffix}.png`) })

      // 重试真的重新读取目录：就绪后提示消失，Function 项恢复可选。
      await retry.click()
      await expect(page.locator('html')).toHaveAttribute('data-function-catalog', 'ready')
      await expect(page.locator('.add-menu-catalog')).toHaveCount(0)
      await expect(imageFunction).not.toHaveAttribute('aria-disabled', 'true')
      await imageFunction.click()
      await expect(page.locator('.react-flow__node')).toHaveCount(1)
      await page.screenshot({ path: shotPath(`canvas-catalog-recovered-${suffix}.png`) })
    })

    test(`the add menu explains a catalog that is still loading ${suffix}`, async ({ page }) => {
      const copy = COPY[locale]
      await page.setViewportSize(viewport)
      await page.goto(`${HARNESS_URL}?locale=${locale}&catalog=pending`)
      await expect(page.locator('html')).toHaveAttribute('data-function-catalog', 'loading')

      await page.locator('.canvas-tool-rail .dock-add').click()
      const notice = page.locator('.add-menu-catalog')
      // 读取中与失败、空态是三条不同的说明。
      await expect(notice.getByRole('status')).toHaveText(copy.catalogLoading)
      await expect(page.getByRole('menuitem', { name: copy.imageFunction }))
        .toHaveAttribute('aria-disabled', 'true')
      await page.screenshot({ path: shotPath(`canvas-catalog-loading-${suffix}.png`) })
    })

    test(`an existing Function node explains a missing catalog entry ${suffix}`, async ({ page }) => {
      const copy = COPY[locale]
      await page.setViewportSize(viewport)
      // 快照里的节点引用了目录中已不存在的函数：面板必须留下，并说明事实。
      await page.goto(`${HARNESS_URL}?locale=${locale}&nodes=function`)
      await expect(page.locator('html')).toHaveAttribute('data-function-catalog', 'ready')

      const node = page.locator('.react-flow__node')
      await expect(node).toHaveCount(1)
      await node.locator('.canvas-node-header').click()

      const panel = page.getByRole('region', { name: copy.panelAria })
      await expect(panel).toBeVisible()
      await expect(panel.getByRole('status')).toHaveText(copy.functionMissing)
      await page.screenshot({ path: shotPath(`canvas-function-missing-${suffix}.png`) })
    })

    test(`a semantic 409 shows the localized conflict banner and toast ${suffix}`, async ({ page }) => {
      const copy = COPY[locale]
      await page.setViewportSize(viewport)
      await page.goto(`${HARNESS_URL}?locale=${locale}&conflict=stale`)
      await expect(page.locator('html')).toHaveAttribute('data-function-catalog', 'ready')

      await page.locator('.canvas-tool-rail .dock-add').click()
      await page.getByRole('menuitem', { name: copy.imageFunction }).click()

      // toast 在 2.8s 后自动消失：先等淡入完成再断言与截图，避免截到空提示。
      const toast = page.locator('#toast')
      await expect(toast).toHaveText(copy.toastServerUpdated)
      await expect(toast).toHaveCSS('opacity', '1')
      const banner = page.locator('.canvas-conflict-banner')
      await expect(banner).toContainText(copy.conflictStale)
      await expect(banner.getByRole('button', { name: copy.conflictRetryLatest })).toBeVisible()
      await expect(banner.getByRole('button', { name: copy.conflictSaveAsNew })).toBeVisible()
      await expect(banner.getByRole('button', { name: copy.conflictDiscard })).toBeInViewport({ ratio: 1 })
      const bannerBox = await banner.boundingBox()
      const toastBox = await toast.boundingBox()
      expect(toastBox!.y).toBeGreaterThanOrEqual(bannerBox!.y + bannerBox!.height)
      await page.screenshot({ path: shotPath(`canvas-conflict-${suffix}.png`) })
      // 409 是终态冲突：不出现“整体重试”恢复入口，只提供就地处理草稿的动作。
      await expect(page.getByRole('alert').getByRole('button', { name: copy.retry, exact: true }))
        .toHaveCount(0)
      await expect(page.locator('.react-flow__node')).toHaveCount(0)
    })

    test(`a failed command batch explains itself with a localized toast ${suffix}`, async ({ page }) => {
      const copy = COPY[locale]
      await page.setViewportSize(viewport)
      await page.goto(`${HARNESS_URL}?locale=${locale}&commands=fail`)
      await expect(page.locator('html')).toHaveAttribute('data-function-catalog', 'ready')

      await page.locator('.canvas-tool-rail .dock-add').click()
      await page.getByRole('menuitem', { name: copy.imageFunction }).click()

      const toast = page.locator('#toast')
      // 批量写入失败的 toast 也走 i18n，并且不在 canvas 上留下假节点。
      await expect(toast).toHaveText(copy.toastOperationFailed)
      await expect(toast).toHaveCSS('opacity', '1')
      await expect(page.locator('.canvas-conflict-banner')).toHaveCount(0)
      await expect(page.locator('.react-flow__node')).toHaveCount(0)
      await page.screenshot({ path: shotPath(`canvas-toast-${suffix}.png`) })
    })

    test(`the zoom reset button is named after its visible percentage ${suffix}`, async ({ page }) => {
      const copy = COPY[locale]
      await page.setViewportSize(viewport)
      await page.goto(`${HARNESS_URL}?locale=${locale}`)

      const group = page.getByRole('group', { name: copy.zoomGroup })
      await expect(group).toBeVisible()
      // 结构顺序：缩小、适应全部、百分比重置、放大。
      const reset = group.locator('button').nth(2)
      await expect(reset).toHaveText('100%')

      // WCAG 2.5.3：可访问名必须包含可见文本，且用同一百分比插值。
      const initialLabel = await reset.getAttribute('aria-label')
      expect(initialLabel).toBe(copy.resetZoom('100%'))
      expect(initialLabel).toContain('100%')

      await group.getByRole('button', { name: copy.zoomIn }).click()
      await expect.poll(async () => (await reset.innerText()).trim()).not.toBe('100%')
      const zoomed = (await reset.innerText()).trim()
      const zoomedLabel = await reset.getAttribute('aria-label')
      expect(zoomedLabel).toBe(copy.resetZoom(zoomed))
      expect(zoomedLabel).toContain(zoomed)

      // 点击仍然重置到 100%，可访问名随可见百分比一起回退。
      await reset.click()
      await expect.poll(async () => (await reset.innerText()).trim()).toBe('100%')
      await expect(reset).toHaveAttribute('aria-label', copy.resetZoom('100%'))
      await page.screenshot({ path: shotPath(`canvas-zoom-${suffix}.png`) })
    })
  }
})

test.describe('Canvas editor empty canvas', () => {
  test('announces save state without a library header or version pill, and the guidance never blocks the stage', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 })
    await page.goto(`${HARNESS_URL}?locale=zh-CN`)

    await expect(page.locator('.editor-view')).toBeVisible()
    // 1.3.0 画布嵌入通用窗格，不再渲染独立的 .editor-header 与 .version-pill，保存状态通过实时区域宣告。
    await expect(page.locator('.editor-header')).toHaveCount(0)
    await expect(page.locator('.version-pill')).toHaveCount(0)
    await expect.poll(() => saveStateText(page)).toBe(COPY['zh-CN'].saved)

    const guidance = page.locator('.canvas-empty-guidance')
    await expect(guidance).toBeVisible()
    await expect(guidance).toContainText(COPY['zh-CN'].title)
    await expect(guidance).toContainText(COPY['zh-CN'].description)
    // 零节点画布没有可导航内容：不渲染缩略图。
    await expect(page.locator('.react-flow__minimap')).toHaveCount(0)

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
    // 空画布不渲染缩略图。
    await expect(page.locator('.react-flow__minimap')).toHaveCount(0)
    await expect(page.locator('html')).toHaveAttribute('data-function-catalog', 'ready')
    await guidance.getByRole('button', { name: COPY['zh-CN'].addEntry }).click()
    await page.getByRole('menuitem', { name: COPY['zh-CN'].imageFunction }).click()

    await expect(page.locator('.react-flow__node')).toHaveCount(1)
    await expect(guidance).toHaveCount(0)
    // 有节点后恢复缩略图。
    await expect(page.locator('.react-flow__minimap')).toBeVisible()
    // 不出现独立画布库头部与版本号，即使 revision 已经前进。
    await expect(page.locator('.editor-header')).toHaveCount(0)
    await expect(page.locator('.version-pill')).toHaveCount(0)
    await expect.poll(() => saveStateText(page)).toBe(COPY['zh-CN'].saved)
  })

  for (const viewport of [
    { width: 1440, height: 900 },
    { width: 390, height: 844 },
  ] as const) {
    test(`the first node is framed clear of the floating add button at ${viewport.width}x${viewport.height}`, async ({
      page,
    }) => {
      await page.setViewportSize(viewport)
      await page.goto(`${HARNESS_URL}?locale=zh-CN`)
      await expect(page.locator('html')).toHaveAttribute('data-function-catalog', 'ready')
      await page.locator('.canvas-empty-guidance')
        .getByRole('button', { name: COPY['zh-CN'].addEntry })
        .click()
      await page.getByRole('menuitem', { name: COPY['zh-CN'].imageFunction }).click()
      const node = page.locator('.react-flow__node')
      await expect(node).toHaveCount(1)

      // 首个节点由初始取景居中：窄屏也不被左侧浮动 `+` 压住，且不超出舞台右缘。
      const add = await box(page.locator('.canvas-tool-rail .dock-add'))
      await expect.poll(async () => (await box(node)).x).toBeGreaterThan(add.x + add.width)
      const nodeBox = await box(node)
      expect(nodeBox.x + nodeBox.width).toBeLessThanOrEqual(viewport.width)
      await page.screenshot({ path: shotPath(`canvas-first-node-${viewport.width}x${viewport.height}.png`) })
    })
  }

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
