import { expect, test, type Page } from './fixture'
import type { EnvironmentCardDTO } from '@/shared/api/generated/contracts'

const HARNESS_URL = '/browser-tests/ide-overlay-harness.html'
const SESSION_KEY = 'kk-studio.tab-state:ide'
const LAST_KEY = 'kk-studio.last-state:ide'
const ENV_A = 'a1000000-0000-4000-8000-000000000001'
const ENV_B = 'a1000000-0000-4000-8000-000000000002'
const DIRECTORY_A = '/workspace/a'
const DIRECTORY_B = '/workspace/b'
const EVENT_NODE = 'e1000000-0000-4000-8000-000000000001'

const CARDS: EnvironmentCardDTO[] = [
  {
    id: ENV_A,
    name: 'local-node',
    ready: true,
    status: 'READY',
    version: '1',
    createTime: 1790812800,
    updateTime: 1790812800,
    capabilities: [],
    statusExpiresAt: null,
    lastSeen: null,
    homeDirectory: DIRECTORY_A,
  },
  {
    id: ENV_B,
    name: 'remote-node',
    ready: true,
    status: 'READY',
    version: '1',
    createTime: 1790812800,
    updateTime: 1790812800,
    capabilities: [],
    statusExpiresAt: null,
    lastSeen: null,
    homeDirectory: DIRECTORY_B,
  },
]

interface OpenCall {
  environmentId: string
  body: Record<string, unknown>
}

interface CloseCall {
  environmentId: string
  viewId: string
  body: Record<string, unknown>
}

interface UsageFrame {
  documentId: string
  revision: string
  views: Array<Record<string, unknown>>
}

