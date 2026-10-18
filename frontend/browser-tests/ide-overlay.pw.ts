import { expect, test, type Page } from './fixture'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const __filename = fileURLToPath(import.meta.url)
const __dirname = path.dirname(__filename)
const REPORTS_DIR = process.env.KK_LAYOUT_REPORTS_DIR ?? path.resolve(__dirname, '../../reports/ide-overlay')
const HARNESS_URL = '/browser-tests/ide-overlay-harness.html'

const HARNESS_ENV_ID = 'e1000000-0000-4000-8000-000000000001'
const HARNESS_DIRECTORY = '/workspace/projects/shared-environment/long-project-directory/packages/frontend/src/features/workspace'
const PANE_1_VIEW_ID = 'c1000000-0000-4000-8000-000000000001'
const PANE_2_VIEW_ID = 'c2000000-0000-4000-8000-000000000002'

const ALLOWED_VIEW_IDS = new Set([PANE_1_VIEW_ID, PANE_2_VIEW_ID])

interface MockAudit {
  openCalls: Array<{ environmentId: string; viewId: string }>
  iframeRequests: Record<string, number>
}

async function setupStrictIdeRoutes(page: Page): Promise<MockAudit> {
  const audit: MockAudit = {
    openCalls: [],
    iframeRequests: {},
  }

  // 1. 严格拦截创建 IDE 视图 API：契约字段与 UUID 必须精确匹配，不使用兜底
  await page.route('**/api/harness/environments/*/ide/views', async (route) => {
    const req = route.request()
    if (req.method() !== 'POST') {
      await route.fulfill({ status: 405, body: 'Method Not Allowed' })
      return
    }

    const envMatch = req.url().match(/environments\/([^/]+)\/ide\/views/)
    if (!envMatch || decodeURIComponent(envMatch[1]) !== HARNESS_ENV_ID) {
      throw new Error(`请求携带未知的环境 ID: ${req.url()}`)
    }

    const body = req.postDataJSON() as { viewId?: string } | null
    const viewId = body?.viewId
    if (!viewId || !ALLOWED_VIEW_IDS.has(viewId)) {
      throw new Error(`收到非法或未登记的 viewId: ${String(viewId)}`)
    }

    audit.openCalls.push({ environmentId: HARNESS_ENV_ID, viewId })
    const entryUrl = `/environment-ide/${HARNESS_ENV_ID}/__ide/bootstrap?token=${viewId}`

    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        status: 200,
        data: {
          viewId,
          environmentId: HARNESS_ENV_ID,
          directory: HARNESS_DIRECTORY,
          entryUrl,
        },
      }),
    })
  })

  // 2. 严格拦截 iframe fixture：记录请求次数与不可变 loadNonce 探针
  await page.route('**/environment-ide/*/__ide/bootstrap*', async (route) => {
    const parsed = new URL(route.request().url())
    const viewId = parsed.searchParams.get('token')
    if (!viewId || !ALLOWED_VIEW_IDS.has(viewId)) {
      throw new Error(`iframe fixture 请求携带非预期的 viewId: ${String(viewId)}`)
    }

    const requestCount = (audit.iframeRequests[viewId] ?? 0) + 1
    audit.iframeRequests[viewId] = requestCount
    const loadNonce = `load-nonce-${viewId}-r${requestCount}`

    const html = `<!doctype html>
<html lang="zh-CN">
  <head>
    <meta charset="utf-8" />
    <title>IDE Fixture Frame</title>
    <style>
      body { margin: 0; padding: 12px; background: #181c19; color: #edf2ed; font-family: monospace; }
      textarea { width: 100%; height: 60px; background: #242c26; color: #edf2ed; border: 1px solid #444; }
    </style>
  </head>
  <body>
    <div id="ide-badge">${viewId}</div>
    <div id="load-nonce" data-testid="load-nonce">${loadNonce}</div>
    <textarea id="terminal-draft-input" data-testid="terminal-draft-input"></textarea>
  </body>
</html>`

    await route.fulfill({
      status: 200,
      contentType: 'text/html; charset=utf-8',
      body: html,
    })
  })

  // 3. 辅助 API 终端创建与显式关闭精确放行，其余路径 404
  await page.route('**/api/harness/environments/*/ide/views/**/terminal', async (route) => {
    if (route.request().method() === 'POST') {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ status: 200, data: null }),
      })
      return
    }
    await route.fulfill({ status: 404 })
  })

  await page.route('**/api/harness/environments/*/ide/views/*', async (route) => {
    if (route.request().method() === 'DELETE') {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ status: 200, data: null }),
      })
      return
    }
    await route.fulfill({ status: 404 })
  })

  return audit
}

