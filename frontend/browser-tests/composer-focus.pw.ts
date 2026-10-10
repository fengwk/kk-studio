import { test, expect } from './fixture'

test.describe('Composer Focus Menu Real React Browser Regression', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/browser-tests/composer-harness.html')
    await page.waitForSelector('[data-testid="pane-1"]')
  })

  test('连续 2 次 Esc：第 1 次 blur 收起菜单且保留草稿，第 2 次 focusOnEscape 聚焦并自动重开 /th 菜单', async ({
    page,
  }) => {
    const pane1 = page.locator('[data-testid="pane-1"]')
    const editor = pane1.locator('.composer-editor')
    const palette = pane1.locator('.thread-command-palette')

    // 1. 聚焦并输入 /th
    await editor.click()
    await editor.fill('/th')
    await expect(palette).toBeVisible()
    await expect(editor).toBeFocused()

    // 2. 第 1 次按 Esc：区域聚焦 -> blur activeElement 并收起菜单，保留草稿 /th
    await page.keyboard.press('Escape')
    await expect(palette).not.toBeVisible()
    await expect(editor).not.toBeFocused()
    await expect(editor).toHaveText('/th')

    // 3. 第 2 次按 Esc：区域未聚焦 -> 全局 Escape 触发 focusOnEscape，聚焦回 editor 并根据保留的 /th 自动重开菜单
    await page.keyboard.press('Escape')
    await expect(editor).toBeFocused()
    await expect(palette).toBeVisible()
    await expect(editor).toHaveText('/th')
  })

  test('Outside click 收起菜单且保留草稿；Internal click 执行命令且不丢失点击', async ({
    page,
  }) => {
    const pane1 = page.locator('[data-testid="pane-1"]')
    const editor = pane1.locator('.composer-editor')
    const palette = pane1.locator('.thread-command-palette')
    const outsideBtn = page.locator('#outside-btn')
    const lastCmd = page.locator('#last-command-val')

    // 1. 输入 /debug 打开菜单
    await editor.click()
    await editor.fill('/debug')
    await expect(palette).toBeVisible()

    // 2. 内部点击：点击 palette 里的 debug 选项
    const debugOption = palette.locator('button', { hasText: 'debug' })
    await expect(debugOption).toBeVisible()
    await debugOption.click()

    // 验证命令正常触发执行，未因 mousedown/focusout 丢失 click，输入框清空
    await expect(lastCmd).toHaveText('debug')
    await expect(editor).toHaveText('')
    await expect(palette).not.toBeVisible()

    // 3. 再次输入 / 打开菜单，然后点击外部区域
    await editor.click()
    await editor.fill('/')
    await expect(palette).toBeVisible()

    // 点击 outside 按钮
    await outsideBtn.click()
    await expect(outsideBtn).toBeFocused()
    await expect(palette).not.toBeVisible()
    // 草稿 / 完整保留
    await expect(editor).toHaveText('/')
  })

  test('+ 按钮双模式：普通文本点击打开、失焦后再 focus 不重开；关闭 slash 提示时主动 blur 防立即再开', async ({
    page,
  }) => {
    const pane1 = page.locator('[data-testid="pane-1"]')
    const editor = pane1.locator('.composer-editor')
    const palette = pane1.locator('.thread-command-palette')
    const addBtn = pane1.locator('.thread-dock-add')
    const outsideBtn = page.locator('#outside-btn')

    // 1. 无 slash 下点击 + 按钮打开命令表
    await editor.click()
    await editor.fill('hello plain text')
    await expect(palette).not.toBeVisible()

    await addBtn.click()
    await expect(palette).toBeVisible()

    // 2. 点击外部失焦收起
    await outsideBtn.click()
    await expect(palette).not.toBeVisible()

    // 3. 再次点击 editor（重新获得焦点）：因为无 slash 且 plusMenuOpen 已清空，不得重开！
    await editor.click()
    await expect(editor).toBeFocused()
    await expect(palette).not.toBeVisible()

    // 4. 输入 /stop 打开 slash 提示
    await editor.fill('/stop')
    await expect(palette).toBeVisible()

    // 5. 点击 + 按钮关闭 slash 提示：必须主动 blur，避免因 editor 保持聚焦且保留 /stop 而立即再次打开！
    await addBtn.click()
    await expect(palette).not.toBeVisible()
    await expect(editor).not.toBeFocused()
    // 等待 100ms 确认没有立即重新弹出
    await page.waitForTimeout(100)
    await expect(palette).not.toBeVisible()
  })

  test('普通文本 Esc 键双向切换：聚焦时 Esc blur，未聚焦时 Esc 聚焦 editor', async ({
    page,
  }) => {
    const pane1 = page.locator('[data-testid="pane-1"]')
    const editor = pane1.locator('.composer-editor')

    await editor.click()
    await editor.fill('ordinary message')
    await expect(editor).toBeFocused()

    // 聚焦时 Esc：失焦
    await page.keyboard.press('Escape')
    await expect(editor).not.toBeFocused()
    await expect(editor).toHaveText('ordinary message')

    // 未聚焦时 Esc：聚焦并保留末尾光标
    await page.keyboard.press('Escape')
    await expect(editor).toBeFocused()
    await expect(editor).toHaveText('ordinary message')
  })

  test('Tab 键区域内部移焦不关闭菜单；移出区域后收起菜单并保留草稿', async ({
    page,
  }) => {
    const pane1 = page.locator('[data-testid="pane-1"]')
    const editor = pane1.locator('.composer-editor')
    const palette = pane1.locator('.thread-command-palette')
    const addBtn = pane1.locator('.thread-dock-add')

    await editor.click()
    await editor.fill('/th')
    await expect(palette).toBeVisible()

    // Tab 移动焦点到 + 按钮（区域内）
    await page.keyboard.press('Tab')
    await expect(addBtn).toBeFocused()
    // 菜单依然保持打开！
    await expect(palette).toBeVisible()

    // 继续多次 Tab 直至移出该 Composer 区域（聚焦到外部控件）
    await page.locator('#outside-btn').focus()
    await expect(palette).not.toBeVisible()
    await expect(editor).toHaveText('/th')
  })

  test('上层 Control Menu 与 Modal 优先拦截 Esc，消费后不冒泡二次处理', async ({
    page,
  }) => {
    const pane1 = page.locator('[data-testid="pane-1"]')
    const editor = pane1.locator('.composer-editor')
    const permControl = pane1.locator('button[aria-label="权限模式"]')
    const permListbox = pane1.locator('ul[aria-label="权限选项"]')
    const openModalBtn = page.locator('#open-modal-btn')
    const modalBackdrop = page.locator('.modal-backdrop-test')

    // 1. 测试 Control Menu 优先
    await permControl.click()
    await expect(permListbox).toBeVisible()

    // 按 Esc：应当先关闭权限选项菜单，消费 Esc，保持 editor 聚焦
    await page.keyboard.press('Escape')
    await expect(permListbox).not.toBeVisible()
    await expect(editor).toBeFocused()

    // 2. 测试 Modal 优先
    await openModalBtn.click()
    await expect(modalBackdrop).toBeVisible()

    // 模态框打开期间按 Esc：应仅关闭模态框，底层 Composer 不得抢夺焦点
    await page.keyboard.press('Escape')
    await expect(modalBackdrop).not.toBeVisible()
    await expect(editor).not.toBeFocused()
  })

  test('多 Pane 隔离：全局 Escape 仅唤醒 focusOnEscape 为 true 的 active pane', async ({
    page,
  }) => {
    const pane1 = page.locator('[data-testid="pane-1"]')
    const pane2 = page.locator('[data-testid="pane-2"]')
    const editor1 = pane1.locator('.composer-editor')
    const editor2 = pane2.locator('.composer-editor')
    const outsideBtn = page.locator('#outside-btn')

    // 焦点在外部
    await outsideBtn.click()
    await expect(outsideBtn).toBeFocused()
    await expect(editor1).not.toBeFocused()
    await expect(editor2).not.toBeFocused()

    // 全局按 Esc
    await page.keyboard.press('Escape')

    // 只有 Pane 1 聚焦，Pane 2 保持未聚焦
    await expect(editor1).toBeFocused()
    await expect(editor2).not.toBeFocused()
  })

  test('禁用 IME 组合键、按键重复与 defaultPrevented 时的误触发', async ({
    page,
  }) => {
    const pane1 = page.locator('[data-testid="pane-1"]')
    const editor = pane1.locator('.composer-editor')
    const palette = pane1.locator('.thread-command-palette')

    await editor.click()
    await editor.fill('/th')
    await expect(palette).toBeVisible()

    // 1. 模拟 IME keyCode 229 的 Escape 事件
    await editor.evaluate((el) => {
      const evt = new KeyboardEvent('keydown', { key: 'Escape', keyCode: 229, bubbles: true, cancelable: true })
      el.dispatchEvent(evt)
    })
    // 菜单未被关闭
    await expect(palette).toBeVisible()

    // 2. 模拟 event.repeat = true 的 Escape 事件
    await editor.evaluate((el) => {
      const evt = new KeyboardEvent('keydown', { key: 'Escape', repeat: true, bubbles: true, cancelable: true })
      el.dispatchEvent(evt)
    })
    // 菜单未被关闭
    await expect(palette).toBeVisible()

    // 3. 模拟 defaultPrevented = true 的 Escape 事件
    await editor.evaluate((el) => {
      const evt = new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true })
      evt.preventDefault()
      el.dispatchEvent(evt)
    })
    // 菜单未被关闭
    await expect(palette).toBeVisible()
  })

  test('失焦后取消待执行 focus 重试定时器，等待超过 32ms 仍稳定失焦不抢焦', async ({
    page,
  }) => {
    const pane1 = page.locator('[data-testid="pane-1"]')
    const editor = pane1.locator('.composer-editor')
    const outsideBtn = page.locator('#outside-btn')

    // 聚焦 editor
    await editor.click()
    await expect(editor).toBeFocused()

    // 点击外部按钮失焦
    await outsideBtn.click()
    await expect(outsideBtn).toBeFocused()

    // 等待超过 32ms（例如 100ms），验证定时器已彻底取消，焦点未被抢回
    await page.waitForTimeout(100)
    await expect(outsideBtn).toBeFocused()
    await expect(editor).not.toBeFocused()
  })
})

