import { expect, test, type Locator, type Page } from './fixture'

/**
 * 共享资源卡的真实浏览器几何/键盘/状态回归：
 * 这些断言必须测计算样式与实际布局盒，不能只用 jsdom 的 class 字符串代替。
 */

const HARNESS_URL = '/browser-tests/resource-card-harness.html'

function standardCard(page: Page): Locator {
  return page.locator('.resource-card.harness-standard')
}

/** 计算样式/几何一次性取回，避免多次 evaluate 之间的不一致。 */
async function cardMetrics(card: Locator) {
  return card.evaluate((element) => {
    const cardStyle = getComputedStyle(element)
    const icon = element.querySelector('.resource-card-icon') as HTMLElement
    const title = element.querySelector('.resource-card-title') as HTMLElement
    const subtitle = element.querySelector('.resource-card-subtitle') as HTMLElement
    const label = element.querySelector('.resource-card-meta-label') as HTMLElement
    const value = element.querySelector('.resource-card-meta-value') as HTMLElement
    const action = element.querySelector('.resource-card-actions button') as HTMLElement
    const actionStyle = getComputedStyle(action)
    const iconStyle = getComputedStyle(icon)
    const titleStyle = getComputedStyle(title)
    const actionRect = action.getBoundingClientRect()
    return {
      padding: cardStyle.padding,
      borderRadius: cardStyle.borderRadius,
      cardMinHeight: cardStyle.minHeight,
      iconWidth: iconStyle.width,
      iconHeight: iconStyle.height,
      iconRadius: iconStyle.borderRadius,
      titleFontSize: titleStyle.fontSize,
      titleFontWeight: titleStyle.fontWeight,
      subtitleFontSize: getComputedStyle(subtitle).fontSize,
      labelFontSize: getComputedStyle(label).fontSize,
      valueFontSize: getComputedStyle(value).fontSize,
      actionHeight: actionRect.height,
      actionFontSize: actionStyle.fontSize,
    }
  })
}

