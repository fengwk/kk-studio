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

async function expectFocusOutline(page: Page, locator: Locator, offset = '1px') {
  await expect(locator).toHaveCSS('outline-width', '1px')
  await expect(locator).toHaveCSS('outline-offset', offset)
  await expect(locator).toHaveCSS('outline-color', await tokenColor(page, '--border'))
  await expect(locator).toHaveCSS('box-shadow', 'none')
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

    // Options remain clickable above the dialog backdrop, including when portalled.
    const trigger = modal.getByRole('button', { name: '弹层内 下拉' })
    await trigger.click()
    await page.getByRole('option', { name: '选项 B' }).click()
    await expect(trigger).toHaveText('选项 B')
  })

  test('keeps focus rings and danger borders on shared controls', async ({ page }) => {
    const text = page.locator('.control-text').first()
    await text.click()
    await expectFocusOutline(page, text)
    const search = page.getByRole('textbox', { name: '页级搜索框' })
    await search.click()
    await expectFocusOutline(page, page.locator('.harness-toolbar .searchbox'))
    await expect(search).toHaveCSS('outline-style', 'none')
    const number = page.getByRole('textbox', { name: '页内 数值输入', exact: true })
    await number.click()
    await expectFocusOutline(page, number)

    const trigger = page.locator('.control-select .ui-select-trigger').first()
    await trigger.click()
    await expectFocusOutline(page, trigger)
    await page.keyboard.press('Escape')

    await page.keyboard.press('Tab')
    const checkboxInput = page.locator('.control-checkbox .ui-checkbox-input').first()
    await checkboxInput.focus()
    await expectFocusOutline(page, page.locator('.control-checkbox .ui-checkbox-box').first())
    await text.focus()
    await expectFocusOutline(page, text)

    const button = page.getByRole('button', { name: '保存', exact: true })
    await button.click()
    await expect(button).toHaveCSS('outline-style', 'none')
    await page.keyboard.press('Tab')
    await button.focus()
    await expectFocusOutline(page, button)

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

    await requiredInput.focus()
    await page.keyboard.press('Escape')
    await expect(page.getByRole('tooltip')).toHaveCount(0)
    await expect(requiredInput).toBeFocused()

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

    await expect(page.locator('.control-checkbox-disabled').first()).toHaveCSS('cursor', 'not-allowed')
  })
})

for (const width of [1440, 390]) {
  test(`renders shared control states at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 })
    await page.goto('/browser-tests/shared-controls-harness.html')
    const capture = (state: string) => page.screenshot({ path: `../reports/r130-visual/shared-${state}-${width}.png`, animations: 'disabled' })

    const text = page.getByRole('textbox', { name: '页内 文本输入' })
    await text.click()
    await expectFocusOutline(page, text)
    await capture('controls')

    const select = page.getByRole('button', { name: '页内 下拉' })
    await select.click()
    await expect(page.getByRole('option', { name: '选项 A' })).toBeVisible()
    await capture('select')
    await page.keyboard.press('Escape')

    const date = page.getByRole('button', { name: '日期' })
    await date.click()
    await expect(page.getByRole('grid')).toBeVisible()
    await expect(date).toBeFocused()
    await capture('date')
    await page.keyboard.press('ArrowRight')
    await page.keyboard.press('Enter')
    await expect(date).toHaveAttribute('value', '2027-06-02')

    const time = page.getByRole('button', { name: '结束时间' })
    await time.click()
    await page.keyboard.press('End')
    await page.keyboard.press('Enter')
    await expect(time).toHaveText('24:00')
    await expect(page.getByRole('listbox', { name: '分钟' }).getByRole('option', { name: '30' })).toBeDisabled()
    await capture('time')
    await page.keyboard.press('Escape')
    await expect(time).toBeFocused()

    const menu = page.getByRole('button', { name: '操作' })
    await menu.focus()
    await page.keyboard.press('Enter')
    await page.keyboard.press('End')
    const item = page.getByRole('menuitem', { name: 'Delete' })
    await expect(item).toBeFocused()
    await expect(item).toHaveCSS('outline-offset', '-1px')
    const menuBox = await page.getByRole('menu').boundingBox()
    expect(menuBox!.x).toBeGreaterThanOrEqual(0)
    expect(menuBox!.x + menuBox!.width).toBeLessThanOrEqual(width)
    await capture('menu')
    await page.keyboard.press('Escape')
    await expect(menu).toBeFocused()

    await page.getByRole('button', { name: '新建空间' }).click()
    const dialog = page.getByRole('dialog', { name: '新建空间' })
    await dialog.getByRole('button', { name: '确认' }).click()
    await expect(dialog.getByRole('alert')).toHaveText('请输入名称')
    await capture('name-dialog')
  })
}

test('keeps long Select trigger values on a single line with ellipsis and full title', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 })
  await page.goto('/browser-tests/shared-controls-harness.html')
  const longLabel = '这是一个用于验证共享下拉触发器单行省略号截断行为的非常长的选项文本 Alpha Bravo Charlie Delta'
  const trigger = page.getByRole('button', { name: '页内 下拉' })
  await trigger.click()
  await page.getByRole('option', { name: longLabel }).click()

  await expectHeight(trigger, 32)
  await expect(trigger).toHaveAttribute('title', longLabel)
  const valueSpan = trigger.locator('.ui-select-value')
  await expect(valueSpan).toHaveCSS('white-space', 'nowrap')
  await expect(valueSpan).toHaveCSS('overflow', 'hidden')
  await expect(valueSpan).toHaveCSS('text-overflow', 'ellipsis')
  const truncated = await valueSpan.evaluate((el) => el.scrollWidth > el.clientWidth)
  expect(truncated).toBe(true)
})
