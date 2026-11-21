import { expect, test, type Locator, type Page } from './fixture'
import type { Frame, Route } from '@playwright/test'
import type { EnvironmentDTO } from '@/shared/api/generated/contracts'
import { testDaemonConfig } from '@/test-support/environment-test-fixtures'

const HARNESS_URL = '/browser-tests/ide-overlay-harness.html'
const SESSION_KEY = 'kk-studio.tab-state:ide'
const ENV_A = 'a1000000-0000-4000-8000-000000000001'
const ENV_B = 'a1000000-0000-4000-8000-000000000002'
const EVENT_NODE = 'e1000000-0000-4000-8000-000000000001'
/** 无目录工作区在 zh-CN 下的标签短名。 */
const EMPTY_WINDOW = '空窗口'
/** 固定托管入口发给 Studio 的唯一 Native 动作消息类型。 */
const NATIVE_ACTION_TYPE = 'kk-studio.ide.native-action'

const CARDS: EnvironmentDTO[] = [
  {
    id: ENV_A,
    name: 'local-node',
    daemonConfig: testDaemonConfig(),
    ready: true,
    status: 'READY',
    version: '1',
    createdAt: 1790812800000,
    updatedAt: 1790812800000,
    capabilities: [],
    statusExpiresAt: null,
    lastSeenAt: null,
    homeDirectory: '/workspace/a',
  },
  {
    id: ENV_B,
    name: 'remote-node',
    daemonConfig: testDaemonConfig(),
    ready: true,
    status: 'READY',
    version: '1',
    createdAt: 1790812800000,
    updatedAt: 1790812800000,
    capabilities: [],
    statusExpiresAt: null,
    lastSeenAt: null,
    homeDirectory: '/workspace/b',
  },
]

/**
 * 一个 view 绑定的线上形状。`directory` 无目录时写 `null`，`workspacePath` 永远是
 * Daemon 登记的 `.code-workspace`；两个字段都显式出现，不靠缺省表达。
 */
interface ViewRecord {
  viewId: string
  environmentId: string
  browserSessionId: string
  eventNodeId: string
  connectionId: string
  claimId: string
  daemonInstanceId: string
  bindingEpoch: string
  directory: string | null
  workspacePath: string
  state: 'OPEN' | 'CLOSING'
  entryUrl: string
}

interface OpenCall {
  environmentId: string
  body: Record<string, unknown>
  pendingAtPost: Record<string, unknown> | null
  sessionBrowserIdAtPost: string | null
}

interface DirectoryCall {
  environmentId: string
  viewId: string
  body: Record<string, unknown>
}

interface CloseCall {
  environmentId: string
  viewId: string
  body: Record<string, unknown>
}

interface TransportState {
  views: Record<string, ViewRecord>
  revision: number
  epoch: number
  openCalls: OpenCall[]
  readCalls: Array<{ environmentId: string; viewId: string; query: Record<string, string> }>
  directoryCalls: DirectoryCall[]
  /** 已删除的 LIST_DIRECTORIES 入口不得再被任何 UI 路径调用。 */
  directoryListCalls: string[]
  closeCalls: CloseCall[]
  terminalCalls: string[]
  usageFrames: Array<{ browserSessionId: string; connectionId: string; revision: string; views: unknown[] }>
  closedConnections: Set<string>
  iframeRequests: Record<string, number>
  eventConnectionIds: string[]
  failNextOpen: 'STALE_BINDING' | null
  holdNextOpen: boolean
  heldOpen: { route: Route; body: Record<string, unknown>; epoch: string } | null
  holdNextDirectory: boolean
  directoryHeld: boolean
  releaseNextDirectory: (() => void) | null
  failNextDirectory: 'IDE_CONTROL_UNAVAILABLE' | null
  failNextClose: 'IDE_CONTROL_UNAVAILABLE' | null
  holdNextClose: boolean
  closeHeld: boolean
  releaseNextClose: (() => void) | null
}

function connectionId(index: number): string {
  return `f1000000-0000-4000-8000-${String(index).padStart(12, '0')}`
}

function daemonId(environmentId: string): string {
  return environmentId === ENV_A
    ? 'd1000000-0000-4000-8000-000000000001'
    : 'd2000000-0000-4000-8000-000000000002'
}

/** 每个绑定唯一的托管工作区文件；无目录时文件里是 `folders: []`。 */
function workspacePath(environmentId: string, viewId: string): string {
  return `/srv/kk-studio/ide/${environmentId}/${viewId}.code-workspace`
}

function entryUrl(environmentId: string, viewId: string, epoch: string): string {
  return `/ide/${environmentId}/${viewId}/__ide/bootstrap?token=${viewId}-${epoch}`
}

function viewWire(view: ViewRecord): Record<string, unknown> {
  return {
    viewId: view.viewId,
    environmentId: view.environmentId,
    browserSessionId: view.browserSessionId,
    eventNodeId: view.eventNodeId,
    connectionId: view.connectionId,
    claimId: view.claimId,
    daemonInstanceId: view.daemonInstanceId,
    bindingEpoch: view.bindingEpoch,
    directory: view.directory,
    workspacePath: view.workspacePath,
    state: view.state,
    entryUrl: view.entryUrl,
  }
}

/** 纯读快照：不发票据，`entryUrl` 必须为空。 */
function snapshotWire(view: ViewRecord): Record<string, unknown> {
  return { ...viewWire(view), entryUrl: null }
}

function problem(status: number, reason: string, title: string): Record<string, unknown> {
  return {
    type: 'about:blank',
    status,
    code: status === 409 ? 'unavailable' : 'conflict',
    reason,
    title,
  }
}

async function pendingAtPost(page: Page): Promise<{
  pending: Record<string, unknown> | null
  browserSessionId: string | null
}> {
  return page.evaluate((key) => {
    const snapshot = JSON.parse(sessionStorage.getItem(key) ?? '{}') as {
      browserSessionId?: string
      recoveryByView?: Record<string, { pending?: Record<string, unknown> }>
    }
    const entries = Object.values(snapshot.recoveryByView ?? {})
    return {
      pending: entries.find((entry) => entry.pending != null)?.pending ?? null,
      browserSessionId: snapshot.browserSessionId ?? null,
    }
  }, SESSION_KEY)
}

