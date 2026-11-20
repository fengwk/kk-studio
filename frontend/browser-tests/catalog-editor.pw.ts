import { expect, test, type Locator, type Page } from './fixture'

/**
 * Catalog 编辑弹窗的移动端触区、布局与区块导航回归：
 * 44px 命中框、安全区留白、Select portal 选项和窄屏无横向溢出都必须来自真实计算样式；
 * 桌面区块导航、校验错误定位与 Agent 能力管理通过真实弹窗 + 受控提交 fixture 验证。
 */

interface TouchTarget {
  selector: string
  name: string
  width: number
  height: number
}

async function visibleTouchTargets(dialog: Locator): Promise<TouchTarget[]> {
  return dialog.evaluate((card) => {
    const visible = (element: Element) => {
      const rect = element.getBoundingClientRect()
      const style = getComputedStyle(element)
      return rect.width > 0
        && rect.height > 0
        && style.visibility !== 'hidden'
        && style.display !== 'none'
    }
    const selectors = [
      'button',
      'input:not([type="checkbox"]):not([type="radio"]):not([type="hidden"])',
      'textarea',
      'label.ui-checkbox',
      '.pricing-factor-details > summary',
    ]
    return selectors.flatMap((selector) =>
      Array.from(card.querySelectorAll(selector))
        .filter(visible)
        .map((element) => {
          const rect = element.getBoundingClientRect()
          return {
            selector,
            name: element.getAttribute('aria-label') ?? element.textContent?.trim().slice(0, 40) ?? '',
            width: rect.width,
            height: rect.height,
          }
        }),
    )
  })
}

function expectTouchTargets(targets: TouchTarget[]) {
  expect(targets.length).toBeGreaterThan(0)
  for (const target of targets) {
    expect(
      target.width,
      `${target.selector} "${target.name}" width`,
    ).toBeGreaterThanOrEqual(43.5)
    expect(
      target.height,
      `${target.selector} "${target.name}" height`,
    ).toBeGreaterThanOrEqual(43.5)
  }
}

async function expectNoHorizontalOverflow(page: Page, body: Locator) {
  expect(await page.evaluate(() =>
    document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true)
  expect(await body.evaluate((el) => el.scrollWidth <= el.clientWidth)).toBe(true)
}

/** 受控提交 fixture 暴露的计数：onSubmit 只累加，不发生真实提交或跳转。 */
function harnessState(page: Page): Locator {
  return page.locator('[data-testid="harness-state"]')
}

async function expectSubmitCount(page: Page, count: number) {
  await expect(harnessState(page)).toHaveAttribute('data-submit-count', String(count))
}

/** 焦点必须落在 selector 内部；平滑滚动与 focus 都发生在 layout effect 中，因此轮询等待。 */
async function expectFocusedInside(page: Page, selector: string) {
  await expect
    .poll(() => page.evaluate((sel) => document.activeElement?.closest(sel) !== null, selector))
    .toBe(true)
}

test('keeps 44px touch targets, safe-area padding and a scrollable body on narrow catalog dialogs', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await page.route('**/api/platform/models/capabilities?**', (route) => route.fulfill({ json: {
    promptCacheOptions: [{ retention: 'NONE' }, { retention: 'SHORT' }],
    defaultPromptCacheRetention: 'SHORT',
    automaticCache: false,
  } }))
  await page.goto('/browser-tests/catalog-editor-harness.html')

  const dialog = page.getByRole('dialog')
  await expect(dialog.getByRole('button', { name: '提示缓存留存 1' })).toBeEnabled()
  const body = dialog.locator('.modal-body')

  const backdropPadding = await page.locator('.modal-backdrop').evaluate((backdrop) => {
    const style = getComputedStyle(backdrop)
    return [style.paddingTop, style.paddingLeft, style.paddingRight, style.paddingBottom]
  })
  // 窄屏启用共享触区模式：12px 间距叠加 env(safe-area-inset-*)。
  expect(backdropPadding).toEqual(['12px', '12px', '12px', '12px'])

  expectTouchTargets(await visibleTouchTargets(dialog))
  await expectNoHorizontalOverflow(page, body)

  // 展开服务档倍率，验证新定价表单中的输入、删除与新增控件均满足触区要求。
  const tiers = dialog.locator('.pricing-plan-section').filter({ has: page.getByRole('heading', { name: '服务档', exact: true }) })
  await tiers.getByText('所有费用项倍率: ×1', { exact: true }).click()
  const tierFactor = tiers.getByRole('textbox', { name: '所有费用项倍率', exact: true })
  await expect(tierFactor).toBeVisible()
  expectTouchTargets(await visibleTouchTargets(tiers))
  await expect(body).toHaveCSS('overflow-y', 'auto')
  expect(await body.evaluate((el) => el.scrollTop)).toBeGreaterThan(0)
  await expect(dialog.getByRole('button', { name: '确认创建' })).toBeInViewport()

  // Select 的 portal 选项在弹窗内打开时也遵循同一触区。
  await dialog.getByRole('button', { name: 'Provider', exact: true }).click()
  const options = page.getByRole('option')
  await expect(options.first()).toBeVisible()
  const optionHeights = await options.evaluateAll((items) =>
    items.map((item) => item.getBoundingClientRect().height))
  expect(optionHeights.every((height) => height >= 43.5)).toBe(true)
  await page.keyboard.press('Escape')
  await expect(page.getByRole('listbox')).toHaveCount(0)

  await tierFactor.scrollIntoViewIfNeeded()
  await page.screenshot({ path: '../reports/layout/catalog-editor-390.png', fullPage: true })

  await page.setViewportSize({ width: 320, height: 700 })
  await tierFactor.scrollIntoViewIfNeeded()
  expectTouchTargets(await visibleTouchTargets(dialog))
  await expectNoHorizontalOverflow(page, body)
  await page.screenshot({ path: '../reports/layout/catalog-editor-320.png', fullPage: true })
})