interface TransportState {
  openCalls: OpenCall[]
  closeCalls: CloseCall[]
  terminalCalls: Array<{ environmentId: string; viewId: string; body: Record<string, unknown> }>
  directoryCalls: Array<{ environmentId: string; viewId: string; body: Record<string, unknown> }>
  directoryListCalls: Array<{ environmentId: string; path: string | null }>
  usageFrames: UsageFrame[]
  iframeRequests: Record<string, number>
  eventConnectionIds: string[]
  failNextOpen: 'network' | 'view-in-use' | 'view-gone' | null
  dirtyCloseOnce: boolean
  epoch: number
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

interface DirectoryFixture {
  parentPath: string | null
  children: Array<{ name: string; path: string }>
  truncated?: boolean
  denied?: boolean
}

/** 只包含宿主真实会返回的直接子目录；logs 用于验证展开时的权限错误展示。 */
const DIRECTORY_FIXTURES: Record<string, DirectoryFixture> = {
  [DIRECTORY_A]: {
    parentPath: '/workspace',
    children: [
      { name: 'projects', path: `${DIRECTORY_A}/projects` },
      { name: 'logs', path: `${DIRECTORY_A}/logs` },
    ],
    truncated: true,
  },
  [`${DIRECTORY_A}/projects`]: {
    parentPath: DIRECTORY_A,
    children: [{ name: 'kk-studio', path: `${DIRECTORY_A}/projects/kk-studio` }],
  },
  [`${DIRECTORY_A}/logs`]: {
    parentPath: DIRECTORY_A,
    children: [],
    denied: true,
  },
  '/workspace': {
    parentPath: '/',
    children: [
      { name: 'a', path: DIRECTORY_A },
      { name: 'b', path: DIRECTORY_B },
    ],
  },
  '/': {
    parentPath: null,
    children: [{ name: 'workspace', path: '/workspace' }],
  },
  [DIRECTORY_B]: {
    parentPath: '/workspace',
    children: [{ name: 'reports', path: `${DIRECTORY_B}/reports` }],
  },
}

async function installTransport(page: Page, state: TransportState): Promise<void> {
  await page.routeWebSocket(/\/api\/events$/, (socket) => {
    state.eventConnectionIds.push(connectionId(state.eventConnectionIds.length + 1))
    const currentConnection = state.eventConnectionIds.at(-1)!
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
        state.usageFrames.push(message as unknown as UsageFrame)
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

    if (method === 'GET' && path === '/api/environments') {
      await route.fulfill({ json: { status: 200, data: CARDS } })
      return
    }

    const openMatch = path.match(/^\/api\/environments\/([^/]+)\/ide\/views$/)
    if (method === 'POST' && openMatch != null) {
      const environmentId = decodeURIComponent(openMatch[1]!)
      const body = (request.postDataJSON() as Record<string, unknown> | null) ?? {}
      state.openCalls.push({ environmentId, body })
      const failure = state.failNextOpen
      state.failNextOpen = null
      if (failure === 'network') {
        await route.abort('failed')
        return
      }
      if (failure === 'view-in-use' || failure === 'view-gone') {
        await route.fulfill({
          status: 409,
          json: {
            status: 409,
            code: 'IDE_UNAVAILABLE',
            message: failure === 'view-in-use'
              ? 'IDE view is in use'
              : 'IDE view is gone; open a new view before retrying',
            errors: { detail: failure === 'view-in-use' ? 'VIEW_IN_USE' : 'VIEW_GONE' },
          },
        })
        return
      }
      state.epoch += 1
      const viewId = String(body.viewId)
      const response = {
        ...body,
        environmentId,
        daemonInstanceId: daemonId(environmentId),
        bindingEpoch: String(state.epoch + 10),
        directory: directory(environmentId),
        workspacePath: directory(environmentId),
        state: 'OPEN',
        entryUrl: `/environment-ide/${environmentId}/${viewId}/__ide/bootstrap?token=${viewId}-${state.epoch}`,
      }
      await route.fulfill({ json: { status: 200, data: response } })
      return
    }

    const terminalMatch = path.match(/^\/api\/environments\/([^/]+)\/ide\/views\/([^/]+)\/terminal$/)
    if (method === 'POST' && terminalMatch != null) {
      state.terminalCalls.push({
        environmentId: decodeURIComponent(terminalMatch[1]!),
        viewId: decodeURIComponent(terminalMatch[2]!),
        body: (request.postDataJSON() as Record<string, unknown> | null) ?? {},
      })
      await route.fulfill({ json: { status: 200, data: null } })
      return
    }

    const directoryListMatch = path.match(/^\/api\/environments\/([^/]+)\/ide\/directories$/)
    if (method === 'GET' && directoryListMatch != null) {
      const environmentId = decodeURIComponent(directoryListMatch[1]!)
      const requestedPath = parsed.searchParams.get('path')
      state.directoryListCalls.push({ environmentId, path: requestedPath })
      const targetPath = requestedPath ?? directory(environmentId)
      const fixture = DIRECTORY_FIXTURES[targetPath]
      if (fixture == null || fixture.denied === true) {
        await route.fulfill({
          status: 403,
          json: {
            status: 403,
            code: 'environment',
            message: `permission denied: ${targetPath}`,
          },
        })
        return
      }
      await route.fulfill({
        json: {
          status: 200,
          data: {
            path: targetPath,
            parentPath: fixture.parentPath,
            children: fixture.children,
            truncated: fixture.truncated === true,
          },
        },
      })
      return
    }

    const directoryMatch = path.match(/^\/api\/environments\/([^/]+)\/ide\/views\/([^/]+)\/directory$/)
    if (method === 'PUT' && directoryMatch != null) {
      const environmentId = decodeURIComponent(directoryMatch[1]!)
      const viewId = decodeURIComponent(directoryMatch[2]!)
      const body = (request.postDataJSON() as Record<string, unknown> | null) ?? {}
      state.directoryCalls.push({ environmentId, viewId, body })
      const targetDirectory = String(body.directory)
      await route.fulfill({
        json: {
          status: 200,
          data: {
            viewId,
            environmentId,
            documentId: body.documentId,
            eventNodeId: EVENT_NODE,
            connectionId: state.eventConnectionIds.at(-1),
            claimId: '91000000-0000-4000-8000-000000000001',
            daemonInstanceId: daemonId(environmentId),
            bindingEpoch: body.bindingEpoch,
            directory: targetDirectory,
            workspacePath: targetDirectory,
            state: 'OPEN',
            entryUrl: null,
          },
        },
      })
      return
    }

    const closeMatch = path.match(/^\/api\/environments\/([^/]+)\/ide\/views\/([^/]+)$/)
    if (method === 'DELETE' && closeMatch != null) {
      const environmentId = decodeURIComponent(closeMatch[1]!)
      const viewId = decodeURIComponent(closeMatch[2]!)
      const body = (request.postDataJSON() as Record<string, unknown> | null) ?? {}
      state.closeCalls.push({ environmentId, viewId, body })
      if (state.dirtyCloseOnce && body.discard !== true) {
        state.dirtyCloseOnce = false
        await route.fulfill({
          status: 409,
          json: {
            status: 409,
            code: 'ide_unavailable',
            message: 'IDE bridge unavailable',
          },
        })
        return
      }
      await route.fulfill({ json: { status: 200, data: null } })
      return
    }

    await route.fulfill({
      status: 404,
      json: { status: 404, message: `Unexpected harness request: ${method} ${path}` },
    })
  })

  await page.route('**/environment-ide/**/__ide/bootstrap*', async (route) => {
    const parsed = new URL(route.request().url())
    const match = parsed.pathname.match(
      /^\/environment-ide\/([^/]+)\/([^/]+)\/__ide\/bootstrap$/,
    )
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
    openCalls: [],
    closeCalls: [],
    terminalCalls: [],
    directoryCalls: [],
    directoryListCalls: [],
    usageFrames: [],
    iframeRequests: {},
    eventConnectionIds: [],
    failNextOpen: null,
    dirtyCloseOnce: false,
    epoch: 0,
  }
}

async function openHarness(page: Page, state: TransportState): Promise<void> {
  await installTransport(page, state)
  await page.goto(HARNESS_URL)
  await expect(page.getByTestId('harness-event-status')).toHaveAttribute('data-connected', 'true')
}

async function openEnvironment(page: Page, which: 'A' | 'B'): Promise<void> {
  await page.getByTestId(which === 'A' ? 'pane-1-open-a' : 'pane-2-open-b').click()
  await expect(page.getByTestId('ide-overlay-panel')).toBeVisible()
}

async function session(page: Page): Promise<{
  presentation: { openedEnvironmentIds: string[]; selectedEnvironmentId: string | null }
  recoveryByEnvironment: Record<string, { viewId: string; daemonInstanceId: string; directory: string }>
}> {
  return page.evaluate((key) => JSON.parse(sessionStorage.getItem(key) ?? '{}'), SESSION_KEY)
}

test.describe('tab-global IDE panel', () => {
  test('refresh resumes the same view from session without rewriting local last state', async ({ page }) => {
    const state = createState()
    await openHarness(page, state)

    await openEnvironment(page, 'A')
    await expect(page.getByTestId('ide-iframe')).toHaveCount(1)
    await expect.poll(() => state.openCalls.length).toBe(1)
    const first = state.openCalls[0]!
    const viewId = String(first.body.viewId)
    expect(first.body.mode).toBe('create')
    expect(first.body).not.toHaveProperty('directory')

    const before = await session(page)
    expect(before.recoveryByEnvironment[ENV_A]?.viewId).toBe(viewId)
    const localBefore = await page.evaluate((key) => localStorage.getItem(key), LAST_KEY)
    expect(localBefore).not.toContain(viewId)

    await page.reload()
    await expect(page.getByTestId('harness-event-status')).toHaveAttribute('data-connected', 'true')
    await expect(page.getByTestId('ide-iframe')).toHaveCount(1)
    await expect.poll(() => state.openCalls.length).toBe(2)

    const resume = state.openCalls[1]!
    expect(resume.environmentId).toBe(ENV_A)
    expect(resume.body.mode).toBe('resume')
    expect(resume.body.viewId).toBe(viewId)
    expect(resume.body).not.toHaveProperty('directory')
    expect(state.iframeRequests[viewId]).toBe(2)
    expect(await page.evaluate((key) => localStorage.getItem(key), LAST_KEY)).toBe(localBefore)
    expect((await session(page)).recoveryByEnvironment[ENV_A]?.viewId).toBe(viewId)
  })

  test('keeps both environment iframes mounted and switches visibility without recreating views', async ({ page }) => {
    const state = createState()
    await openHarness(page, state)
    await page.setViewportSize({ width: 2560, height: 900 })

    await openEnvironment(page, 'A')
    await expect(page.getByTestId('ide-iframe')).toHaveCount(1)
    await openEnvironment(page, 'B')
    await expect(page.getByTestId('ide-iframe')).toHaveCount(2)
    await expect.poll(() => state.openCalls.length).toBe(2)

    const viewA = String(state.openCalls[0]!.body.viewId)
    const viewB = String(state.openCalls[1]!.body.viewId)
    const layerA = page.locator(`[data-testid="ide-view-layer"][data-ide-environment="${ENV_A}"]`)
    const layerB = page.locator(`[data-testid="ide-view-layer"][data-ide-environment="${ENV_B}"]`)
    await expect(layerA).toHaveAttribute('hidden')
    await expect(layerB).not.toHaveAttribute('hidden')

    const panelBox = await page.getByTestId('ide-overlay-panel').boundingBox()
    expect(panelBox).not.toBeNull()
    expect(Math.abs((panelBox!.x + panelBox!.width) - 2560)).toBeLessThanOrEqual(2)
    expect(Math.abs(panelBox!.width - 720)).toBeLessThanOrEqual(2)
    const contentBox = await page.getByTestId('ide-harness-root').boundingBox()
    expect(contentBox!.width + panelBox!.width + 8).toBeCloseTo(2560, 0)

    await page.evaluate(([environmentA, environmentB]) => {
      const win = window as unknown as {
        __ideFrames?: Record<string, Element | null>
      }
      win.__ideFrames = {
        [environmentA!]: document.querySelector(
          `[data-testid="ide-view-layer"][data-ide-environment="${environmentA}"] iframe`,
        ),
        [environmentB!]: document.querySelector(
          `[data-testid="ide-view-layer"][data-ide-environment="${environmentB}"] iframe`,
        ),
      }
    }, [ENV_A, ENV_B])

    await openEnvironment(page, 'A')
    await expect(layerA).not.toHaveAttribute('hidden')
    await expect(layerB).toHaveAttribute('hidden')
    expect(state.openCalls.length).toBe(2)

    const sameFrames = await page.evaluate(([environmentA, environmentB]) => {
      const win = window as unknown as {
        __ideFrames?: Record<string, Element | null>
      }
      return win.__ideFrames?.[environmentA!] === document.querySelector(
        `[data-testid="ide-view-layer"][data-ide-environment="${environmentA}"] iframe`,
      ) && win.__ideFrames?.[environmentB!] === document.querySelector(
        `[data-testid="ide-view-layer"][data-ide-environment="${environmentB}"] iframe`,
      )
    }, [ENV_A, ENV_B])
    expect(sameFrames).toBe(true)
    expect(state.iframeRequests[viewA]).toBe(1)
    expect(state.iframeRequests[viewB]).toBe(1)

    await expect(page.getByTestId('composer-input-pane-1')).toHaveValue('pane 1 initial text')
    await page.getByTestId('composer-input-pane-1').fill('pane one edited')
    await expect(page.getByTestId('composer-input-pane-2')).toHaveValue('pane 2 initial text')

    const stored = await page.evaluate((key) => localStorage.getItem(key) ?? '', LAST_KEY)
    expect(stored).not.toContain(viewA)
    expect(stored).not.toContain(viewB)
    expect(stored).not.toContain('daemonInstanceId')
    expect(stored).not.toContain('bindingEpoch')
    expect(await page.evaluate((key) => sessionStorage.getItem(key) ?? '', SESSION_KEY))
      .toContain(viewA)
  })

  test('resizes the persistent shell, remembers width, and covers mobile safely', async ({ page }, testInfo) => {
    const state = createState()
    await page.setViewportSize({ width: 1440, height: 900 })
    await openHarness(page, state)
    await openEnvironment(page, 'A')
    const frame = page.getByTestId('ide-iframe')
    await expect(frame).toHaveCount(1)
    await page.evaluate(() => {
      ;(window as unknown as { savedFrame: Element | null }).savedFrame = document.querySelector('[data-testid="ide-iframe"]')
      const iframe = document.querySelector<HTMLIFrameElement>('[data-testid="ide-iframe"]')
      iframe?.contentDocument?.body.setAttribute('data-state', 'preserved')
    })
    const divider = page.getByRole('separator', { name: '调整 IDE 面板宽度' })
    await divider.focus()
    await page.keyboard.press('Home')
    expect((await page.getByTestId('ide-overlay-panel').boundingBox())!.width).toBe(360)
    await page.keyboard.press('End')
    expect((await page.getByTestId('ide-harness-root').boundingBox())!.width).toBe(320)
    // 回到默认宽度后检查指针与键盘增量。
    const maxBox = (await divider.boundingBox())!
    await page.mouse.move(maxBox.x + maxBox.width / 2, maxBox.y + maxBox.height / 2)
    await page.mouse.down()
    await page.mouse.move(720, maxBox.y + maxBox.height / 2)
    await page.mouse.up()
    const initialRenders = await page.getByTestId('ide-harness-root').getAttribute('data-renders')
    const box = (await divider.boundingBox())!
    await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2)
    await page.mouse.down()
    await page.mouse.move(box.x + 160, box.y + box.height / 2, { steps: 8 })
    await page.mouse.up()
    const panel = page.getByTestId('ide-overlay-panel')
    expect((await panel.boundingBox())!.width).toBeCloseTo(560, -1)
    expect(await page.getByTestId('ide-harness-root').getAttribute('data-renders')).toBe(initialRenders)
    await divider.focus()
    await page.keyboard.press('ArrowLeft')
    const chosenWidth = (await panel.boundingBox())!.width
    expect(chosenWidth).toBeCloseTo(570, -1)
    await page.screenshot({ path: testInfo.outputPath('ide-desktop.png') })

    await page.getByRole('link', { name: '其他页面' }).click()
    await expect(page.getByRole('link', { name: '返回对话' })).toBeVisible()
    expect(await page.evaluate(() =>
      (window as unknown as { savedFrame: Element }).savedFrame === document.querySelector('[data-testid="ide-iframe"]'),
    )).toBe(true)
    await page.getByRole('link', { name: '返回对话' }).click()
    await page.getByTestId('toggle-a').click()
    await expect(panel).toBeHidden()
    await page.getByTestId('toggle-a').click()
    await expect(panel).toBeVisible()
    expect(await page.evaluate(() =>
      (window as unknown as { savedFrame: Element }).savedFrame === document.querySelector('[data-testid="ide-iframe"]'),
    )).toBe(true)
    expect(state.iframeRequests[String(state.openCalls[0]!.body.viewId)]).toBe(1)
    expect(await frame.evaluate((element: HTMLIFrameElement) => element.contentDocument?.body.getAttribute('data-state')))
      .toBe('preserved')

    await page.setViewportSize({ width: 840, height: 900 })
    expect((await panel.boundingBox())!.width).toBe(512)
    await page.setViewportSize({ width: 390, height: 844 })
    expect((await panel.boundingBox())!.width).toBe(390)
    await expect(divider).toBeHidden()
    const mobileTargets = [
      ...['ide-terminal-btn', 'ide-collapse-btn', 'ide-close-btn', 'ide-copy-directory-btn', 'ide-open-directory-btn']
        .map((id) => page.getByTestId(id)),
      page.getByRole('button', { name: '环境' }),
    ]
    for (const control of mobileTargets) {
      const target = (await control.boundingBox())!
      expect(target.width).toBeGreaterThanOrEqual(44)
      expect(target.height).toBeGreaterThanOrEqual(44)
    }
    await page.screenshot({ path: testInfo.outputPath('ide-mobile.png') })
    await page.getByTestId('ide-open-directory-btn').click()
    const dialog = page.getByRole('dialog', { name: '打开宿主目录' })
    await expect(dialog.getByTestId('ide-directory-location')).toHaveText(DIRECTORY_A)
    const modalBox = (await dialog.boundingBox())!
    expect(modalBox.width).toBe(390)
    expect(modalBox.height).toBe(844)
    // 目录弹窗在移动端全屏，行、展开、帮助与上级按钮都不小于 44px 触区。
    const directoryTargets = [
      dialog.getByTestId('ide-directory-up'),
      dialog.getByTestId('ide-directory-help'),
      dialog.getByTestId('ide-directory-toggle').first(),
      dialog.getByTestId('ide-directory-entry').first(),
    ]
    for (const control of directoryTargets) {
      const target = (await control.boundingBox())!
      expect(target.width).toBeGreaterThanOrEqual(44)
      expect(target.height).toBeGreaterThanOrEqual(44)
    }
    await dialog.getByTestId('ide-directory-help').click()
    await expect(dialog.getByRole('note')).toBeVisible()
    await page.screenshot({ path: testInfo.outputPath('ide-mobile-directory.png') })
    await page.getByRole('button', { name: '取消' }).click()
    state.dirtyCloseOnce = true
    await page.getByTestId('ide-close-btn').click()
    const confirmation = page.getByRole('alertdialog', { name: '强制关闭 IDE 视图？' })
    await expect(confirmation).toBeVisible()
    for (const label of ['取消', '强制关闭']) {
      const target = (await confirmation.getByRole('button', { name: label }).boundingBox())!
      expect(target.width).toBeGreaterThanOrEqual(44)
      expect(target.height).toBeGreaterThanOrEqual(44)
    }
    await confirmation.getByRole('button', { name: '取消' }).click()
    await page.getByTestId('ide-collapse-btn').click()
    await expect(panel).toBeHidden()
    await page.setViewportSize({ width: 1440, height: 900 })
    await page.getByTestId('toggle-a').click()
    expect((await panel.boundingBox())!.width).toBeCloseTo(chosenWidth, 0)
    await page.reload()
    await expect(frame).toHaveCount(1)
    expect((await panel.boundingBox())!.width).toBeCloseTo(chosenWidth, 0)
  })

  test('expands the host directory tree lazily and switches to the confirmed directory', async ({ page }, testInfo) => {
    const state = createState()
    await page.setViewportSize({ width: 1440, height: 900 })
    await openHarness(page, state)
    await openEnvironment(page, 'A')
    await expect(page.getByTestId('ide-iframe')).toHaveCount(1)

    await page.getByTestId('ide-open-directory-btn').click()
    const dialog = page.getByRole('dialog', { name: '打开宿主目录' })
    await expect(dialog.getByTestId('ide-directory-location')).toHaveText(DIRECTORY_A)
    // 首次只读取宿主主目录，展开前不请求任何子目录。
    expect(state.directoryListCalls).toEqual([{ environmentId: ENV_A, path: null }])
    await expect(dialog.getByTestId('ide-directory-truncated')).toBeVisible()

    await dialog.getByRole('button', { name: '展开 projects' }).click()
    await expect(dialog.getByText('kk-studio')).toBeVisible()
    expect(state.directoryListCalls[1]).toEqual({
      environmentId: ENV_A,
      path: `${DIRECTORY_A}/projects`,
    })

    // 展开不可读目录时展示后端原因，而不是空列表。
    await dialog.getByRole('button', { name: '展开 logs' }).click()
    const nodeError = dialog.getByTestId('ide-directory-node-error')
    await expect(nodeError).toContainText('目录加载失败')
    await expect(nodeError).toContainText(`permission denied: ${DIRECTORY_A}/logs`)

    // 逐级向上直到根目录，到根后上级动作不可用。
    await dialog.getByTestId('ide-directory-up').click()
    await expect(dialog.getByTestId('ide-directory-location')).toHaveText('/workspace')
    await dialog.getByTestId('ide-directory-up').click()
    await expect(dialog.getByTestId('ide-directory-location')).toHaveText('/')
    await expect(dialog.getByTestId('ide-directory-up')).toBeDisabled()
    expect(state.directoryListCalls.slice(-2)).toEqual([
      { environmentId: ENV_A, path: '/workspace' },
      { environmentId: ENV_A, path: '/' },
    ])

    // 从根逐层展开回到 projects，选中并确认切换。
    await dialog.getByRole('button', { name: '展开 workspace' }).click()
    await expect(dialog.getByText('a', { exact: true })).toBeVisible()
    await dialog.getByRole('button', { name: '展开 a' }).click()
    await expect(dialog.getByText('projects')).toBeVisible()
    await dialog.getByRole('button', { name: '展开 projects' }).click()
    await expect(dialog.getByText('kk-studio')).toBeVisible()
    await dialog.getByTestId('ide-directory-entry').filter({ hasText: /^projects$/ }).click()
    await expect(dialog.getByTestId('ide-directory-confirm')).toBeEnabled()
    await dialog.getByTestId('ide-directory-confirm').click()
    await expect(dialog).toBeHidden()
    expect(state.directoryCalls.at(-1)).toMatchObject({
      environmentId: ENV_A,
      body: { directory: `${DIRECTORY_A}/projects` },
    })
    await expect(page.getByTestId('ide-directory-badge')).toContainText('projects')

    // 只有确认成功后才写入最近目录，重新打开时展示它。
    await page.getByTestId('ide-open-directory-btn').click()
    const reopened = page.getByRole('dialog', { name: '打开宿主目录' })
    await expect(reopened.getByTestId('ide-recent-directory').first()).toHaveText(
      `${DIRECTORY_A}/projects`,
    )
    await expect(reopened.getByTestId('ide-directory-location')).toHaveText(DIRECTORY_A)
    await reopened.getByTestId('ide-recent-directory').first().click()
    await expect(reopened.getByTestId('ide-directory-location')).toHaveText(
      `${DIRECTORY_A}/projects`,
    )
    await page.screenshot({ path: testInfo.outputPath('ide-directory-desktop.png') })
    await reopened.getByRole('button', { name: '取消' }).click()
    await expect(reopened).toBeHidden()
  })

  test('a duplicated tab cannot steal the original view and requires an explicit new view', async ({ page, context }) => {
    const state = createState()
    await openHarness(page, state)
    await openEnvironment(page, 'A')
    await expect(page.getByTestId('ide-iframe')).toHaveCount(1)
    await expect.poll(() => state.openCalls.length).toBe(1)
    const originalViewId = String(state.openCalls[0]!.body.viewId)
    const copiedSession = await page.evaluate((key) => sessionStorage.getItem(key), SESSION_KEY)
    expect(copiedSession).not.toBeNull()

    const duplicate = await context.newPage()
    await installTransport(duplicate, state)
    await duplicate.addInitScript(({ key, value }) => {
      // 同源 IDE iframe 与顶层共享 sessionStorage；只在顶层 tab 注入复制来的会话。
      if (window.top === window) {
        sessionStorage.setItem(key, value)
      }
    }, { key: SESSION_KEY, value: copiedSession! })
    state.failNextOpen = 'view-in-use'
    await duplicate.goto(HARNESS_URL)
    await expect(duplicate.getByTestId('harness-event-status')).toHaveAttribute('data-connected', 'true')

    // 与刷新相同的 VIEW_IN_USE 只进入等待态，不自动创建替代视图。
    await expect(duplicate.getByTestId('ide-view-in-use')).toBeVisible()
    await expect(duplicate.getByTestId('ide-iframe')).toHaveCount(0)
    await expect(duplicate.getByTestId('ide-open-new-view-btn')).toBeVisible()
    await duplicate.waitForTimeout(300)
    expect(state.openCalls).toHaveLength(2)
    expect((await session(duplicate)).recoveryByEnvironment[ENV_A]?.viewId).toBe(originalViewId)

    await duplicate.getByTestId('ide-open-new-view-btn').click()
    await expect(duplicate.getByTestId('ide-iframe')).toHaveCount(1)
    await expect.poll(() => state.openCalls.length).toBe(3)

    const resume = state.openCalls[1]!
    const replacement = state.openCalls[2]!
    expect(resume.body.mode).toBe('resume')
    expect(resume.body.viewId).toBe(originalViewId)
    expect(replacement.body.mode).toBe('create')
    expect(replacement.body.viewId).not.toBe(originalViewId)
    expect(replacement.body.directory).toBe(DIRECTORY_A)
    expect(state.closeCalls).toHaveLength(0)

    await expect.poll(async () => {
      const stored = await duplicate.evaluate(
        (key) => JSON.parse(sessionStorage.getItem(key) ?? '{}'),
        SESSION_KEY,
      ) as { recoveryByEnvironment?: Record<string, { viewId: string }> }
      return stored.recoveryByEnvironment?.[ENV_A]?.viewId
    }).toBe(replacement.body.viewId)
    expect((await session(page)).recoveryByEnvironment[ENV_A]?.viewId).toBe(originalViewId)
  })

  test('a refresh while the old connection still holds the view waits for explicit resume', async ({ page }) => {
    const state = createState()
    await openHarness(page, state)
    await openEnvironment(page, 'A')
    await expect(page.getByTestId('ide-iframe')).toHaveCount(1)
    await expect.poll(() => state.openCalls.length).toBe(1)
    const viewId = String(state.openCalls[0]!.body.viewId)
    const localBefore = await page.evaluate((key) => localStorage.getItem(key), LAST_KEY)

    state.failNextOpen = 'view-in-use'
    await page.reload()
    await expect(page.getByTestId('harness-event-status')).toHaveAttribute('data-connected', 'true')

    await expect(page.getByTestId('ide-view-in-use')).toBeVisible()
    await expect(page.getByTestId('ide-retry-resume-btn')).toBeVisible()
    await expect(page.getByTestId('ide-open-new-view-btn')).toBeVisible()
    await expect(page.getByTestId('ide-iframe')).toHaveCount(0)
    await page.waitForTimeout(300)
    expect(state.openCalls).toHaveLength(2)
    expect(state.openCalls[1]!.body).toMatchObject({ mode: 'resume', viewId })
    expect((await session(page)).recoveryByEnvironment[ENV_A]?.viewId).toBe(viewId)
    expect(state.closeCalls).toHaveLength(0)

    const resumeClaimId = state.openCalls[1]!.body.claimId
    await page.getByTestId('ide-retry-resume-btn').click()
    await expect(page.getByTestId('ide-iframe')).toHaveCount(1)
    await expect.poll(() => state.openCalls.length).toBe(3)

    const retry = state.openCalls[2]!
    expect(retry.body.mode).toBe('resume')
    expect(retry.body.viewId).toBe(viewId)
    expect(retry.body.claimId).not.toBe(resumeClaimId)
    expect(state.closeCalls).toHaveLength(0)
    expect((await session(page)).recoveryByEnvironment[ENV_A]?.viewId).toBe(viewId)
    expect(await page.evaluate((key) => localStorage.getItem(key), LAST_KEY)).toBe(localBefore)
  })

  test('an unknown claim result keeps the original request and reconciles it without another operation', async ({ page }) => {
    const state = createState()
    await openHarness(page, state)
    state.failNextOpen = 'network'

    await openEnvironment(page, 'A')
    await expect(page.getByTestId('ide-claim-unknown')).toBeVisible()
    expect(state.openCalls).toHaveLength(1)
    const original = state.openCalls[0]!
    await page.waitForTimeout(300)
    expect(state.openCalls).toHaveLength(1)

    await page.getByTestId('ide-retry-claim-btn').click()
    await expect(page.getByTestId('ide-iframe')).toHaveCount(1)
    await expect.poll(() => state.openCalls.length).toBe(2)
    expect(state.openCalls[1]!.body).toEqual(original.body)
    expect(state.terminalCalls).toHaveLength(0)
    expect(state.directoryCalls).toHaveLength(0)
  })

  test('unknown close state keeps the iframe until the user explicitly forces close', async ({ page }) => {
    const state = createState()
    await openHarness(page, state)
    await openEnvironment(page, 'A')
    await expect(page.getByTestId('ide-iframe')).toHaveCount(1)
    await expect.poll(() => state.openCalls.length).toBe(1)
    const viewId = String(state.openCalls[0]!.body.viewId)

    state.dirtyCloseOnce = true
    await page.getByTestId('ide-close-btn').click()
    await expect(page.getByRole('alertdialog', { name: '强制关闭 IDE 视图？' })).toBeVisible()
    await expect(page.getByTestId('ide-iframe')).toHaveCount(1)
    await expect.poll(() => state.closeCalls.length).toBe(1)
    expect(state.closeCalls[0]).toMatchObject({
      environmentId: ENV_A,
      viewId,
      body: { discard: false },
    })

    await page.getByRole('button', { name: '强制关闭' }).click()
    await expect(page.getByTestId('ide-overlay-panel')).toHaveAttribute('hidden')
    await expect(page.getByTestId('ide-iframe')).toHaveCount(0)
    await expect.poll(() => state.closeCalls.length).toBe(2)
    expect(state.closeCalls[1]).toMatchObject({
      environmentId: ENV_A,
      viewId,
      body: { discard: true },
    })
    expect(state.closeCalls[1]!.body.bindingEpoch).toBe(state.closeCalls[0]!.body.bindingEpoch)
    expect(state.closeCalls[1]!.body.documentId).toBe(state.closeCalls[0]!.body.documentId)
  })

  test('event reconnection resumes the existing view without closing or replacing it', async ({ page }) => {
    const state = createState()
    await openHarness(page, state)
    await openEnvironment(page, 'A')
    await expect(page.getByTestId('ide-iframe')).toHaveCount(1)
    await expect.poll(() => state.openCalls.length).toBe(1)
    const viewId = String(state.openCalls[0]!.body.viewId)
    const firstConnection = state.eventConnectionIds[0]

    await page.getByTestId('disconnect-events').click()
    await expect(page.getByTestId('harness-event-status')).toHaveAttribute('data-connected', 'false')
    await expect.poll(() => state.openCalls.length, { timeout: 5000 }).toBe(2)
    await expect(page.getByTestId('harness-event-status')).toHaveAttribute('data-connected', 'true')
    await expect(page.getByTestId('ide-iframe')).toHaveCount(1)

    const resume = state.openCalls[1]!
    expect(resume.body.mode).toBe('resume')
    expect(resume.body.viewId).toBe(viewId)
    expect(resume.body.connectionId).not.toBe(firstConnection)
    expect(state.closeCalls).toHaveLength(0)
    await expect.poll(() => state.iframeRequests[viewId] ?? 0).toBe(2)
  })
})
