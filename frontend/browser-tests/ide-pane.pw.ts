import { expect, test, type Page } from './fixture'
import type { EnvironmentSummaryDTO } from '@/shared/api/generated/contracts'
import { IDE_SESSION_STORAGE_KEY } from '@/features/environment/ide/ide-session-state'

const URL = '/browser-tests/ide-pane-harness.html'
const ENV = 'a1000000-0000-4000-8000-000000000001'
const NODE = 'e1000000-0000-4000-8000-000000000001'
const DAEMON = 'd1000000-0000-4000-8000-000000000001'

async function mockIde(page: Page) {
  const views = new Map<string, Record<string, unknown>>()
  const opens: Array<{ viewId: string; mode: string; pendingClaim?: string; claimId: string; connectionId: string }> = []
  const closes: boolean[] = []
  const closeResults: Array<'cancel' | 'discard' | 'unavailable'> = []
  let revision = 1000
  let epoch = 10
  let connections = 0
  let usage: Record<string, unknown>[] = []
  const adminRequests: string[] = []
  await page.routeWebSocket(/\/ws\/events$/, (socket) => {
    const connectionId = `f1000000-0000-4000-8000-${String(++connections).padStart(12, '0')}`
    let connected = false
    socket.onMessage((raw) => {
      const message = JSON.parse(String(raw))
      if (!connected) {
        connected = true
        socket.send(JSON.stringify({ type: 'connected', eventNodeId: NODE, connectionId, heartbeatIntervalMillis: '20000' }))
      }
      if (message.type === 'subscribe') {
        socket.send(JSON.stringify({ type: 'subscribed', resource: message.resource, cursor: '0' }))
      } else if (message.type === 'ide-use') {
        usage = message.views
        socket.send(JSON.stringify({ type: 'ide-use-accepted', revision: message.revision }))
      }
    })
  })
  await page.route('**/api/**', async (route) => {
    const request = route.request()
    const url = new globalThis.URL(request.url())
    if (url.pathname === '/api/platform/environments') {
      adminRequests.push(request.method())
      await route.fulfill({ status: 403, contentType: 'application/problem+json', json: { status: 403, reason: 'ADMIN_REQUIRED' } })
      return
    }
    if (url.pathname === '/api/platform/environment-summaries') {
      await route.fulfill({ json: [
        { id: ENV, name: 'local-node', ready: true, status: 'READY', capabilities: [] },
        { id: 'a1000000-0000-4000-8000-000000000002', name: 'remote-node', ready: false, status: 'CONNECTING', capabilities: [] },
        { id: 'a1000000-0000-4000-8000-000000000003', name: 'spare-node', ready: false, status: 'OFFLINE', capabilities: [] },
      ] satisfies EnvironmentSummaryDTO[] })
      return
    }
    const path = `/api/ide/environments/${ENV}/views`
    if (request.method() === 'GET' && url.pathname.startsWith(`${path}/`)) {
      const id = url.pathname.slice(path.length + 1)
      const view = views.get(id)
      await route.fulfill({ json: { daemonInstanceId: DAEMON, revision: String(++revision), view: view ? { ...view, entryUrl: null } : null } })
      return
    }
    if (request.method() === 'POST' && url.pathname === path) {
      const body = request.postDataJSON()
      const id = String(body.viewId)
      const pendingClaim = await page.evaluate(([key, viewId]) => (
        JSON.parse(sessionStorage.getItem(key) ?? '{}').recoveryByView?.[viewId]?.pending?.claimId
      ), [IDE_SESSION_STORAGE_KEY, id] as const)
      opens.push({ viewId: id, mode: body.mode, claimId: body.claimId, pendingClaim, connectionId: body.connectionId })
      if (!usage.some(item => item.environmentId === ENV && item.viewId === id && item.claimId === body.claimId)) {
        await route.fulfill({ status: 409, json: { reason: 'STALE_BINDING' } })
        return
      }
      const bindingEpoch = String(++epoch)
      const view = {
        viewId: id, environmentId: ENV, daemonInstanceId: DAEMON, bindingEpoch,
        browserSessionId: body.browserSessionId, eventNodeId: body.eventNodeId,
        connectionId: body.connectionId, claimId: body.claimId, directory: null,
        workspacePath: `/srv/kk-studio/ide/${ENV}/${id}.code-workspace`, state: 'OPEN',
        entryUrl: `/ide/${ENV}/${id}/__ide/bootstrap?token=${id}-${bindingEpoch}`,
      }
      views.set(id, view)
      await route.fulfill({ json: view })
      return
    }
    if (url.pathname.endsWith('/close')) {
      const id = url.pathname.split('/').at(-2)!
      const { discard } = request.postDataJSON()
      closes.push(discard)
      const result = closeResults.shift()
      if (result === 'cancel' || result === 'unavailable') {
        const status = result === 'cancel' ? 400 : 409
        const reason = result === 'cancel' ? 'IDE_CLOSE_CANCELLED' : 'IDE_CONTROL_UNAVAILABLE'
        await route.fulfill({ status, contentType: 'application/problem+json', json: { status, reason, title: reason } })
        return
      }
      views.delete(id)
      await route.fulfill({ status: 204 })
      return
    }
    if (url.pathname.endsWith('/apply-mobile-layout')) {
      await route.fulfill({ status: 204 })
      return
    }
    await route.fulfill({ status: 404 })
  })
  await page.route('**/ide/**/__ide/bootstrap*', async (route) => {
    const id = new globalThis.URL(route.request().url()).pathname.split('/')[3]!
    const view = views.get(id)!
    await route.fulfill({ contentType: 'text/html; charset=utf-8', body: `<!doctype html>
      <html><head><meta charset="utf-8"><style>
      body{margin:0;background:#1e1e1e;color:#ccc;font:13px system-ui}
      header{padding:8px;background:#252526} button{color:inherit;background:#333;border:0;padding:6px 10px}
      textarea{display:block;box-sizing:border-box;width:100%;padding:16px;min-height:8rem;background:transparent;color:#9cdcfe;border:0;resize:none}
      </style></head><body><header><button type="button" data-testid="mock-new-window">New Window</button></header>
      <textarea aria-label="Editor" spellcheck="false"># Workspace</textarea>
      <script>document.querySelector('button').onclick = () => parent.postMessage(${JSON.stringify({
        type: 'kk-studio.ide.native-action', action: 'new-window', viewId: id, bindingEpoch: view.bindingEpoch,
      })}, location.origin)</script></body></html>` })
  })
  return { opens, closes, closeResults, adminRequests }
}

