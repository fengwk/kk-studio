import { expect, test, type Locator, type Page } from '@playwright/test'
import { tokenColor } from './fixture'

/**
 * 共享表单控件在真实 .form-group / .modal-body 上下文中的实测：
 * 32px 几何（compact 28px）、焦点环、校验失败的 danger 边框与禁用色的颜色与光标，
 * 以及 .form-group 规则与共享控件的样式隔离。
 */

const DANGER = 'rgb(240, 133, 133)'

async function expectHeight(locator: Locator, expected: number) {
  const box = await locator.boundingBox()
  expect(box, `${await locator.evaluate((el) => el.className)} 应有可见盒模型`).not.toBeNull()
  expect(Math.round(box?.height ?? 0)).toBe(expected)
}

/**
 * :focus-visible 只在键盘模态下生效：先按一个不动焦点的键建立键盘模态，再聚焦目标控件。
 * ringOn 指定承载焦点环的元素（Checkbox 的环在其兄弟节点上）。
 */
async function targetFocusRing(page: Page, target: Locator, ringOn: Locator, borderColor: string) {
  await page.keyboard.press('Tab')
  await target.focus()
  await expect(page.locator(':focus-visible')).toHaveCount(1)
  // 部分控件对 box-shadow 有过渡，需等到过渡收敛。
  await expect
    .poll(async () => ringOn.evaluate((el) => getComputedStyle(el).boxShadow))
    .toContain(await tokenColor(page, '--focus-ring'))
  await expect(ringOn).toHaveCSS('border-color', borderColor)
}

async function expectPointerFocusIsNeutral(page: Page, locator: Locator) {
  await locator.click()
  await expect(locator).toHaveCSS('border-color', await tokenColor(page, '--border-hover'))
  await expect(locator).toHaveCSS('box-shadow', 'none')
  await expect(locator).toHaveCSS('outline-style', 'none')
}