async function installTransport(page: Page, state: TransportState): Promise<void> {
  await page.routeWebSocket(/\/ws\/events$/, (socket) => {
    const currentConnection = connectionId(state.eventConnectionIds.length + 1)
    state.eventConnectionIds.push(currentConnection)
    socket.onClose(() => { state.closedConnections.add(currentConnection) })
    let connected = false
    socket.onMessage((raw) => {
      const message = JSON.parse(String(raw)) as Record<string, unknown>
      if (!connected) {
        connected = true
        socket.send(JSON.stringify({
          type: 'connected',
          eventNodeId: EVENT_NODE,
          connectionId: currentConnection,
          // 客户端严格解码 connected：缺省 heartbeat 间隔会被判为畸形帧而永不就绪。
          heartbeatIntervalMillis: '20000',
        }))
      }
      if (message.type === 'subscribe') {
        socket.send(JSON.stringify({
          type: 'subscribed',
          resource: message.resource,
          cursor: '0',
        }))
      } else if (message.type === 'ide-use') {
        state.usageFrames.push({ ...message, connectionId: currentConnection } as unknown as {
          browserSessionId: string
          connectionId: string
          revision: string
          views: unknown[]
        })
        socket.send(JSON.stringify({
          type: 'ide-use-accepted',
          revision: message.revision,
        }))
      }
    })
  })

  await page.route('**/api/**', async (route) => {
    const request = route.request()
    const parsed = new URL(request.url())
    const path = parsed.pathname
    const method = request.method()

    if (method === 'GET' && path === '/api/platform/environments') {
      await route.fulfill({ json: CARDS })
      return
    }

    // 目录选择器和最近目录已随 Studio 面板删除：任何调用都记为失败证据。
    const directoriesMatch = path.match(/^\/api\/ide\/environments\/([^/]+)\/directories$/)
    if (method === 'GET' && directoriesMatch != null) {
      state.directoryListCalls.push(decodeURIComponent(directoriesMatch[1]!))
      await route.fulfill({
        status: 404,
        contentType: 'application/problem+json',
        json: problem(404, 'NOT_FOUND', 'directory listing is not part of the managed IDE'),
      })
      return
    }

    const readMatch = path.match(/^\/api\/ide\/environments\/([^/]+)\/views\/([^/]+)$/)
    if (method === 'GET' && readMatch != null) {
      const environmentId = decodeURIComponent(readMatch[1]!)
      const viewId = decodeURIComponent(readMatch[2]!)
      state.readCalls.push({
        environmentId,
        viewId,
        query: Object.fromEntries(parsed.searchParams.entries()),
      })
      if (!hasUsageIntent(state, environmentId, viewId, Object.fromEntries(parsed.searchParams.entries()))) {
        await route.fulfill({
          status: 409,
          json: problem(409, 'STALE_BINDING', 'browser usage intent is not confirmed'),
        })
        return
      }
      state.revision += 1
      const view = state.views[viewId]
      await route.fulfill({
        json: {
          daemonInstanceId: daemonId(environmentId),
          revision: String(state.revision),
          view: view == null ? null : snapshotWire(view),
        },
      })
      return
    }

    const directoryMatch = path.match(/^\/api\/ide\/environments\/([^/]+)\/views\/([^/]+)\/directory$/)
    if (method === 'PUT' && directoryMatch != null) {
      const environmentId = decodeURIComponent(directoryMatch[1]!)
      const viewId = decodeURIComponent(directoryMatch[2]!)
      const body = (request.postDataJSON() as Record<string, unknown> | null) ?? {}
      state.directoryCalls.push({ environmentId, viewId, body })
      const view = state.views[viewId]
      if (view == null || view.environmentId !== environmentId) {
        await route.fulfill({
          status: 404,
          contentType: 'application/problem+json',
          json: problem(404, 'VIEW_GONE', 'view gone'),
        })
        return
      }
      if (state.holdNextDirectory) {
        state.holdNextDirectory = false
        state.directoryHeld = true
        await new Promise<void>((resolve) => { state.releaseNextDirectory = resolve })
        state.directoryHeld = false
        state.releaseNextDirectory = null
      }
      const failure = state.failNextDirectory
      state.failNextDirectory = null
      if (failure != null) {
        await route.fulfill({
          status: 409,
          contentType: 'application/problem+json',
          json: problem(409, failure, 'native folder selection was cancelled'),
        })
        return
      }
      if (
        view.bindingEpoch !== String(body.bindingEpoch)
        || view.daemonInstanceId !== String(body.expectedDaemonInstanceId)
        || view.browserSessionId !== String(body.browserSessionId)
      ) {
        await route.fulfill({
          status: 409,
          contentType: 'application/problem+json',
          json: problem(409, 'STALE_BINDING', 'binding changed'),
        })
        return
      }
      const target = body.directory
      if (typeof target !== 'string' || target.length === 0) {
        await route.fulfill({
          status: 400,
          contentType: 'application/problem+json',
          json: problem(400, 'BAD_REQUEST', 'directory is required'),
        })
        return
      }
      // 只有 Daemon 已确认的工作区快照才回执新目录；轨迹身份与工作区文件不变。
      view.directory = target
      await route.fulfill({ json: viewWire(view) })
      return
    }

    const openMatch = path.match(/^\/api\/ide\/environments\/([^/]+)\/views$/)
    if (method === 'POST' && openMatch != null) {
      const environmentId = decodeURIComponent(openMatch[1]!)
      const body = (request.postDataJSON() as Record<string, unknown> | null) ?? {}
      const pending = await pendingAtPost(page)
      state.openCalls.push({
        environmentId,
        body,
        pendingAtPost: pending.pending,
        sessionBrowserIdAtPost: pending.browserSessionId,
      })
      if (!hasUsageIntent(state, environmentId, String(body.viewId), body)) {
        await route.fulfill({
          status: 409,
          json: problem(409, 'STALE_BINDING', 'browser usage intent is not confirmed'),
        })
        return
      }
      if (state.holdNextOpen) {
        state.holdNextOpen = false
        state.epoch += 1
        state.heldOpen = { route, body, epoch: String(state.epoch + 10) }
        return
      }
      const failure = state.failNextOpen
      state.failNextOpen = null
      if (failure === 'STALE_BINDING') {
        const existing = state.views[String(body.viewId)]
        if (existing != null) {
          state.epoch += 1
          existing.bindingEpoch = String(state.epoch + 10)
        }
        await route.fulfill({
          status: 409,
          contentType: 'application/problem+json',
          json: problem(409, 'STALE_BINDING', 'binding changed'),
        })
        return
      }

      const viewId = String(body.viewId)
      const mode = String(body.mode)
      const existing = state.views[viewId]
      if (mode === 'resume' && existing == null) {
        await route.fulfill({
          status: 409,
          contentType: 'application/problem+json',
          json: problem(409, 'VIEW_GONE', 'view gone'),
        })
        return
      }
      if (
        existing != null
        && existing.browserSessionId !== String(body.browserSessionId)
      ) {
        await route.fulfill({
          status: 409,
          contentType: 'application/problem+json',
          json: problem(409, 'VIEW_OWNED_BY_OTHER_SESSION', 'view owned by another browser session'),
        })
        return
      }
      if (existing != null && existing.claimId === body.claimId
        && existing.connectionId === body.connectionId && existing.eventNodeId === body.eventNodeId) {
        await route.fulfill({ json: viewWire(existing) })
        return
      }
      if (
        mode === 'resume'
        && existing != null
        && existing.bindingEpoch !== String(body.expectedBindingEpoch)
      ) {
        await route.fulfill({
          status: 409,
          contentType: 'application/problem+json',
          json: problem(409, 'STALE_BINDING', 'binding changed'),
        })
        return
      }
      // UI 只用无目录创建；协议仍接受显式目录，因此这里按请求原样登记。
      const requestedDirectory = typeof body.directory === 'string' && body.directory.length > 0
        ? body.directory
        : null
      const view: ViewRecord = existing ?? {
        viewId,
        environmentId,
        browserSessionId: String(body.browserSessionId),
        eventNodeId: String(body.eventNodeId),
        connectionId: String(body.connectionId),
        claimId: String(body.claimId),
        daemonInstanceId: daemonId(environmentId),
        bindingEpoch: '',
        directory: requestedDirectory,
        workspacePath: workspacePath(environmentId, viewId),
        state: 'OPEN',
        entryUrl: '',
      }
      if (mode === 'create' && existing == null) {
        state.epoch += 1
        view.bindingEpoch = String(state.epoch + 10)
      } else {
        state.epoch += 1
        view.bindingEpoch = String(state.epoch + 10)
        view.eventNodeId = String(body.eventNodeId)
        view.connectionId = String(body.connectionId)
        view.claimId = String(body.claimId)
        view.browserSessionId = String(body.browserSessionId)
      }
      view.entryUrl = entryUrl(environmentId, viewId, view.bindingEpoch)
      state.views[viewId] = view
      await route.fulfill({ json: viewWire(view) })
      return
    }

    const terminalMatch = path.match(/^\/api\/ide\/environments\/([^/]+)\/views\/([^/]+)\/focus-terminal$/)
    if (method === 'POST' && terminalMatch != null) {
      state.terminalCalls.push(decodeURIComponent(terminalMatch[2]!))
      await route.fulfill({ status: 204 })
      return
    }

    const closeMatch = path.match(/^\/api\/ide\/environments\/([^/]+)\/views\/([^/]+)\/close$/)
    if (method === 'POST' && closeMatch != null) {
      const environmentId = decodeURIComponent(closeMatch[1]!)
      const viewId = decodeURIComponent(closeMatch[2]!)
      const body = (request.postDataJSON() as Record<string, unknown> | null) ?? {}
      state.closeCalls.push({ environmentId, viewId, body })
      if (state.holdNextClose) {
        state.holdNextClose = false
        state.closeHeld = true
        await new Promise<void>((resolve) => { state.releaseNextClose = resolve })
        state.closeHeld = false
        state.releaseNextClose = null
      }
      const failure = state.failNextClose
      state.failNextClose = null
      if (failure != null) {
        await route.fulfill({
          status: 409,
          contentType: 'application/problem+json',
          json: problem(409, failure, 'native close was not confirmed'),
        })
        return
      }
      delete state.views[viewId]
      await route.fulfill({ status: 204 })
      return
    }

    await route.fulfill({
      status: 404,
      contentType: 'application/problem+json',
      json: problem(404, 'NOT_FOUND', `Unexpected harness request: ${method} ${path}`),
    })
  })

  await page.route('**/ide/**/__ide/bootstrap*', async (route) => {
    const parsed = new URL(route.request().url())
    const match = parsed.pathname.match(/^\/ide\/([^/]+)\/([^/]+)\/__ide\/bootstrap$/)
    if (match == null) {
      await route.fulfill({ status: 404 })
      return
    }
    const environmentId = decodeURIComponent(match[1]!)
    const viewId = decodeURIComponent(match[2]!)
    const count = (state.iframeRequests[viewId] ?? 0) + 1
    state.iframeRequests[viewId] = count
    await route.fulfill({
      status: 200,
      contentType: 'text/html; charset=utf-8',
      body: `<!doctype html>
<html lang="zh-CN">
  <head><meta charset="utf-8"><title>IDE ${environmentId}</title></head>
  <body>
    <div data-testid="ide-fixture-environment">${environmentId}</div>
    <div data-testid="ide-fixture-view">${viewId}</div>
    <div data-testid="ide-fixture-load">load-${count}</div>
    <textarea data-testid="ide-fixture-draft"></textarea>
  </body>
</html>`,
    })
  })
}

