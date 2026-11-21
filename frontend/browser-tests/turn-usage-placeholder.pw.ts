import { join } from 'node:path'
import { expect, test } from './fixture'

/**
 * 失败回合的用量与模型失败卡：
 * - 无用量 footer 只显示本地化的「无用量」并保留分叉入口，不留下孤立占位符；
 * - 模型失败卡主文案是失败类别 + 供应商 error.message，HTTP 状态只是辅助信息，
 *   原始响应默认折叠且可复制。
 */

const TURN_USAGE_SCENARIO = '/browser-tests/chat-layout-harness.html?scenario=turn-usage'
const MODEL_FAILURE_SCENARIO = '/browser-tests/chat-layout-harness.html?scenario=model-failure'

/** 审查环境 mock 返回的真实 HTTP 400 响应体，逐字保留。 */
const REVIEW_RESPONSE_BODY =
  '{"error":{"message":"mock: maximum context length exceeded","type":"invalid_request_error","code":"context_length_exceeded"}}'
const REVIEW_RAW_RESPONSE = `HTTP 400\n${REVIEW_RESPONSE_BODY}`

// 复制用例需要读回真实剪贴板内容。
test.use({ permissions: ['clipboard-read', 'clipboard-write'] })

test('failed turn usage placeholder preserves error cards and TURN_END fork', async ({ page }, testInfo) => {
  await page.setViewportSize({ width: 1280, height: 800 })
  await page.goto(TURN_USAGE_SCENARIO)

  const timeline = page.getByTestId('turn-usage-timeline')
  const placeholder = timeline.locator('[data-turn-end="end-no-usage"]')
  const text = placeholder.locator('.thread-meta-text')
  await expect(text).toHaveText('无用量')
  await expect(text).not.toHaveAttribute('title')
  await expect(timeline).not.toContainText('FAILED')
  const errors = timeline.locator('.thread-block-model-attempt-failure-error')
  await expect(errors).toHaveCount(2)
  await expect(errors.first()).toContainText('离线失败：no-usage')
  await expect(errors.first()).toBeVisible()

  const fork = placeholder.getByTestId('thread-turn-end-branch')
  await expect(fork).toBeEnabled()
  await fork.click()
  await expect(page.getByTestId('fork-entry-id')).toHaveText('end-no-usage')

  const realUsage = timeline.locator('[data-turn-end="end-with-usage"] .thread-meta-text')
  await expect(realUsage).toContainText('↑10')
  await expect(realUsage).toContainText('↓20')
  await expect(realUsage).toHaveAttribute('title', /10 tokens/)
  await page.screenshot({ path: testInfo.outputPath('failed-turn-usage-desktop.png'), fullPage: true })

  // 先改为另一个真实分支目标，确保键盘激活确实再次调用分支，而非读取上次点击的残留。
  await timeline.locator('[data-turn-end="end-with-usage"]')
    .getByTestId('thread-turn-end-branch').click()
  await expect(page.getByTestId('fork-entry-id')).toHaveText('end-with-usage')
  await page.setViewportSize({ width: 375, height: 812 })
  await expect(text).toBeVisible()
  await expect(fork).toBeVisible()
  await fork.focus()
  await page.keyboard.press('Enter')
  await expect(page.getByTestId('fork-entry-id')).toHaveText('end-no-usage')
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(375)
  await page.screenshot({ path: testInfo.outputPath('failed-turn-usage-mobile.png'), fullPage: true })

  await page.getByRole('button', { name: '只读子代理', exact: true }).click()
  await expect(timeline.getByTestId('thread-turn-end-branch')).toHaveCount(0)
  await expect(text).toHaveText('无用量')
  await expect(errors.first()).toBeVisible()
  await page.screenshot({ path: testInfo.outputPath('failed-turn-usage-readonly.png'), fullPage: true })
})

test('empty assistant stays one inline row with its real turn usage and branch', async ({ page }, testInfo) => {
  // 离线 durable Entry 经真实投影和 CSS，验证窄屏也不出现第二行或内部标题。
  await page.goto(TURN_USAGE_SCENARIO)
  for (const width of [1280, 375]) {
    await page.setViewportSize({ width, height: 800 })
    const empty = page.locator('[data-entry-kind="empty_message"]')
    await expect(empty).toHaveCount(1)
    await expect(empty).toHaveClass(/is-inline/)
    await expect(empty).toHaveText('返回内容为空')
    await expect(empty.locator('.thread-entry-text, button')).toHaveCount(0)
    const title = empty.locator('.thread-entry-title')
    await expect(title).toHaveCount(1)
    const metrics = await title.evaluate((element) => {
      const style = getComputedStyle(element)
      return { whiteSpace: style.whiteSpace, height: element.getBoundingClientRect().height, lineHeight: parseFloat(style.lineHeight) }
    })
    expect(metrics.whiteSpace).toBe('nowrap')
    expect(metrics.height).toBeLessThanOrEqual(metrics.lineHeight + 1)
    const usage = page.locator('[data-turn-end="end-empty"]')
    await expect(usage.locator('.thread-meta-text')).toHaveText('↑10 · ↓0 · R0 · W0 · — · cache 0.00% · 0 tok/s')
    await usage.getByTestId('thread-turn-end-branch').click()
    await expect(page.getByTestId('fork-entry-id')).toHaveText('end-empty')
    await empty.scrollIntoViewIfNeeded()
    await page.screenshot({ path: testInfo.outputPath(`empty-assistant-${width}.png`) })
  }
})

