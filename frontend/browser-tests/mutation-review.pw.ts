import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { expect, test, type Locator, type Page } from './fixture'
import { LONG_WRITE_CONTENT, MULTI_HUNK_DIFF } from './mutation-review-fixtures'

const __filename = fileURLToPath(import.meta.url)
const __dirname = path.dirname(__filename)
const REPORTS_DIR = process.env.KK_MUTATION_REPORTS_DIR ?? path.resolve(__dirname, '../../reports/mutation-review')
const HARNESS_URL = '/browser-tests/mutation-review-harness.html'

type HarnessApi = {
  renderWritePending(): void
  renderWriteSuccess(): void
  renderEditStreaming(): void
  renderEditPreparing(): void
  renderEditReviewed(): void
  renderEditConflict(): void
  renderEditSuccess(): void
  renderMutationFailure(tool?: 'write' | 'edit'): void
  renderApprovalMissingReview(): void
  renderApprovalMalformedReview(): void
  renderApprovalValid(): void
}

function harness(page: Page, action: keyof HarnessApi, arg?: 'write' | 'edit'): Promise<void> {
  return page.evaluate(
    ([act, param]) => {
      const api = window.mutationReviewHarness
      api[act as keyof HarnessApi](param as 'write' | 'edit' | undefined)
    },
    [action, arg] as const,
  )
}

async function verifyBoundedScrollAndNoBlowout(locator: Locator): Promise<void> {
  const metrics = await locator.evaluate((el) => {
    const pane = el.closest('.mutation-pane, .mutation-approval-pane')!
    return {
      clientHeight: el.clientHeight,
      scrollHeight: el.scrollHeight,
      clientWidth: el.clientWidth,
      scrollWidth: el.scrollWidth,
      bottom: el.getBoundingClientRect().bottom,
      paneBottom: pane.getBoundingClientRect().bottom,
    }
  })

  expect(metrics.scrollHeight).toBeGreaterThan(metrics.clientHeight)
  // 不能由 flex 收缩和外层裁切伪装成有界视口，否则滚到底仍读不到末尾。
  expect(metrics.bottom).toBeLessThanOrEqual(metrics.paneBottom)
  // 横向无溢出（允许 1px 亚像素抖动）
  expect(metrics.scrollWidth).toBeLessThanOrEqual(metrics.clientWidth + 1)
}

test.beforeAll(() => {
  fs.mkdirSync(REPORTS_DIR, { recursive: true })
})

test.beforeEach(async ({ page }) => {
  await page.goto(HARNESS_URL)
  await expect(page.locator('[data-testid="active-scenario"]')).toBeVisible()
})

