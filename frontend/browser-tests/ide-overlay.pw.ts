import { expect, test, type Page } from './fixture'
import type { Route } from '@playwright/test'
import type { EnvironmentDTO } from '@/shared/api/generated/contracts'
import { testDaemonConfig } from '@/test-support/environment-test-fixtures'

const HARNESS_URL = '/browser-tests/ide-overlay-harness.html'
const SESSION_KEY = 'kk-studio.tab-state:ide'
const ENV_A = 'a1000000-0000-4000-8000-000000000001'
const ENV_B = 'a1000000-0000-4000-8000-000000000002'
const DIRECTORY_A = '/workspace/a'
const DIRECTORY_B = '/workspace/b'
const EVENT_NODE = 'e1000000-0000-4000-8000-000000000001'

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
    homeDirectory: DIRECTORY_A,
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
    homeDirectory: DIRECTORY_B,
  },
]

interface ViewRecord {
  viewId: string
  environmentId: string
  browserSessionId: string
  eventNodeId: string
  connectionId: string
  claimId: string
  daemonInstanceId: string
  bindingEpoch: string
  directory: string
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

interface TransportState {
  views: Record<string, ViewRecord>
  revision: number
  epoch: number
  openCalls: OpenCall[]
  readCalls: Array<{ environmentId: string; viewId: string; query: Record<string, string> }>
  usageFrames: Array<{ browserSessionId: string; revision: string; views: unknown[] }>
  iframeRequests: Record<string, number>
  eventConnectionIds: string[]
  failNextOpen: 'STALE_BINDING' | 'DAEMON_NOT_READY' | 'VIEW_OWNED_BY_OTHER_SESSION' | null
  holdNextOpen: boolean
  heldOpen: { route: Route; body: Record<string, unknown> } | null
}

function connectionId(index: number): string {
  return `f1000000-0000-4000-8000-${String(index).padStart(12, '0')}`
}

function daemonId(environmentId: string): string {
  return environmentId === ENV_A
    ? 'd1000000-0000-4000-8000-000000000001'
    : 'd2000000-0000-4000-8000-000000000002'
}

function directory(environmentId: string): string {
  return environmentId === ENV_A ? DIRECTORY_A : DIRECTORY_B
}

function entryUrl(environmentId: string, viewId: string): string {
  return `/ide/${environmentId}/${viewId}/__ide/bootstrap?token=${viewId}-token`
}

function viewResponse(view: ViewRecord): Record<string, unknown> {
  return {
    ...view,
    entryUrl: view.entryUrl,
  }
}

function snapshotView(view: ViewRecord): Record<string, unknown> {
  return {
    ...view,
    entryUrl: null,
  }
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
      recoveryByEnvironment?: Record<string, { pending?: Record<string, unknown> }>
    }
    const entries = Object.values(snapshot.recoveryByEnvironment ?? {})
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
    let connected = false
    socket.onMessage((raw) => {
      const message = JSON.parse(String(raw)) as Record<string, unknown>
      if (!connected) {
        connected = true
        socket.send(JSON.stringify({
          type: 'connected',
          eventNodeId: EVENT_NODE,
          connectionId: currentConnection,
        }))
      }
      if (message.type === 'subscribe') {
        socket.send(JSON.stringify({
          type: 'subscribed',
          resource: message.resource,
          cursor: '0',
        }))
      } else if (message.type === 'ide-use') {
        state.usageFrames.push(message as unknown as {
          browserSessionId: string
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

    const readMatch = path.match(/^\/api\/ide\/environments\/([^/]+)\/views\/([^/]+)$/)
    if (method === 'GET' && readMatch != null) {
      const environmentId = decodeURIComponent(readMatch[1]!)
      const viewId = decodeURIComponent(readMatch[2]!)
      state.readCalls.push({
        environmentId,
        viewId,
        query: Object.fromEntries(parsed.searchParams.entries()),
      })
      state.revision += 1
      const view = state.views[viewId]
      await route.fulfill({
        json: {
          daemonInstanceId: daemonId(environmentId),
          revision: String(state.revision),
          view: view == null ? null : snapshotView(view),
        },
      })
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
      if (state.holdNextOpen) {
        state.holdNextOpen = false
        state.heldOpen = { route, body }
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
      if (failure === 'DAEMON_NOT_READY') {
        await route.fulfill({
          status: 409,
          contentType: 'application/problem+json',
          json: problem(409, 'DAEMON_NOT_READY', 'daemon not ready'),
        })
        return
      }
      if (failure === 'VIEW_OWNED_BY_OTHER_SESSION') {
        await route.fulfill({
          status: 409,
          contentType: 'application/problem+json',
          json: problem(409, 'VIEW_OWNED_BY_OTHER_SESSION', 'view owned by another browser session'),
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
      const view: ViewRecord = existing ?? {
        viewId,
        environmentId,
        browserSessionId: String(body.browserSessionId),
        eventNodeId: String(body.eventNodeId),
        connectionId: String(body.connectionId),
        claimId: String(body.claimId),
        daemonInstanceId: daemonId(environmentId),
        bindingEpoch: '',
        directory: directory(environmentId),
        workspacePath: directory(environmentId),
        state: 'OPEN',
        entryUrl: entryUrl(environmentId, viewId),
      }
      if (mode === 'create' && existing == null) {
        state.epoch += 1
        view.bindingEpoch = String(state.epoch + 10)
        if (typeof body.directory === 'string') {
          view.directory = body.directory
          view.workspacePath = body.directory
        }
      } else {
        view.eventNodeId = String(body.eventNodeId)
        view.connectionId = String(body.connectionId)
        view.claimId = String(body.claimId)
        view.browserSessionId = String(body.browserSessionId)
      }
      state.views[viewId] = view
      await route.fulfill({ json: viewResponse(view) })
      return
    }

    const terminalMatch = path.match(/^\/api\/ide\/environments\/([^/]+)\/views\/([^/]+)\/focus-terminal$/)
    if (method === 'POST' && terminalMatch != null) {
      await route.fulfill({ status: 204 })
      return
    }

    const closeMatch = path.match(/^\/api\/ide\/environments\/([^/]+)\/views\/([^/]+)\/close$/)
    if (method === 'POST' && closeMatch != null) {
      const body = (request.postDataJSON() as Record<string, unknown> | null) ?? {}
      if (body.discard === true) {
        const viewId = decodeURIComponent(closeMatch[2]!)
        delete state.views[viewId]
      }
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

function createState(): TransportState {
  return {
    views: {},
    revision: 1000,
    epoch: 0,
    openCalls: [],
    readCalls: [],
    usageFrames: [],
    iframeRequests: {},
    eventConnectionIds: [],
    failNextOpen: null,
    holdNextOpen: false,
    heldOpen: null,
  }
}

async function openHarness(page: Page, state: TransportState): Promise<void> {
  await installTransport(page, state)
  await page.goto(HARNESS_URL)
  await expect(page.getByTestId('harness-event-status')).toHaveAttribute('data-connected', 'true')
}

async function openA(page: Page): Promise<void> {
  await page.getByTestId('pane-1-open-a').click()
  await expect(page.getByTestId('ide-iframe')).toHaveCount(1)
  await expect(page.getByTestId('ide-overlay-panel')).toBeVisible()
}

async function storedSession(page: Page): Promise<{
  browserSessionId: string
  recoveryByEnvironment: Record<string, {
    viewId: string
    daemonInstanceId: string
    bindingEpoch?: string
    pending?: Record<string, unknown>
  }>
}> {
  return page.evaluate((key) => JSON.parse(sessionStorage.getItem(key) ?? '{}'), SESSION_KEY)
}

async function saveFrame(page: Page): Promise<void> {
  await page.evaluate(() => {
    const win = window as unknown as { __ideFrame?: Element | null }
    win.__ideFrame = document.querySelector('[data-testid="ide-iframe"]')
  })
}

async function sameFrame(page: Page): Promise<boolean> {
  return page.evaluate(() => {
    const win = window as unknown as { __ideFrame?: Element | null }
    return win.__ideFrame != null
      && win.__ideFrame === document.querySelector('[data-testid="ide-iframe"]')
  })
}

test.describe('Chat-scoped persistent IDE host', () => {
  test('persists the create claim before POST and opens one stable view', async ({ page }) => {
    const state = createState()
    await openHarness(page, state)
    await openA(page)

    expect(state.openCalls).toHaveLength(1)
    const call = state.openCalls[0]!
    expect(call.body.mode).toBe('create')
    expect(call.pendingAtPost).toMatchObject({
      mode: 'create',
      viewId: call.body.viewId,
      claimId: call.body.claimId,
      expectedBindingEpoch: '0',
      expectedDaemonInstanceId: daemonId(ENV_A),
    })
    expect(call.sessionBrowserIdAtPost).toBe(call.body.browserSessionId)
    expect(call.body).not.toHaveProperty('entryUrl')
    expect(call.body).not.toHaveProperty('token')
    expect((await storedSession(page)).recoveryByEnvironment[ENV_A]?.viewId)
      .toBe(call.body.viewId)
  })

  test('refresh resumes the same view immediately from a pure read without replaying create', async ({ page }) => {
    const state = createState()
    await openHarness(page, state)
    await openA(page)
    const viewId = String(state.openCalls[0]!.body.viewId)
    const epoch = String(state.openCalls[0]!.body.expectedBindingEpoch)
    const beforeReads = state.readCalls.length

    await page.reload()
    await expect(page.getByTestId('harness-event-status')).toHaveAttribute('data-connected', 'true')
    await expect(page.getByTestId('ide-iframe')).toHaveCount(1)
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
    expect((await storedSession(page)).recoveryByEnvironment[ENV_A]?.viewId).toBe(viewId)
  })

  test('a new websocket connection resumes immediately and keeps the same iframe', async ({ page }) => {
    const state = createState()
    await openHarness(page, state)
    await openA(page)
    const viewId = String(state.openCalls[0]!.body.viewId)
    await saveFrame(page)
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
    expect(await sameFrame(page)).toBe(true)
    expect(state.iframeRequests[viewId]).toBe(1)
    await expect(page.getByTestId('ide-retry-claim-btn')).toHaveCount(0)
  })

  test('a late response from a replaced connection cannot overwrite the current binding', async ({ page }) => {
    const state = createState()
    await openHarness(page, state)
    await openA(page)
    const viewId = String(state.openCalls[0]!.body.viewId)
    const epoch = String((await storedSession(page)).recoveryByEnvironment[ENV_A]?.bindingEpoch)
    await saveFrame(page)

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
    await expect(page.getByTestId('ide-iframe')).toHaveCount(1)

    await held.route.fulfill({
      json: {
        ...state.views[viewId],
        connectionId: held.body.connectionId,
        eventNodeId: held.body.eventNodeId,
        browserSessionId: held.body.browserSessionId,
        claimId: held.body.claimId,
        entryUrl: entryUrl(ENV_A, viewId),
      },
    })
    await page.waitForTimeout(150)

    expect(state.openCalls).toHaveLength(3)
    expect(await sameFrame(page)).toBe(true)
    expect(state.iframeRequests[viewId]).toBe(1)
    expect((await storedSession(page)).recoveryByEnvironment[ENV_A]?.bindingEpoch).toBe(epoch)
    await expect(page.getByTestId('ide-iframe')).toHaveCount(1)
  })

  test('a STALE_BINDING response is reconciled by one read and a fresh CAS, not a retry timer', async ({ page }) => {
    const state = createState()
    await openHarness(page, state)
    await openA(page)
    const viewId = String(state.openCalls[0]!.body.viewId)
    const initialEpoch = String((await storedSession(page)).recoveryByEnvironment[ENV_A]?.bindingEpoch)
    await saveFrame(page)
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
    expect(state.openCalls[2]!.body.expectedBindingEpoch).toBe(state.views[viewId]!.bindingEpoch)
    expect(state.readCalls.length).toBeGreaterThan(readsBefore + 1)
    expect(await sameFrame(page)).toBe(true)
    expect(state.iframeRequests[viewId]).toBe(1)
    await expect(page.getByTestId('ide-iframe')).toHaveCount(1)
  })

  test('a duplicated tab gets a distinct browser session and cannot steal the original view', async ({ page, context }) => {
    const state = createState()
    await openHarness(page, state)
    await openA(page)
    const originalView = String(state.openCalls[0]!.body.viewId)
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
    await expect(duplicate.getByTestId('ide-iframe')).toHaveCount(0)
    await duplicate.waitForTimeout(250)

    const cloneSession = await storedSession(duplicate)
    expect(cloneSession.browserSessionId).not.toBe((await storedSession(page)).browserSessionId)
    expect(state.openCalls).toHaveLength(1)

    await duplicate.getByTestId('pane-1-open-a').click()
    await expect(duplicate.getByTestId('ide-iframe')).toHaveCount(1)
    await expect.poll(() => state.openCalls.length).toBe(2)
    const replacement = state.openCalls[1]!
    expect(replacement.body.mode).toBe('create')
    expect(replacement.body.viewId).not.toBe(originalView)
    expect((await storedSession(page)).recoveryByEnvironment[ENV_A]?.viewId).toBe(originalView)
    expect((await storedSession(duplicate)).recoveryByEnvironment[ENV_A]?.viewId)
      .toBe(replacement.body.viewId)
  })

  test('BFCache restore reacquires the session lock and reconciles without replacing the iframe', async ({ page }) => {
    const state = createState()
    await openHarness(page, state)
    await openA(page)
    const viewId = String(state.openCalls[0]!.body.viewId)
    await saveFrame(page)

    await page.evaluate(() => {
      window.dispatchEvent(new PageTransitionEvent('pagehide', { persisted: true }))
    })
    await page.evaluate(() => {
      window.dispatchEvent(new PageTransitionEvent('pageshow', { persisted: true }))
    })
    await expect.poll(() => state.openCalls.length).toBe(2)

    expect(state.openCalls[1]!.body).toMatchObject({ mode: 'resume', viewId })
    expect(await sameFrame(page)).toBe(true)
    expect(state.iframeRequests[viewId]).toBe(1)
    await expect(page.getByTestId('ide-iframe')).toHaveCount(1)
  })

  test('leaving Chat removes layout and returns with the same iframe and unsaved text', async ({ page }) => {
    const state = createState()
    await page.setViewportSize({ width: 1440, height: 900 })
    await openHarness(page, state)
    await openA(page)
    const viewId = String(state.openCalls[0]!.body.viewId)
    await saveFrame(page)
    await page.evaluate(() => {
      const frame = document.querySelector<HTMLIFrameElement>('[data-testid="ide-iframe"]')
      const draft = frame?.contentDocument?.querySelector<HTMLTextAreaElement>(
        '[data-testid="ide-fixture-draft"]',
      )
      if (draft != null) draft.value = 'unsaved IDE text'
    })

    await page.getByTestId('other-link').click()
    const other = page.getByTestId('other-page')
    await expect(other).toBeVisible()
    await expect(page.getByTestId('chat-ide-workspace')).toBeHidden()
    await expect(page.getByTestId('ide-overlay-panel')).toBeHidden()
    const otherBox = await other.boundingBox()
    expect(otherBox).not.toBeNull()
    expect(Math.abs((otherBox!.x + otherBox!.width) - 1440)).toBeLessThanOrEqual(2)
    expect(state.openCalls).toHaveLength(1)
    expect(state.iframeRequests[viewId]).toBe(1)

    await page.getByTestId('back-link').click()
    await expect(page.getByTestId('chat-page')).toBeVisible()
    await expect(page.getByTestId('ide-overlay-panel')).toBeVisible()
    expect(await sameFrame(page)).toBe(true)
    expect(state.openCalls).toHaveLength(1)
    expect(await page.getByTestId('ide-iframe').evaluate((element: HTMLIFrameElement) => (
      element.contentDocument?.querySelector<HTMLTextAreaElement>(
        '[data-testid="ide-fixture-draft"]',
      )?.value
    ))).toBe('unsaved IDE text')
  })
})
