import { test, expect } from '@playwright/test'

for (const width of [900, 360, 320]) {
  test(`shared readonly rows and full history at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 800 })
    await page.goto('/browser-tests/subagent-tree-harness.html')
    const history = page.getByRole('region', { name: '子 Agent 执行' })
    // section with accessible name has implicit region semantics.
    await expect(history.locator('li')).toHaveCount(5)
    await expect(history.locator('[data-depth], .thread-tree-connectors')).toHaveCount(0)
    await expect(history.locator('[data-thread-id="failed"]')).toContainText('失败')
    const deep = history.locator('[data-thread-id="deep"] a')
    await expect(deep).toContainText('轮次：7 · 工具：12')
    await expect(deep).toContainText('运行中')
    const worker = history.locator('[data-thread-id="worker"] a')
    await expect(worker).toContainText('等待异步结果')
    await expect(history.locator('[data-thread-id="stopped"] a')).toContainText('已停止')
    await expect(history.locator('[data-thread-id="root"]')).toHaveCount(0)
    await history.focus()
    await page.keyboard.press('ArrowUp')
    await expect(history.locator('[data-thread-id="worker"] a')).toBeFocused()
    await page.keyboard.press('ArrowDown')
    await expect(deep).toBeFocused()
    await page.keyboard.press('Enter')
    await expect(page.getByLabel('selected')).toHaveText('deep')
    expect(await history.evaluate((element) => getComputedStyle(element).borderTopColor)).toBe('rgba(0, 0, 0, 0)')
    expect(await deep.evaluate((element) => getComputedStyle(element).borderTopWidth)).toBe('0px')
    await history.locator('[data-thread-id="failed"] a').click()
    await expect(page.getByLabel('selected')).toHaveText('failed')
    await expect(history.locator('[data-thread-id="failed"]')).toHaveAttribute('aria-current', 'true')
    const sizes = await history.evaluate((el) => ({ client: el.clientWidth, scroll: el.scrollWidth }))
    expect(sizes.scroll).toBeLessThanOrEqual(sizes.client)
    await page.screenshot({ path: `../reports/subagent-tree/tree-${width}.png`, fullPage: true })
    await page.getByRole('button', { name: 'finish', exact: true }).click()
    await expect(page.getByRole('region', { name: '活跃子 Agent' })).toHaveCount(0)
    await expect(history.locator('li')).toHaveCount(5)
    await expect(deep).toContainText('完成')
  })
}

test('320px shared list truncation and reduced-motion status icons', async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 800 })
  await page.emulateMedia({ reducedMotion: 'no-preference' })
  await page.goto('/browser-tests/subagent-tree-harness.html')

  const active = page.getByRole('region', { name: '活跃子 Agent' })
  const running = active.locator('[data-thread-id="deep"]')
  const label = running.locator('.ui-list-row-label')
  await expect(label).toBeVisible()
  expect(await label.evaluate((element) => {
    const style = getComputedStyle(element)
    return {
      clips: style.overflow === 'hidden',
      ellipsis: style.textOverflow === 'ellipsis',
      nowrap: style.whiteSpace === 'nowrap',
      hasOverflow: element.scrollWidth > element.clientWidth,
    }
  })).toEqual({ clips: true, ellipsis: true, nowrap: true, hasOverflow: true })
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)

  const runningIcon = running.locator('.ui-loading-spinner-icon')
  await expect(runningIcon).toBeVisible()
  await expect(runningIcon).not.toHaveCSS('animation-name', 'none')

  const waiting = active.locator('[data-thread-id="worker"] .thread-tree-status')
  await expect(waiting).toHaveAttribute('aria-label', '等待异步结果')
  await expect(waiting.locator('.ui-loading-spinner')).toHaveCount(0)
  await expect(waiting.locator('svg')).toHaveCount(1)
  await expect(waiting.locator('svg')).toHaveCSS('animation-name', 'none')

  await page.emulateMedia({ reducedMotion: 'reduce' })
  await expect(runningIcon).toHaveCSS('animation-name', 'none')
})