test.describe('Mutation Review UI Acceptance & Browser Regression', () => {
  test('Case 1: Write 全文（含缩进与空白、超高滚动）审批前完整且默认展开，390px 与 1280px 有界滚动不撑宽 pane', async ({
    page,
  }) => {
    // 1. 桌面视口 1280px
    await page.setViewportSize({ width: 1280, height: 800 })
    await harness(page, 'renderWritePending')
    await expect(page.locator('[data-testid="active-scenario"]')).toHaveText('write-pending')

    const toolPane = page.locator('[data-testid="tool-message-pane"]')
    await expect(toolPane).toBeVisible()

    // 目标路径与工作目录完整展示
    await expect(toolPane.locator('.thread-tool-preview-target-path')).toHaveText(
      '/workspace/src/LongService.java',
    )
    await expect(toolPane.locator('.thread-tool-preview-target-workdir')).toHaveText(
      '(/workspace/root)',
    )

    // 正文默认展开，且 48 行全文无截断
    const writeViewport = toolPane.locator('.thread-tool-preview-body.is-write')
    await expect(writeViewport).toBeVisible()
    await expect(writeViewport).toContainText('public void longMethodWithIndent() {')
    await expect(writeViewport).toContainText('// indented comment')
    await expect(writeViewport).toContainText('line-48 with code and spaces')
    // 逐字核对，避免 toHaveText 默认归一化空白掩盖缩进、空行或末尾换行丢失。
    expect(await writeViewport.textContent()).toBe(LONG_WRITE_CONTENT)
    await expect(writeViewport).toHaveCSS('white-space', 'pre-wrap')

    // 审批理由修复冗余 "： —" 且完成本地化
    const note = toolPane.locator('.thread-tool-approval-note')
    await expect(note).toBeVisible()
    await expect(note).not.toContainText('： —')
    await expect(note).toContainText('权限规则要求审批')

    // 有界滚动且无横向撑宽
    await verifyBoundedScrollAndNoBlowout(writeViewport)

    // 滚动能力验证
    await writeViewport.evaluate((el) => {
      el.scrollTop = 120
    })
    const scrolledTop = await writeViewport.evaluate((el) => el.scrollTop)
    expect(scrolledTop).toBeGreaterThan(0)

    // 同屏审批卡片验证
    const approvalPane = page.locator('[data-testid="approval-card-pane"]')
    await expect(approvalPane).toBeVisible()
    await expect(approvalPane.locator('.thread-tool-preview-target-path')).toHaveText(
      '/workspace/src/LongService.java',
    )
    await expect(approvalPane.getByRole('button', { name: '允许' })).toBeEnabled()
    await expect(approvalPane.getByRole('button', { name: '拒绝' })).toBeEnabled()
    const approvalWriteViewport = approvalPane.locator('.thread-tool-preview-body.is-write')
    expect(await approvalWriteViewport.textContent()).toBe(LONG_WRITE_CONTENT)
    await expect(approvalWriteViewport).toHaveCSS('white-space', 'pre-wrap')
    await verifyBoundedScrollAndNoBlowout(approvalWriteViewport)

    await page.screenshot({ path: path.join(REPORTS_DIR, 'desktop-write-pending.png') })

    // 2. 窄屏移动视口 390px
    await page.setViewportSize({ width: 390, height: 800 })
    await verifyBoundedScrollAndNoBlowout(writeViewport)
    await verifyBoundedScrollAndNoBlowout(approvalWriteViewport)
    expect(await writeViewport.textContent()).toBe(LONG_WRITE_CONTENT)
    expect(await approvalWriteViewport.textContent()).toBe(LONG_WRITE_CONTENT)

    const contentBox = await page.locator('.mutation-harness-content').boundingBox()
    expect(contentBox?.width).toBeLessThanOrEqual(390)

    await page.screenshot({ path: path.join(REPORTS_DIR, 'mobile-write-pending.png') })
  })

  test('Case 2: Edit 生命周期（流式输入 -> 准备态 -> 后端冻结多 hunk diff）及冻结审批快照冲突优先', async ({
    page,
  }) => {
    await page.setViewportSize({ width: 1280, height: 800 })

    // 阶段 A: 流式输入阶段明确标注为输入，不伪造为真实 diff
    await harness(page, 'renderEditStreaming')
    await expect(page.locator('[data-testid="active-scenario"]')).toHaveText('edit-streaming')

    const toolPane = page.locator('[data-testid="tool-message-pane"]')
    await expect(toolPane.locator('.thread-tool-preview-input-label')).toHaveText('流式输入内容（等待审查）')
    await expect(toolPane.locator('.thread-tool-preview-body.is-input-old')).toHaveText(
      'const oldVal = 1;',
    )
    await expect(toolPane.locator('.thread-tool-preview-body.is-input-new')).toHaveText(
      'const newVal = 2;',
    )
    await expect(toolPane.locator('.thread-tool-preview-body.is-edit')).toHaveCount(0)
    await expect(toolPane.locator('.is-added, .is-removed')).toHaveCount(0)

    await page.screenshot({ path: path.join(REPORTS_DIR, 'desktop-edit-streaming.png') })

    // 阶段 B: 参数结束等待 review，展示准备中占位态
    await harness(page, 'renderEditPreparing')
    await expect(page.locator('[data-testid="active-scenario"]')).toHaveText('edit-preparing')
    await expect(toolPane.locator('.thread-tool-preview-preparing')).toContainText(
      '正在准备审查 Diff…',
    )
    await expect(toolPane.locator('.thread-tool-preview-input-label')).toHaveCount(0)

    await page.screenshot({ path: path.join(REPORTS_DIR, 'desktop-edit-preparing.png') })

    // 阶段 C: 后端冻结真实多 hunk diff 到达，展示权威 diff 与行号
    await harness(page, 'renderEditReviewed')
    await expect(page.locator('[data-testid="active-scenario"]')).toHaveText('edit-reviewed')

    await expect(toolPane.locator('.thread-tool-preview-target-path')).toHaveText(
      '/canonical/src/App.tsx',
    )
    const editViewport = toolPane.locator('.thread-tool-preview-body.is-edit')
    await expect(editViewport).toBeVisible()
    expect(await editViewport.textContent()).toBe(MULTI_HUNK_DIFF)
    await expect(editViewport).toHaveCSS('white-space', 'pre-wrap')
    const approvalEditViewport = page.locator(
      '[data-testid="approval-card-pane"] .thread-tool-preview-body.is-edit',
    )
    expect(await approvalEditViewport.textContent()).toBe(MULTI_HUNK_DIFF)

    // 权威 hunk 与增删行 class 断言
    await expect(editViewport.locator('.is-hunk-header').first()).toContainText('@@ -10,6 +10,6 @@')
    await expect(editViewport.locator('.is-removed').first()).toContainText('-11|const a = 1')
    await expect(editViewport.locator('.is-added').first()).toContainText('+11|const a = 100')
    await expect(editViewport.locator('.is-removed').nth(1)).toContainText('-12|const b = 2')
    await expect(editViewport.locator('.is-added').nth(1)).toContainText('+12|const b = 200')
    await expect(editViewport.locator('.is-hunk-header').nth(1)).toContainText('@@ -40,6 +40,6 @@')
    await expect(editViewport.locator('.is-removed').nth(2)).toContainText('-41|  const x = 10')
    await expect(editViewport.locator('.is-added').nth(2)).toContainText('+41|  const x = 1000')
    await expect(toolPane.locator('.thread-tool-preview-input-label')).toHaveCount(0)

    await page.screenshot({ path: path.join(REPORTS_DIR, 'desktop-edit-reviewed.png') })

    // 阶段 D: 审批快照与终态结果详情冲突时，严格优先展示审批快照
    await harness(page, 'renderEditConflict')
    await expect(page.locator('[data-testid="active-scenario"]')).toHaveText('edit-conflict')
    await expect(toolPane.locator('.thread-tool-preview-body.is-edit')).toContainText(
      '-approved line',
    )
    await expect(toolPane.locator('.thread-tool-preview-body.is-edit')).not.toContainText(
      '-conflicting result line',
    )

    // 移动视口 390px 检查
    await page.setViewportSize({ width: 390, height: 800 })
    await harness(page, 'renderEditReviewed')
    await verifyBoundedScrollAndNoBlowout(toolPane.locator('.thread-tool-preview-body.is-edit'))
    await verifyBoundedScrollAndNoBlowout(approvalEditViewport)
    expect(await approvalEditViewport.textContent()).toBe(MULTI_HUNK_DIFF)
    await page.screenshot({ path: path.join(REPORTS_DIR, 'mobile-edit-reviewed.png') })
  })

  test('Case 3: 缺少或非法 editReview 严格禁用 ALLOW，DENY 可点并走真实同源 route mock', async ({
    page,
  }) => {
    await page.setViewportSize({ width: 1280, height: 800 })

    // 1. 缺失 editReview: ALLOW 禁用、DENY 启用、警示文案出现
    await harness(page, 'renderApprovalMissingReview')
    await expect(page.locator('[data-testid="active-scenario"]')).toHaveText(
      'approval-missing-review',
    )

    const approvalPane = page.locator('[data-testid="approval-card-pane"]')
    const allowBtn = approvalPane.getByRole('button', { name: '允许' })
    const denyBtn = approvalPane.getByRole('button', { name: '拒绝' })

    await expect(allowBtn).toBeDisabled()
    await expect(denyBtn).toBeEnabled()
    await expect(approvalPane.locator('.interaction-error-banner')).toContainText(
      '未获取到有效的编辑审查信息，无法允许。',
    )

    await page.screenshot({ path: path.join(REPORTS_DIR, 'approval-missing-review.png') })

    // 2. 非法 sha256 结构: ALLOW 同样严格禁用
    await harness(page, 'renderApprovalMalformedReview')
    await expect(page.locator('[data-testid="active-scenario"]')).toHaveText(
      'approval-malformed-review',
    )
    await expect(allowBtn).toBeDisabled()
    await expect(denyBtn).toBeEnabled()

    await page.screenshot({ path: path.join(REPORTS_DIR, 'approval-malformed-review.png') })

    // 3. 有效 review: ALLOW 与 DENY 皆启用；点击 DENY 验证提交契约。
    await harness(page, 'renderApprovalValid')
    await expect(page.locator('[data-testid="active-scenario"]')).toHaveText('approval-valid')
    await expect(allowBtn).toBeEnabled()
    await expect(denyBtn).toBeEnabled()

    let interceptedDecision: string | null = null
    await page.route('**/api/harness/threads/*/tool-invocations/*/approval', async (route) => {
      const req = route.request()
      expect(req.method()).toBe('PUT')
      const payload = req.postDataJSON()
      interceptedDecision = payload?.decision
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          status: 200,
          data: {
            id: 'inv-valid-review',
            decision: payload?.decision,
          },
        }),
      })
    })

    await denyBtn.click()
    await expect.poll(() => interceptedDecision).toBe('DENY')

    await page.screenshot({ path: path.join(REPORTS_DIR, 'approval-denied-success.png') })
  })

  for (const [action, invocationId] of [
    ['renderApprovalMissingReview', 'inv-missing-review'],
    ['renderApprovalMalformedReview', 'inv-malformed-review'],
  ] as const) {
    test(`缺少有效审查仍可提交拒绝：${action}`, async ({ page }) => {
      await harness(page, action)
      const approvalPane = page.locator('[data-testid="approval-card-pane"]')
      await expect(approvalPane.getByRole('button', { name: '允许' })).toBeDisabled()

      await page.route(`**/api/harness/threads/thread-1/tool-invocations/${invocationId}/approval`, (route) =>
        route.fulfill({ status: 200, contentType: 'application/json', body: '{"status":200,"data":{}}' }),
      )
      const submitted = page.waitForRequest((request) =>
        request.url().endsWith(`/tool-invocations/${invocationId}/approval`),
      )
      await approvalPane.getByRole('button', { name: '拒绝' }).click()
      const request = await submitted
      expect(request.method()).toBe('PUT')
      expect(request.postDataJSON()).toEqual({
        decision: 'DENY',
        decisionId: expect.stringMatching(/^[0-9a-f-]{36}$/),
        reason: null,
      })
      await expect(approvalPane.getByRole('button', { name: '拒绝' })).toBeEnabled()
      await expect(approvalPane.getByRole('button', { name: '允许' })).toBeDisabled()
    })
  }

  test('Case 4: Write 与 Edit 成功终态去重（仅短中文成功文案，正文/diff 仅在 preview 出现一次，不重复 raw JSON/body）', async ({
    page,
  }) => {
    await page.setViewportSize({ width: 1280, height: 800 })

    // Write 成功终态
    await harness(page, 'renderWriteSuccess')
    await expect(page.locator('[data-testid="active-scenario"]')).toHaveText('write-success')

    const toolPane = page.locator('[data-testid="tool-message-pane"]')
    await expect(toolPane.locator('.thread-tool-mutation-success')).toHaveText('写入成功')
    await expect(toolPane.locator('.thread-tool-preview-body.is-write')).toBeVisible()

    // 确认结果正文中未重复输出 rawDuplicateBody
    await expect(toolPane).not.toContainText('File /workspace/src/LongService.java written successfully:')

    await page.screenshot({ path: path.join(REPORTS_DIR, 'desktop-write-success.png') })

    // Edit 成功终态
    await harness(page, 'renderEditSuccess')
    await expect(page.locator('[data-testid="active-scenario"]')).toHaveText('edit-success')

    await expect(toolPane.locator('.thread-tool-mutation-success')).toHaveText('编辑成功')
    await expect(toolPane.locator('.thread-tool-preview-body.is-edit')).toBeVisible()
    await expect(toolPane).not.toContainText('File edit executed successfully:')

    await page.screenshot({ path: path.join(REPORTS_DIR, 'desktop-edit-success.png') })
  })

  test('Case 5: 真实失败不被成功文案隐藏，完整透传并展示错误摘要', async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 800 })

    await harness(page, 'renderMutationFailure', 'edit')
    await expect(page.locator('[data-testid="active-scenario"]')).toHaveText('mutation-failure')

    const toolPane = page.locator('[data-testid="tool-message-pane"]')
    await expect(toolPane).toContainText('Permission denied: /protected/config.ts')
    await expect(toolPane.locator('.thread-tool-mutation-success')).toHaveCount(0)
    await expect(toolPane).not.toContainText('编辑成功')
    await expect(toolPane).not.toContainText('写入成功')

    await page.screenshot({ path: path.join(REPORTS_DIR, 'mutation-failure-notice.png') })
  })
})
