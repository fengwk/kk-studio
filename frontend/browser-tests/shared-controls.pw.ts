import { expect, test, type Locator, type Page } from '@playwright/test'

/**
 * 共享表单控件在真实 .form-group / .modal-body 上下文中的实测：
 * 32px 几何（compact 28px）、焦点环、校验失败的 danger 边框与禁用色的颜色与光标，
 * 以及旧 .form-group 规则不会改写共享控件。
 */

const GFG_DIM = 'rgb(157, 168, 159)'
const DANGER = 'rgb(240, 133, 133)'
const GREEN = 'rgb(113, 231, 154)'

async function expectHeight(locator: Locator, expected: number) {
  const box = await locator.boundingBox()
  expect(box, `${await locator.evaluate((el) => el.className)} 应有可见盒模型`).not.toBeNull()
  expect(Math.round(box?.height ?? 0)).toBe(expected)
}

/** 读取主题 token 的实际计算色，避免把断言写死在设计稿色值上。 */
async function tokenColor(page: Page, token: string) {
  return page.evaluate((name) => {
    const probe = document.createElement('span')
    probe.style.color = `var(${name})`
    document.body.appendChild(probe)
    const color = getComputedStyle(probe).color
    probe.remove()
    return color
  }, token)
}

/**
 * :focus-visible 只在键盘模态下生效：先按一个不动焦点的键建立键盘模态，再聚焦目标控件。
 * ringOn 指定承载焦点环的元素（Checkbox 的环在其兄弟节点上）。
 */
async function targetFocusRing(page: Page, target: Locator, ringOn: Locator, borderColor: string) {
  await page.keyboard.press('Shift')
  await target.focus()
  await expect(page.locator(':focus-visible')).toHaveCount(1)
  // 部分控件对 box-shadow 有过渡，需等到过渡收敛。
  await expect
    .poll(async () => ringOn.evaluate((el) => getComputedStyle(el).boxShadow))
    .toContain(await tokenColor(page, '--focus-ring'))
  await expect(ringOn).toHaveCSS('border-color', borderColor)
}

async function expectFocusRing(page: Page, locator: Locator, borderColor: string) {
  await locator.focus()
  await expect
    .poll(async () => locator.evaluate((el) => getComputedStyle(el).boxShadow))
    .toContain(await tokenColor(page, '--focus-ring'))
  await expect(locator).toHaveCSS('border-color', borderColor)
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

    // 共享搜索框输入：不套用旧表单控件的表面/边框/内边距，只贴合 32px 外框。
    // 必须取 .form-group 内的实例（页级工具条上的搜索框不受旧规则影响，会掩盖缺陷）。
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
    // 文本输入与数值输入用 :focus 语义。
    await expectFocusRing(page, page.locator('.control-text').first(), GREEN)
    await expectFocusRing(page, page.locator('.control-number').first(), GREEN)

    // Select 触发按钮与 Checkbox 走 :focus-visible 语义：用真实键盘建立模态再聚焦。
    const trigger = page.locator('.control-select .ui-select-trigger').first()
    await targetFocusRing(page, trigger, trigger, GREEN)

    const checkboxInput = page.locator('.control-checkbox .ui-checkbox-input').first()
    const checkboxBox = page.locator('.control-checkbox .ui-checkbox-box').first()
    await targetFocusRing(page, checkboxInput, checkboxBox, GREEN)

    // 校验失败：danger 边框，且不被旧 .form-group.is-error 硬编码色改写。
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

    // 旧 .form-group.is-error 用 !important + 硬编码色，不得改写共享控件。
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

  // 普通（非共享）控件的旧表单规则仍按 32px 节奏生效：证明选择器列表合法且未被整体丢弃。
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
    await expect(page.locator('.control-text-disabled')).toHaveCSS('color', GFG_DIM)
    await expect(page.locator('.control-text-disabled')).toHaveCSS('cursor', 'not-allowed')

    await expect(page.locator('.control-select-disabled .ui-select-trigger')).toHaveCSS(
      'color',
      GFG_DIM,
    )
    await expect(page.locator('.control-select-disabled .ui-select-trigger')).toHaveCSS(
      'cursor',
      'not-allowed',
    )

    await expect(page.locator('.control-number-disabled')).toHaveCSS('color', GFG_DIM)
    await expect(page.locator('.control-number-disabled')).toHaveCSS('cursor', 'not-allowed')

    // 禁用文本输入不得通过整体降透明度制造“看不清”的输入面。
    const inputOpacity = await page
      .locator('.control-text-disabled')
      .evaluate((el) => getComputedStyle(el).opacity)
    expect(inputOpacity).toBe('1')

    await expect(page.locator('.control-checkbox-disabled')).toHaveCSS('cursor', 'not-allowed')
  })
})