async function openIde(page: Page) {
  await page.goto(URL)
  await page.getByRole('button', { name: '在 local-node 打开 VS Code' }).click()
  const frame = page.getByTestId('ide-iframe')
  await expect(frame.contentFrame().getByRole('textbox', { name: 'Editor' })).toBeVisible()
  return frame
}

async function stableHost(page: Page) {
  const before = await page.getByTestId('ide-iframe').evaluateHandle(node => {
    const frame = node as HTMLIFrameElement
    return { frame, document: frame.contentDocument, parent: frame.parentElement, pane: frame.closest('[data-pane-id]') }
  })
  return async () => expect(await before.evaluate(({ frame, document, parent, pane }) => (
    frame.isConnected && frame.contentDocument === document && frame.parentElement === parent && frame.closest('[data-pane-id]') === pane
  ))).toBe(true)
}

test('a non-admin can see environments and open VS Code without querying the admin list', async ({ page }) => {
  const { adminRequests } = await mockIde(page)
  await openIde(page)
  const sidebar = page.getByRole('navigation', { name: '环境列表' })
  for (const name of ['local-node', 'remote-node', 'spare-node']) {
    await expect(sidebar.getByText(name, { exact: true })).toBeVisible()
  }
  await expect(sidebar.getByRole('button')).toHaveCount(1)
  expect(adminRequests).toEqual([])
})

test('keeps the iframe Document across split, swap, fullscreen, narrow hiding, and sibling close', async ({ page }) => {
  await mockIde(page)
  await page.setViewportSize({ width: 1440, height: 900 })
  await openIde(page)
  const check = await stableHost(page)
  await page.getByTestId('split-pane').click()
  await expect(page.locator('[data-pane-id]')).toHaveCount(2)
  await check()
  const pane = page.locator('[data-pane-id]').filter({ has: page.getByTestId('ide-iframe') })
  const x = (await pane.boundingBox())!.x
  await page.getByTestId('swap-panes').click()
  await expect.poll(async () => (await pane.boundingBox())?.x).not.toBe(x)
  await check()
  await page.getByTestId('toggle-fullscreen').click()
  await expect(pane).toBeHidden()
  await check()
  await page.getByTestId('toggle-fullscreen').click()
  await expect(pane).toBeVisible()
  await page.setViewportSize({ width: 390, height: 900 })
  await expect(pane).toBeHidden()
  await check()
  await page.getByTestId('last-pane').click()
  await expect(pane).toBeVisible()
  await check()
  await page.getByTestId('close-sibling').click()
  await expect(page.locator('[data-pane-id]')).toHaveCount(1)
  await check()
})

