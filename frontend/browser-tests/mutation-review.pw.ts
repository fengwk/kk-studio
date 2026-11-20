import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { expect, test, type Locator, type Page } from './fixture'
import { LONG_WRITE_CONTENT, MULTI_HUNK_DIFF } from './mutation-review-fixtures'

const __filename = fileURLToPath(import.meta.url)
const __dirname = path.dirname(__filename)
const REPORTS_DIR = process.env.KK_MUTATION_REPORTS_DIR ?? path.resolve(__dirname, '../../reports/mutation-review')
const HARNESS_URL = '/browser-tests/mutation-review-harness.html'

/** 时间线短窗口：write/edit 固定尾部 10 个逻辑行，与执行阶段无关。 */
const PREVIEW_LINES = 10
const WRITE_LINES = LONG_WRITE_CONTENT.replace(/\n$/, '').split('\n')
const WRITE_TAIL = WRITE_LINES.slice(-PREVIEW_LINES).join('\n')
const EDIT_LINES = MULTI_HUNK_DIFF.split('\n')
const EDIT_TAIL = EDIT_LINES.slice(-PREVIEW_LINES).join('\n')
/** 与 harness 一致的后端冻结 write 审查 diff（新增文件，sha256 为 null）。 */
const WRITE_REVIEW_DIFF = LONG_WRITE_CONTENT.split('\n').slice(0, -1)
  .map((line, index) => `+${index + 1}|${line}`).join('\n')

