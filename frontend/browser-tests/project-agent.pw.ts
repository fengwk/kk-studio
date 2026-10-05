import { resolve } from 'node:path'
import { expect, test } from './fixture'

const reportsDir = resolve(new URL('.', import.meta.url).pathname, '../../reports/layout')

const PROJECT_ID = 'a0000000-0000-0000-0000-000000000001'
const ISSUE_ID = 'b0000000-0000-0000-0000-000000000001'
const VALID_THREAD_ID = 'c0000000-0000-0000-0000-000000000001'
const ILLEGAL_THREAD_ID = 'd0000000-0000-0000-0000-000000000001'

function createProjectSnapshot() {
  return {
    status: 200,
    data: {
      project: {
        id: PROJECT_ID,
        title: 'Alpha Project',
        description: 'Testing target product UI wiring',
        workflow: {
          states: [
            { state: 'INIT', name: '待开始', next: ['IN_PROGRESS', 'DONE'] },
            { state: 'IN_PROGRESS', name: '进行中', next: ['DONE'] },
            { state: 'BLOCKED', name: '业务阻塞' },
            { state: 'DONE', name: '完成' },
          ],
        },
        yoloEnabled: false,
        nextIssueNumber: '2',
        version: '1',
        archivedAt: null,
        createdAt: '2026-09-20T00:00:00Z',
        updatedAt: '2026-09-20T00:00:00Z',
      },
      issues: [
        {
          issue: {
            id: ISSUE_ID,
            projectId: PROJECT_ID,
            number: '1',
            title: 'Design DB schema',
            description: 'Database schema design and migration',
            state: 'INIT',
            paused: false,
            version: '1',
            archivedAt: null,
            createdAt: '2026-09-20T00:00:00Z',
            updatedAt: '2026-09-20T00:00:00Z',
          },
          run: null,
        },
      ],
    },
  }
}

function createIssueDetail() {
  return {
    status: 200,
    data: {
      issue: {
        id: ISSUE_ID,
        projectId: PROJECT_ID,
        number: '1',
        title: 'Design DB schema',
        description: 'Database schema design and migration',
        state: 'INIT',
        paused: false,
        version: '1',
        archivedAt: null,
        createdAt: '2026-09-20T00:00:00Z',
        updatedAt: '2026-09-20T00:00:00Z',
      },
      activities: [],
      nextActivityCursor: null,
      runs: [],
      currentRun: null,
      latestRun: null,
      stageBudgets: [],
      agentThreads: [
        {
          issueId: ISSUE_ID,
          agentName: 'architect',
          threadId: VALID_THREAD_ID,
        },
      ],
    },
  }
}

function createThreadSnapshot() {
  return {
    status: 200,
    data: {
      version: '1',
      thread: {
        name: 'Architecture Thread',
        threadId: VALID_THREAD_ID,
        sessionId: '50000000-0000-0000-0000-000000000001',
        headEntryId: 'e1',
        parentThreadId: null,
        yoloEnabled: false,
        nextCommandSequence: '1',
        version: '1',
        status: 'IDLE',
        processing: false,
        executionControl: 'RUNNABLE',
        branchSettings: {
          agentName: 'architect',
          model: { providerName: 'minimax', modelName: 'MiniMax-M2.7', variant: 'default' },
          environmentName: null,
          goal: null,
        },
        createTime: '2026-09-20T00:00:00Z',
        updateTime: '2026-09-20T00:00:00Z',
      },
      entries: [
        {
          entryId: 'e0',
          threadId: VALID_THREAD_ID,
          parentEntryId: null,
          entryType: 'ROOT',
          payloadJson: '{}',
          createTime: '2026-09-20T00:00:00Z',
        },
        {
          entryId: 'e1',
          threadId: VALID_THREAD_ID,
          parentEntryId: 'e0',
          entryType: 'MESSAGE',
          payloadJson: JSON.stringify({
            message: { role: 'USER', contents: [{ type: 'text', text: '梳理鉴权模块的现有边界' }] },
          }),
          createTime: '2026-09-20T00:00:01Z',
        },
      ],
      queuedCommands: [],
      modelInvocation: null,
      toolInvocations: [],
      modelAttemptFailures: [],
      manualCompaction: { available: false, disabledReason: 'idle' },
      stopReceipts: [],
    },
  }
}

