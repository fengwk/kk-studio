import { expect, test, type Page } from './fixture'
import type { Route, WebSocketRoute } from '@playwright/test'

/**
 * 技能可用性提示真实浏览器回归。
 *
 * 全部数据由 `page.route` 提供：执行根已选择 Environment 时，Agent 配置的技能如果在该
 * Environment 上没有安装当前版本，对话输入区上方给出提示并列出原因；未选择 Environment、
 * 切换 Environment 与安装完成推送后提示消失。
 */

const ROOT_ID = 'a1000000-0000-4000-8000-0000000000b1'
const NAS_ID = 'a2000000-0000-4000-8000-0000000000e1'
const BUILDER_ID = 'a2000000-0000-4000-8000-0000000000e2'
const HARNESS_URL = '/browser-tests/skill-hint-harness.html'
const CURRENT_SHA = 'a'.repeat(64)
const PREVIOUS_SHA = 'b'.repeat(64)

interface InstallState {
  environmentName: string | null
  socket: WebSocketRoute | null
  /** 安装完成：全部 Package 在 NAS 上都成为当前版本。 */
  installed: boolean
}

function rootThread(environmentName: string | null) {
  return {
    name: '根 Thread',
    threadId: ROOT_ID,
    sessionId: 'b1000000-0000-4000-8000-0000000000b1',
    headEntryId: null,
    parentThreadId: null,
    rootThreadId: ROOT_ID,
    yoloEnabled: false,
    nextCommandSequence: '1',
    version: '1',
    status: 'IDLE',
    executionControl: 'RUNNABLE',
    branchSettings: {
      agentName: 'assistant',
      execution: { driver: 'builtin', settings: { modelName: 'VeryLongModelName', variant: 'default' } },
      environmentName,
      goal: null,
    },
    createTime: 1790812800,
    updateTime: 1790812800,
  }
}

function snapshot(environmentName: string | null) {
  return {
    version: '1',
    thread: rootThread(environmentName),
    baseEntryId: null,
    entries: [],
    queuedCommands: [],
    executionStatus: 'IDLE',
    activeAssistantOutput: null,
    toolInvocations: [],
    generationFailures: [],
    stopReceipts: [],
    unconfirmedExecutions: [],
  }
}

function skillSync(
  environmentId: string,
  state: { installedSha256: string | null; error?: string | null; upToDate: boolean },
) {
  return {
    environmentId,
    installedSha256: state.installedSha256,
    localPath: state.installedSha256 == null ? null : '/home/dev/.kkstudio/skills',
    error: state.error ?? null,
    upToDate: state.upToDate,
  }
}

/**
 * NAS 上三个 Package 分别是安装失败、版本不一致与从未上报；builder 全部为当前版本。
 * 从未上报的 Package 在该 Environment 没有摘要行，与既有契约 `GET /api/platform/skill-packages/{name}/environments` 一致。
 */
function summaries(state: InstallState, packageName: string) {
  const builder = skillSync(BUILDER_ID, { installedSha256: CURRENT_SHA, upToDate: true })
  if (state.installed) {
    return [skillSync(NAS_ID, { installedSha256: CURRENT_SHA, upToDate: true }), builder]
  }
  if (packageName === 'ops') {
    return [skillSync(NAS_ID, { installedSha256: null, error: 'git fetch failed', upToDate: false }), builder]
  }
  if (packageName === 'docs-tools') {
    return [skillSync(NAS_ID, { installedSha256: PREVIOUS_SHA, upToDate: false }), builder]
  }
  return packageName === 'release-tools'
    ? [builder]
    : [skillSync(NAS_ID, { installedSha256: CURRENT_SHA, upToDate: true }), builder]
}

async function installSkillHintApi(
  page: Page,
  options: { environmentName?: string | null } = {},
): Promise<InstallState> {
  const state: InstallState = {
    // 注意：null 是"未选择环境"的合法取值，不能用 `??` 回退成默认环境。
    environmentName: options.environmentName === undefined ? 'NAS' : options.environmentName,
    socket: null,
    installed: false,
  }
  await page.routeWebSocket(/\/ws\/events$/, (socket) => {
    state.socket = socket
    socket.onMessage((message) => {
      const frame = JSON.parse(String(message))
      if (frame.type === 'subscribe') {
        socket.send(JSON.stringify({ type: 'subscribed', resource: frame.resource, cursor: '0' }))
      }
    })
  })
  await page.route((url) => new URL(url).pathname.startsWith('/api/'), async (route: Route) => {
    const path = new URL(route.request().url()).pathname
    if (path === `/api/harness/threads/${ROOT_ID}`) {
      await route.fulfill({ json: snapshot(state.environmentName) })
      return
    }
    if (path === `/api/harness/threads/${ROOT_ID}/tree`) {
      // 执行树必须包含被绑定的执行根，否则面板按非法树报错；根自身不出活跃行。
      await route.fulfill({
        json: [{
            threadId: ROOT_ID,
            parentThreadId: null,
            name: '根 Thread',
            agentName: 'assistant',
            model: { modelName: 'VeryLongModelName', variant: 'default' },
            status: 'IDLE',
            executionControl: 'RUNNABLE',
            turnCount: 1,
            toolCallCount: 0,
            outcome: null,
            updateTime: 1774958400,
          }],
      })
      return
    }
    if (path === '/api/harness/interactions') {
      await route.fulfill({
        json: { items: [], total: 0, freshnessAt: 1790812800, nextCursor: null },
      })
      return
    }
    if (path === '/api/platform/environments') {
      await route.fulfill({
        json: [
            {
              id: NAS_ID, name: 'NAS', status: 'READY', ready: true, statusExpiresAt: null,
              lastSeenAt: 1790812800000, capabilities: [], userName: null, homeDirectory: null,
              version: '1', createdAt: 1790812800000, updatedAt: 1790812800000,
            },
            {
              id: BUILDER_ID, name: 'builder', status: 'READY', ready: true, statusExpiresAt: null,
              lastSeenAt: 1790812800000, capabilities: [], userName: null, homeDirectory: null,
              version: '1', createdAt: 1790812800000, updatedAt: 1790812800000,
            },
          ],
      })
      return
    }
    const skillMatch = /^\/api\/platform\/skill-packages\/([^/]+)\/environments$/.exec(path)
    if (skillMatch != null) {
      const packageName = decodeURIComponent(skillMatch[1]!)
      await route.fulfill({ json: summaries(state, packageName) })
      return
    }
    // Agent 定义由基座作为 props 提供；这里只需满足共享查询，不重复注入。
    if (path === '/api/platform/agents') {
      await route.fulfill({ json: [] })
      return
    }
    await route.fulfill({
      json: { pageNumber: 1, pageSize: 50, totalCount: '0', results: [] },
    })
  })
  return state
}