test('model failure card summarizes the provider error instead of showing raw JSON', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 })
  await page.goto(MODEL_FAILURE_SCENARIO)

  const timeline = page.getByTestId('model-failure-timeline')
  const card = timeline.locator('.thread-turn-model-attempt-failure')
  await expect(card).toHaveCount(1)
  await expect(timeline).not.toContainText('FAILED')

  // 主文案：失败类别 + 供应商 error.message；HTTP 状态与错误码只是辅助信息。
  await expect(card.locator('.thread-attempt-failure-kind')).toHaveText('上下文超出模型限制')
  await expect(card.locator('.thread-attempt-failure-detail')).toHaveText('mock: maximum context length exceeded')
  await expect(card.locator('.thread-attempt-failure-http')).toHaveText('HTTP 400')
  await expect(card.locator('.thread-attempt-failure-headline code')).toHaveText('CONTEXT_LIMIT')

  // 原始响应默认折叠：正文与原文都不可见，且没有孤立占位用量。
  const details = card.locator('details.thread-attempt-failure-raw')
  await expect(details).not.toHaveAttribute('open')
  await expect(details.locator('.thread-error-raw')).toBeHidden()
  await expect(details.locator('summary')).toHaveText('原始响应')
  await expect(timeline.locator('.thread-meta-text')).toHaveText('无用量')

  // 展开后原文（含状态行与 JSON）逐字可见。
  await details.locator('summary').click()
  await expect(details).toHaveAttribute('open')
  await expect(details.locator('.thread-error-raw')).toBeVisible()
  await expect(details.locator('.thread-error-raw')).toHaveText(REVIEW_RAW_RESPONSE)
})

test('model failure raw response is copyable from the expanded detail', async ({ page }) => {
  await page.goto(MODEL_FAILURE_SCENARIO)

  const card = page.getByTestId('model-failure-timeline').locator('.thread-turn-model-attempt-failure')
  const details = card.locator('details.thread-attempt-failure-raw')
  await details.locator('summary').click()
  const copy = details.getByRole('button', { name: '复制原始响应' })
  await expect(copy).toBeVisible()
  await copy.click()
  await expect.poll(() => page.evaluate(() => navigator.clipboard.readText())).toBe(REVIEW_RAW_RESPONSE)
})

test('model failure card keeps the summary and copy in English', async ({ page }) => {
  await page.goto(`${MODEL_FAILURE_SCENARIO}&locale=en-US`)

  const card = page.getByTestId('model-failure-timeline').locator('.thread-turn-model-attempt-failure')
  await expect(card.locator('.thread-attempt-failure-kind')).toHaveText('Context exceeds the model limit')
  await expect(card.locator('.thread-attempt-failure-detail')).toHaveText('mock: maximum context length exceeded')
  await expect(card.locator('.thread-attempt-failure-raw > summary')).toHaveText('Raw response')
  await expect(page.locator('.thread-meta-text')).toHaveText('No usage')
})

// 可选自查截图：设置 KK_THREAD_CARDS_SHOTS=<目录> 时才运行（默认无副作用）。
const SHOTS_DIR = process.env.KK_THREAD_CARDS_SHOTS ?? ''

test.describe('model failure acceptance screenshots', () => {
  test.skip(SHOTS_DIR === '', 'set KK_THREAD_CARDS_SHOTS=<dir> to capture model failure screenshots')

  for (const locale of ['zh-CN', 'en-US']) {
    for (const viewport of [{ width: 1440, height: 900 }, { width: 390, height: 844 }]) {
      test(`captures ${locale} at ${viewport.width}x${viewport.height}`, async ({ page }) => {
        await page.setViewportSize(viewport)
        await page.goto(`${MODEL_FAILURE_SCENARIO}&locale=${locale}`)
        const card = page.getByTestId('model-failure-timeline').locator('.thread-turn-model-attempt-failure')
        await expect(card).toBeVisible()
        const prefix = `model-failure-${locale}-${viewport.width}x${viewport.height}`
        await page.screenshot({ path: join(SHOTS_DIR, `${prefix}-collapsed.png`), fullPage: true })
        await card.locator('details.thread-attempt-failure-raw > summary').click()
        await expect(card.locator('.thread-error-raw')).toBeVisible()
        await page.screenshot({ path: join(SHOTS_DIR, `${prefix}-expanded.png`), fullPage: true })
        expect(await page.evaluate(() => document.documentElement.scrollWidth))
          .toBeLessThanOrEqual(viewport.width)
      })
    }
  }
})
