import type { Route, WebSocketRoute } from '@playwright/test'
import type { HarnessSessionEntryDTO, HarnessThreadSnapshotDTO } from '@/shared/api/generated/contracts'
import { expect, test } from './fixture'

const HARNESS_URL = '/browser-tests/compaction-lifecycle-harness.html'
const CHILD_ID = '00000000-0000-4000-8000-000000000010'
const CUT_ID = '00000000-0000-4000-8000-000000000011'
const ROOT_ID = '00000000-0000-4000-8000-000000000001'
const SESSION_ID = '00000000-0000-4000-8000-000000000099'

function entry(
  entryId: string,
  entryType: HarnessSessionEntryDTO['entryType'],
  payload: HarnessSessionEntryDTO['payload'],
  parentEntryId: string | null,
  usageCost: { currency: string; amount: string } | null = null,
): HarnessSessionEntryDTO {
  return {
    entryId,
    sessionId: SESSION_ID,
    parentEntryId,
    entryType,
    payload,
    createdAt: 1767225600000,
    usageCost,
  }
}

/** 全量快照保留切点及到达分支末尾的完整 Entry 链。 */
function branchSnapshot(stopped = false, updated = false): HarnessThreadSnapshotDTO {
  return {
    version: stopped ? '8' : '7',
    baseEntryId: null,
    thread: {
      threadId: CHILD_ID,
      parentThreadId: ROOT_ID,
      name: 'main',
      sessionId: SESSION_ID,
      headEntryId: stopped ? '00000000-0000-4000-8000-000000000018' : '00000000-0000-4000-8000-000000000017',
      rootThreadId: ROOT_ID,
      yoloEnabled: false,
      nextCommandSequence: '3',
      version: stopped ? '8' : '7',
      status: stopped ? 'IDLE' : 'RUNNING',
      executionControl: stopped ? 'STOPPED' : 'RUNNABLE',
      branchSettings: {
        agentName: 'coder',
        execution: { driver: 'builtin', settings: { modelName: 'gpt', variant: 'high' } },
        environmentName: null,
      },
      createdAt: 1767225600000,
      updatedAt: 1767225600000,
    },
    entries: [
      entry('00000000-0000-4000-8000-000000000012', 'TURN_START', { reason: 'INPUT' }, null),
      entry('00000000-0000-4000-8000-000000000013', 'MESSAGE', {
        message: { role: 'USER', contents: [{ type: 'text', text: 'Earlier context' }] },
      }, '00000000-0000-4000-8000-000000000012'),
      entry(CUT_ID, 'TURN_END', {
        turnStartEntryId: '00000000-0000-4000-8000-000000000012',
        outcome: 'COMPLETED',
        continueModel: true,
      }, '00000000-0000-4000-8000-000000000013'),
      entry('00000000-0000-4000-8000-000000000014', 'FORK', {
        mode: 'BRANCH',
        sourceEntryId: CUT_ID,
        sourceThreadId: null,
      }, CUT_ID),
      entry('00000000-0000-4000-8000-00000000000a', 'TURN_START', { reason: 'INPUT' },
        '00000000-0000-4000-8000-000000000014'),
      entry('00000000-0000-4000-8000-000000000015', 'CUSTOM_MESSAGE', {
        message: {
          role: 'USER',
          contents: [{ type: 'text', text: '[Compaction mode]\nSummarize the conversation.' }],
        },
      }, '00000000-0000-4000-8000-00000000000a'),
      entry('00000000-0000-4000-8000-000000000016', 'MESSAGE', {
        message: { role: 'ASSISTANT', contents: [{ type: 'text', text: 'draft' }] },
        assistantMetadata: {
          stopReason: 'COMPLETE',
          usage: {
            inputTokens: 100,
            outputTokens: 20,
            cacheReadTokens: 40,
            cacheWriteTokens: 10,
            reasoningTokens: 5,
            providerTotalTokens: 120,
          },
        },
      }, '00000000-0000-4000-8000-000000000015', { currency: 'USD', amount: '0.0042' }),
      entry('00000000-0000-4000-8000-000000000017', 'MESSAGE', {
        message: {
          role: 'TOOL',
          contents: [{
            type: 'tool_result',
            toolCallId: 'branch-call-tool',
            toolName: 'read',
            rendererKey: 'read',
            error: true,
            contents: [{ type: 'text', text: 'Tools are disabled in compaction mode.' }],
          }],
        },
      }, '00000000-0000-4000-8000-000000000016'),
      ...(stopped ? [entry('00000000-0000-4000-8000-000000000018', 'TURN_END', {
        turnStartEntryId: '00000000-0000-4000-8000-00000000000a',
        outcome: 'STOPPED',
        continueModel: false,
        reason: 'USER_STOP',
      }, '00000000-0000-4000-8000-000000000017')] : []),
    ],
    queuedCommands: [],
    activeAssistantOutput: stopped ? null : {
      generationId: '00000000-0000-4000-8000-000000000019',
      turnStartEntryId: '00000000-0000-4000-8000-00000000000a',
      requestHeadEntryId: '00000000-0000-4000-8000-000000000017',
      status: 'RUNNING',
      attempt: 1,
      sequence: updated ? '2' : '1',
      contents: [{ type: 'text', text: updated ? '生成中的摘要：已收到快照更新' : '生成中的摘要' }],
      error: null,
      resultEntryId: null,
      createdAt: 1767225600000,
      updatedAt: 1767225600000,
    },
    generationFailures: [],
    toolInvocations: [],
    stopReceipts: [],
    unconfirmedExecutions: [],
  }
}

