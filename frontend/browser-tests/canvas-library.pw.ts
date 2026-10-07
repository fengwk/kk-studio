import { expect, test, type Page } from './fixture'

/**
 * Canvas 库的真实浏览器回归：真实组件 + 真实共享控件 + 传输层替身。
 * 断言的是请求日志与几何，而不是 class 字符串。
 */

const HARNESS_URL = '/browser-tests/canvas-library-harness.html'

async function apiLog(page: Page) {
  return page.evaluate(
    () => (window as unknown as { __canvasApiLog: Array<{ method: string; url: string; body?: unknown }> })
      .__canvasApiLog,
  )
}

test.describe('Canvas library real browser regression', () => {
  test('renders the shared resource card metrics and stays single column on a narrow viewport', async ({ page }) => {
    await page.setViewportSize({ width: 1200, height: 900 })
    await page.goto(`${HARNESS_URL}?mode=list`)

    const grid = page.locator('.resource-grid')
    await expect(grid).toBeVisible()
    await expect(grid.locator('.create-card')).toBeVisible()

    const card = page.locator('.resource-card')
    await expect(card).toBeVisible()
    await expect(card.locator('.resource-card-title')).toHaveText('Research board')

    const metrics = await card.evaluate((element) => {
      const cardStyle = getComputedStyle(element)
      const icon = element.querySelector('.resource-card-icon') as HTMLElement
      const title = element.querySelector('.resource-card-title') as HTMLElement
      const action = element.querySelector('.resource-card-actions button') as HTMLElement
      return {
        padding: cardStyle.padding,
        borderRadius: cardStyle.borderRadius,
        iconWidth: getComputedStyle(icon).width,
        iconHeight: getComputedStyle(icon).height,
        titleFontSize: getComputedStyle(title).fontSize,
        titleFontWeight: getComputedStyle(title).fontWeight,
        actionHeight: Math.round(action.getBoundingClientRect().height),
      }
    })
    // 与共享资源卡基座一致，而不是旧的 project-card 皮肤。
    expect(metrics.padding).toBe('16px')
    expect(metrics.borderRadius).toBe('12px')
    expect(metrics.iconWidth).toBe('40px')
    expect(metrics.iconHeight).toBe('40px')
    expect(metrics.titleFontSize).toBe('15px')
    expect(metrics.titleFontWeight).toBe('600')
    expect(metrics.actionHeight).toBe(28)

    expect(await grid.evaluate((element) => getComputedStyle(element).gap)).toBe('16px')

    // 桌面多列。
    const desktopXs = await grid.locator('> *').evaluateAll(
      (items) => items.map((item) => Math.round(item.getBoundingClientRect().x)),
    )
    expect(new Set(desktopXs).size).toBeGreaterThanOrEqual(2)

    // 窄屏单列且不横向溢出。
    await page.setViewportSize({ width: 360, height: 900 })
    const overflow = await page.evaluate(() => ({
      scrollWidth: document.documentElement.scrollWidth,
      clientWidth: document.documentElement.clientWidth,
    }))
    expect(overflow.scrollWidth).toBeLessThanOrEqual(overflow.clientWidth + 1)
    const narrowXs = await grid.locator('> *').evaluateAll(
      (items) => items.map((item) => Math.round(item.getBoundingClientRect().x)),
    )
    expect(new Set(narrowXs).size).toBe(1)
  })

  test('cancelling the create dialog writes nothing', async ({ page }) => {
    await page.goto(`${HARNESS_URL}?mode=list`)

    await page.getByRole('button', { name: '创建新画布' }).click()
    const dialog = page.getByRole('dialog', { name: '创建新画布' })
    await expect(dialog).toBeVisible()
    await dialog.getByRole('textbox', { name: '画布名称' }).fill('取消掉的画布')
    await dialog.getByRole('button', { name: '取消' }).click()

    await expect(dialog).toBeHidden()
    await expect(page.locator('#editor-marker')).toHaveCount(0)
    expect((await apiLog(page)).filter((entry) => entry.method === 'POST')).toEqual([])
  })

  test('confirming the create dialog posts exactly once with the typed name and enters the new canvas', async ({ page }) => {
    await page.goto(`${HARNESS_URL}?mode=list`)

    await page.getByRole('button', { name: '创建新画布' }).click()
    const dialog = page.getByRole('dialog', { name: '创建新画布' })
    await dialog.getByRole('textbox', { name: '画布名称' }).fill('研究看板')
    await dialog.getByRole('button', { name: '创建并进入' }).click()

    await expect(page.locator('#editor-marker')).toBeVisible()
    await expect(page.locator('#editor-marker')).toHaveAttribute(
      'data-canvas-id',
      '00000000-0000-4000-8000-000000000001',
    )
    const posts = (await apiLog(page)).filter((entry) => entry.method === 'POST')
    expect(posts).toHaveLength(1)
    expect(posts[0]!.body).toEqual({ title: '研究看板' })
  })

  test('a pending create keeps a single request and blocks repeated submit and cancel', async ({ page }) => {
    await page.goto(`${HARNESS_URL}?mode=list&create=pending`)

    await page.getByRole('button', { name: '创建新画布' }).click()
    const dialog = page.getByRole('dialog', { name: '创建新画布' })
    await dialog.getByRole('textbox', { name: '画布名称' }).fill('在途画布')
    const confirm = dialog.getByRole('button', { name: '创建并进入' })
    await confirm.click()
    await expect(confirm).toBeDisabled()

    // 在途期间重复点击与取消都不得产生第二次写入或关闭弹窗。
    await confirm.click({ force: true })
    await dialog.getByRole('button', { name: '取消' }).click({ force: true })
    await expect(dialog).toBeVisible()
    expect((await apiLog(page)).filter((entry) => entry.method === 'POST')).toHaveLength(1)
  })

  test('a failed create keeps the dialog and the typed name', async ({ page }) => {
    await page.goto(`${HARNESS_URL}?mode=list&create=error`)

    await page.getByRole('button', { name: '创建新画布' }).click()
    const dialog = page.getByRole('dialog', { name: '创建新画布' })
    const nameInput = dialog.getByRole('textbox', { name: '画布名称' })
    await nameInput.fill('重名画布')
    await dialog.getByRole('button', { name: '创建并进入' }).click()

    await expect(dialog.getByRole('alert')).toContainText('画布名称已存在')
    await expect(nameInput).toHaveValue('重名画布')
    await expect(dialog).toBeVisible()
    expect((await apiLog(page)).filter((entry) => entry.method === 'POST')).toHaveLength(1)
  })

  test('entering an existing card keeps its own id and never creates', async ({ page }) => {
    await page.goto(`${HARNESS_URL}?mode=list`)

    await page.getByRole('button', { name: '进入画布「Research board」' }).click()

    await expect(page.locator('#editor-marker')).toHaveAttribute(
      'data-canvas-id',
      '8d3b8a2e-4b9f-4c5d-9e6f-1a2b3c4d5e6f',
    )
    expect((await apiLog(page)).filter((entry) => entry.method === 'POST')).toEqual([])
  })

  test('an empty library only offers the create card and never creates automatically', async ({ page }) => {
    await page.goto(`${HARNESS_URL}?mode=empty`)

    await page.getByRole('button', { name: '创建新画布' }).click()
    await page.getByRole('dialog', { name: '创建新画布' }).getByRole('button', { name: '取消' }).click()

    await expect(page.locator('.resource-card')).toHaveCount(0)
    await expect(page.locator('.create-card')).toBeVisible()
    await expect(page.locator('#editor-marker')).toHaveCount(0)
    expect((await apiLog(page)).filter((entry) => entry.method === 'POST')).toEqual([])
  })

  test('a library read error offers an explicit retry', async ({ page }) => {
    await page.goto(`${HARNESS_URL}?mode=list-error`)

    await expect(page.getByRole('alert')).toContainText('画布列表加载失败')
    await page.getByRole('button', { name: '重试' }).click()

    await expect(page.locator('.resource-card-title')).toHaveText('Research board')
    expect((await apiLog(page)).filter((entry) => entry.method === 'POST')).toEqual([])
  })
})
