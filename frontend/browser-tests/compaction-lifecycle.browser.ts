import type { Route } from '@playwright/test'
import { expect, test } from './fixture'

const HARNESS_URL = '/browser-tests/compaction-lifecycle-harness.html'
const CHILD_ID = '00000000-0000-4000-8000-000000000010'
const CUT_ID = '00000000-0000-4000-8000-000000000011'
const ROOT_ID = '00000000-0000-4000-8000-000000000001'

function entry(
  entryId: string,
  entryType: string,
  payload: unknown,
  usageCost: { currency: string; amount: string } | null = null,
): unknown {
  return {
    entryId,
    sessionId: 'session-1',
    parentEntryId: null,
    entryType,
    payload,
    createdAt: 1767225600000,
    usageCost,
  }
}

/** 分支快照只包含 FORK 之后的行；读取游标由 afterEntryId 提供。 */
function branchSnapshot() {
  return {
    version: '7',
    baseEntryId: CUT_ID,
    thread: {
      threadId: CHILD_ID,
      parentThreadId: ROOT_ID,
      name: 'main',
      sessionId: 'session-1',
      headEntryId: 'branch-end',
      rootThreadId: ROOT_ID,
      yoloEnabled: false,
      nextCommandSequence: '3',
      version: '7',
      status: 'RUNNING',
      executionControl: 'RUNNABLE',
      branchSettings: {
        agentName: 'coder',
        execution: { driver: 'builtin', settings: { modelName: 'gpt', variant: 'high' } },
        environmentName: null,
      },
      createdAt: 1767225600000,
      updatedAt: 1767225600000,
    },
    entries: [
      entry('branch-fork', 'FORK', {
        mode: 'BRANCH',
        sourceEntryId: CUT_ID,
        sourceThreadId: null,
      }),
      entry('branch-instruction', 'CUSTOM_MESSAGE', {
        message: {
          role: 'USER',
          contents: [{ type: 'text', text: '[Compaction mode]\nSummarize the conversation.' }],
        },
      }),
      entry('branch-call', 'MESSAGE', {
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
      }, { currency: 'USD', amount: '0.0042' }),
      entry('branch-tool', 'MESSAGE', {
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
      }),
      entry('branch-end', 'TURN_END', {
        outcome: 'FAILED',
        continueModel: false,
        reason: 'TURN_FAILED',
      }),
    ],
    queuedCommands: [],
    activeAssistantOutput: null,
    generationFailures: [],
    toolInvocations: [],
    stopReceipts: [],
    unexecutedCleanupItems: [],
    unconfirmedExecutions: [],
  }
}

test('shows every compaction lifecycle state and opens the execution view', async ({ page }) => {
  const stopRequests: Array<{ path: string; body: unknown }> = []
  const snapshotReads: string[] = []
  await page.route((url) => url.pathname.startsWith('/api/'), async (route: Route) => {
    const url = new URL(route.request().url())
    if (url.pathname === `/api/harness/threads/${CHILD_ID}` && route.request().method() === 'GET') {
      snapshotReads.push(url.searchParams.get('afterEntryId') ?? '')
      await route.fulfill({ json: branchSnapshot() })
      return
    }
    if (url.pathname === `/api/harness/threads/${CHILD_ID}/stop`) {
      stopRequests.push({ path: url.pathname, body: route.request().postDataJSON() })
      await route.fulfill({
        json: {
          status: 'STOPPED',
          thread: branchSnapshot().thread,
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

  await expect(cards.nth(0)).toContainText('上下文已压缩')
  await expect(cards.nth(1)).toContainText('上下文压缩失败')
  await expect(cards.nth(1)).toContainText('摘要因输出上限被截断。')
  await expect(cards.nth(2)).toContainText('上下文压缩已停止')
  await expect(cards.nth(2)).toContainText('未生成摘要即被停止。')
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

  // 失败卡提供重新压缩；动作由宿主注入，点击后有一次真实提交。
  const failedCard = page.locator('[data-compaction-state="failed"]')
  await failedCard.getByRole('button', { name: '重新压缩' }).click()
  await expect(page.getByTestId('retry-count')).toHaveText('1')

  await page.screenshot({ path: test.info().outputPath('compaction-lifecycle-desktop.png'), fullPage: true })

  // 打开分支执行视图：只按切点之后的 Entry 读取，展示继承量、指令、调用用量与停用工具结果。
  await failedCard.getByRole('link', { name: '查看执行' }).click()
  await expect(page.getByTestId('compaction-branch-view')).toBeVisible()
  await expect(page.getByTestId('compaction-branch-inherited')).toHaveText('继承的上下文：150 tokens')
  await expect(page.locator('.compaction-branch-instruction')).toContainText('[Compaction mode]')
  await expect(page.locator('.compaction-branch-call')).toHaveCount(1)
  await expect(page.locator('.compaction-branch-call')).toContainText('↑100')
  await expect(page.locator('.compaction-branch-call')).toContainText('$0.0042')
  await expect(page.locator('.compaction-branch-tool-error')).toContainText('read')
  await expect(page.locator('.compaction-branch-tool-error')).toContainText('Tools are disabled in compaction mode.')
  expect(snapshotReads.length).toBeGreaterThanOrEqual(1)
  expect(new Set(snapshotReads)).toEqual(new Set([CUT_ID]))

  const stop = page.getByRole('button', { name: '停止' })
  await expect(stop).toBeVisible()
  await stop.click()
  await expect.poll(() => stopRequests.length).toBe(1)
  expect(stopRequests[0]?.body).toMatchObject({ expectedVersion: '7' })

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
