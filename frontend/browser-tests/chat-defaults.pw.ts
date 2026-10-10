import { expect, test, type Page } from './fixture'

const HARNESS_URL = '/browser-tests/chat-defaults-harness.html'

async function setupEnvironmentRoute(page: Page) {
  await page.route('**/api/harness/environments**', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify([
        {
          id: 'env-docker-node',
          name: 'docker-node',
          status: 'ONLINE',
          version: '1',
          createTime: '2026-10-01T00:00:00Z',
          updateTime: '2026-10-01T00:00:00Z',
        },
        {
          id: 'env-ubuntu',
          name: 'ubuntu',
          status: 'ONLINE',
          version: '1',
          createTime: '2026-10-01T00:00:00Z',
          updateTime: '2026-10-01T00:00:00Z',
        },
      ]),
    })
  })
}

test.describe('Chat defaults & metadata contracts', () => {
  test('create Chat: toggle YOLO, select environment, and submit intended configuration', async ({ page }) => {
    await setupEnvironmentRoute(page)
    await page.goto(HARNESS_URL)

    await page.getByTestId('open-create-btn').click()
    const dialog = page.getByRole('dialog')
    await expect(dialog).toBeVisible()

    // 默认 YOLO 为 false，环境为不选择环境 (null)
    const yoloSwitch = page.getByRole('switch', { name: 'YOLO 模式' })
    await expect(yoloSwitch).toHaveAttribute('aria-checked', 'false')

    const envSelect = page.getByRole('button', { name: '默认环境' })
    await expect(envSelect).toContainText('不选择环境')

    // 开启 YOLO
    await yoloSwitch.click()
    await expect(yoloSwitch).toHaveAttribute('aria-checked', 'true')

    // 选择环境 ubuntu
    await envSelect.click()
    const optionUbuntu = page.getByRole('option', { name: 'ubuntu' })
    await expect(optionUbuntu).toBeVisible()
    await optionUbuntu.click()
    await expect(envSelect).toContainText('ubuntu')

    // 填写标题并提交
    const nameInput = page.getByPlaceholder('Chat 名称（可重名）')
    await nameInput.fill('YOLO Custom Chat')
    await page.getByRole('button', { name: '确认创建' }).click()

    await expect(dialog).not.toBeVisible()

    // 验证提交的真实 payload 包含所选 YOLO 与环境
    const payloadText = await page.getByTestId('submitted-payload').textContent()
    expect(payloadText).not.toBe('none')
    const submitted = JSON.parse(payloadText!)
    expect(submitted).toEqual({
      mode: 'create',
      title: 'YOLO Custom Chat',
      agentName: 'assistant',
      yoloEnabled: true,
      environmentName: 'ubuntu',
    })
  })

  test('create Chat: default selection submits yoloEnabled=false and environmentName=null', async ({ page }) => {
    await setupEnvironmentRoute(page)
    await page.goto(HARNESS_URL)

    await page.getByTestId('open-create-btn').click()
    const dialog = page.getByRole('dialog')
    await expect(dialog).toBeVisible()

    const nameInput = page.getByPlaceholder('Chat 名称（可重名）')
    await nameInput.fill('Safe Chat')
    await page.getByRole('button', { name: '确认创建' }).click()

    await expect(dialog).not.toBeVisible()

    const payloadText = await page.getByTestId('submitted-payload').textContent()
    const submitted = JSON.parse(payloadText!)
    expect(submitted).toEqual({
      mode: 'create',
      title: 'Safe Chat',
      agentName: 'assistant',
      yoloEnabled: false,
      environmentName: null,
    })
  })

  test('edit Chat: echoes initial values, toggles YOLO off, and clears environment to null', async ({ page }) => {
    await setupEnvironmentRoute(page)
    await page.goto(HARNESS_URL)

    await page.getByTestId('open-edit-btn').click()
    const dialog = page.getByRole('dialog')
    await expect(dialog).toBeVisible()

    const nameInput = page.getByPlaceholder('Chat 名称（可重名）')
    await expect(nameInput).toHaveValue('Existing Chat')

    const yoloSwitch = page.getByRole('switch', { name: 'YOLO 模式' })
    await expect(yoloSwitch).toHaveAttribute('aria-checked', 'true')

    const envSelect = page.getByRole('button', { name: '默认环境' })
    await expect(envSelect).toContainText('docker-node')

    // 关闭 YOLO
    await yoloSwitch.click()
    await expect(yoloSwitch).toHaveAttribute('aria-checked', 'false')

    // 清空环境为 null（选择“不选择环境”）
    await envSelect.click()
    const optionNoEnv = page.getByRole('option', { name: '不选择环境' })
    await expect(optionNoEnv).toBeVisible()
    await optionNoEnv.click()
    await expect(envSelect).toContainText('不选择环境')

    // 保存修改
    await page.getByRole('button', { name: '保存修改' }).click()
    await expect(dialog).not.toBeVisible()

    const payloadText = await page.getByTestId('submitted-payload').textContent()
    const submitted = JSON.parse(payloadText!)
    expect(submitted).toEqual({
      mode: 'edit',
      title: 'Existing Chat',
      agentName: 'assistant',
      yoloEnabled: false,
      environmentName: null,
    })
  })

  test('edit Chat: preserves unavailable environment as selectable option', async ({ page }) => {
    await setupEnvironmentRoute(page)
    await page.goto(HARNESS_URL)

    await page.getByTestId('open-edit-unavailable-btn').click()
    const dialog = page.getByRole('dialog')
    await expect(dialog).toBeVisible()

    const envSelect = page.getByRole('button', { name: '默认环境' })
    await expect(envSelect).toContainText('deleted-env （不可用）')

    await envSelect.click()
    const unavailableOption = page.getByRole('option', { name: 'deleted-env （不可用）' })
    await expect(unavailableOption).toBeVisible()
  })

  test('ChatCard: renders YOLO tag and default environment in metadata', async ({ page }) => {
    await setupEnvironmentRoute(page)
    await page.goto(HARNESS_URL)

    const cards = page.locator('.resource-card')
    await expect(cards).toHaveCount(2)

    // 第一张卡片：YOLO 开启，docker-node
    const card1 = cards.nth(0)
    await expect(card1).toContainText('YOLO Docker Chat')
    await expect(card1).toContainText('YOLO 模式')
    await expect(card1).toContainText('开启')
    await expect(card1).toContainText('默认环境')
    await expect(card1).toContainText('docker-node')

    // 第二张卡片：YOLO 关闭，不选择环境
    const card2 = cards.nth(1)
    await expect(card2).toContainText('Safe Null Env Chat')
    await expect(card2).toContainText('YOLO 模式')
    await expect(card2).toContainText('关闭')
    await expect(card2).toContainText('默认环境')
    await expect(card2).toContainText('不选择环境')
  })

  test('desktop layout 1280px: fits without horizontal overflow', async ({ page }) => {
    await setupEnvironmentRoute(page)
    await page.setViewportSize({ width: 1280, height: 800 })
    await page.goto(`${HARNESS_URL}?open=create`)

    const dialog = page.getByRole('dialog')
    await expect(dialog).toBeVisible()

    const pageOverflow = await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth + 1,
    )
    expect(pageOverflow).toBe(true)

    const dialogOverflow = await dialog.evaluate(
      (el) => el.scrollWidth <= el.clientWidth + 1,
    )
    expect(dialogOverflow).toBe(true)
  })

  test('mobile layout 390px: fits without horizontal overflow and form controls remain operable', async ({ page }) => {
    await setupEnvironmentRoute(page)
    await page.setViewportSize({ width: 390, height: 844 })
    await page.goto(`${HARNESS_URL}?open=create`)

    const dialog = page.getByRole('dialog')
    await expect(dialog).toBeVisible()

    const pageOverflow = await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth + 1,
    )
    expect(pageOverflow).toBe(true)

    const dialogOverflow = await dialog.evaluate(
      (el) => el.scrollWidth <= el.clientWidth + 1,
    )
    expect(dialogOverflow).toBe(true)

    // 检查移动端各控件均正常渲染且可触达
    const yoloSwitch = page.getByRole('switch', { name: 'YOLO 模式' })
    await expect(yoloSwitch).toBeVisible()
    await yoloSwitch.click()
    await expect(yoloSwitch).toHaveAttribute('aria-checked', 'true')

    const envSelect = page.getByRole('button', { name: '默认环境' })
    await expect(envSelect).toBeVisible()
    await envSelect.click()
    const option = page.getByRole('option', { name: 'ubuntu' })
    await expect(option).toBeVisible()
    await option.click()
    await expect(envSelect).toContainText('ubuntu')

    await expect(page.getByRole('button', { name: '确认创建' })).toBeVisible()
    await expect(page.getByRole('button', { name: '取消' })).toBeVisible()
  })
})