test('native new-window creates an independent view while ordinary opening reuses the original', async ({ page }) => {
  const mock = await mockIde(page)
  const old = await openIde(page)
  const check = await stableHost(page)
  const original = old.contentFrame()
  await original.getByRole('textbox').fill('unsaved edits')
  await page.getByRole('button', { name: '在 local-node 打开 VS Code' }).click()
  await expect(old).toHaveCount(1)
  await original.getByTestId('mock-new-window').click()
  await expect(page.getByTestId('ide-iframe')).toHaveCount(2)
  await check()
  await expect(page.locator(`[data-ide-view="${mock.opens[0]!.viewId}"]`).contentFrame().getByRole('textbox')).toHaveValue('unsaved edits')
  expect(mock.opens.map(open => open.mode)).toEqual(['create', 'create'])
  expect(new Set(mock.opens.map(open => open.viewId)).size).toBe(2)
  for (const open of mock.opens) expect(open.pendingClaim).toBe(open.claimId)
})

test('native cancel retains the workbench, then discard and explicit force close release it', async ({ page }) => {
  const mock = await mockIde(page)
  await openIde(page)
  const check = await stableHost(page)
  mock.closeResults.push('cancel', 'discard', 'unavailable')
  const cancelled = page.waitForResponse(response => response.url().endsWith('/close') && response.status() === 400)
  await page.getByTestId('close-ide').click()
  await cancelled
  await expect(page.getByRole('alertdialog')).toHaveCount(0)
  await check()
  await page.getByTestId('close-ide').click()
  await expect(page.getByTestId('ide-iframe')).toHaveCount(0)
  await page.getByRole('button', { name: '在 local-node 打开 VS Code' }).click()
  await expect(page.getByTestId('ide-iframe')).toBeVisible()
  await page.getByTestId('close-ide').click()
  await expect(page.getByRole('alertdialog', { name: '强制关闭 IDE 视图？' })).toBeVisible()
  await expect(page.getByTestId('ide-iframe')).toBeVisible()
  await page.getByRole('button', { name: '强制关闭', exact: true }).click()
  await expect(page.getByTestId('ide-iframe')).toHaveCount(0)
  expect(mock.closes).toEqual([false, false, false, true])
})

test('reconnect and refresh claim the same view ID against the new connection', async ({ page }) => {
  const mock = await mockIde(page)
  const frame = await openIde(page)
  const firstUrl = await frame.getAttribute('src')
  await page.getByTestId('reconnect').click()
  await expect(frame).not.toHaveAttribute('src', firstUrl!)
  await expect(frame.contentFrame().getByRole('textbox')).toBeVisible()
  await page.reload()
  await expect(frame.contentFrame().getByRole('textbox')).toBeVisible()
  expect(mock.opens.map(({ viewId, mode }) => [viewId, mode])).toEqual([
    [mock.opens[0]?.viewId, 'create'], [mock.opens[0]?.viewId, 'resume'], [mock.opens[0]?.viewId, 'resume'],
  ])
  expect(new Set(mock.opens.map(({ connectionId }) => connectionId)).size).toBe(3)
})

for (const width of [1440, 390, 320]) {
  test(`renders sidebar and VS Code at ${width}px`, async ({ page }) => {
    await mockIde(page)
    await page.setViewportSize({ width, height: 900 })
    const frame = await openIde(page)
    const paneContent = page.locator('[data-pane-content]').filter({ has: frame })
    expect((await frame.boundingBox())?.width).toBe((await paneContent.boundingBox())?.width)
    if (width < 760) {
      const button = await page.getByRole('button', { name: '在 local-node 打开 VS Code' }).boundingBox()
      expect(button?.width).toBeGreaterThanOrEqual(44)
      expect(button?.height).toBeGreaterThanOrEqual(44)
    }
    await page.locator('.ide-harness').screenshot({ path: `../reports/r130-ide/ide-${width}.png` })
  })
}
