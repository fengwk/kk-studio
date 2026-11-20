import { expect, test, type Page } from './fixture'

const HARNESS_URL = '/browser-tests/loading-skeleton-harness.html'

const SKELETON_CARDS = '#standalone-skeleton-wrap .resource-card-skeleton'
const ENVIRONMENT_CARDS = '#environment-skeleton-wrap .resource-card-skeleton'
const REAL_CARDS = '#real-grid-wrap .harness-real-card'

/** 与首张卡同一行（同一 top）的卡片数，即栅格实际列数。 */
function firstRowCount(page: Page, selector: string): Promise<number> {
  return page.evaluate((sel) => {
    const cards = Array.from(document.querySelectorAll<HTMLElement>(sel))
    if (cards.length === 0) {
      return 0
    }
    const top = cards[0].getBoundingClientRect().top
    return cards.filter((card) => Math.abs(card.getBoundingClientRect().top - top) < 1).length
  }, selector)
}

/** 首张卡的外框宽度，用于确认骨架与真实卡共用同一列模板。 */
function firstCardWidth(page: Page, selector: string): Promise<number> {
  return page.evaluate(
    (sel) => document.querySelector<HTMLElement>(sel)?.getBoundingClientRect().width ?? 0,
    selector,
  )
}

function firstCardHeight(page: Page, selector: string): Promise<number> {
  return page.evaluate(
    (sel) => document.querySelector<HTMLElement>(sel)?.getBoundingClientRect().height ?? 0,
    selector,
  )
}

const VIEWPORTS = [
  { name: '窄屏', width: 390, height: 900 },
  { name: '中屏', width: 900, height: 900 },
  { name: '宽屏', width: 1440, height: 900 },
]

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

  for (const viewport of VIEWPORTS) {
    test(`skeleton first row matches the real grid column count at ${viewport.name} ${viewport.width}px`, async ({ page }) => {
      await page.setViewportSize({ width: viewport.width, height: viewport.height })
      await page.goto(HARNESS_URL)
      await expect(page.locator(REAL_CARDS)).toHaveCount(6)

      const expectedColumns = await firstRowCount(page, REAL_CARDS)
      expect(expectedColumns).toBeGreaterThan(0)

      // 骨架按实际可容纳列数画首行，而不是固定 6 张。
      await expect(page.locator(SKELETON_CARDS)).toHaveCount(expectedColumns)
      // 全部占位都落在同一行。
      expect(await firstRowCount(page, SKELETON_CARDS)).toBe(expectedColumns)
      // 与真实卡共用同一列模板，因此宽度一致。
      const skeletonWidth = await firstCardWidth(page, SKELETON_CARDS)
      const realWidth = await firstCardWidth(page, REAL_CARDS)
      expect(Math.abs(skeletonWidth - realWidth)).toBeLessThanOrEqual(1)
      // 高度由结构撑开，不再锁 280px。
      expect(await firstCardHeight(page, SKELETON_CARDS)).toBeLessThan(280)
      // 窄屏不产生横向溢出。
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)

      await page.screenshot({
        path: `../reports/loading-skeleton/skeleton-${viewport.width}.png`,
        fullPage: true,
      })
    })
  }

  test('recalculates the skeleton first row on resize without reloading', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 })
    await page.goto(HARNESS_URL)
    await expect(page.locator(REAL_CARDS)).toHaveCount(6)

    const wideColumns = await firstRowCount(page, REAL_CARDS)
    await expect(page.locator(SKELETON_CARDS)).toHaveCount(wideColumns)

    await page.setViewportSize({ width: 390, height: 900 })
    await expect(page.locator(SKELETON_CARDS)).toHaveCount(1)
    expect(await firstRowCount(page, REAL_CARDS)).toBe(1)

    await page.setViewportSize({ width: 1440, height: 900 })
    await expect(page.locator(SKELETON_CARDS)).toHaveCount(wideColumns)
  })

  test('environment summary skeleton keeps head, status line and a single action slot', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 })
    await page.goto(HARNESS_URL)
    await expect(page.locator(REAL_CARDS)).toHaveCount(6)

    const columns = await firstRowCount(page, REAL_CARDS)
    await expect(page.locator(ENVIRONMENT_CARDS)).toHaveCount(columns)

    const first = page.locator(ENVIRONMENT_CARDS).first()
    await expect(first.locator('.skeleton-icon')).toBeVisible()
    await expect(first.locator('.skeleton-title')).toBeVisible()
    await expect(first.locator('.skeleton-subtitle')).toBeVisible()
    await expect(first.locator('.skeleton-badge')).toHaveCount(1)
    await expect(first.locator('.skeleton-status')).toHaveCount(1)
    await expect(first.locator('.skeleton-meta-row')).toHaveCount(2)
    await expect(first.locator('.skeleton-action')).toHaveCount(1)

    // 两种形状共享同一栅格宽度，高度按各自结构不同。
    expect(Math.abs(
      await firstCardWidth(page, ENVIRONMENT_CARDS) - await firstCardWidth(page, REAL_CARDS),
    )).toBeLessThanOrEqual(1)
    const environmentHeight = await firstCardHeight(page, ENVIRONMENT_CARDS)
    const resourceHeight = await firstCardHeight(page, SKELETON_CARDS)
    expect(environmentHeight).not.toBe(resourceHeight)

    await page.screenshot({ path: '../reports/loading-skeleton/skeleton-environment.png', fullPage: true })
  })

  test('exposes exactly one screenreader loading status and stays non-focusable', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 })
    await page.goto(HARNESS_URL)

    const wrap = page.locator('#standalone-skeleton-wrap')
    await expect(wrap.locator('[role="status"]')).toHaveCount(1)
    await expect(wrap.locator('[role="status"]')).toHaveAttribute('aria-busy', 'true')
    await expect(wrap.locator('[role="status"]')).toContainText('加载资源中...')

    // 占位本身对读屏隐藏，不重复播报。
    await expect(wrap.locator('.resource-card-skeleton[aria-hidden="true"]')).toHaveCount(
      await firstRowCount(page, SKELETON_CARDS),
    )
    // 没有可聚焦节点，也不吃指针事件。
    await expect(wrap.locator('[tabindex], button, a, input, select, textarea')).toHaveCount(0)
    expect(await page.locator(`${SKELETON_CARDS}`).first().evaluate(
      (el) => getComputedStyle(el).pointerEvents,
    )).toBe('none')

    // 从正文开始连续 Tab，焦点不会落在骨架内。
    await wrap.click({ force: true })
    for (let index = 0; index < 8; index += 1) {
      await page.keyboard.press('Tab')
      expect(await page.evaluate(
        () => document.activeElement?.closest('.resource-skeleton-container') != null,
      )).toBe(false)
    }
  })

  test('validates AiConsoleFrame state transitions: loading shows skeleton without empty list flash, then reveals data and error', async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 900 })
    await page.goto(HARNESS_URL)

    const container = page.locator('#console-frame-container')
    await expect(page.locator(REAL_CARDS)).toHaveCount(6)
    const expectedColumns = await firstRowCount(page, REAL_CARDS)

    // 1. 初次 loading：展示 ResourceCardSkeleton，不展示 CreateCard，避免双网格重复/跳动
    await expect(container.locator('.resource-card-skeleton')).toHaveCount(expectedColumns)
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

  test('prefers-reduced-motion keeps skeleton and spinner static', async ({ page }) => {
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
