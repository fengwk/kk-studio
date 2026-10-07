import { resolve } from 'node:path'
import type { Page } from '@playwright/test'
import { test, expect } from './fixture'

const reportsDir = resolve(new URL('.', import.meta.url).pathname, '../../reports/layout')

async function expectSinglePlanningScroll(page: Page) {
  // 提示词完整自然高度，仅整列纵向滚动，不再把内容关进 50cqh 的嵌套滚动区。
  const metrics = await page.locator('.thread-system-prompt-body').evaluate((el) => ({
    overflowY: getComputedStyle(el).overflowY,
    scrollHeight: el.scrollHeight,
    clientHeight: el.clientHeight,
    columnOverflow: getComputedStyle(el.closest('.thread-debug-col-preview')!).overflowY,
  }))
  expect(metrics.overflowY).toBe('visible')
  expect(metrics.scrollHeight).toBeLessThanOrEqual(metrics.clientHeight + 1)
  expect(metrics.columnOverflow).toBe('auto')
}

test.describe('Responsive Debug Layout Real React Component Regression', () => {
  // Case 1: 1920x900 宽桌面 (宽面板 >= 1100px)
  test('1920x900 wide layout: bounded planning/history and remaining detail, independent scroll, no outer overflow', async ({
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

    // 配置/历史不会随大屏无限增长，详情获得所有余宽。
    const boxPreview = (await colPreview.boundingBox())!
    const boxEvents = (await colEvents.boundingBox())!
    const boxDetail = (await colDetail.boundingBox())!

    expect(boxPreview.width).toBeGreaterThanOrEqual(280)
    expect(boxPreview.width).toBeLessThanOrEqual(360)
    expect(boxEvents.width).toBeGreaterThanOrEqual(280)
    expect(boxEvents.width).toBeLessThanOrEqual(420)
    expect(boxDetail.width).toBeGreaterThan(1100)
    expect(boxPreview.width + boxEvents.width + boxDetail.width).toBeCloseTo((await shell.boundingBox())!.width, 0)

    // 4. 断言外框无纵向及横向滚动 (overflow hidden)
    const shellScroll = await shell.evaluate((el) => ({
      scrollHeight: el.scrollHeight,
      clientHeight: el.clientHeight,
      scrollWidth: el.scrollWidth,
      clientWidth: el.clientWidth,
    }))
    expect(shellScroll.scrollHeight).toBeLessThanOrEqual(shellScroll.clientHeight + 1)
    expect(shellScroll.scrollWidth).toBeLessThanOrEqual(shellScroll.clientWidth + 1)

    // 5. 各列唯一纵向滚动验证
    const promptBody = page.locator('.thread-system-prompt-body')
    const promptScrollBefore = await promptBody.evaluate((el) => el.scrollTop)
    const eventsList = page.locator('.thread-events')
    const eventsScrollBefore = await eventsList.evaluate((el) => el.scrollTop)
    const detailScrollBefore = await colDetail.evaluate((el) => el.scrollTop)
    expect(promptScrollBefore).toBe(0)

    // 滚动规划整列，提示词自身不产生滚动。
    await promptBody.evaluate((el) => {
      el.scrollTop = 50
    })
    expect(await promptBody.evaluate((el) => el.scrollTop)).toBe(0)
    await colPreview.evaluate((el) => { el.scrollTop = 50 })
    expect(await colPreview.evaluate((el) => el.scrollTop)).toBeGreaterThan(0)
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

    await expectSinglePlanningScroll(page)
    const payload = colDetail.locator('.thread-event-detail-payload')
    const payloadMetrics = await payload.evaluate((el) => ({
      overflowX: getComputedStyle(el).overflowX,
      scrollWidth: el.scrollWidth,
      clientWidth: el.clientWidth,
    }))
    expect(payloadMetrics.overflowX).toBe('auto')
    expect(payloadMetrics.scrollWidth).toBeGreaterThan(payloadMetrics.clientWidth)
    expect(await colDetail.evaluate((el) => el.scrollWidth - el.clientWidth)).toBeLessThanOrEqual(1)

    // 7. 底部 Composer 驻留且可用
    const composerBox = (await composer.boundingBox())!
    expect(composerBox.y + composerBox.height).toBeLessThanOrEqual(900)
    expect(composerBox.y).toBeGreaterThan(boxPreview.y + boxPreview.height - 2)

    const composerInput = page.locator('[data-testid="pane-main-composer-input"]')
    await composerInput.fill('测试任务指令')
    expect(await composerInput.inputValue()).toBe('测试任务指令')

    // 8. 验证快照按钮和复制按钮存在
    await colPreview.evaluate((el) => { el.scrollTop = 0 })
    const snapshotBtn = page.getByRole('button', { name: '查看当前调用冻结的规范化 ProviderRequest（非 HTTP 原始报文）', exact: true })
    await expect(snapshotBtn).toBeVisible()
    await expect(snapshotBtn).toHaveText('冻结调用输入')
    const copyBtn = page.getByRole('button', { name: /复制系统提示词|Copy system prompt/ })
    await expect(copyBtn).toBeVisible()

    // 9. 保存截图
    await colDetail.evaluate((el) => { el.scrollTop = 0 })
    await page.screenshot({ path: resolve(reportsDir, 'debug-wide-1920x900.png') })
    await page.screenshot({ path: resolve(reportsDir, 'debug-polish-wide.png') })
  })

  // Case 2: 3834x681 超宽矮窗口
  test('3834x681 ultrawide short layout: bounded side columns without squishing composer', async ({
    page,
  }) => {
    await page.setViewportSize({ width: 3834, height: 681 })
    await page.goto('/browser-tests/debug-harness.html')

    const colPreview = page.locator('.thread-debug-col-preview')
    const colEvents = page.locator('.thread-debug-col-events')
    const colDetail = page.locator('.thread-debug-col-detail')
    const composer = page.locator('[data-testid="pane-main-composer"]')

    // 超宽屏仍保持有界侧栏，不把详情限制在三分之一。
    const boxPreview = (await colPreview.boundingBox())!
    const boxEvents = (await colEvents.boundingBox())!
    const boxDetail = (await colDetail.boundingBox())!

    expect(boxPreview.width).toBe(360)
    expect(boxEvents.width).toBe(420)
    expect(boxDetail.width).toBeGreaterThan(3000)

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

    // 3. 规划列整体滚动；点击 Tool 唤起 Inspector 并聚焦关闭按钮
    await page.getByRole('tab', { name: /请求预览|Request Preview/ }).click()
    await expect(colPreview).toBeVisible()

    await expectSinglePlanningScroll(page)

    const readToolBtn = colPreview.getByRole('button', { name: /工具 read|Tool read/ })
    await readToolBtn.click()
    await expect(colDetail).toBeVisible()
    const inspectorCloseBtn = colDetail.getByRole('button', { name: /关闭检查器|Close inspector/ })
    await expect(inspectorCloseBtn).toBeFocused()

    // 4. 按 Escape 键关闭详情并回退到请求预览页签，且焦点恢复至被点击的 Tool 按钮
    await page.keyboard.press('Escape')
    await expect(colPreview).toBeVisible()
    await expect(colDetail).toBeHidden()
    await expect(readToolBtn).toBeFocused()

    // 保存截图
    await page.screenshot({ path: resolve(reportsDir, 'debug-narrow-954x934.png') })
    await page.screenshot({ path: resolve(reportsDir, 'debug-polish-narrow.png') })
  })

  // Case 4: 移动端单列整体滚动且无横向溢出
  test('360x740 mobile layout: no horizontal overflow and one planning scroll owner', async ({
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

    // 切到规划页签检查滚动归属
    await page.getByRole('tab', { name: /请求预览|Request Preview/ }).click()
    await expectSinglePlanningScroll(page)
    const planning = page.locator('.thread-debug-col-preview')
    expect(await planning.evaluate((el) => el.scrollWidth - el.clientWidth)).toBeLessThanOrEqual(1)
    await planning.locator('.thread-debug-environment').scrollIntoViewIfNeeded()
    await expect(planning.getByText('dev-node', { exact: true })).toHaveClass('status-pill is-neutral')
    await page.getByRole('tab', { name: '事件' }).click()
    await page.locator('.thread-event').first().click()
    const detail = page.locator('.thread-debug-col-detail')
    expect(await detail.evaluate((el) => el.scrollWidth - el.clientWidth)).toBeLessThanOrEqual(1)
    const payload = detail.locator('.thread-event-detail-payload')
    expect(await payload.evaluate((el) => el.scrollWidth)).toBeGreaterThan(await payload.evaluate((el) => el.clientWidth))

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
    // 恢复为三列，选中详情依旧可见
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

  // Case 9: 高度变化不能重新引入提示词嵌套滚动
  test('viewport height resize from 681px to 1100px preserves natural prompt height and column scrolling', async ({
    page,
  }) => {
    await page.goto('/browser-tests/debug-harness.html')

    const heights = [681, 750, 850, 950, 1100]
    for (const h of heights) {
      await page.setViewportSize({ width: 1400, height: h })

      await expectSinglePlanningScroll(page)
    }
  })

  test('Subagent and Cache inspector click, direct title, actual fields and Escape focus restoration', async ({
    page,
  }) => {
    // 954x934 窄屏视口
    await page.setViewportSize({ width: 954, height: 934 })
    await page.goto('/browser-tests/debug-harness.html')

    const colPreview = page.locator('.thread-debug-col-preview')
    const colDetail = page.locator('.thread-debug-col-detail')

    // 切到请求预览页签
    await page.getByRole('tab', { name: /请求预览|Request Preview/ }).click()
    await expect(colPreview).toBeVisible()

    // 1. 点击 Subagent helper chip
    const helperBtn = colPreview.getByRole('button', { name: /子代理 helper|Subagent helper/ })
    await expect(helperBtn).toBeVisible()
    await helperBtn.click()

    // 详情列展示，标题直写 'helper'，去前缀
    await expect(colDetail).toBeVisible()
    const inspector = colDetail.locator('[data-testid="thread-debug-inspector"]')
    await expect(inspector).toHaveAttribute('aria-label', 'helper')
    await expect(colDetail.locator('h3')).toHaveText('helper')
    await expect(colDetail.getByText('Execution and verification helper')).toBeVisible()

    // 按 Escape 局部关闭详情，焦点恢复至 helperBtn
    await page.keyboard.press('Escape')
    await expect(colDetail).toBeHidden()
    await expect(helperBtn).toBeFocused()

    // 2. 点击 Cache chip
    const cacheBtn = colPreview.locator('.thread-debug-meta-item').getByRole('button', { name: /缓存|Cache/ })
    await expect(cacheBtn).toBeVisible()
    await cacheBtn.click()

    // 详情列展示，标题直写 '缓存策略'，去前缀
    await expect(colDetail).toBeVisible()
    await expect(inspector).toHaveAttribute('aria-label', '缓存策略')
    await expect(colDetail.locator('h3')).toHaveText('缓存策略')
    await expect(colDetail.getByText('SHORT')).toBeVisible()
    // 会话缓存键就是真实 Session UUID；断点事实已从契约移除，不得再出现断点行。
    await expect(colDetail.getByText('00000000-0000-0000-0000-000000000001')).toBeVisible()
    await expect(colDetail.getByText(/断点/)).toHaveCount(0)

    // 按 Escape 局部关闭详情，焦点恢复至 cacheBtn
    await page.keyboard.press('Escape')
    await expect(colDetail).toBeHidden()
    await expect(cacheBtn).toBeFocused()
  })

  for (const width of [1400, 800]) {
    test(`first model-output selection, re-entry restoration and explicit close survive background refresh (${width}px)`, async ({ page }) => {
      await page.setViewportSize({ width, height: 900 })
      await page.goto('/browser-tests/debug-harness.html?viewState=1')
      await expect(page.locator('[data-event-id="ev-46"]')).toHaveAttribute('aria-selected', 'true')
      if (width < 1100) {
        await expect(page.getByRole('button', { name: '关闭事件详情' })).toBeFocused()
        await page.getByRole('tab', { name: '事件', exact: true }).click()
        // 即使存在选中详情，roving 导航也不能被详情自动聚焦打断。
        await page.keyboard.press('ArrowRight')
        await expect(page.getByRole('tab', { name: '详情', exact: true })).toBeFocused()
        await page.keyboard.press('ArrowLeft')
      }
      const row = page.locator('[data-event-id="ev-2"]')
      await row.click()
      if (width < 1100) await page.getByRole('tab', { name: '事件', exact: true }).click()
      await page.locator('.thread-events').evaluate((el) => { el.scrollTop = 97 })
      await page.getByRole('button', { name: 'Conversation', exact: true }).click()
      await expect(page.locator('.thread-events-shell')).toHaveCount(0)
      await page.getByRole('button', { name: 'Debug', exact: true }).click()
      await expect(row).toHaveAttribute('aria-selected', 'true')
      if (width < 1100) await page.getByRole('tab', { name: '事件', exact: true }).click()
      await expect.poll(() => page.locator('.thread-events').evaluate((el) => el.scrollTop)).toBe(97)
      if (width < 1100) await page.getByRole('tab', { name: '详情', exact: true }).click()
      await page.getByRole('button', { name: '关闭事件详情' }).click()
      await page.getByRole('button', { name: 'Refresh history' }).click()
      await expect(page.locator('[role="option"][aria-selected="true"]')).toHaveCount(0)
      await page.getByRole('button', { name: 'Conversation', exact: true }).click()
      await page.getByRole('button', { name: 'Debug', exact: true }).click()
      await expect(page.locator('[role="option"][aria-selected="true"]')).toHaveCount(0)
      if (width < 1100) await page.getByRole('tab', { name: '详情', exact: true }).click()
      await expect(page.getByTestId('thread-debug-placeholder')).toBeVisible()
    })
  }

  test('1100px container boundary switches between bounded columns and tabs without overflow', async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 900 })
    await page.goto('/browser-tests/debug-harness.html?paneWidth=1101px')
    const shell = page.locator('.thread-events-shell')
    expect(await shell.evaluate((el) => el.clientWidth)).toBe(1100)
    await expect(shell).toHaveAttribute('data-layout', 'wide')
    expect(await shell.evaluate((el) => el.scrollWidth - el.clientWidth)).toBeLessThanOrEqual(1)
    await page.getByTestId('pane-main').evaluate((el) => { el.style.width = '1100px' })
    await expect(shell).toHaveAttribute('data-layout', 'narrow')
    expect(await shell.evaluate((el) => el.clientWidth)).toBe(1099)
    await expect(page.getByRole('tablist')).toBeVisible()
    expect(await shell.evaluate((el) => el.scrollWidth - el.clientWidth)).toBeLessThanOrEqual(1)
  })

  test('shared prompt copy writes the real Chromium clipboard and exposes temporary success', async ({ page, context }) => {
    await page.setViewportSize({ width: 1400, height: 900 })
    await context.grantPermissions(['clipboard-read', 'clipboard-write'])
    await page.goto('/browser-tests/debug-harness.html')
    const prompt = await page.locator('.thread-system-prompt-body').textContent()
    const button = page.locator('.thread-debug-prompt-copy')
    await expect(button).toHaveAttribute('title', '复制系统提示词')
    await button.click()
    await expect(button).toHaveAccessibleName('已复制')
    expect(await page.evaluate(() => navigator.clipboard.readText())).toBe(prompt)
    await expect(button).toHaveAccessibleName('复制系统提示词')
    await expect(button).toHaveAttribute('title', '复制系统提示词')
    await expect(page.getByRole('alert')).toHaveCount(0)
  })
})
