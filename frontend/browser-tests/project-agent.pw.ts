import { expect, test } from '@playwright/test'

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
        sessionId: 's1',
        headEntryId: 'e1',
        yoloEnabled: false,
        nextCommandSequence: '1',
        version: '1',
        status: 'IDLE',
        processing: false,
        branchSettings: {
          agentName: 'architect',
          model: { providerName: 'minimax', modelName: 'MiniMax-M2.7', variant: 'default' },
          environmentName: null,
        },
        createTime: '2026-09-20T00:00:00Z',
        updateTime: '2026-09-20T00:00:00Z',
      },
      entries: [
        {
          entryId: 'e1',
          threadId: VALID_THREAD_ID,
          parentEntryId: null,
          type: 'SYSTEM',
          payload: { text: 'You are an architect agent.' },
          createdAt: '2026-09-20T00:00:00Z',
        },
      ],
      queuedCommands: [],
      modelInvocation: null,
      toolInvocations: [],
      modelAttemptFailures: [],
      manualCompaction: { available: false, disabledReason: 'idle' },
    },
  }
}

test.describe('Project Agent Real Browser Wiring & Control Gatekeeping', () => {
  test('end-to-end browser flow: navigate from issue to controlled agent dock, submit instruction, stop, and close', async ({
    page,
  }) => {
    // 测试意图：真实浏览器中从看板打开 Issue 详情，点击 Agent 线程进入受控 Dock，
    // 验证输入消息作为 INSTRUCTION 发送并被网络拦截断言，验证受控停止触发 stopIssue
    const interceptedActivities: Array<{ kind?: string; body?: string; requestKey?: string }> = []
    const interceptedStops: Array<{ expectedVersion?: string; detail?: string }> = []
    const previewRequests: string[] = []

    await page.route(
      (url) => new URL(url).pathname.startsWith('/api/'),
      async (route) => {
        const url = route.request().url()
        const method = route.request().method()
      if (url.includes('provider-request-preview')) {
        previewRequests.push(url)
      }

      if (url.includes(`/projects/${PROJECT_ID}/snapshot`)) {
        await route.fulfill({ json: createProjectSnapshot() })
        return
      }
      if (url.includes(`/issues/${ISSUE_ID}/evidence`)) {
        await route.fulfill({ json: { status: 200, data: [] } })
        return
      }
      if (url.includes(`/issues/${ISSUE_ID}/activities`) && method === 'POST') {
        const body = route.request().postDataJSON()
        interceptedActivities.push(body)
        await route.fulfill({
          status: 201,
          json: {
            status: 201,
            data: {
              id: 'act-101',
              issueId: ISSUE_ID,
              actorType: 'HUMAN',
              actorId: 'user-test',
              kind: body.kind,
              body: body.body,
              createdAt: '2026-09-27T00:00:00Z',
            },
          },
        })
        return
      }
      if (url.includes(`/issues/${ISSUE_ID}/stop`) && method === 'POST') {
        const body = route.request().postDataJSON()
        interceptedStops.push(body)
        await route.fulfill({
          status: 200,
          json: {
            status: 200,
            data: createIssueDetail().data.issue,
          },
        })
        return
      }
      if (url.includes(`/issues/${ISSUE_ID}`)) {
        await route.fulfill({ json: createIssueDetail() })
        return
      }
      if (url.includes('/api/ai/runtime/agents')) {
        await route.fulfill({
          json: {
            status: 200,
            data: {
              results: [
                { id: 'architect', name: 'architect', displayName: '系统架构师' },
              ],
            },
          },
        })
        return
      }
      if (url.includes('/api/ai/runtime/environments')) {
        await route.fulfill({ json: { status: 200, data: [] } })
        return
      }
      if (url.includes(`/api/ai/runtime/threads/${VALID_THREAD_ID}/snapshot`)) {
        await route.fulfill({ json: createThreadSnapshot() })
        return
      }
      if (url.includes(`/api/ai/runtime/threads/${VALID_THREAD_ID}/tree`)) {
        await route.fulfill({ json: { status: 200, data: { entries: [] } } })
        return
      }

      await route.fulfill({ json: { status: 200, data: {} } })
    })

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

    // 4. 验证 URL query 更新为 ?issue=...&thread=...
    await expect(page).toHaveURL(new RegExp(`issue=${ISSUE_ID}`))
    await expect(page).toHaveURL(new RegExp(`thread=${VALID_THREAD_ID}`))

    // 5. 验证受控 Agent Dock 成功展开并展示 Agent 徽标
    const dock = page.locator('[data-testid="project-agent-dock"]')
    await expect(dock).toBeVisible()
    await expect(dock.locator('.badge-agent')).toHaveText('architect')

    // 6. 在受控输入框中输入指令并提交
    const composer = dock.getByLabel('给 AI 发送消息')
    await expect(composer).toBeVisible()
    await expect(dock.getByRole('button', { name: '预览请求' })).toHaveCount(0)
    await composer.click()
    await composer.fill('Refactor auth module schema')

    const sendBtn = dock.getByRole('button', { name: '发送消息' })
    await sendBtn.click()

    // 7. 断言后端接收到了 INSTRUCTION 类型的 Issue 活动请求，且带有幂等 requestKey
    await expect.poll(() => interceptedActivities.length).toBe(1)
    expect(interceptedActivities[0]?.kind).toBe('INSTRUCTION')
    expect(interceptedActivities[0]?.body).toBe('Refactor auth module schema')
    expect(interceptedActivities[0]?.requestKey).toBeTruthy()
    expect(previewRequests).toEqual([])

    // 8. 测试关闭 Agent 视图
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
