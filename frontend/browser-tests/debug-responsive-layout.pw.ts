import { resolve } from 'node:path'
import { test, expect } from '@playwright/test'

const reportsDir = resolve(new URL('.', import.meta.url).pathname, '../../reports/layout')

test.describe('Responsive Debug Layout Real React Component Regression', () => {
  // Case 1: 1920x900 宽桌面 (宽面板 >= 1100px)
  test('1920x900 wide layout: 3 equal-width columns, independent scroll, no outer overflow, composer usable', async ({
    page,
  }) => {
    await page.setViewportSize({ width: 1920, height: 900 })
    await page.goto('/browser-tests/debug-harness.html')

    const shell = page.locator('.thread-events-shell')
    await expect(shell).toHaveAttribute('data-layout', 'wide')

    const colPreview = page.locator('.thread-debug-col-preview')
    const colEvents = page.locator('.thread-debug-col-events')
    const colDetail = page.locator('.thread-debug-col-detail')
    const composer = page.locator('[data-testid="pane-main-composer"]')
    const tabs = page.locator('.thread-debug-tabs')

    // 1. 宽屏下 tabs 必须隐藏（被 suppressed）
    await expect(tabs).toHaveCount(0)

    // 2. 三列必须同时可见
    await expect(colPreview).toBeVisible()
    await expect(colEvents).toBeVisible()
    await expect(colDetail).toBeVisible()

    // 3. 断言三列等宽 (误差 <= 2px)
    const boxPreview = (await colPreview.boundingBox())!
    const boxEvents = (await colEvents.boundingBox())!
    const boxDetail = (await colDetail.boundingBox())!

    expect(Math.abs(boxPreview.width - boxEvents.width)).toBeLessThanOrEqual(2)
    expect(Math.abs(boxEvents.width - boxDetail.width)).toBeLessThanOrEqual(2)
    expect(boxPreview.width).toBeGreaterThan(550)

    // 4. 断言外框无纵向及横向滚动 (overflow hidden)
    const shellScroll = await shell.evaluate((el) => ({
      scrollHeight: el.scrollHeight,
      clientHeight: el.clientHeight,
      scrollWidth: el.scrollWidth,
      clientWidth: el.clientWidth,
    }))
    expect(shellScroll.scrollHeight).toBeLessThanOrEqual(shellScroll.clientHeight + 1)
    expect(shellScroll.scrollWidth).toBeLessThanOrEqual(shellScroll.clientWidth + 1)

    // 5. 各列独立纵向滚动验证 (且每列内部无嵌套纵向滚动)
    const previewScrollBefore = await colPreview.evaluate((el) => el.scrollTop)
    const eventsList = page.locator('.thread-events')
    const eventsScrollBefore = await eventsList.evaluate((el) => el.scrollTop)
    const detailScrollBefore = await colDetail.evaluate((el) => el.scrollTop)
    expect(previewScrollBefore).toBe(0)

    // 滚动 Preview 列
    await colPreview.evaluate((el) => {
      el.scrollTop = 150
    })
    const previewScrollAfter = await colPreview.evaluate((el) => el.scrollTop)
    expect(previewScrollAfter).toBeGreaterThan(0)
    // 其他两列滚动不受影响
    expect(await eventsList.evaluate((el) => el.scrollTop)).toBe(eventsScrollBefore)
    expect(await colDetail.evaluate((el) => el.scrollTop)).toBe(detailScrollBefore)

    // 滚动 Events 列表
    await eventsList.evaluate((el) => {
      el.scrollTop = 200
    })
    expect(await eventsList.evaluate((el) => el.scrollTop)).toBeGreaterThan(0)
    expect(await colDetail.evaluate((el) => el.scrollTop)).toBe(detailScrollBefore)

    // 选中一个事件并滚动 Detail 列
    await page.locator('.thread-event').first().click()
    await expect(colDetail.locator('.thread-event-detail')).toBeVisible()

    await colDetail.evaluate((el) => {
      el.scrollTop = 100
    })
    expect(await colDetail.evaluate((el) => el.scrollTop)).toBeGreaterThan(0)

    // 6. 验证系统提示词无嵌套纵向滚动（无 overflow-y: auto/scroll）
    const promptPreScrollable = await page.locator('.thread-system-prompt-body').evaluate((el) => {
      const style = window.getComputedStyle(el)
      return style.overflowY === 'auto' || style.overflowY === 'scroll'
    })
    expect(promptPreScrollable).toBe(false)

    // 7. 底部 Composer 驻留且可用
    const composerBox = (await composer.boundingBox())!
    expect(composerBox.y + composerBox.height).toBeLessThanOrEqual(900)
    expect(composerBox.y).toBeGreaterThan(boxPreview.y + boxPreview.height - 2)

    const composerInput = page.locator('[data-testid="pane-main-composer-input"]')
    await composerInput.fill('测试任务指令')
    expect(await composerInput.inputValue()).toBe('测试任务指令')

    // 8. 保存截图
    await page.screenshot({ path: resolve(reportsDir, 'debug-wide-1920x900.png') })
  })

  // Case 2: 3834x681 超宽矮窗口
  test('3834x681 ultrawide short layout: 3 equal-width columns without squishing composer', async ({
    page,
  }) => {
    await page.setViewportSize({ width: 3834, height: 681 })
    await page.goto('/browser-tests/debug-harness.html')

    const colPreview = page.locator('.thread-debug-col-preview')
    const colEvents = page.locator('.thread-debug-col-events')
    const colDetail = page.locator('.thread-debug-col-detail')
    const composer = page.locator('[data-testid="pane-main-composer"]')

    // 三列并排等宽
    const boxPreview = (await colPreview.boundingBox())!
    const boxEvents = (await colEvents.boundingBox())!
    const boxDetail = (await colDetail.boundingBox())!

    expect(Math.abs(boxPreview.width - boxEvents.width)).toBeLessThanOrEqual(2)
    expect(Math.abs(boxEvents.width - boxDetail.width)).toBeLessThanOrEqual(2)
    expect(boxPreview.width).toBeGreaterThan(1200)

    // 外层容器无滚动
    const shell = page.locator('.thread-events-shell')
    const shellScroll = await shell.evaluate((el) => ({
      scrollHeight: el.scrollHeight,
      clientHeight: el.clientHeight,
    }))
    expect(shellScroll.scrollHeight).toBeLessThanOrEqual(shellScroll.clientHeight + 1)

    // Composer 位于底部且可见可用
    const composerBox = (await composer.boundingBox())!
    expect(composerBox.y + composerBox.height).toBeLessThanOrEqual(681)
    await expect(page.locator('[data-testid="pane-main-composer-submit"]')).toBeVisible()

    // 保存截图
    await page.screenshot({ path: resolve(reportsDir, 'debug-ultrawide-3834x681.png') })
  })

  // Case 3: 954x934 窄窗口自适应、Tabs 切换与焦点流转
  test('954x934 narrow layout: tabs switch cleanly, single column full width, detail activation on click and focus restoration', async ({
    page,
  }) => {
    await page.setViewportSize({ width: 954, height: 934 })
    await page.goto('/browser-tests/debug-harness.html')

    const shell = page.locator('.thread-events-shell')
    await expect(shell).toHaveAttribute('data-layout', 'narrow')

    const tabs = page.locator('.thread-debug-tabs')
    await expect(tabs).toBeVisible()

    // 默认展示事件列表单列
    const colEvents = page.locator('.thread-debug-col-events')
    const colPreview = page.locator('.thread-debug-col-preview')
    const colDetail = page.locator('.thread-debug-col-detail')

    await expect(colEvents).toBeVisible()
    await expect(colPreview).toBeHidden()
    await expect(colDetail).toBeHidden()

    // 事件列占满可用宽度
    const boxEvents = (await colEvents.boundingBox())!
    expect(boxEvents.width).toBeGreaterThan(900)

    // 1. 点击事件项：自动激活详情页签，且焦点自动安全引导至关闭按钮
    const targetEvent = page.locator('.thread-event').nth(2)
    await targetEvent.click()

    await expect(colDetail).toBeVisible()
    await expect(colEvents).toBeHidden()
    const boxDetail = (await colDetail.boundingBox())!
    expect(boxDetail.width).toBeGreaterThan(900)

    const closeBtn = colDetail.locator('button.thread-interaction-close')
    await expect(closeBtn).toBeFocused()

    // 2. 点击关闭按钮：回退到事件列表，且焦点恢复至具有键盘交互的 listbox（非不可 focus 的 option div）
    await closeBtn.click()
    await expect(colEvents).toBeVisible()
    await expect(colDetail).toBeHidden()
    const eventsList = page.locator('.thread-events')
    await expect(eventsList).toBeFocused()

    // 3. 点击请求预览页签并点击 Tool 按钮：唤起 Tool Inspector 并聚焦关闭按钮
    await page.getByRole('tab', { name: '请求预览' }).click()
    await expect(colPreview).toBeVisible()

    const readToolBtn = colPreview.locator('button[aria-label="Tool read"]')
    await readToolBtn.click()
    await expect(colDetail).toBeVisible()
    const inspectorCloseBtn = colDetail.locator('button[aria-label="Close inspector"]')
    await expect(inspectorCloseBtn).toBeFocused()

    // 4. 按 Escape 键关闭详情并回退到请求预览页签，且焦点恢复至被点击的 Tool 按钮
    await page.keyboard.press('Escape')
    await expect(colPreview).toBeVisible()
    await expect(colDetail).toBeHidden()
    await expect(readToolBtn).toBeFocused()

    // 保存截图
    await page.screenshot({ path: resolve(reportsDir, 'debug-narrow-954x934.png') })
  })

  // Case 4: 360x740 移动端无横向溢出
  test('360x740 mobile layout: no horizontal overflow, tabs cleanly switch', async ({
    page,
  }) => {
    await page.setViewportSize({ width: 360, height: 740 })
    await page.goto('/browser-tests/debug-harness.html')

    const tabs = page.locator('.thread-debug-tabs')
    await expect(tabs).toBeVisible()

    const shell = page.locator('.thread-events-shell')
    const shellScroll = await shell.evaluate((el) => ({
      scrollWidth: el.scrollWidth,
      clientWidth: el.clientWidth,
    }))
    expect(shellScroll.scrollWidth).toBeLessThanOrEqual(shellScroll.clientWidth + 1)

    const composerInput = page.locator('[data-testid="pane-main-composer-input"]')
    await expect(composerInput).toBeVisible()

    await page.screenshot({ path: resolve(reportsDir, 'debug-mobile-360x740.png') })
  })

  // Case 5: 1920x1080 大窗口内 480 宽 pane (严格按容器实际宽度判定，非 window viewport)
  test('480px pane inside 1920x1080 window: must use narrow tab layout based on container width', async ({
    page,
  }) => {
    await page.setViewportSize({ width: 1920, height: 1080 })
    await page.goto('/browser-tests/debug-harness.html?paneWidth=480px')

    const shell = page.locator('.thread-events-shell')
    await expect(shell).toHaveAttribute('data-layout', 'narrow')

    const tabs = page.locator('.thread-debug-tabs')
    const colEvents = page.locator('.thread-debug-col-events')
    const colPreview = page.locator('.thread-debug-col-preview')

    await expect(tabs).toBeVisible()
    await expect(colEvents).toBeVisible()
    await expect(colPreview).toBeHidden()

    const boxEvents = (await colEvents.boundingBox())!
    expect(boxEvents.width).toBeGreaterThan(450)
    expect(boxEvents.width).toBeLessThanOrEqual(480)

    await page.screenshot({ path: resolve(reportsDir, 'debug-pane-480px.png') })
  })

  // Case 6: 动态 Pane 容器 Resize 切换 (wide -> narrow -> wide)
  test('dynamic container resize smoothly transitions layoutMode and preserves selection', async ({
    page,
  }) => {
    await page.setViewportSize({ width: 1400, height: 900 })
    await page.goto('/browser-tests/debug-harness.html')

    const shell = page.locator('.thread-events-shell')
    await expect(shell).toHaveAttribute('data-layout', 'wide')

    // 在宽模式下选择一个事件
    const firstEvent = page.locator('.thread-event').first()
    await firstEvent.click()
    await expect(page.locator('.thread-event-detail')).toBeVisible()

    // 动态缩窄容器至 750px
    await page.locator('[data-testid="test-controls"]').evaluate((el) => {
      ;(el as HTMLElement).style.display = 'flex'
    })
    await page.locator('[data-testid="btn-resize-narrow"]').click()

    // ResizeObserver 监听到宽度变化，自动切为 narrow 模式
    await expect(shell).toHaveAttribute('data-layout', 'narrow')
    // 选中的事件详情依然保持展示
    await expect(page.locator('.thread-debug-col-detail')).toBeVisible()
    await expect(page.locator('.thread-event-detail')).toBeVisible()

    // 再动态恢复容器至 1250px
    await page.locator('[data-testid="btn-resize-wide"]').click()
    await expect(shell).toHaveAttribute('data-layout', 'wide')
    // 恢复为 3 等宽列，选中详情依旧可见
    await expect(page.locator('.thread-debug-col-preview')).toBeVisible()
    await expect(page.locator('.thread-debug-col-events')).toBeVisible()
    await expect(page.locator('.thread-debug-col-detail')).toBeVisible()
  })

  // Case 7: 真实 WAI-ARIA Tabs 键盘导航 (Roving tabIndex)
  test('WAI-ARIA roving tabIndex and keyboard navigation across narrow tabs', async ({
    page,
  }) => {
    await page.setViewportSize({ width: 800, height: 800 })
    await page.goto('/browser-tests/debug-harness.html')

    const previewTab = page.getByRole('tab', { name: '请求预览' })
    const eventsTab = page.getByRole('tab', { name: '事件' })
    const detailTab = page.getByRole('tab', { name: '详情' })

    // 初始处于事件 tab，聚焦它
    await eventsTab.focus()
    await expect(eventsTab).toBeFocused()
    await expect(eventsTab).toHaveAttribute('tabindex', '0')
    await expect(previewTab).toHaveAttribute('tabindex', '-1')
    await expect(detailTab).toHaveAttribute('tabindex', '-1')

    // 按 ArrowRight 移动到 detailTab
    await page.keyboard.press('ArrowRight')
    await expect(detailTab).toBeFocused()
    await expect(detailTab).toHaveAttribute('aria-selected', 'true')
    await expect(detailTab).toHaveAttribute('tabindex', '0')
    await expect(eventsTab).toHaveAttribute('tabindex', '-1')

    // 按 Home 键跳到第一个 tab（请求预览）
    await page.keyboard.press('Home')
    await expect(previewTab).toBeFocused()
    await expect(previewTab).toHaveAttribute('aria-selected', 'true')
    await expect(previewTab).toHaveAttribute('tabindex', '0')

    // 按 End 键跳到最后一个 tab（详情）
    await page.keyboard.press('End')
    await expect(detailTab).toBeFocused()
    await expect(detailTab).toHaveAttribute('aria-selected', 'true')

    // 按 ArrowLeft 回到事件 tab
    await page.keyboard.press('ArrowLeft')
    await expect(eventsTab).toBeFocused()
    await expect(eventsTab).toHaveAttribute('aria-selected', 'true')
  })

  // Case 8: 多 Pane 分屏 ID 隔离与局部 Escape 互不干扰
  test('multiple split panes isolate IDs and handle Escape locally without cross-pane interference', async ({
    page,
  }) => {
    await page.setViewportSize({ width: 2560, height: 900 })
    await page.goto('/browser-tests/debug-harness.html?multi=1')

    const pane1 = page.locator('[data-testid="pane-1"]')
    const pane2 = page.locator('[data-testid="pane-2"]')

    await expect(pane1).toBeVisible()
    await expect(pane2).toBeVisible()

    // 验证两 pane 内部基于 React useId 生成的 DOM ID 完全独立隔离
    const pane1FirstEventId = await pane1.locator('.thread-event').first().getAttribute('id')
    const pane2FirstEventId = await pane2.locator('.thread-event').first().getAttribute('id')

    expect(pane1FirstEventId).toBeTruthy()
    expect(pane2FirstEventId).toBeTruthy()
    expect(pane1FirstEventId).not.toBe(pane2FirstEventId)

    // 在 Pane 1 中选中事件
    await pane1.locator('.thread-event').first().click()
    await expect(pane1.locator('.thread-event-detail')).toBeVisible()
    // Pane 2 此时未选中事件，显示无选中占位
    await expect(pane2.locator('[data-testid="thread-debug-placeholder"]')).toBeVisible()

    // 在 Pane 1 内部按 Escape 键关闭详情
    await pane1.locator('button.thread-interaction-close').focus()
    await page.keyboard.press('Escape')

    // Pane 1 详情关闭，显示占位
    await expect(pane1.locator('[data-testid="thread-debug-placeholder"]')).toBeVisible()
    // Pane 2 状态完全不受影响
    await expect(pane2.locator('[data-testid="thread-debug-placeholder"]')).toBeVisible()
  })
})