/** 模拟真实 Backend：本连接最新已 ACK 的 usage 必须包含精确 owner/view/claim。 */
function hasUsageIntent(
  state: TransportState,
  environmentId: string,
  viewId: string,
  request: Record<string, unknown>,
): boolean {
  if (request.eventNodeId !== EVENT_NODE || state.closedConnections.has(String(request.connectionId))) {
    return false
  }
  for (let index = state.usageFrames.length - 1; index >= 0; index -= 1) {
    const frame = state.usageFrames[index]!
    if (frame.connectionId !== request.connectionId) continue
    return frame.browserSessionId === request.browserSessionId && frame.views.some((value) => {
      if (typeof value !== 'object' || value == null) return false
      const view = value as Record<string, unknown>
      return view.environmentId === environmentId && view.viewId === viewId && view.claimId === request.claimId
    })
  }
  return false
}

function createState(): TransportState {
  return {
    views: {},
    revision: 1000,
    epoch: 0,
    openCalls: [],
    readCalls: [],
    directoryCalls: [],
    directoryListCalls: [],
    closeCalls: [],
    terminalCalls: [],
    usageFrames: [],
    closedConnections: new Set(),
    iframeRequests: {},
    eventConnectionIds: [],
    failNextOpen: null,
    holdNextOpen: false,
    heldOpen: null,
    holdNextDirectory: false,
    directoryHeld: false,
    releaseNextDirectory: null,
    failNextDirectory: null,
    failNextClose: null,
    holdNextClose: false,
    closeHeld: false,
    releaseNextClose: null,
  }
}

async function openHarness(page: Page, state: TransportState): Promise<void> {
  await installTransport(page, state)
  await page.goto(HARNESS_URL)
  await expect(page.getByTestId('harness-event-status')).toHaveAttribute('data-connected', 'true')
}

/** 只有一个活动 iframe：其它已打开 view 仍然挂载但隐藏。 */
function activeFrame(page: Page): Locator {
  return page.locator('[data-testid="ide-iframe"]:visible')
}

async function expectActiveView(page: Page, viewId: string): Promise<void> {
  await expect(activeFrame(page)).toHaveCount(1)
  await expect(activeFrame(page)).toHaveAttribute('src', new RegExp(`/${viewId}/`))
}

/** 已挂载的托管 iframe 数量；隐藏的 view 不卸载，因此数量按 view 累计。 */
async function mountedFrameCount(page: Page): Promise<number> {
  return page.locator('iframe[src*="/ide/"]').count()
}

function tabFor(page: Page, viewId: string): Locator {
  return page.locator(`[data-testid="ide-tab"][data-ide-view="${viewId}"]`)
}

/** 选中态在标签内的选择按钮上（标签容器只是外壳）。 */
function tabSelectButton(page: Page, viewId: string): Locator {
  return tabFor(page, viewId).getByRole('button').first()
}

async function expectSelectedTab(page: Page, viewId: string): Promise<void> {
  await expect(tabSelectButton(page, viewId)).toHaveAttribute('aria-current', 'page')
  for (const tab of await page.getByTestId('ide-tab').all()) {
    if (await tab.getAttribute('data-ide-view') === viewId) {
      continue
    }
    await expect(tab.getByRole('button').first()).not.toHaveAttribute('aria-current')
  }
}

async function tabViewIds(page: Page): Promise<string[]> {
  return page.getByTestId('ide-tab').evaluateAll((tabs) => (
    tabs.map((tab) => tab.getAttribute('data-ide-view') ?? '')
  ))
}

function envTrigger(page: Page): Locator {
  return page.getByTestId('ide-toolbar').getByRole('button', { name: '环境' })
}

async function selectEnvironment(page: Page, name: string): Promise<void> {
  await envTrigger(page).click()
  await page.getByRole('option', { name }).click()
}

/** 首次显式打开：进入无目录空窗口，返回该 view。 */
async function openA(page: Page, state: TransportState): Promise<string> {
  await page.getByTestId('pane-1-open-a').click()
  await expect.poll(() => state.openCalls.length).toBe(1)
  const viewId = String(state.openCalls[0]!.body.viewId)
  await expect(page.getByTestId('ide-overlay-panel')).toBeVisible()
  await expectActiveView(page, viewId)
  return viewId
}

/** 顶栏 `+` 直接新建独立零根 view。 */
async function addEmptyView(page: Page, state: TransportState): Promise<string> {
  const before = state.openCalls.length
  await page.getByTestId('ide-open-workspace-btn').click()
  await expect.poll(() => state.openCalls.length).toBe(before + 1)
  const viewId = String(state.openCalls[before]!.body.viewId)
  await expect(tabFor(page, viewId)).toBeVisible()
  return viewId
}

async function storedSession(page: Page): Promise<{
  browserSessionId: string
  presentation: {
    selectedEnvironmentId: string | null
    viewIdsByEnvironment: Record<string, string[]>
    selectedViewIdsByEnvironment: Record<string, string>
  }
  recoveryByView: Record<string, {
    environmentId: string
    viewId: string
    daemonInstanceId: string
    bindingEpoch?: string
    directory?: string | null
    pending?: Record<string, unknown>
  }>
}> {
  return page.evaluate((key) => JSON.parse(sessionStorage.getItem(key) ?? '{}'), SESSION_KEY)
}

async function saveFrameFor(page: Page, viewId: string): Promise<void> {
  await page.evaluate((id) => {
    const win = window as unknown as { __ideFrames?: Record<string, Element | null> }
    win.__ideFrames = win.__ideFrames ?? {}
    win.__ideFrames[id] = document.querySelector(`iframe[src*="/${id}/"]`)
  }, viewId)
}

async function sameFrameFor(page: Page, viewId: string): Promise<boolean> {
  return page.evaluate((id) => {
    const win = window as unknown as { __ideFrames?: Record<string, Element | null> }
    const saved = win.__ideFrames?.[id]
    return saved != null && saved === document.querySelector(`iframe[src*="/${id}/"]`)
  }, viewId)
}

async function frameSrc(page: Page, viewId: string): Promise<string | null> {
  const frame = page.locator(`iframe[src*="/${viewId}/"]`)
  if (await frame.count() === 0) {
    return null
  }
  return frame.first().getAttribute('src')
}

async function ideFrame(page: Page, viewId: string): Promise<Frame> {
  const frame = page.frames().find((candidate) => candidate.url().includes(`/${viewId}/`))
  expect(frame, `IDE frame for ${viewId}`).toBeTruthy()
  return frame!
}