test('root pane lists the skills the selected environment cannot run', async ({ page }) => {
  await installSkillHintApi(page)
  await page.goto(HARNESS_URL)

  const hint = page.getByTestId('thread-skill-hint')
  await expect(hint).toBeVisible()
  await expect(hint.getByText('以下技能在当前环境不可用')).toBeVisible()
  await expect(hint.getByText('docs-tools / docs')).toBeVisible()
  await expect(hint.getByText('版本不一致')).toBeVisible()
  await expect(hint.getByText('ops / deploy')).toBeVisible()
  await expect(hint.getByText('安装失败')).toBeVisible()
  await expect(hint.getByText('release-tools / migrate')).toBeVisible()
  await expect(hint.getByText('未安装')).toBeVisible()
  // 已安装当前版本的技能不进入提示。
  await expect(hint.getByText('dev-tools / dev')).toHaveCount(0)

  // 提示位于 Composer 之上并与输入区同宽，不产生横向溢出。
  const hintBox = (await hint.boundingBox())!
  const composerBox = (await page.locator('.thread-composer').boundingBox())!
  expect(hintBox.y + hintBox.height).toBeLessThanOrEqual(composerBox.y + 1)
  expect(hintBox.width).toBeLessThanOrEqual(composerBox.width)
  const overflow = await page.evaluate(
    () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
  )
  expect(overflow).toBeLessThanOrEqual(0)

  await page.screenshot({ path: test.info().outputPath('skill-hint-visible.png'), fullPage: false })
})

// 未选择环境时技能走 vfs 路径，输入区上方不出现提示。
test('root pane without a selected environment shows no skill hint', async ({ page }) => {
  await installSkillHintApi(page, { environmentName: null })
  await page.goto(HARNESS_URL)

  await expect(page.locator('.thread-composer')).toBeVisible()
  await expect(page.getByTestId('thread-skill-hint')).toHaveCount(0)
  await page.screenshot({
    path: test.info().outputPath('skill-hint-no-environment.png'),
    fullPage: false,
  })
})

// 切换到已安装全部技能的环境后，同一份 Agent 技能不再产生提示。
test('switching to an environment with every skill installed clears the hint', async ({ page }) => {
  await installSkillHintApi(page)
  await page.goto(HARNESS_URL)
  await expect(page.getByTestId('thread-skill-hint')).toBeVisible()

  const environmentTrigger = page
    .locator('.thread-composer')
    .getByRole('button', { name: '环境', exact: true })
  await expect(environmentTrigger).toContainText('NAS')
  await environmentTrigger.click()
  await page.getByRole('option', { name: 'builder' }).click()

  await expect(environmentTrigger).toContainText('builder')
  await expect(page.getByTestId('thread-skill-hint')).toHaveCount(0)
  await page.screenshot({
    path: test.info().outputPath('skill-hint-switched-environment.png'),
    fullPage: false,
  })
})

// 安装完成由环境 changed 推送告知：只回读安装摘要，提示随当前版本的安装事实消失。
test('a committed environment change clears the hint once the skills are current', async ({ page }) => {
  const state = await installSkillHintApi(page)
  await page.goto(HARNESS_URL)
  await expect(page.getByTestId('thread-skill-hint')).toBeVisible()

  state.installed = true
  expect(state.socket).not.toBeNull()
  state.socket!.send(JSON.stringify({
    type: 'event',
    resource: { kind: 'environments' },
    name: 'changed',
    data: {},
  }))

  await expect(page.getByTestId('thread-skill-hint')).toHaveCount(0)
  await expect(page.locator('.thread-composer')).toBeVisible()
  await page.screenshot({ path: test.info().outputPath('skill-hint-installed.png'), fullPage: false })
})