test('navigates model sections on desktop and reveals preset and pricing errors inline', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 })
  await page.route('**/api/platform/models/capabilities?**', (route) => route.fulfill({ json: {
    promptCacheOptions: [{ retention: 'NONE' }, { retention: 'SHORT' }],
    defaultPromptCacheRetention: 'SHORT',
    automaticCache: false,
  } }))

  // 预设错误：导航跳到「模型预设」不提交，提交后内联错误被定位到。
  await page.goto('/browser-tests/catalog-editor-harness.html?scenario=model&errors=preset')
  const dialog = page.getByRole('dialog')
  await expect(dialog.getByRole('button', { name: '提示缓存留存 1' })).toBeEnabled()
  const body = dialog.locator('.modal-body')
  const nav = dialog.getByRole('navigation', { name: '区块导航' })

  await expect(nav).toBeVisible()
  expect((await nav.getByRole('button').allTextContents()).map((label) => label.trim()))
    .toEqual(['基本信息', '请求限制与能力', '模型预设', '价格与费率'])
  // 宽屏是「导航列 + 表单列」双轨，不是把导航折叠进正文。
  const trackCount = await body.evaluate((el) =>
    getComputedStyle(el).gridTemplateColumns.split(/\s+/).filter(Boolean).length)
  expect(trackCount).toBe(2)

  await nav.getByRole('button', { name: '模型预设' }).click()
  await expectFocusedInside(page, '#catalog-section-presets')
  await expect(dialog.locator('#catalog-section-presets')).toBeInViewport()
  // 跳转按钮不提交表单。
  await expectSubmitCount(page, 0)

  await dialog.getByRole('button', { name: '确认创建' }).click()
  await expectSubmitCount(page, 1)
  const presetError = dialog.locator('#catalog-section-presets .field-error')
  await expect(presetError).toHaveText('Variant ID 不能重复')
  await expect(presetError).toBeInViewport()
  await expectFocusedInside(page, '#catalog-section-presets')
  expect(await page.evaluate(() => document.activeElement?.classList.contains('field-error'))).toBe(true)
  // 本场景没有外发 banner，正文里只有内联错误。
  await expect(dialog.getByRole('alert')).toHaveCount(0)
  await page.screenshot({ path: '../reports/layout/catalog-editor-desktop-preset-error.png', fullPage: true })

  // 价格错误：从正文顶部提交后必须滚动到下方价格分组，而不是停在弹窗顶部。
  await page.goto('/browser-tests/catalog-editor-harness.html?scenario=model&errors=price')
  await expect(dialog.getByRole('button', { name: '提示缓存留存 1' })).toBeEnabled()
  expect(await body.evaluate((el) => el.scrollTop)).toBe(0)
  await dialog.getByRole('button', { name: '确认创建' }).click()
  await expectSubmitCount(page, 1)
  const pricingError = dialog.locator('#catalog-section-pricing .field-error').first()
  await expect(pricingError).toHaveText('请检查价格：填写 0 或正数即可')
  await expect(pricingError).toBeInViewport()
  await expect(dialog.locator('#catalog-section-pricing')).toBeInViewport()
  await expectFocusedInside(page, '#catalog-section-pricing')
  await expect.poll(() => body.evaluate((el) => el.scrollTop)).toBeGreaterThan(0)
  // banner 与内联错误同文时不再重复展示。
  await expect(dialog.getByRole('alert')).toHaveCount(0)
  await page.screenshot({ path: '../reports/layout/catalog-editor-desktop-price-error.png', fullPage: true })

  // 只有当错误没有内联落点时才回落到 banner。
  await page.goto('/browser-tests/catalog-editor-harness.html?scenario=model&errors=general')
  await expect(dialog.getByRole('button', { name: '提示缓存留存 1' })).toBeEnabled()
  await dialog.getByRole('button', { name: '确认创建' }).click()
  await expectSubmitCount(page, 1)
  const banner = dialog.getByRole('alert')
  await expect(banner).toHaveText('保存失败，请检查表单后重试')
  await expect(banner).toBeInViewport()
  expect(await page.evaluate(() => document.activeElement?.classList.contains('form-error-banner'))).toBe(true)
})