const BROWSER_VIEWPORTS = [
  { width: 390, height: 844, name: 'mobile-390' },
  { width: 1280, height: 800, name: 'desktop-1280' },
]

for (const vp of BROWSER_VIEWPORTS) {
  test.describe(`First-Send Draft Acceptance & Composer Focus at ${vp.width}x${vp.height} (${vp.name})`, () => {
    test.beforeEach(async ({ page }) => {
      await page.setViewportSize({ width: vp.width, height: vp.height })
      await page.goto('/browser-tests/composer-harness.html')
      await page.waitForSelector('[data-testid="pane-1"]')
    })

    test('首次草稿发送并重挂载后，新 editor 在 enabled 时恢复焦点并可立即继续键入', async ({
      page,
    }) => {
      const pane1 = page.locator('[data-testid="pane-1"]')
      const editor = pane1.locator('.composer-editor')
      const sendBtn = pane1.locator('button[aria-label="发送消息"]')
      const firstSendCheck = page.locator('#chk-first-send-mode')
      const pane1State = page.locator('#pane1-state')

      // 开启首次发送模拟模式（提交 -> pending/disabled -> remount 新 key -> disabled 降沿）
      await firstSendCheck.check()

      await editor.click()
      await editor.fill('第一句草稿')

      if (vp.width === 390) {
        await sendBtn.click()
      } else {
        await page.keyboard.press('Enter')
      }

      // 等待首发绑定与重新挂载完成且 disabled 降为 false
      await expect(pane1State).toHaveAttribute('data-disabled', 'false', { timeout: 5000 })
      await expect(pane1State).toHaveAttribute('data-pending', 'false')

      // 重新挂载后的 bound editor 成功恢复键入焦点
      await expect(editor).toBeFocused({ timeout: 5000 })

      // 无需重新点击，直接继续键入第二句
      await page.keyboard.type('第二句键入')
      await expect(editor).toHaveText('第二句键入')
    })

    test('pending 降沿遭遇 temporary disabled 时不丢意图，disabled 解除时恢复焦点', async ({
      page,
    }) => {
      const pane1 = page.locator('[data-testid="pane-1"]')
      const editor = pane1.locator('.composer-editor')
      const outsideBtn = page.locator('#outside-btn')
      const togglePendingBtn = page.locator('#btn-toggle-pane1-pending')
      const toggleDisabledBtn = page.locator('#btn-toggle-pane1-disabled')

      // 1. 初始聚焦 editor 并输入
      await editor.click()
      await expect(editor).toBeFocused()

      // 2. 模拟进入 pending 与 disabled 状态
      await togglePendingBtn.click()
      await toggleDisabledBtn.click()

      // 焦点移至外部按钮
      await outsideBtn.click()
      await expect(outsideBtn).toBeFocused()
      await expect(editor).not.toBeFocused()

      // 3. pending 降沿（请求完成），但此时 disabled 暂未解除
      await togglePendingBtn.click()
      // 验证仍在 disabled 时不抢焦，editor 依然不获得焦点
      await expect(togglePendingBtn).toBeFocused()
      await expect(editor).not.toBeFocused()

      // 4. disabled 解除（流式结束或元数据就绪）
      await toggleDisabledBtn.click()

      // 消费保留的恢复意图，焦点成功回到 editor
      await expect(editor).toBeFocused({ timeout: 5000 })
    })

    test('首次发送绑定完成时若存在 blocking modal，不得抢占模态框焦点', async ({
      page,
    }) => {
      const pane1 = page.locator('[data-testid="pane-1"]')
      const editor = pane1.locator('.composer-editor')
      const sendBtn = pane1.locator('button[aria-label="发送消息"]')
      const firstSendCheck = page.locator('#chk-first-send-mode')
      const openModalBtn = page.locator('#open-modal-btn')
      const modalCloseBtn = page.locator('#modal-close-btn')
      const pane1State = page.locator('#pane1-state')

      await firstSendCheck.check()
      await editor.click()
      await editor.fill('模态测试草稿')
      await sendBtn.click()

      // 在首发 acceptance 完成前打开 blocking modal
      await openModalBtn.click()
      await expect(modalCloseBtn).toBeVisible()
      await modalCloseBtn.focus()
      await expect(modalCloseBtn).toBeFocused()

      // 等待首发绑定流程结束且 disabled 恢复为 false
      await expect(pane1State).toHaveAttribute('data-disabled', 'false', { timeout: 5000 })

      // 验证模态框按钮依然保有焦点，底层的 Pane 1 没有抢占焦点
      await expect(modalCloseBtn).toBeFocused()
      await expect(editor).not.toBeFocused()
    })

    test('首次发送绑定完成时若焦点已切换到其他 pane，不得抢占其他 pane 焦点', async ({
      page,
    }) => {
      const pane1 = page.locator('[data-testid="pane-1"]')
      const pane2 = page.locator('[data-testid="pane-2"]')
      const editor1 = pane1.locator('.composer-editor')
      const editor2 = pane2.locator('.composer-editor')
      const sendBtn1 = pane1.locator('button[aria-label="发送消息"]')
      const firstSendCheck = page.locator('#chk-first-send-mode')
      const pane1State = page.locator('#pane1-state')

      await firstSendCheck.check()
      await editor1.click()
      await editor1.fill('Pane 1 首次发送')
      await sendBtn1.click()

      // 在 Pane 1 响应返回前，用户切换到 Pane 2 键入
      await editor2.click()
      await expect(editor2).toBeFocused()

      // 等待 Pane 1 绑定流程完成
      await expect(pane1State).toHaveAttribute('data-disabled', 'false', { timeout: 5000 })

      // Pane 2 保持聚焦，Pane 1 不得夺走焦点
      await expect(editor2).toBeFocused()
      await expect(editor1).not.toBeFocused()
    })
  })
}
