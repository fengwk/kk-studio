import { test, expect } from '@playwright/test'

for (const width of [900, 360, 320]) {
  test(`active tree in the thread widget layout at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 800 })
    await page.goto('/browser-tests/subagent-tree-harness.html')

    const tree = page.getByRole('region', { name: '活跃子 Agent' })
    await expect(tree.locator('li')).toHaveCount(3)
    await expect(tree.locator('[data-thread-id="root"]')).toHaveCount(0)
    await expect(tree.locator('[data-thread-id="finished"]')).toHaveCount(0)
    await expect(tree.locator('[data-thread-id="planner"]')).toHaveAttribute('data-depth', '0')
    await expect(tree.locator('[data-thread-id="worker"]')).toHaveAttribute('data-depth', '1')
    await expect(tree.locator('[data-thread-id="deep"]')).toHaveAttribute('data-depth', '2')
    await expect(tree.locator('.active-thread-tree-count')).toHaveText('2 个活跃')
    await expect(tree.locator('[data-thread-id="worker"] .thread-tree-status')).toHaveAttribute('aria-label', '等待异步结果')
    await expect(tree.locator('[data-thread-id="deep"] .thread-tree-status')).toHaveAttribute('aria-label', '运行中')

    const deep = tree.locator('[data-thread-id="deep"] a')
    await deep.click()
    await expect(page.getByLabel('selected')).toHaveText('deep')
    await expect(tree.locator('[data-thread-id="deep"]')).toHaveAttribute('aria-current', 'true')
    await tree.locator('[data-thread-id="worker"] a').click()
    await expect(page.getByLabel('selected')).toHaveText('worker')

    const sizes = await tree.evaluate((element) => ({ client: element.clientWidth, scroll: element.scrollWidth }))
    expect(sizes.scroll).toBeLessThanOrEqual(sizes.client)
    expect(await tree.evaluate((element) => getComputedStyle(element).borderTopWidth)).toBe('1px')
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
    await page.screenshot({ path: `../reports/subagent-tree/tree-${width}.png`, fullPage: true })

    await page.getByRole('button', { name: 'finish', exact: true }).click()
    await expect(page.getByRole('region', { name: '活跃子 Agent' })).toHaveCount(0)
    await expect(page.locator('.thread-widget-zone')).toHaveCSS('display', 'none')
    await expect(page.locator('.thread-widget-stack')).toHaveCount(1)
  })
}

test('320px active rows truncate labels and status icons respect reduced motion', async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 800 })
  await page.emulateMedia({ reducedMotion: 'no-preference' })
  await page.goto('/browser-tests/subagent-tree-harness.html')

  const tree = page.getByRole('region', { name: '活跃子 Agent' })
  const running = tree.locator('[data-thread-id="deep"]')
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

  const waiting = tree.locator('[data-thread-id="worker"] .thread-tree-status')
  await expect(waiting).toHaveAttribute('aria-label', '等待异步结果')
  await expect(waiting.locator('.ui-loading-spinner')).toHaveCount(0)
  await expect(waiting.locator('svg')).toHaveCount(1)
  await expect(waiting.locator('svg')).toHaveCSS('animation-name', 'none')

  await page.emulateMedia({ reducedMotion: 'reduce' })
  await expect(runningIcon).toHaveCSS('animation-name', 'none')
})
