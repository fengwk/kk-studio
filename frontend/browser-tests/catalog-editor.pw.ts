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
    promptCacheState: 'CONFIGURABLE',
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

  // 现有规则及其覆盖倍率无需额外操作即可编辑。
  const tiers = dialog.locator('.pricing-plan-section').filter({ has: page.getByRole('heading', { name: '服务档', exact: true }) })
  const tierRule = tiers.locator('.pricing-rule-details').first()
  const tierFactor = tierRule.getByRole('textbox', { name: '所有费用项倍率', exact: true })
  await expect(tierFactor).toBeVisible()
  const factorOverrides = tierRule.locator('.pricing-factor-details')
  await expect(factorOverrides.locator('input[inputmode="decimal"]')).toHaveCount(5)
  await expect(factorOverrides.locator('input[inputmode="decimal"]').first()).toBeVisible()

  // 定价表单文案全部来自 i18n，不留缺失 key 占位。
  await expect(dialog).not.toContainText('⟦missing:')

  // 新增规则自动展开，不必再点摘要就能直接编辑。
  await tiers.getByRole('button', { name: '添加服务档', exact: true }).click()
  await expect(tiers.locator('.pricing-rule-details')).toHaveCount(2)
  const addedTierRule = tiers.locator('.pricing-rule-details').nth(1)
  await expect(addedTierRule.getByRole('textbox', { name: '所有费用项倍率', exact: true })).toBeVisible()

  expectTouchTargets(await visibleTouchTargets(tiers))
  await expect(body).toHaveCSS('overflow-y', 'auto')
  expect(await body.evaluate((el) => el.scrollTop)).toBeGreaterThan(0)
  await expect(dialog.getByRole('button', { name: '创建' })).toBeInViewport()

  // Select 的 portal 选项在弹窗内打开时也遵循同一触区。
  await dialog.getByRole('button', { name: '提供商', exact: true }).click()
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

test('shows model sections in one column and reveals preset and pricing errors inline', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 })
  await page.route('**/api/platform/models/capabilities?**', (route) => route.fulfill({ json: {
    promptCacheOptions: [{ retention: 'NONE' }, { retention: 'SHORT' }],
    defaultPromptCacheRetention: 'SHORT',
    promptCacheState: 'CONFIGURABLE',
  } }))

  // 无目录栏，提交后仍自动定位到正文的内联错误。
  await page.goto('/browser-tests/catalog-editor-harness.html?scenario=model&errors=preset')
  const dialog = page.getByRole('dialog')
  await expect(dialog.getByRole('button', { name: '提示缓存留存 1' })).toBeEnabled()
  const body = dialog.locator('.modal-body')
  await expect(dialog.getByRole('navigation')).toHaveCount(0)
  await expect(dialog.locator('#catalog-section-presets')).toBeVisible()
  expect(await body.evaluate((el) => el.scrollTop)).toBe(0)

  await dialog.getByRole('button', { name: '创建' }).click()
  await expectSubmitCount(page, 1)
  const presetError = dialog.locator('#catalog-section-presets .field-error')
  await expect(presetError).toHaveText('预设 ID 不能重复')
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
  await dialog.getByRole('button', { name: '创建' }).click()
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
  await dialog.getByRole('button', { name: '创建' }).click()
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
  const panelOf = (legend: string) =>
    dialog.locator('fieldset.capability-picker').filter({ has: page.locator('legend', { hasText: legend }) })
  const skillsPanel = panelOf('技能')

  await expect(dialog.getByRole('heading', { name: '工具 / 技能 / 子 Agent' })).toBeVisible()
  await expect(dialog.locator('fieldset.capability-picker > legend')).toHaveText([
    '工具 · 已选 0', '技能 · 已选 0', '子 Agent · 已选 0',
  ])
  await expect(dialog.getByRole('navigation')).toHaveCount(0)

  // 能力直接可搜索、可选择。
  const search = skillsPanel.getByRole('searchbox', { name: '搜索技能' })
  await expect(search).toBeVisible()
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

  await expect(skillsPanel.locator('legend')).toHaveText('技能 · 已选 1')
  await expectNoHorizontalOverflow(page, body)
  await page.screenshot({ path: '../reports/layout/catalog-editor-agent-390.png', fullPage: true })

  // 保存按钮在窄屏页脚始终可见，提交走受控 fixture：只累加计数，不跳转。
  const save = dialog.getByRole('button', { name: '创建' })
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
  await expect(skillsPanel.getByRole('alert')).toContainText('资源加载失败')
  await expect(skillsPanel.getByText('目录还没有技能')).toHaveCount(0)
  await skillsPanel.getByRole('button', { name: '重试' }).click()
  await expect(harnessState(page)).toHaveAttribute('data-retry-count', '1')
  await page.screenshot({ path: '../reports/layout/catalog-editor-agent-directory-error.png', fullPage: true })

  // 英文界面的搜索和筛选标签仍正确本地化。
  await page.goto('/browser-tests/catalog-editor-harness.html?scenario=agent&locale=en-US')
  const englishSkills = panelOf('Skills')
  await expect(englishSkills.getByRole('searchbox', { name: 'Search skills' })).toBeVisible()
  await expect(englishSkills.getByRole('button', { name: 'Selected', exact: true })).toBeVisible()
})

test('scrolls to and focuses an inline capability error on submit', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/browser-tests/catalog-editor-harness.html?scenario=agent&errors=skills')

  const dialog = page.getByRole('dialog')
  const skillsPanel = dialog
    .locator('fieldset.capability-picker')
    .filter({ has: page.locator('legend', { hasText: '技能' }) })

  await expect(dialog.locator('.field-error')).toHaveCount(0)

  await dialog.getByRole('button', { name: '创建' }).click()
  await expectSubmitCount(page, 1)

  // 错误在下方能力区：提交后滚到可见并聚焦。
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
