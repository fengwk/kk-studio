import { expect, test } from '@playwright/test'

test.describe('Chat Workspace 1-9 Panes Layout, Footer & User Attachments', () => {
  test('Layout switcher supports 1-9 panes with accurate CSS grid bounding boxes and responsive stacking', async ({
    page,
  }) => {
    // 宽屏视口 1920x1080
    await page.setViewportSize({ width: 1920, height: 1080 })
    await page.goto('/browser-tests/chat-layout-harness.html')

    const select = page.locator('#chat-layout-select')
    await expect(select).toBeVisible()
    await expect(select).toHaveValue('split-2')

    // 1. 验证 2 分屏布局：2 个 pane 左右等宽并排
    const pane1 = page.locator('[data-testid="pane-item-1"]')
    const pane2 = page.locator('[data-testid="pane-item-2"]')
    await expect(pane1).toBeVisible()
    await expect(pane2).toBeVisible()

    const box1_split2 = (await pane1.boundingBox())!
    const box2_split2 = (await pane2.boundingBox())!
    expect(Math.abs(box1_split2.width - box2_split2.width)).toBeLessThanOrEqual(2)
    expect(box2_split2.x).toBeGreaterThan(box1_split2.x + box1_split2.width - 2)

    // 2. 通过 select 切换到 5 布局：左侧一整高跨 2 行 + 右侧 2x2（共 3 列等宽）
    await select.selectOption('grid-5')
    await expect(select).toHaveValue('grid-5')

    const pane3 = page.locator('[data-testid="pane-item-3"]')
    const pane4 = page.locator('[data-testid="pane-item-4"]')
    const pane5 = page.locator('[data-testid="pane-item-5"]')
    await expect(pane1).toBeVisible()
    await expect(pane2).toBeVisible()
    await expect(pane3).toBeVisible()
    await expect(pane4).toBeVisible()
    await expect(pane5).toBeVisible()

    const box1_g5 = (await pane1.boundingBox())!
    const box2_g5 = (await pane2.boundingBox())!
    const box3_g5 = (await pane3.boundingBox())!
    const box4_g5 = (await pane4.boundingBox())!

    // 3 列宽度相等
    expect(Math.abs(box1_g5.width - box2_g5.width)).toBeLessThanOrEqual(2)
    expect(Math.abs(box2_g5.width - box3_g5.width)).toBeLessThanOrEqual(2)

    // Pane 1 跨越两行，其高度应该等于 Pane 2 + Pane 4 + gap（容许 4px 渲染舍入）
    const rightColCombinedHeight = (box4_g5.y + box4_g5.height) - box2_g5.y
    expect(Math.abs(box1_g5.height - rightColCombinedHeight)).toBeLessThanOrEqual(4)

    // 3. 通过 select 切换到 7 布局：左侧一整高跨 2 行 + 右侧 3x2（共 4 列等宽）
    await select.selectOption('grid-7')
    await expect(select).toHaveValue('grid-7')

    const pane6 = page.locator('[data-testid="pane-item-6"]')
    const pane7 = page.locator('[data-testid="pane-item-7"]')
    await expect(pane6).toBeVisible()
    await expect(pane7).toBeVisible()

    const box1_g7 = (await pane1.boundingBox())!
    const box2_g7 = (await pane2.boundingBox())!
    const box3_g7 = (await pane3.boundingBox())!
    const box4_g7 = (await pane4.boundingBox())!
    const box5_g7 = (await pane5.boundingBox())!

    // 4 列宽度相等
    expect(Math.abs(box1_g7.width - box2_g7.width)).toBeLessThanOrEqual(2)
    expect(Math.abs(box2_g7.width - box3_g7.width)).toBeLessThanOrEqual(2)
    expect(Math.abs(box3_g7.width - box4_g7.width)).toBeLessThanOrEqual(2)

    // Pane 1 跨越两行，其高度约等于 Pane 2 + Pane 5 + gap
    const rightCol7CombinedHeight = (box5_g7.y + box5_g7.height) - box2_g7.y
    expect(Math.abs(box1_g7.height - rightCol7CombinedHeight)).toBeLessThanOrEqual(4)

    // 4. 通过 select 切换到 9 布局：3x3 均匀网格
    await select.selectOption('grid-9')
    await expect(select).toHaveValue('grid-9')

    const pane8 = page.locator('[data-testid="pane-item-8"]')
    const pane9 = page.locator('[data-testid="pane-item-9"]')
    await expect(pane8).toBeVisible()
    await expect(pane9).toBeVisible()

    const box1_g9 = (await pane1.boundingBox())!
    const box2_g9 = (await pane2.boundingBox())!
    const box9_g9 = (await pane9.boundingBox())!

    expect(Math.abs(box1_g9.width - box2_g9.width)).toBeLessThanOrEqual(2)
    expect(Math.abs(box1_g9.height - box2_g9.height)).toBeLessThanOrEqual(2)
    expect(box9_g9.x).toBeGreaterThan(box1_g9.x)
    expect(box9_g9.y).toBeGreaterThan(box1_g9.y)

    // 5. 窄屏自适应测试 (768px): 切换为单列纵向排列，取消跨行
    await page.setViewportSize({ width: 768, height: 900 })
    // 给响应式媒体查询应用预留一帧渲染
    await page.waitForTimeout(100)

    const box1_narrow = (await pane1.boundingBox())!
    const box2_narrow = (await pane2.boundingBox())!

    // 窄屏下两者垂直堆叠，横向占满宽度
    expect(box2_narrow.y).toBeGreaterThanOrEqual(box1_narrow.y + box1_narrow.height - 2)
    expect(Math.abs(box1_narrow.width - box2_narrow.width)).toBeLessThanOrEqual(2)

    // 验证外层 grid 无横向滚动溢出
    const gridEl = page.locator('[data-testid="chat-pane-grid"]')
    const gridScroll = await gridEl.evaluate((el) => ({
      scrollWidth: el.scrollWidth,
      clientWidth: el.clientWidth,
    }))
    expect(gridScroll.scrollWidth).toBeLessThanOrEqual(gridScroll.clientWidth + 1)
  })

  test('ThreadStatusFooter aligns left, orders units correctly with CSS vertical dividers and natural wrapping', async ({
    page,
  }) => {
    await page.setViewportSize({ width: 1280, height: 800 })
    await page.goto('/browser-tests/chat-layout-harness.html')

    const footer = page.locator('[data-testid="pane-1-footer"]')
    await expect(footer).toBeVisible()

    // 验证状态栏中的 5 个单元与文本内容
    const units = footer.locator('.thread-status-unit')
    await expect(units).toHaveCount(5)

    // 顺序必须严格为: 环境 | 上下文 | 累计usage | cache N% | tok/s
    await expect(units.nth(0)).toContainText('production')
    await expect(units.nth(1)).toContainText('16k/128k')
    await expect(units.nth(2)).toContainText('↑12k · ↓800 · R4.0k · $0.042')
    await expect(units.nth(3)).toContainText('cache 25%')
    await expect(units.nth(4)).toContainText('475 tok/s')

    // 验证各单元左对齐布局（flex justify-content 不是 flex-end 或 space-between）
    const lineJustify = await footer.locator('.thread-status-line').evaluate((el) => {
      return window.getComputedStyle(el).justifyContent
    })
    expect(['flex-start', 'start', 'normal']).toContain(lineJustify)

    // 验证超窄屏 (320px) 下换行无横向溢出
    await page.setViewportSize({ width: 320, height: 600 })
    await page.waitForTimeout(100)

    const statusLine = footer.locator('.thread-status-line')
    const overflowInfo = await statusLine.evaluate((el) => ({
      scrollWidth: el.scrollWidth,
      clientWidth: el.clientWidth,
    }))
    expect(overflowInfo.scrollWidth).toBeLessThanOrEqual(overflowInfo.clientWidth + 2)
  })

  test('User message attachments use unified 6px gap without asymmetric margin', async ({
    page,
  }) => {
    await page.setViewportSize({ width: 1280, height: 800 })
    await page.goto('/browser-tests/chat-layout-harness.html')

    const userBlock = page.locator('.thread-block-user')
    await expect(userBlock).toBeVisible()

    // 验证父容器采用 flex column 且 gap 规范为 6px
    const blockStyle = await userBlock.evaluate((el) => {
      const style = window.getComputedStyle(el)
      return {
        display: style.display,
        flexDirection: style.flexDirection,
        gap: style.gap,
      }
    })
    expect(blockStyle.display).toBe('flex')
    expect(blockStyle.flexDirection).toBe('column')
    expect(blockStyle.gap).toBe('6px')

    // 验证 .thread-user-attachments 移除了多余的 margin-top: 6px (计算值为 0px)
    const attachmentsEl = page.locator('.thread-user-attachments')
    const attachmentsMarginTop = await attachmentsEl.evaluate((el) => {
      return window.getComputedStyle(el).marginTop
    })
    expect(attachmentsMarginTop).toBe('0px')
  })
})