test.describe('shared form controls in real form contexts', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/browser-tests/shared-controls-harness.html')
  })

  test('keeps 32px geometry for every control type inside .form-group', async ({ page }) => {
    const heights: Array<[string, number]> = [
      ['.control-text', 32],
      ['.control-text-invalid', 32],
      ['.control-text-disabled', 32],
      ['.control-select .ui-select-trigger', 32],
      ['.control-select-invalid .ui-select-trigger', 32],
      ['.control-select-disabled .ui-select-trigger', 32],
      ['.control-select-compact .ui-select-trigger', 28],
      ['.control-number', 32],
      ['.control-number-invalid', 32],
      ['.control-number-disabled', 32],
      // 调用方传入的修饰类名必须与基础类名共存，否则基础样式会被替换掉。
      ['.control-number-decorated', 32],
      ['.control-checkbox', 32],
      ['.control-checkbox-invalid', 32],
      ['.control-checkbox-disabled', 32],
      ['.control-textarea', 76],
      ['.searchbox', 32],
    ]

    for (const [selector, expected] of heights) {
      await expectHeight(page.locator(selector).first(), expected)
    }

    // 共享搜索框输入贴合 32px 外框；在 .form-group 内检查样式隔离。
    const searchInput = page.locator('.form-group .searchbox .ui-search-input').first()
    await expect(searchInput).toHaveCSS('border-top-width', '0px')
    await expect(searchInput).toHaveCSS('background-color', 'rgba(0, 0, 0, 0)')
    await expect(searchInput).toHaveCSS('padding-left', '2px')
    const box = await searchInput.boundingBox()
    expect(Math.round(box?.height ?? 0)).toBeLessThanOrEqual(32)
  })

  test('keeps the same geometry inside a modal body', async ({ page }) => {
    await page.locator('#open-controls-dialog').click()
    const modal = page.locator('.modal-card .modal-body')
    await expect(modal).toBeVisible()

    for (const [selector, expected] of [
      ['.control-text', 32],
      ['.control-select .ui-select-trigger', 32],
      ['.control-number', 32],
      ['.control-checkbox', 32],
      ['.searchbox', 32],
    ] as Array<[string, number]>) {
      await expectHeight(modal.locator(selector).first(), expected)
    }
  })

  test('keeps focus rings and danger borders on shared controls', async ({ page }) => {
    // 鼠标聚焦只改变中性边框，不显示白/绿焦点光晕。
    await expectPointerFocusIsNeutral(page, page.locator('.control-text').first())
    await expectPointerFocusIsNeutral(page, page.locator('.control-number').first())

    // Select 触发按钮与 Checkbox 走 :focus-visible 语义：用真实键盘建立模态再聚焦。
    const neutralBorder = await tokenColor(page, '--border-hover')
    const trigger = page.locator('.control-select .ui-select-trigger').first()
    await targetFocusRing(page, trigger, trigger, neutralBorder)

    const checkboxInput = page.locator('.control-checkbox .ui-checkbox-input').first()
    const checkboxBox = page.locator('.control-checkbox .ui-checkbox-box').first()
    await targetFocusRing(page, checkboxInput, checkboxBox, await tokenColor(page, '--accent'))

    await page.keyboard.press('Tab')
    const textInput = page.locator('.control-text').first()
    await textInput.focus()
    await expect(textInput).toHaveCSS('outline-style', 'solid')
    await expect(textInput).toHaveCSS('outline-width', '2px')
    await expect(textInput).toHaveCSS('outline-color', await tokenColor(page, '--focus'))

    // 校验失败：共享控件保持 danger 边框。
    for (const selector of [
      '.control-text-invalid',
      '.control-select-invalid .ui-select-trigger',
      '.control-number-invalid',
      '.control-textarea-invalid',
    ]) {
      await expect(page.locator(selector).first()).toHaveCSS('border-color', DANGER)
    }
    await expect(page.locator('.control-checkbox-invalid .ui-checkbox-box')).toHaveCSS(
      'border-color',
      DANGER,
    )

    // .form-group.is-error 的规则不得改写共享控件。
    await expect(page.locator('.control-text-error-context')).toHaveCSS('border-color', DANGER)
    await expect(page.locator('.control-number-error-context')).toHaveCSS('border-color', DANGER)
    for (const selector of ['.control-text-error-context', '.control-number-error-context']) {
      const shadow = await page
        .locator(selector)
        .first()
        .evaluate((el) => getComputedStyle(el).boxShadow)
      expect(shadow).not.toContain('rgba(255, 96, 96')
    }
  })

  test('shows HelpHint on hover/focus, dismisses it, and keeps required semantics', async ({ page }) => {
    const trigger = page.getByRole('button', { name: '帮助：资源名称' })
    const requiredInput = page.getByRole('textbox', { name: '资源名称' })
    await expect(requiredInput).toHaveAttribute('required', '')
    await expect(page.getByText('*', { exact: true })).toBeVisible()

    await trigger.hover()
    const tooltip = page.getByRole('tooltip')
    await expect(tooltip).toHaveText('用于识别该资源。')
    const box = await tooltip.boundingBox()
    expect(box).not.toBeNull()
    expect(box!.x).toBeGreaterThanOrEqual(8)
    expect(box!.x + box!.width).toBeLessThanOrEqual(1272)
    await tooltip.hover()
    await expect(tooltip).toBeVisible()

    await page.keyboard.press('Escape')
    await expect(page.getByRole('tooltip')).toHaveCount(0)

    await page.keyboard.press('Tab')
    await trigger.focus()
    await expect(page.getByRole('tooltip')).toBeVisible()
    await page.getByRole('heading', { name: '表单上下文' }).click()
    await expect(page.getByRole('tooltip')).toHaveCount(0)

    await page.setViewportSize({ width: 390, height: 844 })
    await trigger.hover()
    const narrowBox = await page.getByRole('tooltip').boundingBox()
    expect(narrowBox).not.toBeNull()
    expect(narrowBox!.x).toBeGreaterThanOrEqual(8)
    expect(narrowBox!.x + narrowBox!.width).toBeLessThanOrEqual(382)
  })

  test('opens and closes HelpHint using real touch input', async ({ browser }) => {
    const context = await browser.newContext({
      baseURL: `http://127.0.0.1:${process.env.KK_BROWSER_PORT ?? '5174'}`,
      hasTouch: true,
      isMobile: true,
      viewport: { width: 390, height: 844 },
    })
    const touchPage = await context.newPage()
    await touchPage.goto('/browser-tests/shared-controls-harness.html')
    const trigger = touchPage.getByRole('button', { name: '帮助：资源名称' })

    await trigger.tap()
    await expect(touchPage.getByRole('tooltip')).toBeVisible()
    await trigger.tap()
    await expect(touchPage.getByRole('tooltip')).toHaveCount(0)

    await context.close()
  })

  // 普通（非共享）控件的表单规则按 32px 节奏生效。
  test('applies the shared 32px rhythm to plain .form-group controls', async ({ page }) => {
    const plain = await page.evaluate(() => {
      const wrapper = document.createElement('div')
      wrapper.className = 'form-group'
      wrapper.innerHTML =
        '<span>标签</span><input id="probe-plain"><select id="probe-select"></select><textarea id="probe-textarea"></textarea>'
      document.body.appendChild(wrapper)
      return {
        input: getComputedStyle(document.getElementById('probe-plain') as Element).height,
        select: getComputedStyle(document.getElementById('probe-select') as Element).height,
        textareaMin: getComputedStyle(document.getElementById('probe-textarea') as Element).minHeight,
      }
    })

    expect(plain.input).toBe('32px')
    expect(plain.select).toBe('32px')
    expect(plain.textareaMin).toBe('96px')
  })

  test('keeps disabled semantics without dimming whole input surfaces', async ({ page }) => {
    // 禁用文字使用次要前景色 token，而不是整体降透明度。
    const mutedForeground = await tokenColor(page, '--fg-muted')
    await expect(page.locator('.control-text-disabled')).toHaveCSS('color', mutedForeground)
    await expect(page.locator('.control-text-disabled')).toHaveCSS('cursor', 'not-allowed')

    await expect(page.locator('.control-select-disabled .ui-select-trigger')).toHaveCSS(
      'color',
      mutedForeground,
    )
    await expect(page.locator('.control-select-disabled .ui-select-trigger')).toHaveCSS(
      'cursor',
      'not-allowed',
    )

    await expect(page.locator('.control-number-disabled')).toHaveCSS('color', mutedForeground)
    await expect(page.locator('.control-number-disabled')).toHaveCSS('cursor', 'not-allowed')

    // 禁用文本输入不得通过整体降透明度制造“看不清”的输入面。
    const inputOpacity = await page
      .locator('.control-text-disabled')
      .evaluate((el) => getComputedStyle(el).opacity)
    expect(inputOpacity).toBe('1')

    await expect(page.locator('.control-checkbox-disabled')).toHaveCSS('cursor', 'not-allowed')
  })
})
