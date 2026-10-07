import { expect, test } from './fixture'

test.describe('Layout Foundation Infrastructure Contract', () => {
  // 验证静态 preview 加载预构建 bundle，无 dev 运行时、HMR 或实时转译请求
  test('static preview loads pre-built bundles without dev HMR or on-demand transpilation requests', async ({
    page,
  }) => {
    const requestedUrls: string[] = []
    page.on('request', (req) => {
      requestedUrls.push(req.url())
    })

    await page.goto('/browser-tests/layout-foundation-harness.html')
    await expect(page.locator('#open-foundation-dialog')).toBeVisible()

    expect(requestedUrls.length).toBeGreaterThan(0)
    expect(requestedUrls.filter((url) => url.includes('/src/'))).toEqual([])
    expect(requestedUrls.filter((url) => url.includes('/@vite/client'))).toEqual([])
    expect(requestedUrls.filter((url) => url.includes('/.vite/deps/'))).toEqual([])
    expect(
      requestedUrls.filter((url) => url.includes('/assets/') && url.endsWith('.js')).length,
    ).toBeGreaterThan(0)
  })

  // 关闭弹窗后，焦点应归还打开它的按钮。
  test('dialog restores focus to its opener on close', async ({ page }, testInfo) => {
    await page.goto('/browser-tests/layout-foundation-harness.html')

    const opener = page.locator('#open-foundation-dialog')
    await opener.click()

    const dialog = page.getByRole('dialog', { name: '基础交互弹窗' })
    await expect(dialog).toBeVisible()
    // 初始焦点落在卡片内第一个可聚焦控件（头部关闭按钮），不在 opener 上。
    await expect(dialog.getByRole('button', { name: '关闭' })).toBeFocused()
    await page.screenshot({ path: testInfo.outputPath('foundation-dialog-open.png') })

    await dialog.getByRole('button', { name: '取消' }).click()
    await expect(dialog).toHaveCount(0)
    await expect(opener).toBeFocused()
  })

  // 嵌套 Select 优先处理 Escape，第二次 Escape 才关闭外层弹窗。
  test('real Select inside the dialog consumes Escape before the dialog closes', async ({ page }, testInfo) => {
    await page.goto('/browser-tests/layout-foundation-harness.html')

    await page.locator('#open-foundation-dialog').click()
    const dialog = page.getByRole('dialog', { name: '基础交互弹窗' })
    const trigger = dialog.getByRole('button', { name: '模型' })

    await trigger.click()
    await expect(page.getByRole('listbox')).toBeVisible()
    await page.screenshot({ path: testInfo.outputPath('foundation-dialog-select-open.png') })

    // 第一次 Escape 只收 Select 弹层：焦点回到触发器，弹窗保持打开。
    await page.keyboard.press('Escape')
    await expect(page.getByRole('listbox')).toHaveCount(0)
    await expect(dialog).toBeVisible()
    await expect(trigger).toBeFocused()

    // 第二次 Escape 才关弹窗，并把焦点归还给 opener。
    await page.keyboard.press('Escape')
    await expect(dialog).toHaveCount(0)
    await expect(page.locator('#open-foundation-dialog')).toBeFocused()
  })
})