test.describe('Shared resource card foundation', () => {
  test('keeps the compact shared card metrics, action size and 16px grid gap', async ({ page }) => {
    await page.setViewportSize({ width: 1200, height: 900 })
    await page.goto(HARNESS_URL)

    const card = standardCard(page)
    await expect(card).toBeVisible()
    const metrics = await cardMetrics(card)

    expect(metrics.padding).toBe('16px')
    expect(metrics.borderRadius).toBe('12px')
    expect(metrics.iconWidth).toBe('40px')
    expect(metrics.iconHeight).toBe('40px')
    expect(metrics.iconRadius).toBe('8px')
    expect(metrics.titleFontSize).toBe('15px')
    expect(metrics.titleFontWeight).toBe('600')
    expect(metrics.subtitleFontSize).toBe('12px')
    expect(metrics.labelFontSize).toBe('12px')
    expect(metrics.valueFontSize).toBe('13px')
    // 紧凑动作 = 28px（普通控件 32px 的紧凑档）。
    expect(metrics.actionHeight).toBe(28)
    expect(metrics.actionFontSize).toBe('12px')
    // 卡片不再带固定 min-height。
    expect(metrics.cardMinHeight).toBe('auto')

    const gridGap = await page
      .locator('.resource-grid')
      .first()
      .evaluate((element) => getComputedStyle(element).gap)
    expect(gridGap).toBe('16px')

    // 1200px 宽度下栅格自适应为多列。
    const xs = await page
      .locator('.resource-grid')
      .first()
      .locator('> *')
      .evaluateAll((items) => items.map((item) => Math.round(item.getBoundingClientRect().x)))
    expect(new Set(xs).size).toBeGreaterThanOrEqual(2)
  })

  test('grows with content instead of reserving a fixed card height', async ({ page }) => {
    await page.setViewportSize({ width: 1200, height: 900 })
    await page.goto(HARNESS_URL)

    const minimal = page.locator('.resource-card.harness-minimal')
    const long = page.locator('.resource-card.harness-long')
    const minimalHeight = (await minimal.boundingBox())!.height
    const longHeight = (await long.boundingBox())!.height

    // 旧 .info-card 固定 220px；共享卡按内容生长。
    expect(minimalHeight).toBeLessThan(150)
    expect(longHeight).toBeGreaterThan(minimalHeight)
  })

  test('truncates a long title while keeping the full name, and wraps long values without overflow', async ({
    page,
  }) => {
    await page.setViewportSize({ width: 360, height: 900 })
    await page.goto(HARNESS_URL)

    const long = page.locator('.resource-card.harness-long')
    const title = long.locator('.resource-card-title')
    const titleMetrics = await title.evaluate((element) => ({
      full: element.getAttribute('title'),
      scrollWidth: element.scrollWidth,
      clientWidth: element.clientWidth,
      ellipsis: getComputedStyle(element).textOverflow,
    }))
    expect(titleMetrics.full).toBe(
      'a-very-long-resource-name-that-must-be-truncated-with-the-full-name-kept-available',
    )
    expect(titleMetrics.ellipsis).toBe('ellipsis')
    expect(titleMetrics.scrollWidth).toBeGreaterThan(titleMetrics.clientWidth)

    // 换行值必须留在卡内，不能横向溢出。
    const card = (await long.boundingBox())!
    const wrappedValue = long.locator('.resource-card-meta-value.is-wrap')
    const wrappedBox = (await wrappedValue.boundingBox())!
    expect(wrappedBox.x + wrappedBox.width).toBeLessThanOrEqual(card.x + card.width - 16 + 0.5)
    expect(await wrappedValue.evaluate((element) => getComputedStyle(element).whiteSpace)).toBe(
      'normal',
    )

    // 空值行保留占位高度且不显示伪占位符。
    const emptyValue = long.locator('.resource-card-meta-value.is-empty')
    expect(await emptyValue.evaluate((element) => element.textContent)).toBe('')
    expect((await emptyValue.boundingBox())!.height).toBeGreaterThan(0)
  })

  test('stays a single column without horizontal overflow on a narrow viewport', async ({ page }) => {
    await page.setViewportSize({ width: 360, height: 900 })
    await page.goto(HARNESS_URL)

    const overflow = await page.evaluate(() => ({
      scrollWidth: document.documentElement.scrollWidth,
      clientWidth: document.documentElement.clientWidth,
    }))
    expect(overflow.scrollWidth).toBeLessThanOrEqual(overflow.clientWidth + 1)

    const xs = await page
      .locator('.resource-grid')
      .first()
      .locator('> *')
      .evaluateAll((items) => items.map((item) => Math.round(item.getBoundingClientRect().x)))
    expect(new Set(xs).size).toBe(1)
  })

  test('keeps keyboard focus ring, danger and disabled states on card actions', async ({ page }) => {
    await page.setViewportSize({ width: 1200, height: 900 })
    await page.goto(HARNESS_URL)

    // 键盘导航到卡片动作：必须出现可见焦点环。
    const target = page.locator('#card-focus-target')
    for (let i = 0; i < 12; i += 1) {
      await page.keyboard.press('Tab')
      const focusedId = await page.evaluate(() => document.activeElement?.id ?? '')
      if (focusedId === 'card-focus-target') {
        break
      }
    }
    await expect(target).toBeFocused()
    const focusShadow = await target.evaluate((element) => getComputedStyle(element).boxShadow)
    expect(focusShadow).not.toBe('none')
    expect(focusShadow).toContain('113, 231, 154')

    // 禁用动作：不可点击、视觉降透明度并显示禁用光标。
    const disabled = page.locator('#card-disabled-action')
    await expect(disabled).toBeDisabled()
    const disabledStyle = await disabled.evaluate((element) => ({
      opacity: getComputedStyle(element).opacity,
      cursor: getComputedStyle(element).cursor,
      color: getComputedStyle(element).color,
    }))
    expect(disabledStyle.cursor).toBe('not-allowed')
    expect(Number(disabledStyle.opacity)).toBeLessThan(1)

    // danger 语义必须落到主题 danger token，而不是临时硬编码色。
    const dangerToken = await page.evaluate(() =>
      getComputedStyle(document.documentElement).getPropertyValue('--danger').trim(),
    )
    const dangerProbe = await page.evaluate((token) => {
      const probe = document.createElement('span')
      probe.style.color = token
      document.body.append(probe)
      const applied = getComputedStyle(probe).color
      probe.remove()
      return applied
    }, dangerToken)
    expect(disabledStyle.color).toBe(dangerProbe)
  })
})