async function setDraft(page: Page, viewId: string, value: string): Promise<void> {
  const frame = await ideFrame(page, viewId)
  await frame.evaluate((text) => {
    const draft = document.querySelector<HTMLTextAreaElement>('[data-testid="ide-fixture-draft"]')
    if (draft == null) {
      throw new Error('IDE frame draft fixture is missing')
    }
    draft.value = text
  }, value)
}

async function readDraft(page: Page, viewId: string): Promise<string> {
  const frame = await ideFrame(page, viewId)
  return frame.evaluate(() => (
    document.querySelector<HTMLTextAreaElement>('[data-testid="ide-fixture-draft"]')?.value ?? ''
  ))
}

/** 由该 view 自己的 iframe 发出 Native 动作消息，来源窗口与 origin 都与真实工作台一致。 */
async function postNative(
  page: Page,
  viewId: string,
  message: Record<string, unknown> | string,
): Promise<void> {
  const frame = await ideFrame(page, viewId)
  await frame.evaluate((payload) => {
    window.parent.postMessage(payload, window.location.origin)
  }, message)
}

function nativeAction(
  state: TransportState,
  viewId: string,
  action: 'open-folder' | 'new-window' | 'close-workspace',
  directory?: string,
): Record<string, unknown> {
  return {
    type: NATIVE_ACTION_TYPE,
    viewId,
    bindingEpoch: state.views[viewId]!.bindingEpoch,
    action,
    ...(directory == null ? {} : { directory }),
  }
}

async function nativeOpenFolder(
  page: Page,
  state: TransportState,
  viewId: string,
  directory: string,
): Promise<void> {
  await postNative(page, viewId, nativeAction(state, viewId, 'open-folder', directory))
}

/** 已移除的 Studio 选择器/复制/终端控件必须完全消失。 */
async function expectNoLegacyIdeControls(page: Page): Promise<void> {
  for (const testId of [
    'ide-terminal-btn',
    'ide-copy-directory-btn',
    'ide-open-directory-btn',
    'ide-directory-badge',
    'ide-directory-entry',
    'ide-directory-confirm',
  ]) {
    await expect(page.getByTestId(testId), `${testId} should be removed from the managed IDE`).toHaveCount(0)
  }
}

async function toolbarBox(page: Page): Promise<{ x: number; y: number; width: number; height: number }> {
  const box = await page.getByTestId('ide-toolbar').boundingBox()
  expect(box, 'IDE toolbar box').not.toBeNull()
  return box!
}

/**
 * 单行工具栏：环境、每个标签、`+` 与收起都落在同一行带内，正文紧随其后。
 * 任何换行或第二条工具栏都会让某个控件离开该带。
 */
async function expectSingleToolbarRow(page: Page): Promise<void> {
  const toolbar = await toolbarBox(page)
  const controls: Locator[] = [
    envTrigger(page),
    page.getByTestId('ide-open-workspace-btn'),
    page.getByTestId('ide-collapse-btn'),
    ...await page.getByTestId('ide-tab').all(),
  ]
  for (const control of controls) {
    const box = await control.boundingBox()
    expect(box, 'toolbar control box').not.toBeNull()
    expect(box!.y).toBeGreaterThanOrEqual(toolbar.y - 1)
    expect(box!.y + box!.height).toBeLessThanOrEqual(toolbar.y + toolbar.height + 1)
  }
  const body = await page.getByTestId('ide-panel-body').boundingBox()
  expect(body, 'IDE panel body box').not.toBeNull()
  expect(body!.y).toBeGreaterThanOrEqual(toolbar.y + toolbar.height - 1)
}

