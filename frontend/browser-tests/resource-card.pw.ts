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

  test('keeps the head badge right-aligned inside the title row', async ({ page }) => {
    await page.setViewportSize({ width: 1200, height: 900 })
    await page.goto(HARNESS_URL)

    const head = standardCard(page).locator('.resource-card-head')
    const badge = page.locator('#standard-badge')
    await expect(badge).toBeVisible()
    const headBox = (await head.boundingBox())!
    const badgeBox = (await badge.boundingBox())!

    // 标记落在标题行右端，且不越出卡片内边距。
    expect(badgeBox.x).toBeGreaterThan(headBox.x + headBox.width / 2)
    expect(badgeBox.x + badgeBox.width).toBeLessThanOrEqual(headBox.x + headBox.width + 0.5)
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

const PANELS_HARNESS_URL = '/browser-tests/resource-card-harness.html?panels=1'

test.describe('Real Models and Providers panels layout and CreateCard contract', () => {
  // 1280px 宽屏：空态新建卡片保持至少 280px 最小高度，不再矮扁坍塌
  test('ensures empty state CreateCard respects min-height 280px on 1280px desktop', async ({
    page,
  }, testInfo) => {
    await page.setViewportSize({ width: 1280, height: 900 })
    await page.goto(PANELS_HARNESS_URL)

    const modelEmptyCard = page.locator('#section-models-empty .create-card')
    const providerEmptyCard = page.locator('#section-providers-empty .create-card')

    await expect(modelEmptyCard).toBeVisible()
    await expect(providerEmptyCard).toBeVisible()

    const modelBox = (await modelEmptyCard.boundingBox())!
    const providerBox = (await providerEmptyCard.boundingBox())!

    const modelComputedMinHeight = await modelEmptyCard.evaluate(
      (el) => getComputedStyle(el).minHeight,
    )
    const providerComputedMinHeight = await providerEmptyCard.evaluate(
      (el) => getComputedStyle(el).minHeight,
    )

    expect(modelComputedMinHeight).toBe('280px')
    expect(providerComputedMinHeight).toBe('280px')

    // 实际渲染高度必须 >= 280px（在空态下约为 280px）
    expect(modelBox.height).toBeGreaterThanOrEqual(280)
    expect(providerBox.height).toBeGreaterThanOrEqual(280)

    console.log(
      `[Measure] 1280px empty state: Model CreateCard=${modelBox.height}px, Provider CreateCard=${providerBox.height}px`,
    )

    await page.screenshot({ path: testInfo.outputPath('desktop-empty-create-cards.png') })
  })

  // 1280px 宽屏：同行卡片自然等高 (grid stretch)，内容丰富时卡片高度自然增长
  test('stretches to same height in same row and grows naturally with content on 1280px desktop', async ({
    page,
  }, testInfo) => {
    await page.setViewportSize({ width: 1280, height: 900 })
    await page.goto(PANELS_HARNESS_URL)

    // 模型列表网格：包含 1 个 CreateCard 与 2 个真实模型卡片
    const populatedSection = page.locator('#section-models-populated')
    const createCard = populatedSection.locator('.create-card')
    const resourceCards = populatedSection.locator('.resource-card')

    await expect(createCard).toBeVisible()
    await expect(resourceCards.first()).toBeVisible()

    const createBox = (await createCard.boundingBox())!
    const firstResourceBox = (await resourceCards.first().boundingBox())!

    // 1280px 容器下，前两张卡片落在同一行（y 坐标相同），CSS grid stretch 保证它们等高
    if (Math.abs(createBox.y - firstResourceBox.y) < 4) {
      expect(Math.abs(createBox.height - firstResourceBox.height)).toBeLessThanOrEqual(1.5)
    }

    // 含有更多变体和长描述的卡片（或随内容生长的典型卡片）高度 >= 280px
    for (let i = 0; i < (await resourceCards.count()); i += 1) {
      const box = (await resourceCards.nth(i).boundingBox())!
      expect(box.height).toBeGreaterThanOrEqual(280)
      console.log(`[Measure] 1280px populated state: Model card [${i}]=${box.height}px`)
    }
    console.log(`[Measure] 1280px populated state: CreateCard=${createBox.height}px`)

    const providerCard = page.locator('#section-providers-populated .resource-card').first()
    const providerBox = (await providerCard.boundingBox())!
    console.log(`[Measure] 1280px populated state: Provider card=${providerBox.height}px`)

    await page.screenshot({ path: testInfo.outputPath('desktop-populated-cards.png') })
  })

  // 390px 窄屏移动视口：单列自适应、无横向溢出，空态保持最小高度
  test('adapts to 390px mobile viewport without horizontal overflow and retains min-height', async ({
    page,
  }, testInfo) => {
    await page.setViewportSize({ width: 390, height: 844 })
    await page.goto(PANELS_HARNESS_URL)

    const overflow = await page.evaluate(() => ({
      scrollWidth: document.documentElement.scrollWidth,
      clientWidth: document.documentElement.clientWidth,
    }))
    expect(overflow.scrollWidth).toBeLessThanOrEqual(overflow.clientWidth + 1)

    // 空态 CreateCard 在窄屏依然保持 280px 最小高度
    const modelEmptyCard = page.locator('#section-models-empty .create-card')
    const providerEmptyCard = page.locator('#section-providers-empty .create-card')

    const modelBox = (await modelEmptyCard.boundingBox())!
    const providerBox = (await providerEmptyCard.boundingBox())!

    expect(modelBox.height).toBeGreaterThanOrEqual(280)
    expect(providerBox.height).toBeGreaterThanOrEqual(280)

    console.log(
      `[Measure] 390px mobile empty state: Model CreateCard=${modelBox.height}px, Provider CreateCard=${providerBox.height}px`,
    )

    // 窄屏下每个栅格皆单列排布（每个卡片的 x 相同）
    const modelGridItems = page.locator('#section-models-populated .resource-grid > *')
    const xs = await modelGridItems.evaluateAll((items) =>
      items.map((item) => Math.round(item.getBoundingClientRect().x)),
    )
    expect(new Set(xs).size).toBe(1)

    await page.screenshot({ path: testInfo.outputPath('mobile-panels-layout.png') })
  })

  // 创建按钮点击可操作：无论是空态还是有卡片列表，点击 CreateCard 均能触发操作回调
  test('handles click interaction on CreateCard in both empty and populated states', async ({
    page,
  }) => {
    await page.setViewportSize({ width: 1280, height: 900 })
    await page.goto(PANELS_HARNESS_URL)

    const actionLog = page.locator('#action-log')
    await expect(actionLog).toHaveText('idle')

    // 1. 点击模型空态新建卡片
    await page.locator('#section-models-empty .create-card').click()
    await expect(actionLog).toHaveText('create-model-empty')

    // 2. 点击供应商空态新建卡片
    await page.locator('#section-providers-empty .create-card').click()
    await expect(actionLog).toHaveText('create-provider-empty')

    // 3. 点击有卡片场景下的模型新建卡片
    await page.locator('#section-models-populated .create-card').click()
    await expect(actionLog).toHaveText('create-model-populated')

    // 4. 点击有卡片场景下的供应商新建卡片
    await page.locator('#section-providers-populated .create-card').click()
    await expect(actionLog).toHaveText('create-provider-populated')
  })
})