type HarnessApi = {
  renderWritePending(): void
  renderWriteSuccess(): void
  renderEditStreaming(): void
  renderEditStreamingMultiEntry(): void
  renderEditPreparing(): void
  renderEditReviewed(): void
  renderEditConflict(): void
  renderEditSuccess(): void
  renderMutationFailure(tool?: 'write' | 'edit'): void
  renderApprovalMissingReview(): void
  renderApprovalMalformedReview(): void
  renderApprovalValid(): void
  renderEditDurableHistory(): void
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

interface ElementMetrics {
  clientHeight: number
  scrollHeight: number
  clientWidth: number
  scrollWidth: number
  bottom: number
  paneBottom: number
}

function elementMetrics(locator: Locator): Promise<ElementMetrics> {
  return locator.evaluate((el) => {
    const pane = el.closest('.mutation-pane, .mutation-approval-pane')
    const rect = el.getBoundingClientRect()
    return {
      clientHeight: el.clientHeight,
      scrollHeight: el.scrollHeight,
      clientWidth: el.clientWidth,
      scrollWidth: el.scrollWidth,
      bottom: rect.bottom,
      paneBottom: pane == null ? rect.bottom : pane.getBoundingClientRect().bottom,
    }
  })
}

/**
 * 短窗口是普通正文：不创建内部纵向滚动，也不靠外层裁切隐藏尾部。
 * 横向由 `white-space: pre` 自行溢出，不折成多行撑高卡片。
 */
async function expectPreviewWindow(locator: Locator): Promise<void> {
  const metrics = await elementMetrics(locator)
  expect(metrics.scrollHeight).toBeLessThanOrEqual(metrics.clientHeight + 1)
}

/** 详情与审批材料必须有界滚动，且不能由 flex 收缩或外层裁切伪装成有界视口。 */
async function expectBoundedSurface(locator: Locator): Promise<void> {
  const metrics = await elementMetrics(locator)
  expect(metrics.scrollHeight).toBeGreaterThan(metrics.clientHeight)
  expect(metrics.bottom).toBeLessThanOrEqual(metrics.paneBottom + 1)
  expect(metrics.scrollWidth).toBeLessThanOrEqual(metrics.clientWidth + 1)
}

/** 有界容器自身声明（overflow 与 max-height），不依赖内容高度。 */
async function expectBoundedContainer(locator: Locator): Promise<void> {
  const style = await locator.evaluate((el) => {
    const computed = getComputedStyle(el)
    return { overflowY: computed.overflowY, maxHeight: computed.maxHeight }
  })
  expect(style.overflowY).toBe('auto')
  expect(style.maxHeight).not.toBe('none')
}

/** 详情区自身有界滚动；内部正文不裁剪、不叠第二层滚动条。 */
async function expectBoundedDetails(locator: Locator): Promise<void> {
  await expectBoundedContainer(locator)
  const metrics = await elementMetrics(locator)
  expect(metrics.scrollHeight).toBeGreaterThan(metrics.clientHeight)
  expect(metrics.bottom).toBeLessThanOrEqual(metrics.paneBottom + 1)
  expect(metrics.scrollWidth).toBeLessThanOrEqual(metrics.clientWidth + 1)
}

/** 详情里的完整正文是普通内容：自身不再裁剪。 */
async function expectNoInnerCrop(locator: Locator): Promise<void> {
  const metrics = await elementMetrics(locator)
  expect(metrics.scrollHeight).toBeLessThanOrEqual(metrics.clientHeight + 1)
  expect(metrics.scrollWidth).toBeLessThanOrEqual(metrics.clientWidth + 1)
}

test.beforeAll(() => {
  fs.mkdirSync(REPORTS_DIR, { recursive: true })
})

test.beforeEach(async ({ page }) => {
  // 独立审批卡不订阅资源，但仍使用真实事件 Provider。
  await page.routeWebSocket(/\/ws\/events$/, (ws) => {
    ws.send(JSON.stringify({
      type: 'connected',
      eventNodeId: 'a1000000-0000-4000-8000-0000000000e1',
      connectionId: 'a1000000-0000-4000-8000-0000000000e2',
      heartbeatIntervalMillis: '60000',
    }))
  })
  await page.goto(HARNESS_URL)
  await expect(page.locator('[data-testid="active-scenario"]')).toBeVisible()
})

test.describe('Mutation Review UI Acceptance & Browser Regression', () => {
  test('Case 1: 时间线 write 短窗口固定尾部 10 行、earlier 计数在上方、白空格 pre；chevron 只追加有界完整详情', async ({
    page,
  }) => {
    await page.setViewportSize({ width: 1280, height: 800 })
    await harness(page, 'renderWritePending')
    await expect(page.locator('[data-testid="active-scenario"]')).toHaveText('write-pending')

    const toolPane = page.locator('[data-testid="tool-message-pane"]')
    const previewPane = toolPane.locator('.thread-disclosure-preview')
    const preview = previewPane.locator('.thread-tool-preview-body.is-write')
    const hint = previewPane.locator('.thread-disclosure-hint')
    const toggle = toolPane.locator('.thread-disclosure-toggle')
    const details = toolPane.locator('.thread-disclosure-body .thread-tool-details')

    // Header 完整展示目标与工作目录；预览不重复路径行。
    await expect(toolPane.locator('.thread-tool-summary-detail')).toHaveText(
      '/workspace/src/LongService.java in /workspace/root',
    )
    await expect(toolPane.locator('.thread-tool-preview-target-path')).toHaveCount(0)

    // 预览只有尾部 10 行：前 38 行（含长缩进行）不在短窗口里。
    await expect(preview).toBeVisible()
    expect(await preview.textContent()).toBe(WRITE_TAIL)
    await expect(preview).not.toContainText('longMethodWithIndent')
    await expect(preview).not.toContainText('line-1 with code and spaces')
    // 白空格 pre：不折行、不吞缩进，横向溢出由容器自己滚动。
    await expect(preview).toHaveCSS('white-space', 'pre')
    await expect(preview).toHaveCSS('overflow-x', 'auto')
    await expectPreviewWindow(preview)

    // 省略行数在预览上方，且不是第二个展开入口（单一入口是 Header chevron）。
    await expect(hint).toHaveText('… 上方还有 38 行')
    await expect(previewPane.locator('button.thread-disclosure-hint')).toHaveCount(0)
    const hintBox = await hint.boundingBox()
    const previewBox = await preview.boundingBox()
    expect(hintBox!.y).toBeLessThan(previewBox!.y)

    // 收起态没有详情区；chevron 用真实操作名称暴露展开能力。
    await expect(details).toHaveCount(0)
    await expect(toggle).toHaveAttribute('aria-label', '展开详情')
    await expect(toggle).toHaveAttribute('aria-expanded', 'false')
    const previewHeightBefore = previewBox!.height

    await toggle.click()
    await expect(toggle).toHaveAttribute('aria-label', '收起详情')
    await expect(toggle).toHaveAttribute('aria-expanded', 'true')

    // 详情是有界、可聚焦的只读区域，承载完整待写正文。
    const detailsRegion = toolPane.getByRole('region', { name: '完整详情' })
    await expect(detailsRegion).toBeVisible()
    await expectBoundedContainer(detailsRegion)
    await detailsRegion.focus()
    await expect(detailsRegion).toBeFocused()
    const detailsContent = detailsRegion.locator('.thread-tool-preview-body.is-write')
    expect(await detailsContent.textContent()).toBe(LONG_WRITE_CONTENT)
    await expect(detailsContent).toHaveCSS('white-space', 'pre-wrap')
    await expect(detailsContent).toContainText('public void longMethodWithIndent() {')
    await expect(detailsContent).toContainText('line-48 with code and spaces')
    await expectBoundedDetails(detailsRegion)
    await expectNoInnerCrop(detailsContent)

    // 展开不改变预览配额：仍是同一段尾部 10 行与同一提示。
    expect(await preview.textContent()).toBe(WRITE_TAIL)
    await expect(hint).toHaveText('… 上方还有 38 行')
    const previewHeightAfter = (await preview.boundingBox())!.height
    expect(Math.abs(previewHeightAfter - previewHeightBefore)).toBeLessThanOrEqual(1)

    await page.screenshot({ path: path.join(REPORTS_DIR, 'desktop-write-tail-details.png') })

    // 再次点击恢复收起态，预览依旧不变。
    await toggle.click()
    await expect(details).toHaveCount(0)
    await expect(toggle).toHaveAttribute('aria-label', '展开详情')
    expect(await preview.textContent()).toBe(WRITE_TAIL)

    // 窄屏：短窗口不折行撑高；详情在卡片内纵向滚动，长行折行而不横向撑宽。
    await page.setViewportSize({ width: 390, height: 800 })
    await expectPreviewWindow(preview)
    expect(await preview.textContent()).toBe(WRITE_TAIL)
    await toggle.click()
    await expect(detailsRegion).toBeVisible()
    await expectBoundedDetails(detailsRegion)
    const contentBox = await page.locator('.mutation-harness-content').boundingBox()
    expect(contentBox?.width).toBeLessThanOrEqual(390)

    await page.screenshot({ path: path.join(REPORTS_DIR, 'mobile-write-tail-details.png') })
  })

  test('Case 2: write 审批默认有界展示完整待写正文，原生「审查 Diff」摘要展开后端冻结 diff', async ({
    page,
  }) => {
    await page.setViewportSize({ width: 1280, height: 800 })
    await harness(page, 'renderWritePending')
    await expect(page.locator('[data-testid="active-scenario"]')).toHaveText('write-pending')

    const approvalPane = page.locator('[data-testid="approval-card-pane"]')
    await expect(approvalPane).toBeVisible()
    await expect(approvalPane.locator('.thread-tool-preview-target-path')).toHaveText(
      '/workspace/src/LongService.java',
    )
    const allowBtn = approvalPane.getByRole('button', { name: '允许' })
    const denyBtn = approvalPane.getByRole('button', { name: '拒绝' })
    await expect(allowBtn).toBeEnabled()
    await expect(denyBtn).toBeEnabled()

    // 默认看完整待写正文（保证审批材料不是从流式前缀推出来的）。
    const writeBody = approvalPane.locator('.thread-tool-preview-body.is-write')
    await expect(writeBody).toBeVisible()
    expect(await writeBody.textContent()).toBe(LONG_WRITE_CONTENT)
    await expect(writeBody).toHaveCSS('white-space', 'pre-wrap')
    await expectBoundedSurface(writeBody)

    // 后端冻结的审查 diff 折叠在原生 details + summary 中，默认不占用审批卡高度。
    const reviewDiff = approvalPane.locator('.thread-tool-preview-body.is-edit')
    await expect(reviewDiff).toBeHidden()
    const reviewSummary = approvalPane.locator('details summary', { hasText: '审查 Diff' })
    await expect(reviewSummary).toHaveCount(1)
    await reviewSummary.click()
    await expect(reviewDiff).toBeVisible()
    expect(await reviewDiff.textContent()).toBe(WRITE_REVIEW_DIFF)
    await expect(reviewDiff).toHaveCSS('white-space', 'pre-wrap')
    await expectBoundedSurface(reviewDiff)
    // 展开 diff 后完整正文仍在，审批材料两段互不替代。
    await expect(writeBody).toBeVisible()

    await page.screenshot({ path: path.join(REPORTS_DIR, 'desktop-write-approval-body-diff.png') })

    // 窄屏审批材料仍不撑宽卡片。
    await page.setViewportSize({ width: 390, height: 800 })
    await expectBoundedSurface(writeBody)
    await expectBoundedSurface(reviewDiff)
    const contentBox = await page.locator('.mutation-harness-content').boundingBox()
    expect(contentBox?.width).toBeLessThanOrEqual(390)

    await page.screenshot({ path: path.join(REPORTS_DIR, 'mobile-write-approval-body-diff.png') })
  })

  test('Case 3: edit 审批保持有界完整 diff，不出现 write 正文或「审查 Diff」折叠', async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 800 })
    await harness(page, 'renderEditReviewed')
    await expect(page.locator('[data-testid="active-scenario"]')).toHaveText('edit-reviewed')

    const approvalPane = page.locator('[data-testid="approval-card-pane"]')
    await expect(approvalPane.locator('.thread-tool-preview-target-path')).toHaveText(
      '/canonical/src/App.tsx',
    )
    await expect(approvalPane.getByRole('button', { name: '允许' })).toBeEnabled()
    await expect(approvalPane.getByRole('button', { name: '拒绝' })).toBeEnabled()

    const approvalDiff = approvalPane.locator('.thread-tool-preview-body.is-edit')
    await expect(approvalDiff).toBeVisible()
    expect(await approvalDiff.textContent()).toBe(MULTI_HUNK_DIFF)
    await expect(approvalDiff).toHaveCSS('white-space', 'pre-wrap')
    await expectBoundedSurface(approvalDiff)
    // edit 审批材料是完整权威 diff，不折叠、也不混入 write 正文。
    await expect(approvalPane.locator('details')).toHaveCount(0)
    await expect(approvalPane.locator('.thread-tool-preview-body.is-write')).toHaveCount(0)

    await page.screenshot({ path: path.join(REPORTS_DIR, 'desktop-edit-approval-unchanged.png') })
  })

  test('Case 4: edit 生命周期（流式拟修改内容 -> 准备态 -> 冻结多 hunk）与时间线尾部窗口', async ({
    page,
  }) => {
    await page.setViewportSize({ width: 1280, height: 800 })

    const toolPane = page.locator('[data-testid="tool-message-pane"]')
    const previewPane = toolPane.locator('.thread-disclosure-preview')

    // 阶段 A: 流式只展示新替换文本，标注为「拟修改内容」，不伪造 diff。
    await harness(page, 'renderEditStreaming')
    await expect(page.locator('[data-testid="active-scenario"]')).toHaveText('edit-streaming')
    await expect(previewPane.locator('.thread-tool-preview-input-label')).toHaveText('拟修改内容')
    await expect(previewPane.locator('.thread-tool-preview-body.is-input-old')).toHaveCount(0)
    await expect(previewPane.locator('.thread-tool-preview-body.is-input-new')).toHaveText(
      'const newVal = 2;',
    )
    await expect(toolPane.locator('.thread-tool-preview-body.is-edit')).toHaveCount(0)
    await expect(toolPane.locator('.is-added, .is-removed')).toHaveCount(0)

    // 旧文本只在手动打开的详情里出现。
    await toolPane.locator('.thread-disclosure-toggle').click()
    const details = toolPane.locator('.thread-tool-details')
    await expect(details.locator('.thread-tool-preview-input-label')).toHaveText('拟修改内容')
    await expect(details.locator('.thread-tool-preview-body.is-input-old')).toHaveText(
      'const oldVal = 1;',
    )
    await expect(details.locator('.thread-tool-preview-body.is-input-new')).toHaveText(
      'const newVal = 2;',
    )

    await page.screenshot({ path: path.join(REPORTS_DIR, 'desktop-edit-streaming.png') })

    // 阶段 A2: 多条目共享同一短窗口，保留逐条编号与 replace_all 徽标；只渲染已到达的新文本。
    await harness(page, 'renderEditStreamingMultiEntry')
    await expect(page.locator('[data-testid="active-scenario"]')).toHaveText('edit-streaming-multi')
    await expect(toolPane.locator('.thread-tool-summary-detail')).toHaveText(
      '/src/App.tsx [3 changes, replace_all]',
    )
    await expect(previewPane.locator('.thread-tool-preview-input-label')).toHaveText('拟修改内容')
    const previewBlocks = previewPane.locator('.thread-tool-preview-input-block')
    await expect(previewBlocks).toHaveCount(2)
    await expect(previewBlocks.nth(0).locator('.thread-tool-preview-input-heading'))
      .toContainText('第 1/3 处修改')
    await expect(previewBlocks.nth(0).locator('.thread-tool-preview-badge')).toHaveText('替换全部')
    await expect(previewBlocks.nth(1).locator('.thread-tool-preview-input-heading'))
      .toContainText('第 2/3 处修改')
    await expect(previewBlocks.nth(1).locator('.thread-tool-preview-badge')).toHaveCount(0)
    await expect(previewBlocks.nth(1).locator('.is-input-new')).toHaveText('const b = 200;')
    await expect(previewPane.locator('.thread-tool-preview-body.is-input-old')).toHaveCount(0)
    await expect(previewPane).not.toContainText('const a = 1;')
    await expect(previewPane).not.toContainText('const c = 3;')
    await expect(toolPane.locator('.is-added, .is-removed')).toHaveCount(0)
    const inputOverflow = await previewPane.locator('.thread-tool-preview-input-section').evaluate((el) => ({
      clientWidth: el.clientWidth,
      scrollWidth: el.scrollWidth,
    }))
    expect(inputOverflow.scrollWidth).toBeLessThanOrEqual(inputOverflow.clientWidth + 1)

    // 详情补全三条：尚未到达 new_string 的条目只在这里出现原始文本。
    await toolPane.locator('.thread-disclosure-toggle').click()
    const detailBlocks = toolPane.locator('.thread-tool-details .thread-tool-preview-input-block')
    await expect(detailBlocks).toHaveCount(3)
    await expect(detailBlocks.nth(0).locator('.thread-tool-preview-badge')).toHaveText('替换全部')
    await expect(detailBlocks.nth(2).locator('.thread-tool-preview-input-heading'))
      .toContainText('第 3/3 处修改')
    await expect(detailBlocks.nth(2).locator('.is-input-old')).toHaveText('const c = 3;')
    await expect(detailBlocks.nth(2).locator('.is-input-new')).toHaveCount(0)

    await page.screenshot({ path: path.join(REPORTS_DIR, 'desktop-edit-streaming-multi.png') })

    // 阶段 B: 参数已完整但还没有权威审查，仍预览拟替换文本而非伪造 diff。
    await harness(page, 'renderEditPreparing')
    await expect(page.locator('[data-testid="active-scenario"]')).toHaveText('edit-preparing')
    await expect(previewPane.locator('.thread-tool-preview-preparing')).toContainText(
      '正在准备审查 Diff…',
    )
    await expect(previewPane.locator('.thread-tool-preview-input-label')).toHaveText('拟修改内容')
    await expect(previewPane.locator('.is-input-new')).toHaveText('const a = 100;')
    await expect(previewPane.locator('.is-edit, .is-input-old')).toHaveCount(0)
    await expect(toolPane.locator('.thread-tool-details')).toHaveCount(0)

    await page.screenshot({ path: path.join(REPORTS_DIR, 'desktop-edit-preparing.png') })

    // 阶段 C: 后端冻结多 hunk diff 到达，时间线仍是尾部 10 行，详情才是完整 diff。
    await harness(page, 'renderEditReviewed')
    await expect(page.locator('[data-testid="active-scenario"]')).toHaveText('edit-reviewed')
    await expect(toolPane.locator('.thread-tool-summary-detail')).toContainText('/src/App.tsx')
    await expect(toolPane.locator('.thread-tool-preview-target-path')).toHaveCount(0)
    const editPreview = previewPane.locator('.thread-tool-preview-body.is-edit')
    await expect(editPreview).toBeVisible()
    expect(await editPreview.textContent()).toBe(EDIT_TAIL)
    await expect(editPreview).toHaveCSS('white-space', 'pre')
    await expectPreviewWindow(editPreview)
    await expect(previewPane.locator('.thread-disclosure-hint')).toHaveText('… 上方还有 17 行')
    // 尾部窗口保留权威 hunk 行标记，但不含更早的 hunk 头。
    await expect(editPreview.locator('.is-removed')).toHaveCount(1)
    await expect(editPreview.locator('.is-removed').first()).toContainText('-81|  return <div>old text</div>')
    await expect(editPreview.locator('.is-added')).toHaveCount(1)
    await expect(editPreview.locator('.is-added').first()).toContainText('+81|  return <div>new updated text</div>')
    await expect(editPreview.locator('.is-hunk-header')).toHaveCount(0)
    await expect(toolPane.locator('.thread-tool-preview-input-label')).toHaveCount(0)

    await toolPane.locator('.thread-disclosure-toggle').click()
    const fullDiff = toolPane.locator('.thread-tool-details .thread-tool-preview-body.is-edit')
    expect(await fullDiff.textContent()).toBe(MULTI_HUNK_DIFF)
    await expect(fullDiff.locator('.is-hunk-header')).toHaveCount(3)
    await expect(fullDiff.locator('.is-added')).toHaveCount(5)
    await expect(fullDiff.locator('.is-removed')).toHaveCount(5)
    await expectBoundedDetails(toolPane.locator('.thread-tool-details'))
    await expectNoInnerCrop(fullDiff)
    expect(await editPreview.textContent()).toBe(EDIT_TAIL)

    await page.screenshot({ path: path.join(REPORTS_DIR, 'desktop-edit-reviewed.png') })

    // 阶段 D: 审批快照与终态结果详情冲突时，严格优先展示审批快照。
    await harness(page, 'renderEditConflict')
    await expect(page.locator('[data-testid="active-scenario"]')).toHaveText('edit-conflict')
    const conflictPreview = previewPane.locator('.thread-tool-preview-body.is-edit')
    expect(await conflictPreview.textContent()).toBe('-approved line\n+approved line')
    await expect(conflictPreview).not.toContainText('conflicting result line')
    await expect(previewPane.locator('.thread-disclosure-hint')).toHaveCount(0)
    await expect(toolPane.locator('.thread-tool-details')).toHaveCount(0)
    await expect(toolPane.locator('.thread-disclosure-toggle')).toHaveCount(0)

    // 移动视口 390px：时间线短窗口不造内部滚动，审批材料仍有界且不撑宽。
    await page.setViewportSize({ width: 390, height: 800 })
    await harness(page, 'renderEditReviewed')
    await expect(page.locator('[data-testid="active-scenario"]')).toHaveText('edit-reviewed')
    await expectPreviewWindow(previewPane.locator('.thread-tool-preview-body.is-edit'))
    expect(await previewPane.locator('.thread-tool-preview-body.is-edit').textContent()).toBe(EDIT_TAIL)
    const approvalEditDiff = page.locator(
      '[data-testid="approval-card-pane"] .thread-tool-preview-body.is-edit',
    )
    await expectBoundedSurface(approvalEditDiff)
    expect(await approvalEditDiff.textContent()).toBe(MULTI_HUNK_DIFF)
    await page.screenshot({ path: path.join(REPORTS_DIR, 'mobile-edit-reviewed.png') })
  })

  test('Case 5: 缺少或非法 editReview 严格禁用 ALLOW，DENY 可点并走真实同源 route mock', async ({
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
    await expect(approvalPane.locator('.interaction-error-banner')).toHaveText(
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

    const approvalUrl = '**/api/harness/threads/thread-1/tool-invocations/inv-valid-review/approval'
    await page.route(approvalUrl, (route) => route.fulfill({ json: {} }))
    const submitted = page.waitForRequest(approvalUrl)
    await denyBtn.click()
    const request = await submitted
    expect(request.method()).toBe('PUT')
    expect(request.postDataJSON()).toEqual({
      decision: 'DENY',
      decisionId: expect.stringMatching(/^[0-9a-f-]{36}$/),
      reason: null,
    })
    await expect(denyBtn).toBeEnabled()
    await expect(approvalPane.locator('.interaction-error-banner')).toHaveCount(0)

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
        route.fulfill({ status: 200, contentType: 'application/json', body: '{}' }),
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

  test('Case 6: write/edit 成功终态短窗口与审批态一致，完整正文或 diff 只出现在详情中', async ({
    page,
  }) => {
    await page.setViewportSize({ width: 1280, height: 800 })

    const toolPane = page.locator('[data-testid="tool-message-pane"]')
    const previewPane = toolPane.locator('.thread-disclosure-preview')
    const toggle = toolPane.locator('.thread-disclosure-toggle')

    // 审批态与终态的短窗口使用同一规则。
    await harness(page, 'renderWritePending')
    await expect(page.locator('[data-testid="active-scenario"]')).toHaveText('write-pending')
    const pendingPreview = previewPane.locator('.thread-tool-preview-body.is-write')
    await expect(pendingPreview).toBeVisible()
    const pendingTail = await pendingPreview.textContent()
    expect(pendingTail).toBe(WRITE_TAIL)

    await harness(page, 'renderWriteSuccess')
    await expect(page.locator('[data-testid="active-scenario"]')).toHaveText('write-success')
    const writePreview = previewPane.locator('.thread-tool-preview-body.is-write')
    await expect(writePreview).toBeVisible()
    expect(await writePreview.textContent()).toBe(WRITE_TAIL)
    expect(pendingTail).toBe(WRITE_TAIL)
    await expect(previewPane.locator('.thread-disclosure-hint')).toHaveText('… 上方还有 38 行')
    // 逐行新增不播报，状态文案也不冒充成功图标。
    await expect(toolPane.locator('.thread-tool-mutation-success')).toHaveCount(0)
    // 结果正文里的重复全量内容不进入卡片。
    await expect(toolPane).not.toContainText('File /workspace/src/LongService.java written successfully:')
    await expect(toolPane.locator('.thread-tool-preview-body.is-write')).toHaveCount(1)

    await toggle.click()
    await expect(toolPane.locator('.thread-tool-preview-body.is-write')).toHaveCount(2)
    const writeDetails = toolPane.locator('.thread-tool-details .thread-tool-preview-body.is-write')
    expect(await writeDetails.textContent()).toBe(LONG_WRITE_CONTENT)
    expect(await writePreview.textContent()).toBe(WRITE_TAIL)

    await page.screenshot({ path: path.join(REPORTS_DIR, 'desktop-write-success.png') })

    // Edit 成功终态
    await harness(page, 'renderEditSuccess')
    await expect(page.locator('[data-testid="active-scenario"]')).toHaveText('edit-success')
    const editPreview = previewPane.locator('.thread-tool-preview-body.is-edit')
    await expect(editPreview).toBeVisible()
    expect(await editPreview.textContent()).toBe(EDIT_TAIL)
    await expect(previewPane.locator('.thread-disclosure-hint')).toHaveText('… 上方还有 17 行')
    await expect(toolPane.locator('.thread-tool-mutation-success')).toHaveCount(0)
    await expect(toolPane).not.toContainText('File edit executed successfully:')

    await toggle.click()
    const editDetails = toolPane.locator('.thread-tool-details .thread-tool-preview-body.is-edit')
    expect(await editDetails.textContent()).toBe(MULTI_HUNK_DIFF)
    expect(await editPreview.textContent()).toBe(EDIT_TAIL)

    await page.screenshot({ path: path.join(REPORTS_DIR, 'desktop-edit-success.png') })
  })

  test('Case 7: 真实失败不被成功文案隐藏，错误在独立错误槽位展示且不造空预览', async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 800 })

    await harness(page, 'renderMutationFailure', 'edit')
    await expect(page.locator('[data-testid="active-scenario"]')).toHaveText('mutation-failure')

    const toolPane = page.locator('[data-testid="tool-message-pane"]')
    await expect(toolPane.locator('.thread-disclosure-error-text')).toHaveText(
      'Permission denied: /protected/config.ts',
    )
    // 没有可见预览就没有预览段、分割线和详情入口。
    await expect(toolPane.locator('.thread-disclosure-preview')).toHaveCount(0)
    await expect(toolPane.locator('.thread-tool-details')).toHaveCount(0)
    await expect(toolPane.locator('.thread-disclosure-toggle')).toHaveCount(0)
    await expect(toolPane.locator('.thread-tool-mutation-success')).toHaveCount(0)
    await expect(toolPane).not.toContainText('编辑成功')
    await expect(toolPane).not.toContainText('写入成功')

    await page.screenshot({ path: path.join(REPORTS_DIR, 'mutation-failure-notice.png') })
  })

  test('Case 8: durable 历史恢复同样按尾部 10 行截取，详情补全完整 diff', async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 800 })
    await harness(page, 'renderEditDurableHistory')
    await expect(page.locator('[data-testid="active-scenario"]')).toHaveText(
      'edit-durable-history',
    )

    const toolPane = page.locator('[data-testid="tool-message-pane"]')
    const previewPane = toolPane.locator('.thread-disclosure-preview')
    await expect(toolPane.locator('.thread-tool-summary-detail')).toContainText('/src/App.tsx')
    await expect(toolPane.locator('.thread-tool-preview-target-path')).toHaveCount(0)
    const editPreview = previewPane.locator('.thread-tool-preview-body.is-edit')
    await expect(editPreview).toBeVisible()
    expect(await editPreview.textContent()).toBe(EDIT_TAIL)
    await expect(previewPane.locator('.thread-disclosure-hint')).toHaveText('… 上方还有 17 行')
    // 结果摘要已由预览承载，不再重复输出。
    await expect(toolPane).not.toContainText('Edited successfully.')

    await toolPane.locator('.thread-disclosure-toggle').click()
    const fullDiff = toolPane.locator('.thread-tool-details .thread-tool-preview-body.is-edit')
    expect(await fullDiff.textContent()).toBe(MULTI_HUNK_DIFF)
    await expectBoundedDetails(toolPane.locator('.thread-tool-details'))
    await expectNoInnerCrop(fullDiff)
    expect(await editPreview.textContent()).toBe(EDIT_TAIL)
    await expectPreviewWindow(editPreview)

    await page.screenshot({ path: path.join(REPORTS_DIR, 'desktop-edit-durable-history.png') })
  })
})
