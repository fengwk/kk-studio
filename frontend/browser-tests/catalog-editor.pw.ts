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

async function expectNoHorizontalOverflow(page: Page) {
  expect(await page.evaluate(() =>
    document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true)
}

test('keeps 44px touch targets, safe-area padding and a scrollable body on narrow catalog dialogs', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/browser-tests/catalog-editor-harness.html')

  const dialog = page.getByRole('dialog')
  await expect(dialog).toBeVisible()

  const backdropPadding = await page.locator('.modal-backdrop').evaluate((backdrop) => {
    const style = getComputedStyle(backdrop)
    return [style.paddingTop, style.paddingLeft, style.paddingRight, style.paddingBottom]
  })
  // 窄屏启用共享触区模式：12px 间距叠加 env(safe-area-inset-*)。
  expect(backdropPadding).toEqual(['12px', '12px', '12px', '12px'])

  expectTouchTargets(await visibleTouchTargets(dialog))
  await expectNoHorizontalOverflow(page)

  // 展开覆盖规则后，规则正文内的输入、删除与新增按钮同样不低于 44px。
  await dialog.getByRole('button', { name: /override-1/ }).click()
  const ruleBody = dialog.locator('.pricing-rule-body')
  await expect(ruleBody).toBeVisible()
  expectTouchTargets(await visibleTouchTargets(ruleBody))

  // Select 的 portal 选项在弹窗内打开时也遵循同一触区。
  await dialog.getByRole('button', { name: 'Provider', exact: true }).click()
  const options = page.getByRole('option')
  await expect(options.first()).toBeVisible()
  const optionHeights = await options.evaluateAll((items) =>
    items.map((item) => item.getBoundingClientRect().height))
  expect(optionHeights.every((height) => height >= 43.5)).toBe(true)
  await page.keyboard.press('Escape')
  await expect(page.getByRole('listbox')).toHaveCount(0)

  await page.screenshot({ path: '../reports/layout/catalog-editor-390.png', fullPage: true })

  await page.setViewportSize({ width: 320, height: 700 })
  expectTouchTargets(await visibleTouchTargets(dialog))
  await expectNoHorizontalOverflow(page)
  await page.screenshot({ path: '../reports/layout/catalog-editor-320.png', fullPage: true })
})