test('manages agent capabilities and keeps save visible on narrow dialogs', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/browser-tests/catalog-editor-harness.html?scenario=agent')

  const dialog = page.getByRole('dialog')
  const body = dialog.locator('.modal-body')
  const nav = dialog.getByRole('navigation', { name: '区块导航' })
  const panelOf = (legend: string) =>
    dialog.locator('fieldset.capability-picker').filter({ has: page.locator('legend', { hasText: legend }) })
  const skillsPanel = panelOf('技能')

  await expect(dialog.getByRole('heading', { name: '工具 / 技能 / 子代理' })).toBeVisible()
  await expect(dialog.locator('fieldset.capability-picker > legend')).toHaveText([
    '工具 · 已选 0', '技能 · 已选 0', '子代理 · 已选 0',
  ])
  // 窄屏导航变成正文顶部的横向跳转条。
  await expect(nav).toHaveCSS('flex-direction', 'row')
  await nav.getByRole('button', { name: '执行', exact: true }).click()
  await expect(dialog.locator('#catalog-section-execution')).toBeFocused()
  await expect.poll(() => body.evaluate((element) => element.scrollTop)).toBeGreaterThan(100)
  await nav.getByRole('button', { name: '基本信息' }).click()
  await expect(dialog.locator('#catalog-section-basics')).toBeFocused()
  await expectSubmitCount(page, 0)

  // 能力明细默认收起：首屏只呈现入口与已选数量。
  await expect(skillsPanel.locator('details.capability-selection-details[open]')).toHaveCount(0)
  await expect(skillsPanel.getByText('管理技能', { exact: true })).toBeVisible()
  await expect(skillsPanel.getByRole('searchbox', { name: '搜索技能' })).toBeHidden()

  await skillsPanel.locator('summary').click()
  const search = skillsPanel.getByRole('searchbox', { name: '搜索技能' })
  await expect(search).toBeVisible()
  await expect(skillsPanel.locator('details.capability-selection-details[open]')).toHaveCount(1)
  await expect(skillsPanel.locator('.capability-name')).toHaveText([
    'engineering / review',
    'engineering / deploy',
  ])

  // 搜索只在面板内过滤；回车不得提交表单。
  await search.fill('deploy')
  await expect(skillsPanel.locator('.capability-name')).toHaveText(['engineering / deploy'])
  await search.press('Enter')
  await expectSubmitCount(page, 0)
  await expect(dialog).toBeVisible()

  await search.fill('review')
  await skillsPanel.locator('label.ui-checkbox', { hasText: 'engineering / review' }).click()
  await expect(skillsPanel.getByRole('checkbox', { name: /engineering \/ review/ })).toBeChecked()
  await expect(skillsPanel.locator('label.ui-checkbox.is-selected')).toHaveCount(1)

  // 「已选」筛选只看当前勾选项，「全部」恢复完整目录（搜索词先清空，只留筛选条件）。
  await search.fill('')
  await expect(skillsPanel.locator('.capability-name')).toHaveText([
    'engineering / review',
    'engineering / deploy',
  ])
  await skillsPanel.getByRole('button', { name: '已选', exact: true }).click()
  await expect(skillsPanel.locator('.capability-name')).toHaveText(['engineering / review'])
  await skillsPanel.getByRole('button', { name: '全部', exact: true }).click()
  await expect(skillsPanel.locator('.capability-name')).toHaveText([
    'engineering / review',
    'engineering / deploy',
  ])

  // 收起后已选项以摘要行呈现。
  await skillsPanel.locator('summary').click()
  await expect(skillsPanel.locator('details.capability-selection-details[open]')).toHaveCount(0)
  await expect(skillsPanel.locator('legend')).toHaveText('技能 · 已选 1')
  await expect(skillsPanel.locator('.capability-selection-summary')).toHaveText('engineering / review')
  await expect(skillsPanel.locator('.capability-selection-summary')).toBeVisible()
  await expectNoHorizontalOverflow(page, body)
  await page.screenshot({ path: '../reports/layout/catalog-editor-agent-390.png', fullPage: true })

  // 保存按钮在窄屏页脚始终可见，提交走受控 fixture：只累加计数，不跳转。
  const save = dialog.getByRole('button', { name: '确认创建' })
  await expect(save).toBeVisible()
  await expect(save).toBeEnabled()
  await expect(save).toBeInViewport()
  const urlBeforeSave = page.url()
  await save.click()
  await expectSubmitCount(page, 1)
  await expect(dialog).toBeVisible()
  // 受控提交：既没有真实请求，也没有浏览器原生提交造成的页面跳转。
  expect(page.url()).toBe(urlBeforeSave)

  // 目录查询失败时面板给出失败与重试，而不是伪装成「目录还没有技能」。
  await page.goto('/browser-tests/catalog-editor-harness.html?scenario=agent&directories=error')
  await skillsPanel.locator('summary').click()
  await expect(skillsPanel.getByRole('alert')).toContainText('资源加载失败')
  await expect(skillsPanel.getByText('目录还没有技能')).toHaveCount(0)
  await skillsPanel.getByRole('button', { name: '重试' }).click()
  await expect(harnessState(page)).toHaveAttribute('data-retry-count', '1')
  await page.screenshot({ path: '../reports/layout/catalog-editor-agent-directory-error.png', fullPage: true })

  // 英文界面：能力管理入口与搜索标签来自 manageSkills / searchSkills 两个英文键。
  await page.goto('/browser-tests/catalog-editor-harness.html?scenario=agent&locale=en-US')
  const englishSkills = panelOf('Skills')
  await expect(englishSkills.getByText('Manage skills', { exact: true })).toBeVisible()
  await englishSkills.locator('summary').click()
  await expect(englishSkills.getByRole('searchbox', { name: 'Search skills' })).toBeVisible()
  await expect(englishSkills.getByRole('button', { name: 'Selected', exact: true })).toBeVisible()
  await expect(dialog.getByRole('navigation', { name: 'Section navigation' })).toBeVisible()
})