test('shows every compaction lifecycle state and opens the execution view', async ({ page }) => {
  const stopRequests: Array<{ path: string; body: unknown }> = []
  const snapshotReads: Array<string | null> = []
  let stopped = false
  let updated = false
  let pushSnapshot: (() => void) | undefined
  const sendThreadSnapshot = (socket: WebSocketRoute) => socket.send(JSON.stringify({
    type: 'event',
    resource: { kind: 'thread', id: CHILD_ID },
    name: 'snapshot',
    data: branchSnapshot(stopped, updated),
  }))
  await page.routeWebSocket(/\/ws\/events$/, (socket) => {
    socket.send(JSON.stringify({
      type: 'connected',
      eventNodeId: 'a1000000-0000-4000-8000-0000000000e1',
      connectionId: 'a1000000-0000-4000-8000-0000000000e2',
      heartbeatIntervalMillis: '60000',
    }))
    socket.onMessage((message) => {
      const frame = JSON.parse(String(message))
      if (frame.type === 'subscribe') {
        socket.send(JSON.stringify({ type: 'subscribed', resource: frame.resource, cursor: '0' }))
        if (frame.resource.kind === 'thread' && frame.resource.id === CHILD_ID) {
          pushSnapshot = () => sendThreadSnapshot(socket)
          pushSnapshot()
        }
      } else if (frame.type === 'refresh' && frame.resource.kind === 'thread'
        && frame.resource.id === CHILD_ID) {
        sendThreadSnapshot(socket)
      }
    })
  })
  await page.route((url) => url.pathname.startsWith('/api/'), async (route: Route) => {
    const url = new URL(route.request().url())
    if (url.pathname === `/api/harness/threads/${CHILD_ID}` && route.request().method() === 'GET') {
      snapshotReads.push(url.searchParams.get('afterEntryId'))
      await route.fulfill({ json: branchSnapshot(stopped) })
      return
    }
    if (url.pathname === `/api/harness/threads/${CHILD_ID}/stop` && route.request().method() === 'POST') {
      stopped = true
      stopRequests.push({ path: url.pathname, body: route.request().postDataJSON() })
      await route.fulfill({
        json: {
          status: 'STOPPED',
          thread: branchSnapshot(true).thread,
          stoppedThreads: [],
        },
      })
      return
    }
    await route.fulfill({ status: 404, json: { title: 'not mocked' } })
  })

  await page.setViewportSize({ width: 1440, height: 1200 })
  await page.goto(HARNESS_URL)

  const cards = page.locator('[data-entry-kind="compaction"]')
  await expect(cards).toHaveCount(4)
  await expect(cards.nth(0)).toHaveAttribute('data-compaction-state', 'compacted')
  await expect(cards.nth(1)).toHaveAttribute('data-compaction-state', 'failed')
  await expect(cards.nth(2)).toHaveAttribute('data-compaction-state', 'stopped')
  await expect(cards.nth(3)).toHaveAttribute('data-compaction-state', 'running')

  const compactedCard = cards.nth(0)
  await expect(compactedCard).toContainText('上下文已压缩')
  await expect(compactedCard.locator('.thread-disclosure-preview')).toHaveCount(0)
  await compactedCard.getByRole('button', { name: '展开详情' }).click()
  await expect(compactedCard.locator('.thread-disclosure-preview')).toContainText('会话摘要')
  await compactedCard.getByRole('button', { name: '收起详情' }).click()

  const failedCard = cards.nth(1)
  await expect(failedCard).toContainText('上下文压缩失败')
  await expect(failedCard).toContainText('摘要因输出上限被截断。')
  await expect(failedCard.getByRole('link', { name: '查看执行' }))
    .toHaveAttribute('href', '/threads/00000000-0000-4000-8000-000000000032?afterEntryId=00000000-0000-4000-8000-00000000000a')
  await expect(failedCard.getByRole('button')).toHaveCount(0)

  const stoppedCard = cards.nth(2)
  await expect(stoppedCard).toContainText('上下文压缩已停止')
  await expect(stoppedCard).toContainText('未生成摘要即被停止。')
  await expect(stoppedCard.getByRole('button')).toHaveCount(0)

  await expect(cards.nth(3)).toContainText('正在压缩上下文…')
  await expect(cards.nth(3)).toContainText('正在整理以上内容。')

  // 首条保留消息之前只有一条分隔线，说明以上内容已压缩为摘要。
  const separator = page.locator('.thread-compaction-separator')
  await expect(separator).toHaveCount(1)
  await expect(separator).toHaveText('以上内容已压缩为摘要')
  const retained = page.getByText('保留的最近消息').first()
  await expect(retained).toBeVisible()
  await expect.poll(async () => {
    const separatorBox = await separator.boundingBox()
    const retainedBox = await retained.boundingBox()
    return separatorBox != null && retainedBox != null && separatorBox.y < retainedBox.y
  }).toBe(true)

  await page.screenshot({ path: test.info().outputPath('compaction-lifecycle-desktop.png'), fullPage: true })

  // 实时快照在不改 Thread version 时也必须刷新助手正文。
  await cards.nth(3).getByRole('link', { name: '查看执行' }).click()
  const branch = page.getByTestId('compaction-branch-view')
  const transcript = branch.getByRole('log')
  await expect(transcript.getByText('生成中的摘要', { exact: true })).toBeVisible()
  await expect(branch.getByText('正在生成摘要…')).toBeVisible()
  await expect(branch.getByRole('textbox')).toHaveCount(0)
  await expect(transcript).not.toContainText('Earlier context')
  await expect(transcript).not.toContainText('[Compaction mode]')
  await expect(transcript).not.toContainText('Tools are disabled')
  await expect.poll(() => pushSnapshot != null).toBe(true)
  updated = true
  pushSnapshot?.()
  await expect(transcript.getByText('生成中的摘要：已收到快照更新')).toBeVisible()
  await page.screenshot({ path: test.info().outputPath('compaction-branch-streaming-desktop.png') })

  await expect(page.locator('.compaction-branch-instruction')).toBeHidden()
  await page.getByText('诊断', { exact: true }).click()
  await expect(page.getByTestId('compaction-branch-inherited')).toBeVisible()
  await expect(page.getByTestId('compaction-branch-inherited')).toHaveText('继承的上下文：150 tokens')
  await expect(page.locator('.compaction-branch-instruction')).toContainText('[Compaction mode]')
  await expect(page.locator('.compaction-branch-call')).toHaveCount(1)
  await expect(page.locator('.compaction-branch-call')).toContainText('↑100')
  await expect(page.locator('.compaction-branch-call')).toContainText('$0.0042')
  await expect(page.locator('.compaction-branch-tool-error')).toContainText('read')
  await expect(page.locator('.compaction-branch-tool-error')).toContainText('Tools are disabled in compaction mode.')
  expect(snapshotReads).toEqual([null])

  const stop = page.getByRole('button', { name: '停止' })
  await expect(stop).toBeVisible()
  await stop.click()
  await expect.poll(() => stopRequests.length).toBe(1)
  expect(stopRequests[0]?.body).toMatchObject({ expectedVersion: '7' })
  expect(stopRequests[0]?.path).toBe(`/api/harness/threads/${CHILD_ID}/stop`)
  await expect(stop).toHaveCount(0)
  await expect(branch.getByRole('status')).toContainText('上下文压缩已停止 · 候选摘要')
  await expect(transcript.getByText('draft', { exact: true })).toBeVisible()
  await expect(transcript).not.toContainText('生成中的摘要')
  expect(snapshotReads).toEqual([null])

  // 移动端宽度：卡片与分支视图都保持可读，不出现横向溢出。
  await page.setViewportSize({ width: 390, height: 844 })
  await page.screenshot({ path: test.info().outputPath('compaction-branch-mobile.png'), fullPage: true })
  await expect(page.locator('.compaction-branch-view')).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth))
    .toBeLessThanOrEqual(await page.evaluate(() => window.innerWidth))

  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto(HARNESS_URL)
  await expect(page.locator('[data-entry-kind="compaction"]')).toHaveCount(4)
  await page.screenshot({ path: test.info().outputPath('compaction-lifecycle-mobile.png'), fullPage: true })
  expect(await page.evaluate(() => document.documentElement.scrollWidth))
    .toBeLessThanOrEqual(await page.evaluate(() => window.innerWidth))
})
