import { expect, test } from '@playwright/test'

test.describe('Canvas Generation Panel Real Browser Layout Regression', () => {
  test('Standard desktop layout: renders prompt textarea, attached reference chips, candidate buttons, parameter controls, and expansion toggle', async ({
    page,
  }) => {
    await page.setViewportSize({ width: 1440, height: 900 })
    await page.goto('/browser-tests/canvas-generation-harness.html')

    const panel = page.locator('.generation-panel')
    await expect(panel).toBeVisible()

    // 1. 验证面板头部：模型标题、就绪状态、展开/收起按钮
    await expect(panel.locator('.generation-panel-head').getByText('Image Generator Pro')).toBeVisible()
    await expect(panel.locator('.generation-panel-head').getByText('就绪')).toBeVisible()
    const expandBtn = panel.getByRole('button', { name: '展开面板' })
    await expect(expandBtn).toBeVisible()

    // 2. 验证提示词多行输入框几何尺寸与原样文本
    const promptTextarea = panel.getByRole('textbox', { name: '提示词' })
    await expect(promptTextarea).toBeVisible()
    await expect(promptTextarea).toHaveValue('A scenic landscape with golden sunrise over misty mountains')
    const promptBox = (await promptTextarea.boundingBox())!
    expect(promptBox.width).toBeGreaterThanOrEqual(280)
    expect(promptBox.height).toBeGreaterThanOrEqual(40)

    // 3. 验证独立引用挂载芯片与候选栏
    const attachedChip = panel.locator('.generation-attached-chip')
    await expect(attachedChip).toBeVisible()
    await expect(attachedChip).toContainText('@Source Photo_0')
    const deleteRefBtn = attachedChip.getByRole('button', { name: /删除/ })
    await expect(deleteRefBtn).toBeVisible()

    // 4. 验证候选资源栏
    const candidateBtn = panel.locator('.generation-reference')
    await expect(candidateBtn).toBeVisible()
    await expect(candidateBtn).toContainText('@Source Photo_0')

    // 5. 验证参数控件：比例下拉与数量数字输入框
    const ratioSelect = panel.getByLabel('比例')
    await expect(ratioSelect).toBeVisible()
    await expect(ratioSelect).toHaveValue('16:9')

    const countInput = panel.getByLabel('数量')
    await expect(countInput).toBeVisible()
    await expect(countInput).toHaveValue('2')

    // 6. 验证面板展开与收起尺寸切换
    const initialBox = (await panel.boundingBox())!
    await expandBtn.click()
    await expect(panel).toHaveClass(/expanded/)
    const expandedBox = (await panel.boundingBox())!
    expect(expandedBox.width).toBeGreaterThan(initialBox.width)

    const collapseBtn = panel.getByRole('button', { name: '收起面板' })
    await collapseBtn.click()
    await expect(panel).not.toHaveClass(/expanded/)
    const collapsedBox = (await panel.boundingBox())!
    expect(collapsedBox.width).toBeLessThan(expandedBox.width)
  })

  test('Mobile viewport (360x740): panel fits within mobile width without horizontal blowout', async ({
    page,
  }) => {
    await page.setViewportSize({ width: 360, height: 740 })
    await page.goto('/browser-tests/canvas-generation-harness.html')

    const panel = page.locator('.generation-panel')
    await expect(panel).toBeVisible()

    const promptTextarea = panel.getByRole('textbox', { name: '提示词' })
    await expect(promptTextarea).toBeVisible()

    // 验证面板与输入框在 360px 宽度下不发生水平破框
    const panelBox = (await panel.boundingBox())!
    expect(panelBox.width).toBeLessThanOrEqual(360)
  })

  test('Raw JSON mode: displays explicit alert banner, full JSON editor with raw data, and no silent overwrite', async ({
    page,
  }) => {
    await page.setViewportSize({ width: 1440, height: 900 })
    await page.goto('/browser-tests/canvas-generation-harness.html')

    // 切换至包含非字符串 prompt 结构的 Raw 模式
    await page.locator('#btn-mode-raw').click()

    const panel = page.locator('.generation-panel')
    await expect(panel).toBeVisible()

    // 1. 验证显式提示条出现，告知进入完整 JSON 模式
    const alert = panel.getByRole('alert')
    await expect(alert).toBeVisible()
    await expect(alert).toContainText('入参 prompt 包含非字符串结构')

    // 2. 验证显示完整 JSON 配置多行文本域，且未清空原始 prompt 对象
    const rawTextarea = panel.getByRole('textbox', { name: '参数 JSON' })
    await expect(rawTextarea).toBeVisible()
    const val = await rawTextarea.inputValue()
    const parsed = JSON.parse(val)
    expect(parsed.prompt).toEqual({ segments: [{ type: 'TEXT', text: 'custom object prompt' }] })
    expect(parsed.ratio).toBe('16:9')

    // 3. 验证输入合法修改后更新输出
    await rawTextarea.fill(JSON.stringify({ prompt: 'updated raw prompt', ratio: '1:1' }, null, 2))
    const output = page.locator('#scheduled-output')
    await expect(output).toBeVisible()
    await expect(output).toContainText('updated raw prompt')
  })
})