test('expands a collapsed capability section to reveal its submit error', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/browser-tests/catalog-editor-harness.html?scenario=agent&errors=skills')

  const dialog = page.getByRole('dialog')
  const skillsPanel = dialog
    .locator('fieldset.capability-picker')
    .filter({ has: page.locator('legend', { hasText: '技能' }) })

  // 提交前：能力明细全部收起，页面里没有任何错误落点。
  await expect(dialog.locator('details.capability-selection-details[open]')).toHaveCount(0)
  await expect(dialog.locator('.field-error')).toHaveCount(0)

  await dialog.getByRole('button', { name: '确认创建' }).click()
  await expectSubmitCount(page, 1)

  // 错误在收起的分组里：必须自动展开、滚动到可见并聚焦。
  await expect(skillsPanel.locator('details.capability-selection-details[open]')).toHaveCount(1)
  const skillError = skillsPanel.locator('.field-error')
  await expect(skillError).toHaveText('Skills 名称冲突，请检查勾选项')
  await expect(skillError).toBeVisible()
  await expect(skillError).toBeInViewport()
  await expectFocusedInside(page, 'fieldset.capability-picker')
  expect(await page.evaluate(() => document.activeElement?.classList.contains('field-error'))).toBe(true)
  // 与内联错误同文，不再重复弹出 banner。
  await expect(dialog.getByRole('alert')).toHaveCount(0)
  await page.screenshot({ path: '../reports/layout/catalog-editor-agent-error-expand.png', fullPage: true })
})