test.describe('Chat IDE Overlay Responsive & Iframe Identity Durability', () => {
  test.beforeAll(() => {
    if (!fs.existsSync(REPORTS_DIR)) {
      fs.mkdirSync(REPORTS_DIR, { recursive: true })
    }
  })

  test('Case 1: 宽桌面响应式 min(640px, 60%) 靠右贴齐，窄 Pane 100% 覆盖且原 Pane 输入保持 mounted', async ({
    page,
  }) => {
    await setupStrictIdeRoutes(page)

    await page.setViewportSize({ width: 1440, height: 900 })
    await page.goto(HARNESS_URL)

    const pane1 = page.locator('[data-testid="test-pane-1"]')
    await expect(pane1).toBeVisible()

    const composerInput = page.locator('[data-testid="composer-input-pane-1"]')
    await expect(composerInput).toBeVisible()
    await composerInput.fill('message draft before ide open')

    await page.click('[data-testid="p1-open-btn"]')

    const overlay = pane1.locator('[data-testid="ide-overlay-panel"]')
    await expect(overlay).toBeVisible()

    const iframe = pane1.locator('iframe[data-testid="ide-iframe"]')
    await expect(iframe).toBeVisible()

    const paneBox = (await pane1.boundingBox())!
    const overlayBox = (await overlay.boundingBox())!

    // 宽桌面 1000px: 60% = 600px, min(640px, 600px) = 600px
    expect(Math.abs(overlayBox.width - 600)).toBeLessThanOrEqual(2)
    // 靠右贴齐
    expect(Math.abs((overlayBox.x + overlayBox.width) - (paneBox.x + paneBox.width))).toBeLessThanOrEqual(2)

    const toolbar = overlay.getByTestId('ide-toolbar')
    const directory = toolbar.getByRole('button', { name: '打开目录' })
    await expect(directory).toHaveText(HARNESS_DIRECTORY)
    await expect(directory).toHaveAttribute('title', `Isolated Dev Node · ${HARNESS_DIRECTORY}`)
    expect((await toolbar.boundingBox())!.height).toBeLessThanOrEqual(34)
    await expect(toolbar.getByRole('button')).toHaveCount(4)

    await expect(composerInput).toBeVisible()
    await composerInput.fill('message draft after wide ide open')
    expect(await composerInput.inputValue()).toBe('message draft after wide ide open')

    await page.screenshot({ path: path.join(REPORTS_DIR, 'ide-overlay-desktop.png') })

    await page.click('[data-testid="p1-set-narrow"]')

    const narrowPaneBox = (await pane1.boundingBox())!
    const narrowOverlayBox = (await overlay.boundingBox())!

    expect(Math.abs(narrowPaneBox.width - 360)).toBeLessThanOrEqual(2)
    expect(Math.abs(narrowOverlayBox.width - 360)).toBeLessThanOrEqual(2)
    expect(Math.abs(narrowOverlayBox.width - narrowPaneBox.width)).toBeLessThanOrEqual(2)

    expect((await toolbar.boundingBox())!.height).toBeLessThanOrEqual(34)
    expect(await toolbar.evaluate((el) => el.scrollWidth <= el.clientWidth)).toBe(true)
    const directoryLabel = toolbar.getByTestId('ide-directory-badge')
    expect(await directoryLabel.evaluate((el) => el.scrollWidth > el.clientWidth)).toBe(true)
    await directory.click()
    await expect(page.getByTestId('ide-directory-input')).toHaveValue(HARNESS_DIRECTORY)
    await page.getByRole('button', { name: '取消', exact: true }).click()

    // 原 pane composer 仍保持 attached
    await expect(composerInput).toBeAttached()
    expect(await composerInput.inputValue()).toBe('message draft after wide ide open')

    await page.screenshot({ path: path.join(REPORTS_DIR, 'ide-overlay-mobile.png') })
  })

  test('Case 2: 容器 layout 切换及 visible 收起恢复过程中，iframe 同 DOM 引用、单次网络请求与输入状态保持', async ({
    page,
  }) => {
    const audit = await setupStrictIdeRoutes(page)

    await page.setViewportSize({ width: 1440, height: 900 })
    await page.goto(HARNESS_URL)

    const pane1 = page.locator('[data-testid="test-pane-1"]')
    await page.click('[data-testid="p1-open-btn"]')

    const overlay = pane1.locator('[data-testid="ide-overlay-panel"]')
    await expect(overlay).toBeVisible()

    const iframe = pane1.locator('iframe[data-testid="ide-iframe"]')
    await expect(iframe).toBeVisible()

    const frame = page.frameLocator('[data-pane-id="pane-1"] iframe[data-testid="ide-iframe"]')
    const draftInput = frame.locator('#terminal-draft-input')
    await expect(draftInput).toBeVisible()

    const draftContent = 'npm test -- --grep "ide-overlay"'
    await draftInput.fill(draftContent)
    await expect(draftInput).toHaveValue(draftContent)

    // 记录 DOM 实例与首屏 nonce
    await page.evaluate(() => {
      const el = document.querySelector('[data-pane-id="pane-1"] iframe[data-testid="ide-iframe"]')
      ;(window as unknown as { __cachedIframeEl: Element | null }).__cachedIframeEl = el
    })
    const initialNonce = await frame.locator('#load-nonce').textContent()
    expect(initialNonce).toBeTruthy()
    expect(audit.iframeRequests[PANE_1_VIEW_ID]).toBe(1)

    // 阶段 A: 容器 Layout 宽 -> 窄 (360px) -> 宽 (1000px) 动态切换
    await page.click('[data-testid="p1-set-narrow"]')
    await page.click('[data-testid="p1-set-wide"]')

    const isSameAfterResize = await page.evaluate(() => {
      const current = document.querySelector('[data-pane-id="pane-1"] iframe[data-testid="ide-iframe"]')
      return (window as unknown as { __cachedIframeEl: Element | null }).__cachedIframeEl === current
    })
    expect(isSameAfterResize).toBe(true)
    expect(audit.iframeRequests[PANE_1_VIEW_ID]).toBe(1)
    expect(await frame.locator('#load-nonce').textContent()).toBe(initialNonce)
    await expect(draftInput).toHaveValue(draftContent)

    // 阶段 B: visible 收起 -> 恢复
    await page.click('[data-testid="p1-collapse-btn"]')
    await expect(overlay).toHaveAttribute('hidden', '')
    await expect(overlay).toBeHidden()
    await expect(iframe).toBeAttached()

    await page.click('[data-testid="p1-restore-btn"]')
    await expect(overlay).toBeVisible()
    await expect(overlay).not.toHaveAttribute('hidden')

    const isSameAfterRestore = await page.evaluate(() => {
      const current = document.querySelector('[data-pane-id="pane-1"] iframe[data-testid="ide-iframe"]')
      return (window as unknown as { __cachedIframeEl: Element | null }).__cachedIframeEl === current
    })
    expect(isSameAfterRestore).toBe(true)
    expect(audit.iframeRequests[PANE_1_VIEW_ID]).toBe(1)
    expect(await frame.locator('#load-nonce').textContent()).toBe(initialNonce)
    await expect(draftInput).toHaveValue(draftContent)
  })

  test('Case 3: 双 Pane 独立 viewId 与 iframe 隔离，互不串扰且 API OPEN 仅调用一次', async ({ page }) => {
    const audit = await setupStrictIdeRoutes(page)

    await page.setViewportSize({ width: 1440, height: 900 })
    await page.goto(HARNESS_URL)

    await page.click('[data-testid="p1-open-btn"]')
    const p1Frame = page.frameLocator('[data-pane-id="pane-1"] iframe[data-testid="ide-iframe"]')
    await expect(p1Frame.locator('#ide-badge')).toHaveText(PANE_1_VIEW_ID)

    await page.click('[data-testid="p2-open-btn"]')
    const p2Frame = page.frameLocator('[data-pane-id="pane-2"] iframe[data-testid="ide-iframe"]')
    await expect(p2Frame.locator('#ide-badge')).toHaveText(PANE_2_VIEW_ID)

    expect(audit.openCalls.length).toBe(2)
    expect(audit.openCalls.map((c) => c.viewId)).toEqual([PANE_1_VIEW_ID, PANE_2_VIEW_ID])
    expect(audit.iframeRequests[PANE_1_VIEW_ID]).toBe(1)
    expect(audit.iframeRequests[PANE_2_VIEW_ID]).toBe(1)

    await p1Frame.locator('#terminal-draft-input').fill('p1 distinct workspace state')
    await p2Frame.locator('#terminal-draft-input').fill('p2 distinct workspace state')

    await page.click('[data-testid="p1-collapse-btn"]')
    const p1Overlay = page.locator('[data-testid="test-pane-1"] [data-testid="ide-overlay-panel"]')
    await expect(p1Overlay).toBeHidden()

    const p2Overlay = page.locator('[data-testid="test-pane-2"] [data-testid="ide-overlay-panel"]')
    await expect(p2Overlay).toBeVisible()
    await expect(p2Frame.locator('#terminal-draft-input')).toHaveValue('p2 distinct workspace state')

    await page.click('[data-testid="p1-restore-btn"]')
    await expect(p1Overlay).toBeVisible()

    await expect(p1Frame.locator('#terminal-draft-input')).toHaveValue('p1 distinct workspace state')
    await expect(p2Frame.locator('#terminal-draft-input')).toHaveValue('p2 distinct workspace state')

    // 确认网络请求次数与 API OPEN 仍各为 1 次
    expect(audit.openCalls.length).toBe(2)
    expect(audit.iframeRequests[PANE_1_VIEW_ID]).toBe(1)
    expect(audit.iframeRequests[PANE_2_VIEW_ID]).toBe(1)
  })
})