test.describe('Project Agent Real Browser Wiring & Control Gatekeeping', () => {
  test('end-to-end browser flow: the Issue dock hosts an owner-free bound Thread with message, Goal and Stop', async ({
    page,
  }) => {
    // 测试意图：真实浏览器中 Issue 只是组织容器。打开 Agent 线程后，消息走 per-thread
    // 命令批次（不再被劫持成 Issue INSTRUCTION）、Stop 走 per-thread Stop（不再 stopIssue）、
    // Goal 面板可用；三者请求体都不带产品 owner/target，且绝不触发 Issue 动作接口。
    const commandBatches: Array<Record<string, unknown>> = []
    const stopRequests: Array<Record<string, unknown>> = []
    const issueActivities: unknown[] = []
    const issueStops: unknown[] = []
    const cancelledText = '把未消费的输入还给我'
    let stopCount = 0

    await page.route(
      (url) => new URL(url).pathname.startsWith('/api/'),
      async (route) => {
        const url = route.request().url()
        const method = route.request().method()
        const path = new URL(url).pathname

        if (path === `/api/harness/threads/${VALID_THREAD_ID}/command-batches` && method === 'POST') {
          commandBatches.push(route.request().postDataJSON())
          await route.fulfill({ json: { status: 200, data: { accepted: true, thread: createThreadSnapshot().data.thread } } })
          return
        }
        if (path === `/api/harness/threads/${VALID_THREAD_ID}/stop` && method === 'POST') {
          const body = route.request().postDataJSON()
          stopRequests.push(body)
          stopCount += 1
          await route.fulfill({
            json: {
              status: 200,
              data: {
                status: 'STOPPED',
                thread: {
                  ...createThreadSnapshot().data.thread,
                  status: 'STOPPED',
                  executionControl: 'STOPPED',
                  version: String(stopCount + 1),
                },
                stoppedThreads: [
                  {
                    threadId: VALID_THREAD_ID,
                    stopRequestId: body.stopRequestId,
                    stoppedTurnEndEntryId: null,
                    cancelledCommandCount: 1,
                    cancelledInputs: [
                      {
                        sequence: '1',
                        idempotencyKey: 'cmd-restored-1',
                        type: 'USER_MESSAGE',
                        payloadJson: JSON.stringify({
                          message: { role: 'USER', contents: [{ type: 'text', text: cancelledText }] },
                        }),
                      },
                    ],
                  },
                ],
              },
            },
          })
          return
        }
        if (path === `/api/harness/threads/${VALID_THREAD_ID}`) {
          await route.fulfill({ json: createThreadSnapshot() })
          return
        }
        if (path === `/api/harness/threads/${VALID_THREAD_ID}/tree`) {
          await route.fulfill({ json: { status: 200, data: [] } })
          return
        }
        if (path === `/api/harness/threads/${VALID_THREAD_ID}/model-request-debug`) {
          await route.fulfill({ json: { status: 200, data: null } })
          return
        }
        if (path === `/api/harness/environments`) {
          await route.fulfill({ json: { status: 200, data: [] } })
          return
        }
        if (path === `/api/issues/${ISSUE_ID}`) {
          await route.fulfill({ json: createIssueDetail() })
          return
        }
        if (path === `/api/issues/${ISSUE_ID}/activities`) {
          if (method === 'POST') {
            issueActivities.push(route.request().postDataJSON())
          }
          await route.fulfill({ json: { status: 200, data: [] } })
          return
        }
        if (path === `/api/issues/${ISSUE_ID}/evidence`) {
          await route.fulfill({ json: { status: 200, data: [] } })
          return
        }
        if (path.startsWith(`/api/issues/${ISSUE_ID}`)) {
          if (path.endsWith('/stop') && method === 'POST') {
            issueStops.push(route.request().postDataJSON())
          }
          await route.fulfill({ json: { status: 200, data: createIssueDetail().data.issue } })
          return
        }
        if (path === `/api/projects/${PROJECT_ID}/snapshot`) {
          await route.fulfill({ json: createProjectSnapshot() })
          return
        }
        if (path.startsWith(`/api/projects/${PROJECT_ID}/issues/${ISSUE_ID}`)) {
          await route.fulfill({ json: createIssueDetail() })
          return
        }
        if (path === '/api/ai/catalog/agents') {
          await route.fulfill({
            json: {
              status: 200,
              data: {
                pageNumber: 1,
                pageSize: 50,
                totalCount: 1,
                results: [
                  { name: 'architect', description: '系统架构师', model: 'MiniMax-M2.7', variant: 'default',
                    config: { inheritParentEnvironment: true, tools: [], skills: [], subagents: [] }, version: '1' },
                ],
              },
            },
          })
          return
        }
        if (path === '/api/ai/catalog/models') {
          await route.fulfill({
            json: {
              status: 200,
              data: {
                pageNumber: 1,
                pageSize: 50,
                totalCount: 0,
                results: [],
              },
            },
          })
          return
        }

        await route.fulfill({ json: { status: 200, data: {} } })
      },
    )

    await page.setViewportSize({ width: 1440, height: 900 })
    await page.goto('/browser-tests/project-agent-harness.html')

    // 1. 看板上找到 Issue 卡片并点击
    const issueCard = page.getByText('Design DB schema')
    await expect(issueCard).toBeVisible()
    await issueCard.click()

    // 2. 详情弹窗打开，切换到 Agent 线程 Tab
    const dialog = page.getByRole('dialog', { name: 'Issue #1 详情' })
    await expect(dialog).toBeVisible()
    const agentTab = dialog.getByRole('button', { name: /Agent 线程/ })
    await agentTab.click()

    // 3. 点击打开线程视图按钮
    const openThreadBtn = dialog.getByTitle('打开此 Agent 线程视图')
    await expect(openThreadBtn).toBeVisible()
    await openThreadBtn.click()

    // 4. URL query 更新为 ?issue=...&thread=...
    await expect(page).toHaveURL(new RegExp(`issue=${ISSUE_ID}`))
    await expect(page).toHaveURL(new RegExp(`thread=${VALID_THREAD_ID}`))

    // 5. Dock 展开并展示 Agent 徽标与已绑定的 Thread
    const dock = page.locator('[data-testid="project-agent-dock"]')
    await expect(dock).toBeVisible()
    await expect(dock.locator('.badge-agent')).toHaveText('architect')
    await expect(dock.getByRole('heading', { name: 'Architecture Thread' })).toBeVisible()
    await expect(dock.getByText('梳理鉴权模块的现有边界')).toBeVisible()

    // 6. 通用输入：消息走 per-thread 命令批次，不再变成 Issue INSTRUCTION
    const composer = dock.getByLabel('给 AI 发送消息')
    await expect(composer).toBeVisible()
    await composer.click()
    await composer.fill('Refactor auth module schema')
    await dock.getByRole('button', { name: '发送消息' }).click()

    await expect.poll(() => commandBatches.length).toBe(1)
    const messageBatch = commandBatches[0]!
    expect(Object.keys(messageBatch).sort()).toEqual([
      'commands', 'expectedHeadEntryId', 'expectedNextCommandSequence',
    ])
    expect(messageBatch.expectedHeadEntryId).toBe('e1')
    expect(messageBatch.expectedNextCommandSequence).toBe('1')
    expect(messageBatch.commands).toEqual([
      expect.objectContaining({
        type: 'USER_MESSAGE',
        contents: [{ type: 'TEXT', text: 'Refactor auth module schema' }],
      }),
    ])
    expect(issueActivities).toEqual([])
    expect(dock.getByRole('button', { name: '预览请求' })).toHaveCount(0)
    await page.screenshot({ path: resolve(reportsDir, 'project-agent-owner-free.png') })

    // 7. Goal：容器不再封锁 Goal 面板，设置目标同样走 owner-free 命令批次
    await composer.click()
    await composer.fill('/goal')
    const palette = dock.locator('.thread-command-palette')
    await expect(palette).toBeVisible()
    await palette.locator('button', { hasText: 'goal' }).click()
    const goalPanel = dock.getByRole('region', { name: '目标' })
    await expect(goalPanel).toBeVisible()
    await goalPanel.locator('#goal-input').fill('交付可回滚的迁移方案')
    await goalPanel.getByRole('button', { name: '设置目标' }).click()

    await expect.poll(() => commandBatches.length).toBe(2)
    const goalBatch = commandBatches[1]!
    expect(goalBatch).not.toHaveProperty('owner')
    expect(goalBatch).not.toHaveProperty('target')
    expect(goalBatch.commands).toEqual([
      expect.objectContaining({ type: 'GOAL', text: '交付可回滚的迁移方案' }),
    ])

    // 8. Stop：走 per-thread Stop（不再 stopIssue），并把未消费输入退回可见草稿
    await composer.click()
    await composer.fill('/stop')
    await palette.locator('button', { hasText: 'stop' }).click()

    await expect.poll(() => stopRequests.length).toBe(1)
    expect(Object.keys(stopRequests[0] ?? {}).sort()).toEqual(['expectedVersion', 'stopRequestId'])
    expect(stopRequests[0]?.expectedVersion).toBe('1')
    expect(issueStops).toEqual([])
    // 回退到编辑区的是原始人类输入本身，绝不是 durable payload JSON 转储。
    await expect(composer).toHaveText(cancelledText)
    await expect(composer).not.toContainText('"contents"')
    await page.screenshot({ path: resolve(reportsDir, 'project-agent-stop-restores-draft.png') })

    // 9. 关闭 Agent 视图
    const closeBtn = dock.getByLabel('关闭 Agent 视图')
    await closeBtn.click()
    await expect(dock).toHaveCount(0)
  })

  test('gatekeeping security: blocks invalid thread and prevents AgentPane mount', async ({
    page,
  }) => {
    // 测试意图：真实浏览器中直接携带非法 thread 访问项目页面，门禁拦截并展示警告横幅，绝不挂载 AgentPane
    await page.route(
      (url) => new URL(url).pathname.startsWith('/api/'),
      async (route) => {
        const url = route.request().url()
      if (url.includes(`/projects/${PROJECT_ID}/snapshot`)) {
        await route.fulfill({ json: createProjectSnapshot() })
        return
      }
      if (url.includes(`/issues/${ISSUE_ID}`)) {
        await route.fulfill({ json: createIssueDetail() })
        return
      }
      await route.fulfill({ json: { status: 200, data: {} } })
    })

    await page.goto(
      `/browser-tests/project-agent-harness.html?issue=${ISSUE_ID}&thread=${ILLEGAL_THREAD_ID}`,
    )

    // 验证展示安全拒绝告警
    const errorBanner = page.getByText(
      '目标 Thread 不属于该 Issue 绑定的 Agent 线程或 Run 记录，已拒绝接入',
    )
    await expect(errorBanner).toBeVisible()

    // 验证严禁挂载 Agent 输入框
    const composer = page.getByLabel('给 AI 发送消息')
    await expect(composer).toHaveCount(0)
  })
})
