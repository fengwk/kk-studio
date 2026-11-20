import { expect, test, type Locator, type Page } from './fixture'

/**
 * Catalog 编辑弹窗的移动端触区与布局回归：
 * 44px 命中框、安全区留白、Select portal 选项和窄屏无横向溢出都必须来自真实计算样式。
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
      '.pricing-rule-details > summary',
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

  // 展开服务档规则：既有规则默认折叠，点摘要展开后统一倍率立即可见，单费用项覆盖倍率在嵌套 details 中。
  const tiers = dialog.locator('.pricing-plan-section').filter({ has: page.getByRole('heading', { name: '服务档', exact: true }) })
  const tierRule = tiers.locator('.pricing-rule-details').first()
  await expect(tierRule).not.toHaveAttribute('open', '')
  await tiers.locator('.pricing-rule-details > summary').first().click()
  await expect(tierRule).toHaveAttribute('open', '')
  const tierFactor = tierRule.getByRole('textbox', { name: '所有费用项倍率', exact: true })
  await expect(tierFactor).toBeVisible()
  const factorOverrides = tierRule.locator('.pricing-factor-details')
  await expect(factorOverrides).not.toHaveAttribute('open', '')
  await factorOverrides.locator('summary').click()
  await expect(factorOverrides.locator('input[inputmode="decimal"]')).toHaveCount(5)
  await expect(factorOverrides.locator('input[inputmode="decimal"]').first()).toBeVisible()

  // 定价表单文案全部来自 i18n，不留缺失 key 占位。
  await expect(dialog).not.toContainText('⟦missing:')

  // 新增规则自动展开，不必再点摘要就能直接编辑。
  await tiers.getByRole('button', { name: '添加服务档', exact: true }).click()
  await expect(tiers.locator('.pricing-rule-details')).toHaveCount(2)
  const addedTierRule = tiers.locator('.pricing-rule-details').nth(1)
  await expect(addedTierRule).toHaveAttribute('open', '')
  await expect(addedTierRule.getByRole('textbox', { name: '所有费用项倍率', exact: true })).toBeVisible()

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
