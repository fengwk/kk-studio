import { expect, test, type Page } from './fixture'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const __filename = fileURLToPath(import.meta.url)
const __dirname = path.dirname(__filename)
const REPORTS_DIR = path.resolve(__dirname, '../../reports/layout')

async function selectLayoutOption(page: Page, value: string, label: string) {
  const trigger = page.locator('#chat-layout-select')
  await trigger.click()
  const listbox = page.getByRole('listbox')
  await expect(listbox).toBeVisible()
  const option = listbox.getByRole('option', { name: label, exact: true })
  await option.click()
  await expect(listbox).toHaveCount(0)
  await expect(trigger).toHaveAttribute('data-value', value)
}

test.describe('Chat Workspace 1-9 Panes Layout, Footer & User Attachments', () => {
  test('Layout switcher supports 1-9 panes with accurate CSS grid bounding boxes and responsive stacking', async ({
    page,
  }) => {
    // 宽屏视口 1920x1080
    await page.setViewportSize({ width: 1920, height: 1080 })
    await page.goto('/browser-tests/chat-layout-harness.html')

    const trigger = page.locator('#chat-layout-select')
    await expect(trigger).toBeVisible()
    await expect(trigger).toHaveAttribute('data-value', 'split-2')

    // 1. 验证 2 分屏布局：2 个 pane 左右等宽并排
    const pane1 = page.locator('[data-testid="pane-item-1"]')
    const pane2 = page.locator('[data-testid="pane-item-2"]')
    await expect(pane1).toBeVisible()
    await expect(pane2).toBeVisible()

    const box1_split2 = (await pane1.boundingBox())!
    const box2_split2 = (await pane2.boundingBox())!
    expect(Math.abs(box1_split2.width - box2_split2.width)).toBeLessThanOrEqual(2)
    expect(box2_split2.x).toBeGreaterThan(box1_split2.x + box1_split2.width - 2)

    // 2. 通过组件下拉切换到 5 布局：左侧一整高跨 2 行 + 右侧 2x2（共 3 列等宽）
    await selectLayoutOption(page, 'grid-5', '5')

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

    // 3. 通过组件下拉切换到 7 布局：左侧一整高跨 2 行 + 右侧 3x2（共 4 列等宽）
    await selectLayoutOption(page, 'grid-7', '7')

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

    // 4. 通过组件下拉切换到 9 布局：3x3 均匀网格
    await selectLayoutOption(page, 'grid-9', '9')

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

  test('Chevron icon is visible with bounding box >= 12px', async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 800 })
    await page.goto('/browser-tests/chat-layout-harness.html')

    const trigger = page.locator('#chat-layout-select')
    await expect(trigger).toBeVisible()

    const chevron = trigger.locator('.ui-select-chevron')
    await expect(chevron).toBeVisible()

    const bbox = (await chevron.boundingBox())!
    expect(bbox).not.toBeNull()
    expect(bbox.width).toBeGreaterThanOrEqual(12)
    expect(bbox.height).toBeGreaterThanOrEqual(12)
  })

  test('Selected option displays Check icon with theme color instead of native select highlight', async ({
    page,
  }) => {
    await page.setViewportSize({ width: 1280, height: 800 })
    await page.goto('/browser-tests/chat-layout-harness.html')

    const trigger = page.locator('#chat-layout-select')
    await trigger.click()

    const listbox = page.getByRole('listbox')
    await expect(listbox).toBeVisible()

    // 默认初始布局为 2 (split-2)
    const selectedOption = listbox.locator('.ui-select-option[aria-selected="true"]')
    await expect(selectedOption).toBeVisible()
    await expect(selectedOption).toHaveAttribute('data-value', 'split-2')

    // 选中项 Check 图标可见且尺寸正常
    const checkIcon = selectedOption.locator('.ui-select-option-indicator svg')
    await expect(checkIcon).toBeVisible()
    const checkBbox = (await checkIcon.boundingBox())!
    expect(checkBbox.width).toBeGreaterThanOrEqual(12)
    expect(checkBbox.height).toBeGreaterThanOrEqual(12)

    // 验证颜色是项目的绿色主题而不是操作系统的蓝色原生高亮
    const optionColor = await selectedOption.evaluate((el) => {
      return window.getComputedStyle(el).color
    })
    expect(optionColor).not.toBe('rgb(0, 106, 255)')
    expect(optionColor).not.toBe('rgb(0, 120, 215)')

    // 验证绿色分量高于红、蓝分量（匹配主题绿色）
    const match = optionColor.match(/rgb\((\d+),\s*(\d+),\s*(\d+)\)/)
    if (match) {
      const [, r, g, b] = match.map(Number)
      expect(g).toBeGreaterThan(r)
      expect(g).toBeGreaterThan(b)
    }

    // 验证弹出菜单使用主题圆角与阴影
    const menuStyle = await listbox.evaluate((el) => {
      const style = window.getComputedStyle(el)
      return {
        borderRadius: style.borderRadius,
        position: style.position,
      }
    })
    // Select 菜单已 portal 到 body，用 fixed 视口坐标避免滚动容器裁剪。
    expect(menuStyle.position).toBe('fixed')
    expect(await listbox.evaluate(element => element.parentElement === document.body)).toBe(true)
    expect(parseFloat(menuStyle.borderRadius)).toBeGreaterThan(0)
  })

  test('Menu closes on outside pointerdown and restores focus on Escape', async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 800 })
    await page.goto('/browser-tests/chat-layout-harness.html')

    const trigger = page.locator('#chat-layout-select')

    // 1. Outside click 关闭
    await trigger.click()
    const listbox = page.getByRole('listbox')
    await expect(listbox).toBeVisible()

    await page.locator('h1').click()
    await expect(listbox).toHaveCount(0)

    // 2. Escape 关闭并聚焦回 trigger
    await trigger.click()
    await expect(listbox).toBeVisible()

    await page.keyboard.press('Escape')
    await expect(listbox).toHaveCount(0)
    await expect(trigger).toBeFocused()
  })

  test('Keyboard navigation: ArrowDown + Enter switches layout cleanly', async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 800 })
    await page.goto('/browser-tests/chat-layout-harness.html')

    const trigger = page.locator('#chat-layout-select')
    await trigger.focus()
    // 按 Enter 打开菜单
    await page.keyboard.press('Enter')
    const listbox = page.getByRole('listbox')
    await expect(listbox).toBeVisible()

    // 初始选项为 split-2，ArrowDown 移动到 3
    await page.keyboard.press('ArrowDown')
    await page.keyboard.press('Enter')

    await expect(listbox).toHaveCount(0)
    await expect(trigger).toHaveAttribute('data-value', 'split-3')
    await expect(trigger.locator('.ui-select-value')).toHaveText('3')
    await expect(page.locator('.chat-pane-grid.layout-split-3')).toBeVisible()
    await expect(trigger).toBeFocused()
  })

  test('Narrow viewport 320px and 768px: right-aligned menu does not overflow horizontally', async ({
    page,
  }) => {
    // 1. 320px 超窄屏验证
    await page.setViewportSize({ width: 320, height: 600 })
    await page.goto('/browser-tests/chat-layout-harness.html')

    const trigger = page.locator('#chat-layout-select')
    await expect(trigger).toBeVisible()

    // 验证 trigger 处于真实右侧工具栏位置（右边界大于 200px）
    const triggerBox320 = (await trigger.boundingBox())!
    expect(triggerBox320.x + triggerBox320.width).toBeGreaterThan(200)

    await trigger.click()
    const listbox320 = page.getByRole('listbox')
    await expect(listbox320).toBeVisible()

    const menuBox320 = (await listbox320.boundingBox())!
    // 菜单右边缘不超过视口宽度 320px
    expect(menuBox320.x + menuBox320.width).toBeLessThanOrEqual(320)
    // 菜单左边缘不小于 0（不往左溢出视口）
    expect(menuBox320.x).toBeGreaterThanOrEqual(0)

    await page.keyboard.press('Escape')

    // 2. 768px 平板窄屏验证
    await page.setViewportSize({ width: 768, height: 900 })
    await trigger.click()
    const listbox768 = page.getByRole('listbox')
    await expect(listbox768).toBeVisible()

    const menuBox768 = (await listbox768.boundingBox())!
    expect(menuBox768.x + menuBox768.width).toBeLessThanOrEqual(768)
    expect(menuBox768.x).toBeGreaterThanOrEqual(0)
  })

  test('Generate desktop and mobile open-state screenshots for layout review', async ({
    page,
  }) => {
    // 1. Desktop 展开截图 (1280x800)
    await page.setViewportSize({ width: 1280, height: 800 })
    await page.goto('/browser-tests/chat-layout-harness.html')

    const trigger = page.locator('#chat-layout-select')
    await trigger.click()
    await expect(page.getByRole('listbox')).toBeVisible()

    const desktopPath = path.join(REPORTS_DIR, 'chat-layout-select-desktop-open.png')
    await page.screenshot({
      path: desktopPath,
      fullPage: false,
    })

    await page.keyboard.press('Escape')

    // 2. Mobile 展开截图 (375x667)
    await page.setViewportSize({ width: 375, height: 667 })
    await page.goto('/browser-tests/chat-layout-harness.html')

    const mobileTrigger = page.locator('#chat-layout-select')
    await mobileTrigger.click()
    await expect(page.getByRole('listbox')).toBeVisible()

    const mobilePath = path.join(REPORTS_DIR, 'chat-layout-select-mobile-open.png')
    await page.screenshot({
      path: mobilePath,
      fullPage: false,
    })
  })

  test('ThreadStatusFooter keeps a single grouped line with ellipsis and complete hover facts', async ({
    page,
  }) => {
    // 确保 reports/layout 目录存在
    fs.mkdirSync(REPORTS_DIR, { recursive: true })

    // 1. 桌面视口 1280x800（验证 split-2 与分屏 split-3 下单行分组分隔与省略截断）
    await page.setViewportSize({ width: 1280, height: 800 })
    await page.goto('/browser-tests/chat-layout-harness.html')

    const pane1Footer = page.locator('[data-testid="pane-1-footer"]')
    await expect(pane1Footer).toBeVisible()

    // 全部事实落在唯一一行：环境/上下文/用量由 U+2223 分组，组内统计项由 U+00B7 连接
    const pane1Lines = pane1Footer.locator('.thread-status-line')
    await expect(pane1Lines).toHaveCount(1)
    await expect(pane1Lines.nth(0)).toHaveText(
      'production ∣ ctx 16k/128k ∣ ↑12k · ↓800 · R4.0k · $0.042 · cache 25% · 475 tok/s',
    )

    // 超宽被省略的信息必须仍能通过 hover 完整读取，且不含内部口径说明
    const pane1Title = (await pane1Lines.nth(0).getAttribute('title')) ?? ''
    expect(pane1Title).toContain('环境：production')
    expect(pane1Title).toContain('上下文占用（最近一次模型调用估算）：约 16000 / 128000 tokens')
    expect(pane1Title).toContain('未缓存输入：12000 tokens；输出：800 tokens；推理：150 tokens')
    expect(pane1Title).toContain('缓存读取：4000 tokens；缓存写入：0 tokens')
    expect(pane1Title).toContain('估算费用：$0.042；缓存命中率：25%；生成速度：475 tok/s')
    expect(pane1Title).not.toContain('含首次请求')
    expect(pane1Title).not.toContain('非待发请求精确值')

    // 切换到 3 分屏，使每列宽度约束至 ~400px，验证桌面分屏场景下的真实截断
    await selectLayoutOption(page, 'split-3', '3')
    const pane2Footer = page.locator('[data-testid="pane-2-footer"]')
    await expect(pane2Footer).toBeVisible()
    const pane2Lines = pane2Footer.locator('.thread-status-line')
    await expect(pane2Lines).toHaveCount(1)

    // 长环境名与长用量在 split-3 宽度下内容超出，验证真实 overflow：scrollWidth > clientWidth 且单行 ellipsis
    const pane2UsageOverflow = await pane2Lines.evaluate((el) => {
      const style = window.getComputedStyle(el)
      return {
        whiteSpace: style.whiteSpace,
        textOverflow: style.textOverflow,
        overflow: style.overflow,
        scrollWidth: el.scrollWidth,
        clientWidth: el.clientWidth,
        clientHeight: el.clientHeight,
      }
    })
    expect(pane2UsageOverflow.whiteSpace).toBe('nowrap')
    expect(pane2UsageOverflow.textOverflow).toBe('ellipsis')
    expect(pane2UsageOverflow.overflow).toBe('hidden')
    expect(pane2UsageOverflow.scrollWidth).toBeGreaterThan(pane2UsageOverflow.clientWidth)
    expect(pane2UsageOverflow.clientHeight).toBeLessThanOrEqual(24)

    // 长环境名同样需要在唯一一行中保留并可通过 hover 读到
    const pane2Title = (await pane2Lines.getAttribute('title')) ?? ''
    expect(pane2Title).toContain('环境：production-us-east-long-cluster-primary-node')
    expect(pane2Title).toContain('未缓存输入：123456 tokens；输出：654321 tokens')

    // 无环境且无闭合回合用量时，零事实与占位符仍稳定落在唯一一行
    const pane3Line = page.locator('[data-testid="pane-3-footer"] .thread-status-line')
    await expect(pane3Line).toHaveCount(1)
    await expect(pane3Line).toHaveText('未选择环境 ∣ ↑0 · ↓0 · — · cache — · — tok/s')
    expect((await pane3Line.getAttribute('title')) ?? '').toContain('生成速度：暂无数据')

    // 桌面分屏截图
    await page.screenshot({
      path: path.join(REPORTS_DIR, 'chat-usage-layout-desktop-split.png'),
      fullPage: false,
    })

    // 切回 split-2 并截图
    await selectLayoutOption(page, 'split-2', '2')
    await page.screenshot({
      path: path.join(REPORTS_DIR, 'chat-usage-layout-desktop-1280.png'),
      fullPage: false,
    })

    // 2. 移动端 375px 视口
    await page.setViewportSize({ width: 375, height: 812 })
    await page.waitForTimeout(100)

    // 验证 MetaMessageBlock turn_usage 单行截断
    const metaUsage = page.locator('.kind-turn_usage .thread-meta-text').first()
    await expect(metaUsage).toBeVisible()
    const metaUsageStyle = await metaUsage.evaluate((el) => {
      const style = window.getComputedStyle(el)
      return {
        whiteSpace: style.whiteSpace,
        textOverflow: style.textOverflow,
        overflow: style.overflow,
        scrollWidth: el.scrollWidth,
        clientWidth: el.clientWidth,
        clientHeight: el.clientHeight,
        title: el.getAttribute('title') || '',
      }
    })
    expect(metaUsageStyle.whiteSpace).toBe('nowrap')
    expect(metaUsageStyle.textOverflow).toBe('ellipsis')
    expect(metaUsageStyle.overflow).toBe('hidden')
    expect(metaUsageStyle.scrollWidth).toBeGreaterThan(metaUsageStyle.clientWidth)
    expect(metaUsageStyle.clientHeight).toBeLessThanOrEqual(24)
    expect(metaUsageStyle.title).toContain('未缓存输入：12400 tokens')
    expect(metaUsageStyle.title).toContain('生成速度：900 tok/s')
    // 图例只保留短对照，不再夹带内部实现说明。
    expect(metaUsageStyle.title).not.toContain('注：')

    // 基础 meta 样式保留换行；省略规则只作用于回合用量。
    const metaInfo = page.locator('[data-testid="base-meta-style"] .thread-meta-text')
    await expect(metaInfo).toBeVisible()
    const metaInfoStyle = await metaInfo.evaluate((el) => ({
      whiteSpace: window.getComputedStyle(el).whiteSpace,
      clientHeight: el.clientHeight,
    }))
    expect(metaInfoStyle.whiteSpace).toBe('pre-wrap')
    expect(metaInfoStyle.clientHeight).toBeGreaterThan(24)

    // 唯一一行在 375px 下必然超出，验证单行省略与高度约束。
    const pane1Usage375 = await pane1Lines.nth(0).evaluate((el) => {
      const style = window.getComputedStyle(el)
      return {
        whiteSpace: style.whiteSpace,
        textOverflow: style.textOverflow,
        overflow: style.overflow,
        scrollWidth: el.scrollWidth,
        clientWidth: el.clientWidth,
        clientHeight: el.clientHeight,
      }
    })
    expect(pane1Usage375.whiteSpace).toBe('nowrap')
    expect(pane1Usage375.textOverflow).toBe('ellipsis')
    expect(pane1Usage375.overflow).toBe('hidden')
    expect(pane1Usage375.clientHeight).toBeLessThanOrEqual(24)
    expect(pane1Usage375.scrollWidth).toBeGreaterThan(pane1Usage375.clientWidth)
    const pane2Usage375 = await pane2Lines.evaluate((el) => ({
      scrollWidth: el.scrollWidth,
      clientWidth: el.clientWidth,
      clientHeight: el.clientHeight,
    }))
    expect(pane2Usage375.scrollWidth).toBeGreaterThan(pane2Usage375.clientWidth)
    expect(pane2Usage375.clientHeight).toBeLessThanOrEqual(24)

    // 验证页面与容器无横向滚动溢出
    const overflow375 = await page.evaluate(() => ({
      pageScrollWidth: document.documentElement.scrollWidth,
      pageClientWidth: document.documentElement.clientWidth,
      bodyScrollWidth: document.body.scrollWidth,
      bodyClientWidth: document.body.clientWidth,
    }))
    expect(overflow375.pageScrollWidth).toBeLessThanOrEqual(overflow375.pageClientWidth + 1)
    expect(overflow375.bodyScrollWidth).toBeLessThanOrEqual(overflow375.bodyClientWidth + 1)

    // 移动端 375px 截图
    await page.screenshot({
      path: path.join(REPORTS_DIR, 'chat-usage-layout-mobile-375.png'),
      fullPage: false,
    })

    // 3. 超窄屏 320px 视口
    await page.setViewportSize({ width: 320, height: 600 })
    await page.waitForTimeout(100)

    const metaUsage320 = await metaUsage.evaluate((el) => ({
      clientHeight: el.clientHeight,
      scrollWidth: el.scrollWidth,
      clientWidth: el.clientWidth,
    }))
    expect(metaUsage320.clientHeight).toBeLessThanOrEqual(24)
    expect(metaUsage320.scrollWidth).toBeGreaterThan(metaUsage320.clientWidth)

    const pane1Usage320 = await pane1Lines.nth(0).evaluate((el) => ({
      clientHeight: el.clientHeight,
      scrollWidth: el.scrollWidth,
      clientWidth: el.clientWidth,
    }))
    expect(pane1Usage320.clientHeight).toBeLessThanOrEqual(24)
    expect(pane1Usage320.scrollWidth).toBeGreaterThan(pane1Usage320.clientWidth)

    const overflow320 = await page.evaluate(() => ({
      pageScrollWidth: document.documentElement.scrollWidth,
      pageClientWidth: document.documentElement.clientWidth,
    }))
    expect(overflow320.pageScrollWidth).toBeLessThanOrEqual(overflow320.pageClientWidth + 1)

    // 超窄屏 320px 截图
    await page.screenshot({
      path: path.join(REPORTS_DIR, 'chat-usage-layout-mobile-320.png'),
      fullPage: false,
    })
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

  test('Compact layout selector: exact 80px menu width, 28px option min-height, all 9 items fit without scrollbar when height >= 350, scrollable on ultra-short screen, 320px non-overflowing', async ({
    page,
  }) => {
    // 1. 验证高度 >= 350px (例如 500x350): 菜单宽度严格为 80px，option 最小高度为 28px，全部 9 项无滚动条
    await page.setViewportSize({ width: 500, height: 350 })
    await page.goto('/browser-tests/chat-layout-harness.html')

    const trigger = page.locator('#chat-layout-select')
    await trigger.click()
    const listbox = page.getByRole('listbox')
    await expect(listbox).toBeVisible()

    const menuBox = (await listbox.boundingBox())!
    // 宽度必须严格等于 80px (误差 <= 1px，避免 148px)
    expect(Math.abs(menuBox.width - 80)).toBeLessThanOrEqual(1)

    // 验证所有 9 个选项的 min-height 且高度 >= 28px
    const options = listbox.locator('.ui-select-option')
    await expect(options).toHaveCount(9)
    for (let i = 0; i < 9; i++) {
      const optBox = (await options.nth(i).boundingBox())!
      expect(optBox.height).toBeGreaterThanOrEqual(27.5)
    }

    // 验证 9 项不需要滚动：scrollHeight <= clientHeight + 1
    const scrollInfo = await listbox.evaluate((el) => ({
      scrollHeight: el.scrollHeight,
      clientHeight: el.clientHeight,
    }))
    expect(scrollInfo.scrollHeight).toBeLessThanOrEqual(scrollInfo.clientHeight + 1)

    await page.keyboard.press('Escape')

    // 2. 超短屏幕 (例如 500x240): 菜单仍可纵向滚动
    await page.setViewportSize({ width: 500, height: 240 })
    await trigger.click()
    await expect(listbox).toBeVisible()

    const shortScrollInfo = await listbox.evaluate((el) => ({
      scrollHeight: el.scrollHeight,
      clientHeight: el.clientHeight,
    }))
    expect(shortScrollInfo.scrollHeight).toBeGreaterThan(shortScrollInfo.clientHeight)

    await page.keyboard.press('Escape')

    // 3. 320px 超窄屏：菜单不横向溢出视口
    await page.setViewportSize({ width: 320, height: 400 })
    await trigger.click()
    await expect(listbox).toBeVisible()

    const box320 = (await listbox.boundingBox())!
    expect(box320.x + box320.width).toBeLessThanOrEqual(320)
    expect(box320.x).toBeGreaterThanOrEqual(0)
    expect(Math.abs(box320.width - 80)).toBeLessThanOrEqual(1)
  })
})

