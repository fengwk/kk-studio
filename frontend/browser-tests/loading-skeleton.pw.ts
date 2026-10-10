import { expect, test } from './fixture'

const HARNESS_URL = '/browser-tests/loading-skeleton-harness.html'

test.describe('Theme Loading Polish & ResourceCardSkeleton Browser Verification', () => {
  test('validates button spinner, aria-busy, text retention and prevention of duplicate clicks', async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 900 })
    await page.goto(HARNESS_URL)

    const button = page.locator('#test-loading-button')
    const compactButton = page.locator('#test-compact-button')
    const counter = page.locator('#click-counter')

    // 1. 验证 loading 状态下的视觉与语义
    await expect(button).toHaveAttribute('aria-busy', 'true')
    await expect(button).toBeDisabled()
    await expect(button.locator('.ui-loading-spinner')).toBeVisible()
    await expect(button).toContainText('确认提交')

    await expect(compactButton).toHaveAttribute('aria-busy', 'true')
    await expect(compactButton).toBeDisabled()
    await expect(compactButton.locator('.ui-loading-spinner')).toBeVisible()

    // 2. 验证 loading 状态下点击不触发（防重复提交）
    await button.click({ force: true })
    await compactButton.click({ force: true })
    await expect(counter).toHaveText('点击次数: 0')

    // 3. 切换 loading 为 false，验证恢复可交互
    await page.click('#toggle-btn-loading')
    await expect(button).not.toHaveAttribute('aria-busy')
    await expect(button).toBeEnabled()
    await expect(button.locator('.ui-loading-spinner')).toBeHidden()

    await button.click()
    await expect(counter).toHaveText('点击次数: 1')

    await page.screenshot({ path: '../reports/loading-skeleton/button-loading.png' })
  })

  test('validates skeleton layout, stable ~280px height and 6 placeholders', async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 900 })
    await page.goto(HARNESS_URL)

    const skeletons = page.locator('#standalone-skeleton-wrap .resource-card-skeleton')
    await expect(skeletons).toHaveCount(6)

    // 验证每个卡片计算高度约为 280px
    const firstCardHeight = await skeletons.first().evaluate((el) => el.getBoundingClientRect().height)
    expect(firstCardHeight).toBeGreaterThanOrEqual(275)
    expect(firstCardHeight).toBeLessThanOrEqual(285)

    // 验证骨架内部包含 head, meta, actions 结构
    await expect(skeletons.first().locator('.skeleton-icon')).toBeVisible()
    await expect(skeletons.first().locator('.skeleton-title')).toBeVisible()
    await expect(skeletons.first().locator('.skeleton-subtitle')).toBeVisible()
    await expect(skeletons.first().locator('.skeleton-meta-row')).toHaveCount(3)
    await expect(skeletons.first().locator('.skeleton-action')).toHaveCount(2)

    await page.screenshot({ path: '../reports/loading-skeleton/skeleton-desktop-1280.png' })
  })

  test('validates AiConsoleFrame state transitions: loading shows skeleton without empty list flash, then reveals data and error', async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 900 })
    await page.goto(HARNESS_URL)

    const container = page.locator('#console-frame-container')

    // 1. 初次 loading：展示 ResourceCardSkeleton，不展示 CreateCard，避免双网格重复/跳动
    await expect(container.locator('.resource-card-skeleton')).toHaveCount(6)
    await expect(container.locator('.create-card')).toBeHidden()
    await expect(container.locator('.resource-card:not(.resource-card-skeleton)')).toBeHidden()

    // 2. 切换为 Success：骨架消失，CreateCard 与实际数据卡片展示
    await page.click('#toggle-console-success')
    await expect(container.locator('.resource-card-skeleton')).toHaveCount(0)
    await expect(container.locator('.create-card')).toBeVisible()
    await expect(container.locator('.resource-card:not(.resource-card-skeleton)')).toBeVisible()
    await expect(container).toContainText('GPT-4o')

    // 3. 切换为 Error：展示 StateBlock 错误，且不被骨架盖住
    await page.click('#toggle-console-error')
    await expect(container.locator('.resource-card-skeleton')).toHaveCount(0)
    await expect(container.locator('.state-block.danger')).toBeVisible()
    await expect(container).toContainText('模拟资源加载错误')

    await page.screenshot({ path: '../reports/loading-skeleton/console-frame-states.png' })
  })

  test('validates responsive layout with no horizontal overflow at 1280px and 390px', async ({ page }) => {
    // 1280px 桌面视口
    await page.setViewportSize({ width: 1280, height: 900 })
    await page.goto(HARNESS_URL)
    const desktopOverflow = await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)
    expect(desktopOverflow).toBe(true)

    // 390px 移动视口
    await page.setViewportSize({ width: 390, height: 844 })
    await page.goto(HARNESS_URL)
    const mobileOverflow = await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)
    expect(mobileOverflow).toBe(true)

    // 390px 下骨架卡片仍保持正常渲染
    const mobileSkeletons = page.locator('#standalone-skeleton-wrap .resource-card-skeleton')
    await expect(mobileSkeletons).toHaveCount(6)

    await page.screenshot({ path: '../reports/loading-skeleton/skeleton-mobile-390.png' })
  })

  test('validates prefers-reduced-motion disables skeleton shimmer and spinner animation', async ({ page }) => {
    await page.emulateMedia({ reducedMotion: 'reduce' })
    await page.setViewportSize({ width: 1280, height: 900 })
    await page.goto(HARNESS_URL)

    // 验证骨架条动画被关闭
    const skeletonAnimation = await page.locator('.skeleton-box').first().evaluate((el) => {
      return getComputedStyle(el).animationName
    })
    expect(skeletonAnimation).toBe('none')

    // 验证 spinner 动画被关闭
    const spinnerAnimation = await page.locator('.ui-loading-spinner-icon').first().evaluate((el) => {
      return getComputedStyle(el).animationName
    })
    expect(spinnerAnimation).toBe('none')

    await page.screenshot({ path: '../reports/loading-skeleton/reduced-motion.png' })
  })
})