test.describe('Chat-scoped persistent IDE host', () => {
  test('creates a first empty window without a directory and persists the claim before POST', async ({ page }) => {
    const state = createState()
    await openHarness(page, state)
    const viewId = await openA(page, state)

    expect(state.openCalls).toHaveLength(1)
    const call = state.openCalls[0]!
    expect(call.environmentId).toBe(ENV_A)
    expect(call.body.mode).toBe('create')
    // 首次显式打开不猜 HOME：create 请求不带目录。
    expect(call.body.directory ?? null).toBeNull()
    expect(JSON.stringify(call.body)).not.toContain('/workspace/a')
    expect(call.pendingAtPost).toMatchObject({
      mode: 'create',
      viewId: call.body.viewId,
      claimId: call.body.claimId,
      expectedBindingEpoch: '0',
      expectedDaemonInstanceId: daemonId(ENV_A),
    })
    expect(call.pendingAtPost?.directory ?? null).toBeNull()
    expect(call.sessionBrowserIdAtPost).toBe(call.body.browserSessionId)
    expect(call.body).not.toHaveProperty('entryUrl')
    expect(call.body).not.toHaveProperty('token')
    expect((await storedSession(page)).recoveryByView[viewId]?.viewId).toBe(viewId)

    // 无目录的短名标签，且没有任何 Studio 目录/终端/复制控件。
    await expect(page.getByTestId('ide-tab')).toHaveCount(1)
    await expect(tabFor(page, viewId)).toContainText(EMPTY_WINDOW)
    await expectNoLegacyIdeControls(page)
    expect(state.directoryListCalls).toHaveLength(0)
    expect(state.directoryCalls).toHaveLength(0)

    // 环境 | 标签 × | + | 收起 单行。
    await expectSingleToolbarRow(page)
    await expect(tabFor(page, viewId).getByTestId('ide-close-btn')).toHaveCount(1)
    await expect(page.getByTestId('ide-open-workspace-btn')).toBeVisible()
    await expectSelectedTab(page, viewId)
    expect(await mountedFrameCount(page)).toBe(1)
  })

  test('maps native Open Folder to the current tab and renames it only after the confirmed receipt', async ({ page }) => {
    const state = createState()
    await openHarness(page, state)
    const viewId = await openA(page, state)
    await saveFrameFor(page, viewId)
    const entryUrlBefore = await frameSrc(page, viewId)
    const tab = tabFor(page, viewId)

    state.holdNextDirectory = true
    await nativeOpenFolder(page, state, viewId, '/workspace/a/child')
    await expect.poll(() => state.directoryHeld).toBe(true)
    // 同一个 view 的在途命令只跑一次：重复消息不得再发一次目录切换。
    await nativeOpenFolder(page, state, viewId, '/workspace/a/child')
    await page.waitForTimeout(150)
    expect(state.directoryCalls).toHaveLength(1)

    // 回执未确认前标签仍是空窗口，视图、tab 数量与 iframe 都不变。
    await expect(tab).toContainText(EMPTY_WINDOW)
    await expect(page.getByTestId('ide-tab')).toHaveCount(1)
    expect(await sameFrameFor(page, viewId)).toBe(true)
    expect(state.directoryCalls).toHaveLength(1)
    const call = state.directoryCalls[0]!
    expect(call).toMatchObject({ environmentId: ENV_A, viewId })
    expect(call.body).toMatchObject({
      browserSessionId: state.views[viewId]!.browserSessionId,
      bindingEpoch: state.views[viewId]!.bindingEpoch,
      expectedDaemonInstanceId: daemonId(ENV_A),
      directory: '/workspace/a/child',
    })

    state.releaseNextDirectory?.()
    await expect(tab).toContainText('child')
    await expect(tab).not.toContainText(EMPTY_WINDOW)

    // 已确认的目录切换不改身份：同一个 view、同一个 iframe、不新建标签。
    await expect(page.getByTestId('ide-tab')).toHaveCount(1)
    expect(state.openCalls).toHaveLength(1)
    expect(await sameFrameFor(page, viewId)).toBe(true)
    expect(await frameSrc(page, viewId)).toBe(entryUrlBefore)
    await expectActiveView(page, viewId)

    // 取消或失败的目录选择不改标签名。
    state.failNextDirectory = 'IDE_CONTROL_UNAVAILABLE'
    await nativeOpenFolder(page, state, viewId, '/workspace/a/cancelled')
    await expect.poll(() => state.directoryCalls.length).toBe(2)
    await expect(tab).toContainText('child')
    await expect(tab).not.toContainText('cancelled')
    expect(await mountedFrameCount(page)).toBe(1)
  })

  test('maps native New Window to an independent empty view without reloading or hijacking the sender', async ({ page, context }) => {
    const state = createState()
    await openHarness(page, state)
    const firstViewId = await openA(page, state)
    await saveFrameFor(page, firstViewId)
    const firstEntryUrl = await frameSrc(page, firstViewId)

    await postNative(page, firstViewId, nativeAction(state, firstViewId, 'new-window'))
    await expect.poll(() => state.openCalls.length).toBe(2)
    const secondViewId = String(state.openCalls[1]!.body.viewId)
    expect(secondViewId).not.toBe(firstViewId)
    expect(state.openCalls[1]!.body.mode).toBe('create')
    expect(state.openCalls[1]!.body.directory ?? null).toBeNull()

    await expect(page.getByTestId('ide-tab')).toHaveCount(2)
    await expectActiveView(page, secondViewId)
    await expect(tabFor(page, secondViewId)).toContainText(EMPTY_WINDOW)

    // 不弹浏览器页，也不把旧绑定导航到别处。
    expect(context.pages()).toHaveLength(1)
    expect(await sameFrameFor(page, firstViewId)).toBe(true)
    expect(await frameSrc(page, firstViewId)).toBe(firstEntryUrl)
    expect(await mountedFrameCount(page)).toBe(2)
    await expect.poll(() => state.iframeRequests[firstViewId]).toBe(1)
    expect(state.iframeRequests[secondViewId]).toBe(1)
  })

  test('dismisses a late close error only on its original view', async ({ page }) => {
    const state = createState()
    await openHarness(page, state)
    const first = await openA(page, state)
    const second = await addEmptyView(page, state)
    await tabSelectButton(page, first).click()

    // 在切换到同环境的另一个标签后，才返回原标签的关闭失败。
    state.holdNextClose = true
    state.failNextClose = 'IDE_CONTROL_UNAVAILABLE'
    const close = tabFor(page, first).getByTestId('ide-close-btn')
    await close.click()
    await expect.poll(() => state.closeHeld).toBe(true)
    await tabSelectButton(page, second).click()
    state.releaseNextClose?.()
    await expect(close).toBeEnabled()
    const banner = page.getByTestId('ide-close-error')
    const dialog = page.getByRole('alertdialog')
    await expect(banner).toHaveCount(0)
    await expect(dialog).toHaveCount(0)

    await tabSelectButton(page, first).click()
    await expect(banner).toContainText('IDE 控制通道不可用')
    await expect(dialog).toHaveCount(0)
    await banner.getByRole('button', { name: '忽略' }).click()
    await expect(banner).toHaveCount(0)
    await expect(page.getByTestId('ide-tab')).toHaveCount(2)
    expect(state.closeCalls).toHaveLength(1)
  })

  test('maps native Close Workspace to the normal close, keeps the toolbar and never reopens the last view', async ({ page }) => {
    const state = createState()
    await openHarness(page, state)
    const viewId = await openA(page, state)

    // 原生保存/放弃/取消还没结算时不得移除标签。
    state.failNextClose = 'IDE_CONTROL_UNAVAILABLE'
    await postNative(page, viewId, nativeAction(state, viewId, 'close-workspace'))
    await expect.poll(() => state.closeCalls.length).toBe(1)
    expect(state.closeCalls[0]).toMatchObject({ environmentId: ENV_A, viewId })
    expect(state.closeCalls[0]!.body).toMatchObject({
      browserSessionId: state.views[viewId]!.browserSessionId,
      bindingEpoch: state.views[viewId]!.bindingEpoch,
      expectedDaemonInstanceId: daemonId(ENV_A),
      discard: false,
    })
    const dialog = page.getByRole('alertdialog')
    await expect(dialog).toBeVisible()
    await expect(page.getByTestId('ide-tab')).toHaveCount(1)
    await dialog.getByRole('button', { name: '取消' }).click()
    await expect(dialog).toHaveCount(0)
    await expect(page.getByTestId('ide-tab')).toHaveCount(1)
    await expectActiveView(page, viewId)

    // 在途关闭只发一次：重复的 native 关闭消息不会叠加第二个关闭请求。
    state.holdNextClose = true
    await postNative(page, viewId, nativeAction(state, viewId, 'close-workspace'))
    await expect.poll(() => state.closeHeld).toBe(true)
    await postNative(page, viewId, nativeAction(state, viewId, 'close-workspace'))
    await page.waitForTimeout(150)
    expect(state.closeCalls).toHaveLength(2)
    state.releaseNextClose?.()
    await expect(page.getByTestId('ide-tab')).toHaveCount(0)
    expect(await mountedFrameCount(page)).toBe(0)
    expect(state.views[viewId]).toBeUndefined()

    // 关完只留顶栏：不显示说明页，也不自动重开。
    await expect(page.getByTestId('ide-overlay-panel')).toBeVisible()
    await expect(page.getByTestId('ide-toolbar')).toBeVisible()
    await expect(page.getByTestId('ide-open-workspace-btn')).toBeVisible()
    await expect(page.getByTestId('ide-collapse-btn')).toBeVisible()
    await page.waitForTimeout(400)
    expect(state.openCalls).toHaveLength(1)

    // 只有显式 `+` 才新建下一个空窗口。
    const nextViewId = await addEmptyView(page, state)
    expect(nextViewId).not.toBe(viewId)
    await expect(tabFor(page, nextViewId)).toContainText(EMPTY_WINDOW)
    await expectActiveView(page, nextViewId)
  })

  test('keeps two tabs on the same directory independent and closing an inactive one keeps the current view', async ({ page }) => {
    const state = createState()
    await openHarness(page, state)
    const firstViewId = await openA(page, state)
    await saveFrameFor(page, firstViewId)
    const secondViewId = await addEmptyView(page, state)
    await saveFrameFor(page, secondViewId)
    await expectActiveView(page, secondViewId)

    const shared = '/workspace/shared-project'
    await nativeOpenFolder(page, state, secondViewId, shared)
    await expect.poll(() => state.directoryCalls.length).toBe(1)
    await expect(tabFor(page, secondViewId)).toContainText('shared-project')

    await tabSelectButton(page, firstViewId).click()
    await expectActiveView(page, firstViewId)
    await expectSelectedTab(page, firstViewId)
    await nativeOpenFolder(page, state, firstViewId, shared)
    await expect.poll(() => state.directoryCalls.length).toBe(2)
    await expect(tabFor(page, firstViewId)).toContainText('shared-project')

    // 同目录不去重：仍是两个独立 view、两个已挂载 iframe。
    await expect(page.getByTestId('ide-tab')).toHaveCount(2)
    expect(state.openCalls).toHaveLength(2)
    expect(Object.keys(state.views).sort()).toEqual([firstViewId, secondViewId].sort())
    expect(await mountedFrameCount(page)).toBe(2)
    expect(await sameFrameFor(page, firstViewId)).toBe(true)
    expect(await sameFrameFor(page, secondViewId)).toBe(true)
    await expect(tabFor(page, firstViewId).getByTestId('ide-close-btn')).toHaveCount(1)
    await expect(tabFor(page, secondViewId).getByTestId('ide-close-btn')).toHaveCount(1)

    // 非活动标签的 `×` 只关闭自己，不切换当前视图。
    await tabFor(page, secondViewId).getByTestId('ide-close-btn').click()
    await expect.poll(() => state.closeCalls.length).toBe(1)
    expect(state.closeCalls[0]!.viewId).toBe(secondViewId)
    await expect(page.getByTestId('ide-tab')).toHaveCount(1)
    await expectActiveView(page, firstViewId)
    expect(await mountedFrameCount(page)).toBe(1)
    expect(state.views[firstViewId]).toBeDefined()
    expect(state.views[secondViewId]).toBeUndefined()
  })

  test('accepts native actions only from the selected ready view and rejects forged, stale or malformed messages', async ({ page }) => {
    const state = createState()
    await openHarness(page, state)
    const firstViewId = await openA(page, state)
    const secondViewId = await addEmptyView(page, state)
    await expectActiveView(page, secondViewId)
    const epoch = state.views[secondViewId]!.bindingEpoch

    const expectIgnored = async (label: string, post: () => Promise<void>): Promise<void> => {
      const before = {
        directories: state.directoryCalls.length,
        opens: state.openCalls.length,
        closes: state.closeCalls.length,
      }
      await post()
      await page.waitForTimeout(150)
      expect(state.directoryCalls.length, label).toBe(before.directories)
      expect(state.openCalls.length, label).toBe(before.opens)
      expect(state.closeCalls.length, label).toBe(before.closes)
    }

    // 隐藏标签的 iframe 不是当前工作台。
    await expectIgnored('hidden view', () => nativeOpenFolder(page, state, firstViewId, '/workspace/a/hidden'))
    // 来自 Studio 页面自身而不是 iframe 的消息。
    await expectIgnored('foreign source', () => page.evaluate((message) => {
      window.postMessage(message, window.location.origin)
    }, nativeAction(state, secondViewId, 'open-folder', '/workspace/a/foreign')))
    // 来源窗口正确但 origin 不是同源。
    await expectIgnored('wrong origin', () => page.evaluate(({ message, viewId }) => {
      const frame = document.querySelector<HTMLIFrameElement>(`iframe[src*="/${viewId}/"]`)
      window.dispatchEvent(new MessageEvent('message', {
        data: message,
        origin: 'https://evil.example',
        source: frame?.contentWindow ?? null,
      }))
    }, {
      message: nativeAction(state, secondViewId, 'open-folder', '/workspace/a/wrong-origin'),
      viewId: secondViewId,
    }))
    // viewId 与来源 iframe 不一致。
    await expectIgnored('view mismatch', () => postNative(
      page,
      secondViewId,
      { ...nativeAction(state, firstViewId, 'open-folder', '/workspace/a/other-view') },
    ))
    // 过期 bindingEpoch。
    await expectIgnored('stale epoch', () => postNative(page, secondViewId, {
      type: NATIVE_ACTION_TYPE,
      viewId: secondViewId,
      bindingEpoch: '1',
      action: 'open-folder',
      directory: '/workspace/a/stale-epoch',
    }))
    // 缺字段、未知动作、控制字符路径、缺目录、额外命令字段与畸形负载。
    await expectIgnored('missing epoch', () => postNative(page, secondViewId, {
      type: NATIVE_ACTION_TYPE,
      viewId: secondViewId,
      action: 'open-folder',
      directory: '/workspace/a/missing-epoch',
    }))
    await expectIgnored('unknown action', () => postNative(page, secondViewId, {
      type: NATIVE_ACTION_TYPE,
      viewId: secondViewId,
      bindingEpoch: epoch,
      action: 'focus-terminal',
    }))
    await expectIgnored('control character path', () => postNative(page, secondViewId, {
      type: NATIVE_ACTION_TYPE,
      viewId: secondViewId,
      bindingEpoch: epoch,
      action: 'open-folder',
      directory: '/workspace/a/\u0001crafted',
    }))
    await expectIgnored('over long path', () => postNative(page, secondViewId, {
      type: NATIVE_ACTION_TYPE,
      viewId: secondViewId,
      bindingEpoch: epoch,
      action: 'open-folder',
      directory: `/workspace/a/${'x'.repeat(4200)}`,
    }))
    await expectIgnored('missing directory', () => postNative(page, secondViewId, {
      type: NATIVE_ACTION_TYPE,
      viewId: secondViewId,
      bindingEpoch: epoch,
      action: 'open-folder',
    }))
    await expectIgnored('extra command', () => postNative(page, secondViewId, {
      type: NATIVE_ACTION_TYPE,
      viewId: secondViewId,
      bindingEpoch: epoch,
      action: 'open-folder',
      directory: '/workspace/a/extra-command',
      command: 'workbench.action.terminal.new',
      url: 'https://evil.example/',
    }))
    await expectIgnored('non object message', () => postNative(page, secondViewId, NATIVE_ACTION_TYPE))
    // 收起后没有可见工作台，消息同样不被接受。
    await page.getByTestId('toggle-a').click()
    await expect(page.getByTestId('ide-overlay-panel')).toBeHidden()
    await expectIgnored('collapsed panel', () => nativeOpenFolder(page, state, secondViewId, '/workspace/a/collapsed'))
    await page.getByTestId('toggle-a').click()
    await expect(page.getByTestId('ide-overlay-panel')).toBeVisible()
    await expectActiveView(page, secondViewId)
    await expectSelectedTab(page, secondViewId)

    // 以上全部不生效：标签仍是两个空窗口。
    await expect(page.getByTestId('ide-tab')).toHaveCount(2)
    await expect(page.getByTestId('ide-tab').filter({ hasText: EMPTY_WINDOW })).toHaveCount(2)
    expect(await mountedFrameCount(page)).toBe(2)

    // 正向对照：当前可见工作台的有效动作照常生效。
    await nativeOpenFolder(page, state, secondViewId, '/workspace/a/accepted')
    await expect.poll(() => state.directoryCalls.length).toBe(1)
    await expect(tabFor(page, secondViewId)).toContainText('accepted')
    await expect(tabFor(page, firstViewId)).toContainText(EMPTY_WINDOW)
  })

  test('a late confirmed directory receipt cannot rename another view', async ({ page }) => {
    const state = createState()
    await openHarness(page, state)
    const firstViewId = await openA(page, state)
    const secondViewId = await addEmptyView(page, state)

    await tabSelectButton(page, firstViewId).click()
    await expectActiveView(page, firstViewId)
    state.holdNextDirectory = true
    await nativeOpenFolder(page, state, firstViewId, '/workspace/a/late')
    await expect.poll(() => state.directoryHeld).toBe(true)

    await tabSelectButton(page, secondViewId).click()
    await expectActiveView(page, secondViewId)
    await expectSelectedTab(page, secondViewId)
    state.releaseNextDirectory?.()
    await expect.poll(() => state.directoryCalls.length).toBe(1)
    await expect(tabFor(page, firstViewId)).toContainText('late')

    await page.waitForTimeout(150)
    await expect(tabFor(page, secondViewId)).toContainText(EMPTY_WINDOW)
    await expectActiveView(page, secondViewId)
    const saved = await storedSession(page)
    expect(saved.recoveryByView[firstViewId]?.directory).toBe('/workspace/a/late')
    expect(saved.recoveryByView[secondViewId]?.directory ?? null).toBeNull()
    expect(saved.presentation.selectedViewIdsByEnvironment[ENV_A]).toBe(secondViewId)
  })

  test('keeps each Environment tabs and selection across switching and refresh', async ({ page }) => {
    const state = createState()
    await openHarness(page, state)
    const aFirstViewId = await openA(page, state)
    const aSecondViewId = await addEmptyView(page, state)

    // 再次从 Chat 入口打开只聚焦已有标签，不新建。
    await page.getByTestId('pane-1-open-a').click()
    await expectActiveView(page, aSecondViewId)
    expect(state.openCalls).toHaveLength(2)

    await page.getByTestId('pane-2-open-b').click()
    await expect.poll(() => state.openCalls.length).toBe(3)
    const bFirstViewId = String(state.openCalls[2]!.body.viewId)
    expect(state.openCalls[2]!.environmentId).toBe(ENV_B)
    expect(state.openCalls[2]!.body.directory ?? null).toBeNull()
    await expectActiveView(page, bFirstViewId)
    await expect(page.getByTestId('ide-tab')).toHaveCount(1)

    await selectEnvironment(page, 'local-node')
    await expect(envTrigger(page)).toContainText('local-node')
    await expect(page.getByTestId('ide-tab')).toHaveCount(2)
    await expectActiveView(page, aSecondViewId)
    // 环境切换不创建新 view，也不卸载其它环境的 iframe。
    expect(state.openCalls).toHaveLength(3)
    expect(await mountedFrameCount(page)).toBe(3)

    const aOrder = [aFirstViewId, aSecondViewId]
    expect(await tabViewIds(page)).toEqual(aOrder)

    await page.reload()
    await expect(page.getByTestId('harness-event-status')).toHaveAttribute('data-connected', 'true')
    await expect.poll(() => mountedFrameCount(page)).toBe(3)
    await expect(page.getByTestId('ide-tab')).toHaveCount(2)
    expect(await tabViewIds(page)).toEqual(aOrder)
    await expectActiveView(page, aSecondViewId)

    const restored = await storedSession(page)
    expect(restored.presentation.selectedViewIdsByEnvironment).toMatchObject({
      [ENV_A]: aSecondViewId,
      [ENV_B]: bFirstViewId,
    })
    expect(restored.presentation.viewIdsByEnvironment[ENV_A]).toEqual(aOrder)
    expect(restored.presentation.viewIdsByEnvironment[ENV_B]).toEqual([bFirstViewId])

    await selectEnvironment(page, 'remote-node')
    await expect(page.getByTestId('ide-tab')).toHaveCount(1)
    await expectActiveView(page, bFirstViewId)
    await selectEnvironment(page, 'local-node')
    await expect(page.getByTestId('ide-tab')).toHaveCount(2)
    await expectActiveView(page, aSecondViewId)
  })

  test('refresh resumes the same view immediately from a pure read without replaying create', async ({ page }) => {
    const state = createState()
    await openHarness(page, state)
    const viewId = await openA(page, state)
    const epoch = String((await storedSession(page)).recoveryByView[viewId]?.bindingEpoch)
    const beforeReads = state.readCalls.length

    await page.reload()
    await expect(page.getByTestId('harness-event-status')).toHaveAttribute('data-connected', 'true')
    await expect.poll(() => mountedFrameCount(page)).toBe(1)
    await expectActiveView(page, viewId)
    await expect.poll(() => state.openCalls.length).toBe(2)

    expect(state.readCalls.length).toBeGreaterThan(beforeReads)
    expect(state.readCalls.at(-1)).toMatchObject({
      environmentId: ENV_A,
      viewId,
    })
    expect(state.openCalls[1]!.body).toMatchObject({
      mode: 'resume',
      viewId,
      expectedBindingEpoch: epoch,
      expectedDaemonInstanceId: daemonId(ENV_A),
    })
    expect(state.openCalls[1]!.body.claimId).not.toBe(state.openCalls[0]!.body.claimId)
    expect((await storedSession(page)).recoveryByView[viewId]?.viewId).toBe(viewId)
    await expect(tabFor(page, viewId)).toContainText(EMPTY_WINDOW)
  })

  test('a new websocket connection resumes immediately and keeps the same iframe', async ({ page }) => {
    const state = createState()
    await openHarness(page, state)
    const viewId = await openA(page, state)
    await saveFrameFor(page, viewId)
    const firstConnection = state.eventConnectionIds[0]

    await page.getByTestId('disconnect-events').click()
    await expect(page.getByTestId('harness-event-status')).toHaveAttribute('data-connected', 'false')
    await expect(page.getByTestId('harness-event-status')).toHaveAttribute('data-connected', 'true')
    await expect.poll(() => state.openCalls.length).toBe(2)

    expect(state.openCalls[1]!.body).toMatchObject({
      mode: 'resume',
      viewId,
      connectionId: state.eventConnectionIds[1],
    })
    expect(state.openCalls[1]!.body.connectionId).not.toBe(firstConnection)
    expect(await sameFrameFor(page, viewId)).toBe(true)
    await expect.poll(() => state.iframeRequests[viewId]).toBe(2)
    await expect(page.getByTestId('ide-retry-claim-btn')).toHaveCount(0)
  })

  test('a late response from a replaced connection cannot overwrite the current binding', async ({ page }) => {
    const state = createState()
    await openHarness(page, state)
    const viewId = await openA(page, state)
    await saveFrameFor(page, viewId)

    state.holdNextOpen = true
    await page.getByTestId('disconnect-events').click()
    await expect(page.getByTestId('harness-event-status')).toHaveAttribute('data-connected', 'false')
    await expect(page.getByTestId('harness-event-status')).toHaveAttribute('data-connected', 'true')
    await expect.poll(() => state.openCalls.length).toBe(2)
    await expect.poll(() => state.heldOpen != null).toBe(true)
    const held = state.heldOpen!
    expect(held.body.connectionId).toBe(state.eventConnectionIds[1])

    await page.getByTestId('disconnect-events').click()
    await expect(page.getByTestId('harness-event-status')).toHaveAttribute('data-connected', 'false')
    await expect(page.getByTestId('harness-event-status')).toHaveAttribute('data-connected', 'true')
    await expect.poll(() => state.openCalls.length).toBe(3)
    await expect.poll(() => mountedFrameCount(page)).toBe(1)

    const currentEpoch = state.views[viewId]!.bindingEpoch
    await held.route.fulfill({
      json: {
        ...viewWire(state.views[viewId]!),
        connectionId: held.body.connectionId,
        eventNodeId: held.body.eventNodeId,
        browserSessionId: held.body.browserSessionId,
        claimId: held.body.claimId,
        bindingEpoch: held.epoch,
        entryUrl: entryUrl(ENV_A, viewId, held.epoch),
      },
    })
    await page.waitForTimeout(150)

    expect(state.openCalls).toHaveLength(3)
    expect(await sameFrameFor(page, viewId)).toBe(true)
    expect(state.iframeRequests[viewId]).toBe(2)
    expect((await storedSession(page)).recoveryByView[viewId]?.bindingEpoch).toBe(currentEpoch)
    await expect.poll(() => mountedFrameCount(page)).toBe(1)
  })

  test('a STALE_BINDING response is reconciled by one read and a fresh CAS, not a retry timer', async ({ page }) => {
    const state = createState()
    await openHarness(page, state)
    const viewId = await openA(page, state)
    const initialEpoch = String((await storedSession(page)).recoveryByView[viewId]?.bindingEpoch)
    await saveFrameFor(page, viewId)
    const readsBefore = state.readCalls.length

    state.failNextOpen = 'STALE_BINDING'
    await page.getByTestId('disconnect-events').click()
    await expect(page.getByTestId('harness-event-status')).toHaveAttribute('data-connected', 'false')
    await expect(page.getByTestId('harness-event-status')).toHaveAttribute('data-connected', 'true')
    await expect.poll(() => state.openCalls.length).toBe(3)

    expect(state.openCalls[1]!.body).toMatchObject({
      mode: 'resume',
      viewId,
      expectedBindingEpoch: initialEpoch,
    })
    expect(state.openCalls[2]!.body.mode).toBe('resume')
    expect(state.openCalls[2]!.body.viewId).toBe(viewId)
    expect(state.openCalls[2]!.body.expectedBindingEpoch).not.toBe(initialEpoch)
    expect(BigInt(state.openCalls[2]!.body.expectedBindingEpoch as string))
      .toBeLessThan(BigInt(state.views[viewId]!.bindingEpoch))
    expect(state.readCalls.length).toBeGreaterThan(readsBefore + 1)
    expect(await sameFrameFor(page, viewId)).toBe(true)
    await expect.poll(() => state.iframeRequests[viewId]).toBe(2)
    await expect.poll(() => mountedFrameCount(page)).toBe(1)
  })

  test('a duplicated tab gets a distinct browser session and cannot steal the original view', async ({ page, context }) => {
    const state = createState()
    await openHarness(page, state)
    const originalView = await openA(page, state)
    const copiedSession = await page.evaluate((key) => sessionStorage.getItem(key), SESSION_KEY)
    expect(copiedSession).not.toBeNull()

    const duplicate = await context.newPage()
    await installTransport(duplicate, state)
    await duplicate.addInitScript(({ key, value }) => {
      if (window.top === window) {
        sessionStorage.setItem(key, value)
      }
    }, { key: SESSION_KEY, value: copiedSession! })
    await duplicate.goto(HARNESS_URL)
    await expect(duplicate.getByTestId('harness-event-status')).toHaveAttribute('data-connected', 'true')
    expect(await mountedFrameCount(duplicate)).toBe(0)
    await duplicate.waitForTimeout(250)

    const cloneSession = await storedSession(duplicate)
    expect(cloneSession.browserSessionId).not.toBe((await storedSession(page)).browserSessionId)
    expect(state.openCalls).toHaveLength(1)

    await duplicate.getByTestId('pane-1-open-a').click()
    await expect.poll(() => mountedFrameCount(duplicate)).toBe(1)
    await expect.poll(() => state.openCalls.length).toBe(2)
    const replacement = state.openCalls[1]!
    expect(replacement.body.mode).toBe('create')
    expect(replacement.body.viewId).not.toBe(originalView)
    expect((await storedSession(page)).recoveryByView[originalView]?.viewId).toBe(originalView)
    expect((await storedSession(duplicate)).recoveryByView[String(replacement.body.viewId)]?.viewId)
      .toBe(replacement.body.viewId)
  })

  test('BFCache restore reacquires the session lock and reconciles without replacing the iframe', async ({ page }) => {
    const state = createState()
    await openHarness(page, state)
    const viewId = await openA(page, state)
    await saveFrameFor(page, viewId)
    const reads = state.readCalls.length

    await page.evaluate(() => {
      window.dispatchEvent(new PageTransitionEvent('pagehide', { persisted: true }))
    })
    await page.evaluate(() => {
      window.dispatchEvent(new PageTransitionEvent('pageshow', { persisted: true }))
    })
    await expect.poll(() => state.readCalls.length).toBeGreaterThan(reads)
    await expect(page.getByTestId('ide-reconciling')).toHaveCount(0)

    expect(state.openCalls).toHaveLength(1)
    expect(await sameFrameFor(page, viewId)).toBe(true)
    expect(state.iframeRequests[viewId]).toBe(1)
    await expect.poll(() => mountedFrameCount(page)).toBe(1)
  })

  test('leaving Chat removes layout and returns with the same iframe and unsaved text', async ({ page }) => {
    const state = createState()
    await page.setViewportSize({ width: 1440, height: 900 })
    await openHarness(page, state)
    const viewId = await openA(page, state)
    await saveFrameFor(page, viewId)
    await setDraft(page, viewId, 'unsaved IDE text')

    await page.getByTestId('other-link').click()
    const other = page.getByTestId('other-page')
    await expect(other).toBeVisible()
    const host = page.getByTestId('chat-ide-workspace')
    await expect(host).toBeHidden()
    await expect(host).toHaveAttribute('hidden', '')
    expect(await host.evaluate((element) => (element as HTMLElement).inert)).toBe(true)
    expect(await host.evaluate((element) => element.contains(document.activeElement))).toBe(false)
    await expect(page.getByTestId('ide-overlay-panel')).toBeHidden()
    const otherBox = await other.boundingBox()
    expect(otherBox).not.toBeNull()
    expect(Math.abs((otherBox!.x + otherBox!.width) - 1440)).toBeLessThanOrEqual(2)
    expect(state.openCalls).toHaveLength(1)
    expect(state.iframeRequests[viewId]).toBe(1)
    expect(await mountedFrameCount(page)).toBe(1)

    await page.getByTestId('back-link').click()
    await expect(page.getByTestId('chat-page')).toBeVisible()
    await expect(page.getByTestId('ide-overlay-panel')).toBeVisible()
    await expectActiveView(page, viewId)
    expect(await sameFrameFor(page, viewId)).toBe(true)
    expect(state.openCalls).toHaveLength(1)
    expect(await readDraft(page, viewId)).toBe('unsaved IDE text')
  })

  test('collapsing hides the panel without closing or unmounting the view', async ({ page }) => {
    const state = createState()
    await openHarness(page, state)
    const viewId = await openA(page, state)
    await saveFrameFor(page, viewId)
    await setDraft(page, viewId, 'unsaved before collapse')

    await page.getByTestId('ide-collapse-btn').click()
    await expect(page.getByTestId('ide-overlay-panel')).toBeHidden()
    expect(await mountedFrameCount(page)).toBe(1)
    expect(state.closeCalls).toHaveLength(0)
    expect(state.openCalls).toHaveLength(1)

    await page.getByTestId('toggle-a').click()
    await expect(page.getByTestId('ide-overlay-panel')).toBeVisible()
    await expectActiveView(page, viewId)
    expect(await sameFrameFor(page, viewId)).toBe(true)
    expect(await readDraft(page, viewId)).toBe('unsaved before collapse')
    expect(state.openCalls).toHaveLength(1)
    expect(state.iframeRequests[viewId]).toBe(1)
  })

  test('keeps one fixed toolbar row with scrollable tabs at mobile width', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 })
    const state = createState()
    await openHarness(page, state)
    const viewIds = [await openA(page, state)]
    for (let index = 1; index < 4; index += 1) {
      viewIds.push(await addEmptyView(page, state))
    }
    await expect(page.getByTestId('ide-tab')).toHaveCount(4)

    // 长目录名让标签必然溢出，验证环境与收起仍固定在单行内。
    for (const [index, viewId] of viewIds.entries()) {
      const name = `mobile-workspace-directory-name-${index}`
      await tabSelectButton(page, viewId).click()
      await expectActiveView(page, viewId)
      await nativeOpenFolder(page, state, viewId, `/workspace/${name}`)
      await expect(tabFor(page, viewId)).toContainText(name)
    }
    await expect.poll(() => state.directoryCalls.length).toBe(4)

    await expect(page.getByTestId('ide-toolbar')).toHaveCount(1)
    await expectSingleToolbarRow(page)
    const toolbar = await toolbarBox(page)
    expect(toolbar.width).toBeGreaterThanOrEqual(360)
    expect(toolbar.height).toBeLessThanOrEqual(64)

    // 环境与收起固定在单行内且完全可见。
    const envBox = await envTrigger(page).boundingBox()
    const collapseBox = await page.getByTestId('ide-collapse-btn').boundingBox()
    expect(envBox).not.toBeNull()
    expect(collapseBox).not.toBeNull()
    expect(envBox!.x).toBeGreaterThanOrEqual(0)
    expect(envBox!.x + envBox!.width).toBeLessThanOrEqual(390 + 1)
    expect(collapseBox!.x).toBeGreaterThanOrEqual(0)
    expect(collapseBox!.x + collapseBox!.width).toBeLessThanOrEqual(390 + 1)

    // 标签始终单行，溢出由标签区域自己横向滚动。
    const rows = await page.getByTestId('ide-tab').evaluateAll((tabs) => (
      new Set(tabs.map((tab) => Math.round(tab.getBoundingClientRect().top))).size
    ))
    expect(rows).toBe(1)
    const overflow = await page.getByTestId('ide-tab').first().evaluate((tab) => {
      let node = tab.parentElement
      while (node != null) {
        if (node.scrollWidth > node.clientWidth + 1) {
          return { scrollWidth: node.scrollWidth, clientWidth: node.clientWidth }
        }
        node = node.parentElement
      }
      return null
    })
    expect(overflow).not.toBeNull()
    expect(overflow!.scrollWidth).toBeGreaterThan(overflow!.clientWidth)

    // `+` 紧跟最后一个标签，和标签一起滚动。
    const plusBox = await page.getByTestId('ide-open-workspace-btn').boundingBox()
    const lastTabBox = await page.getByTestId('ide-tab').last().boundingBox()
    expect(plusBox).not.toBeNull()
    expect(lastTabBox).not.toBeNull()
    expect(plusBox!.x).toBeGreaterThanOrEqual(lastTabBox!.x + lastTabBox!.width - 1)
    expect(plusBox!.x - (lastTabBox!.x + lastTabBox!.width)).toBeLessThanOrEqual(32)

    // 正文从工具栏下方开始，工具栏不占两行。
    const bodyBox = await page.getByTestId('ide-panel-body').boundingBox()
    expect(bodyBox).not.toBeNull()
    expect(bodyBox!.y).toBeGreaterThanOrEqual(toolbar.y + toolbar.height - 1)
    await expectActiveView(page, viewIds.at(-1)!)
  })

  test('the Chat environment entry still focuses the terminal after the toolbar terminal control is gone', async ({ page }) => {
    const state = createState()
    await openHarness(page, state)
    await page.getByTestId('pane-1-open-a-shell').click()
    await expect.poll(() => state.openCalls.length).toBe(1)
    const viewId = String(state.openCalls[0]!.body.viewId)
    await expectActiveView(page, viewId)
    await expect.poll(() => state.terminalCalls.length).toBe(1)
    expect(state.terminalCalls[0]).toBe(viewId)

    await expectNoLegacyIdeControls(page)
    await expectSingleToolbarRow(page)
    await expect(page.getByTestId('ide-tab')).toHaveCount(1)
    await expect(tabFor(page, viewId)).toContainText(EMPTY_WINDOW)
    expect(state.openCalls[0]!.body.directory ?? null).toBeNull()
  })
})
