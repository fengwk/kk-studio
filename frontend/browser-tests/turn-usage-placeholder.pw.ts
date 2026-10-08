import { expect, test } from './fixture'

test('failed turn usage placeholder preserves error cards and TURN_END fork', async ({ page }, testInfo) => {
  await page.setViewportSize({ width: 1280, height: 800 })
  await page.goto('/browser-tests/chat-layout-harness.html?scenario=turn-usage')

  const timeline = page.getByTestId('turn-usage-timeline')
  const placeholder = timeline.locator('[data-turn-end="end-no-usage"]')
  const text = placeholder.locator('.thread-meta-text')
  await expect(text).toHaveText('-')
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
  await expect(text).toHaveText('-')
  await expect(errors.first()).toBeVisible()
  await page.screenshot({ path: testInfo.outputPath('failed-turn-usage-readonly.png'), fullPage: true })
})