test.describe('ThinkingBlock collapsed tail', () => {
  test('keeps one ellipsized tail line, updates the tail, and expands the original Markdown', async ({
    page,
  }) => {
    fs.mkdirSync(REPORTS_DIR, { recursive: true })
    await page.setViewportSize({ width: 1280, height: 800 })
    await page.goto('/browser-tests/chat-layout-harness.html')

    const block = page.locator('[data-testid="pane-1-thinking"]')
    const line = block.locator('.thread-thinking-line')
    const tail = block.locator('.thread-thinking-tail')
    const ellipsis = block.locator('.thread-thinking-ellipsis')
    const expand = block.getByRole('button', { name: '展开思考' })

    await expect(line).toBeVisible()
    // 收起态保留最新尾部并丢弃前文：左侧出现省略标记，尾部是 Markdown 结尾。
    await expect(ellipsis).toBeVisible()
    await expect(tail).toContainText('第二项')
    await expect(tail).not.toContainText('前置排查记录')
    // 收起箭头指向右（>），展开后向下（v）。
    await expect(expand.locator('svg.lucide-chevron-right')).toHaveCount(1)
    // 省略边界落在字素簇上：尾部不得残留孤立代理码元。
    expect((await tail.textContent()) ?? '').not.toMatch(
      /[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]/,
    )

    // 单行、无横滚/纵滚，且未使用 rtl 反向排列。
    const lineBox = await line.evaluate((el) => {
      const style = window.getComputedStyle(el)
      return {
        whiteSpace: style.whiteSpace,
        overflow: style.overflow,
        direction: style.direction,
        scrollWidth: el.scrollWidth,
        clientWidth: el.clientWidth,
        scrollHeight: el.scrollHeight,
        clientHeight: el.clientHeight,
      }
    })
    expect(lineBox.whiteSpace).toBe('nowrap')
    expect(lineBox.overflow).toBe('hidden')
    expect(lineBox.direction).toBe('ltr')
    expect(lineBox.scrollWidth).toBeLessThanOrEqual(lineBox.clientWidth + 1)
    expect(lineBox.scrollHeight).toBeLessThanOrEqual(lineBox.clientHeight + 1)
    expect(lineBox.clientHeight).toBeLessThanOrEqual(24)

    // 省略标记在尾部左侧；展开按钮位于第一行末尾且不覆盖文本。
    const tailBox = (await tail.boundingBox())!
    const ellipsisBox = (await ellipsis.boundingBox())!
    const toggleBox = (await expand.boundingBox())!
    expect(ellipsisBox.x).toBeLessThan(tailBox.x)
    expect(tailBox.x + tailBox.width).toBeLessThanOrEqual(toggleBox.x + 2)
    expect(toggleBox.y).toBeLessThan(tailBox.y + tailBox.height)
    expect(toggleBox.y + toggleBox.height).toBeGreaterThan(tailBox.y)

    // 追加内容后尾部动态更新，仍然保留左侧省略且不产生滚动条。
    await block.locator('[data-testid="thinking-append"]').click()
    await expect(tail).toContainText('补充：尾部更新')
    await expect(ellipsis).toBeVisible()
    await expect(tail).not.toContainText('前置排查记录')
    const appendedBox = await line.evaluate((el) => ({
      scrollWidth: el.scrollWidth,
      clientWidth: el.clientWidth,
      clientHeight: el.clientHeight,
    }))
    expect(appendedBox.scrollWidth).toBeLessThanOrEqual(appendedBox.clientWidth + 1)
    expect(appendedBox.clientHeight).toBeLessThanOrEqual(24)

    await page.screenshot({
      path: path.join(REPORTS_DIR, 'chat-thinking-collapsed-desktop.png'),
      fullPage: false,
    })

    // 展开同一个框：原始 Markdown 由 MarkdownRenderer 渲染，路径与英文顺序保持原样。
    await expand.click()
    const expanded = block.locator('.thread-thinking-text .md-root')
    await expect(expanded).toBeVisible()
    await expect(expanded.locator('h1')).toHaveText('结论')
    await expect(expanded.locator('strong')).toHaveText('未被反转')
    await expect(expanded.locator('li')).toHaveCount(3)
    await expect(expanded).toContainText('/usr/local/lib/node_modules/kk-studio 保持原顺序')
    await expect(expanded).toContainText('👨‍👩‍👧‍👦')
    const collapse = block.getByRole('button', { name: '收起思考' })
    await expect(collapse).toHaveAttribute('aria-expanded', 'true')
    await expect(collapse.locator('svg.lucide-chevron-down')).toHaveCount(1)

    await page.screenshot({
      path: path.join(REPORTS_DIR, 'chat-thinking-expanded-desktop.png'),
      fullPage: false,
    })

    // 收起后窄屏仍然单行、无横滚、按钮不覆盖文本。
    await page.setViewportSize({ width: 375, height: 812 })
    await collapse.click()
    await expect(line).toBeVisible()
    const narrow = await line.evaluate((el) => ({
      scrollWidth: el.scrollWidth,
      clientWidth: el.clientWidth,
      clientHeight: el.clientHeight,
    }))
    expect(narrow.scrollWidth).toBeLessThanOrEqual(narrow.clientWidth + 1)
    expect(narrow.clientHeight).toBeLessThanOrEqual(24)
    const overflow375 = await page.evaluate(() => ({
      pageScrollWidth: document.documentElement.scrollWidth,
      pageClientWidth: document.documentElement.clientWidth,
    }))
    expect(overflow375.pageScrollWidth).toBeLessThanOrEqual(overflow375.pageClientWidth + 1)

    await page.screenshot({
      path: path.join(REPORTS_DIR, 'chat-thinking-collapsed-mobile-375.png'),
      fullPage: false,
    })
  })

  test('registers width measurement after empty thinking becomes streaming content', async ({
    page,
  }) => {
    // 空思考时收起态节点不存在；同一元素实例出现内容后必须重新注册测量并投影出尾部省略。
    await page.setViewportSize({ width: 1280, height: 800 })
    await page.goto('/browser-tests/chat-layout-harness.html')

    const block = page.locator('[data-testid="pane-1-thinking-stream"]')
    await expect(block.locator('.thread-block-thinking')).toHaveCount(0)
    await block.locator('[data-testid="thinking-stream"]').click()

    const line = block.locator('.thread-thinking-line')
    await expect(line).toBeVisible()
    await expect(block.locator('.thread-thinking-ellipsis')).toBeVisible()
    await expect(block.locator('.thread-thinking-tail')).not.toContainText('前置排查记录')

    const box = await line.evaluate((el) => ({
      scrollWidth: el.scrollWidth,
      clientWidth: el.clientWidth,
      clientHeight: el.clientHeight,
    }))
    expect(box.scrollWidth).toBeLessThanOrEqual(box.clientWidth + 1)
    expect(box.clientHeight).toBeLessThanOrEqual(24)
  })
})
