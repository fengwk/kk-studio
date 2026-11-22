import { expect, test, type Page } from './fixture'

const HARNESS_URL = '/browser-tests/chat-defaults-harness.html'

async function setupEnvironmentRoute(page: Page) {
  await page.route('**/api/platform/environments**', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify([
        {
          id: 'env-docker-node',
          name: 'docker-node',
          status: 'READY',
          version: '1',
          createdAt: 1790812800000,
          updatedAt: 1790812800000,
        },
        {
          id: 'env-ubuntu',
          name: 'ubuntu',
          status: 'READY',
          version: '1',
          createdAt: 1790812800000,
          updatedAt: 1790812800000,
        },
      ]),
    })
  })
}

test.describe('Chat defaults & metadata contracts', () => {
  test('mobile create dialog keeps the YOLO switch and footer reachable', async ({ page }, testInfo) => {
    await page.setViewportSize({ width: 360, height: 640 })
    await setupEnvironmentRoute(page)
    await page.goto(HARNESS_URL)
    await page.getByTestId('open-create-btn').click()
    const dialog = page.getByRole('dialog')
    const yolo = dialog.getByRole('switch', { name: 'YOLO 模式' })
    const box = (await yolo.boundingBox())!
    expect(box.width).toBeGreaterThanOrEqual(44)
    expect(box.height).toBeGreaterThanOrEqual(44)
    await yolo.click()
    await expect(yolo).toHaveAttribute('aria-checked', 'true')
    await expect(dialog.getByRole('button', { name: '创建' })).toBeVisible()
    await page.screenshot({ path: testInfo.outputPath('chat-create-mobile.png'), animations: 'disabled' })
  })

  test('Agent search confirms without submitting the Chat form', async ({ page }, testInfo) => {
    await page.setViewportSize({ width: 360, height: 640 })
    await setupEnvironmentRoute(page)
    await page.goto(HARNESS_URL)
    await page.getByTestId('open-create-btn').click()
    const dialog = page.getByRole('dialog')
    await dialog.getByPlaceholder('对话名称').fill('Review Chat')
    const field = dialog.getByRole('button', { name: 'Agent', exact: true })
    const search = dialog.getByRole('combobox', { name: '搜索' })

    await field.click()
    await expect(search).toBeFocused()
    await expect(dialog.getByRole('option', { name: /assistant/ })).toHaveAttribute('aria-current', 'true')
    await search.press('ArrowDown')
    await expect(dialog.getByRole('option', { name: /reviewer/ })).toHaveAttribute('aria-selected', 'true')
    await expect(field).toHaveText('assistant')
    await search.press('Escape')
    await expect(search).not.toBeVisible()
    await expect(field).toBeFocused()

    await field.press('Enter')
    await search.fill('missing')
    await expect(dialog.getByRole('status')).toContainText('没有匹配')
    await search.press('Enter')
    await expect(field).toHaveText('assistant')
    await search.fill('pull requests')
    const reviewer = dialog.getByRole('option', { name: 'reviewer Checks pull requests' })
    await expect(reviewer).toBeInViewport()
    await page.screenshot({ path: testInfo.outputPath('agent-search-mobile.png'), animations: 'disabled' })
    await search.press('Enter')
    await expect(search).not.toBeVisible()
    await expect(field).toBeFocused()
    await expect(field).toHaveText('reviewer')
    await expect(page.getByTestId('submitted-payload')).toHaveText('none')
    await dialog.getByRole('button', { name: '创建' }).click()
    await expect(page.getByTestId('submitted-payload')).toContainText('"agentName":"reviewer"')
  })

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
    const nameInput = page.getByPlaceholder('对话名称')
    await nameInput.fill('YOLO Custom Chat')
    await page.getByRole('button', { name: '创建' }).click()

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

    const nameInput = page.getByPlaceholder('对话名称')
    await nameInput.fill('Safe Chat')
    await page.getByRole('button', { name: '创建' }).click()

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

    const nameInput = page.getByPlaceholder('对话名称')
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
    await expect(envSelect).toContainText('deleted-env · 不可用')

    await envSelect.click()
    const unavailableOption = page.getByRole('option', { name: 'deleted-env · 不可用' })
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

    await expect(page.getByRole('button', { name: '创建' })).toBeVisible()
    await expect(page.getByRole('button', { name: '取消' })).toBeVisible()
  })

  // YOLO 行排版契约：标签与说明各自独立成行，开关垂直居中留在文字块右侧。
  const yoloRowCopy = {
    'zh-CN': {
      label: 'YOLO 模式',
      description: '自动批准工具调用，跳过人工确认',
    },
    'en-US': {
      label: 'YOLO Mode',
      description: 'Automatically approve tool calls without manual confirmation',
    },
  } as const

  const yoloRowViewports = [
    { name: 'desktop', width: 1440, height: 900 },
    { name: 'mobile', width: 390, height: 844 },
  ] as const

  for (const locale of ['zh-CN', 'en-US'] as const) {
    for (const viewport of yoloRowViewports) {
      test(`create Chat YOLO row: separate label/description with the switch centered beside (${locale} ${viewport.width}x${viewport.height})`, async ({ page }, testInfo) => {
        const copy = yoloRowCopy[locale]
        await page.setViewportSize({ width: viewport.width, height: viewport.height })
        await setupEnvironmentRoute(page)
        await page.goto(`${HARNESS_URL}?open=create&locale=${locale}`)

        const dialog = page.getByRole('dialog')
        await expect(dialog).toBeVisible()

        const row = dialog.locator('.create-chat-yolo-row')
        const text = row.locator('.create-chat-yolo-text')
        const label = row.locator('.field-label')
        const description = row.locator('.create-chat-yolo-description')
        const yoloSwitch = row.getByRole('switch')

        // 标签与说明是两个独立元素，文案各自完整，开关仍由标签提供无障碍名称。
        await expect(label).toHaveText(copy.label)
        await expect(description).toHaveText(copy.description)
        await expect(yoloSwitch).toHaveAccessibleName(copy.label)
        await expect(yoloSwitch).toHaveAttribute('aria-checked', 'false')

        // 滚到行可见位置，让几何断言和截图都基于同一个稳定状态。
        await row.scrollIntoViewIfNeeded()

        const labelBox = (await label.boundingBox())!
        const descriptionBox = (await description.boundingBox())!
        const textBox = (await text.boundingBox())!
        const switchBox = (await yoloSwitch.boundingBox())!

        // 标签在上、说明在下且左缘对齐：说明换行也不会挤回标签同一行。
        expect(labelBox.y + labelBox.height).toBeLessThanOrEqual(descriptionBox.y + 0.5)
        expect(Math.abs(descriptionBox.x - labelBox.x)).toBeLessThanOrEqual(1)

        // 开关在文字块右侧，并与文字块的垂直中心对齐。
        expect(switchBox.x).toBeGreaterThanOrEqual(textBox.x + textBox.width)
        const textCenterY = textBox.y + textBox.height / 2
        const switchCenterY = switchBox.y + switchBox.height / 2
        expect(Math.abs(switchCenterY - textCenterY)).toBeLessThanOrEqual(1)

        // 该行与弹窗、页面都不产生横向溢出。
        expect(await row.evaluate((el) => el.scrollWidth <= el.clientWidth + 1)).toBe(true)
        expect(await dialog.evaluate((el) => el.scrollWidth <= el.clientWidth + 1)).toBe(true)
        expect(
          await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1),
        ).toBe(true)

        await page.screenshot({
          path: testInfo.outputPath(`chat-yolo-row-${locale}-${viewport.name}-${viewport.width}x${viewport.height}.png`),
          animations: 'disabled',
        })
      })
    }
  }
})
