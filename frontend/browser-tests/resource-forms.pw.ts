import { expect, test, tokenColor } from './fixture'

test('resource forms preserve native semantics, shared geometry and controlled edits at wide and narrow widths', async ({ page }) => {
  // 允许表单读取模型能力，但草稿编辑不应发出其他 API 请求。
  const unexpectedRequests: string[] = []
  await page.route('**/api/**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    if (request.method() === 'GET' && url.pathname === '/api/platform/models/capabilities'
        && url.searchParams.get('providerName') === 'demo-provider'
        && url.searchParams.get('modelId') === 'wire-model') {
      await route.fulfill({ json: {
        promptCacheOptions: [{ retention: 'NONE' }],
        defaultPromptCacheRetention: 'NONE',
        promptCacheState: 'CONFIGURABLE',
      } })
      return
    }
    unexpectedRequests.push(`${request.method()} ${url.pathname}`)
    await route.abort()
  })
  for (const width of [1280, 390, 320]) {
    await page.setViewportSize({ width, height: 900 })
    await page.goto('/browser-tests/resource-card-harness.html?forms')
    const provider = page.getByRole('region', { name: 'Provider form' })
    const model = page.getByRole('region', { name: 'Model form' })
    const agent = page.getByRole('region', { name: 'Agent form' })
    await expect(provider.getByPlaceholder('minimax')).toHaveAttribute('readonly', '')
    const password = provider.getByPlaceholder('留空保留当前密钥')
    await expect(password).toHaveAttribute('type', 'password')
    await expect(password).toHaveAttribute('autocomplete', 'off')
    await password.fill('fixture-value')
    await expect(password).toHaveValue('fixture-value')
    const invalid = provider.getByPlaceholder('https://api.example.invalid/v1')
    await expect(invalid).toHaveAttribute('aria-invalid', 'true')
    await invalid.focus()
    await expect(invalid).toHaveCSS('border-top-color', 'rgb(240, 133, 133)')
    // 等到能力读取完成、模型预设控件可用，再测量布局与截图。
    const cacheRetention = model.getByRole('button', { name: '提示缓存留存 1' })
    await expect(cacheRetention).toBeEnabled()
    await expect(cacheRetention).toHaveAttribute('data-value', 'NONE')
    await expect(model.getByLabel('提供商', { exact: true })).toBeDisabled()
    await expect(model.getByRole('textbox', { name: '模型 ID', exact: true }))
      .toHaveJSProperty('required', true)
    // 1.3.0 推理关闭时不渲染思考强度字段；开启推理后立即可编辑。
    await expect(model.getByLabel('思考强度 1', { exact: true })).toHaveCount(0)
    const reasoningToggle = model.locator('.ui-checkbox', { hasText: /^推理$/ })
    await reasoningToggle.click()
    await expect(model.getByLabel('思考强度 1', { exact: true })).toBeEnabled()
    await reasoningToggle.click()
    await expect(model.getByLabel('思考强度 1', { exact: true })).toHaveCount(0)
    await expect(model.getByRole('button', { name: '删除预设', exact: true })).toBeDisabled()
    // 提供商与 Agent 表单共用同一批目录标签。
    await expect(provider.getByRole('textbox', { name: '名称', exact: true }))
      .toHaveAttribute('readonly', '')
    await expect(provider.getByRole('textbox', { name: '说明', exact: true })).toBeVisible()
    await expect(agent.getByRole('textbox', { name: '名称', exact: true }))
      .toHaveAttribute('readonly', '')
    await expect(agent.getByRole('textbox', { name: '说明', exact: true })).toBeVisible()
    await expect(agent.getByRole('button', { name: '默认模型', exact: true })).toBeVisible()
    // 基础费率输入按 zh-CN 精确标签定位。
    const price = model.getByLabel('输入', { exact: true })
    // 十进制编辑器保留文本精度与尾零，不经过原生 number 的浮点转换。
    await expect(price).toHaveAttribute('type', 'text')
    await expect(price).toHaveAttribute('inputmode', 'decimal')
    await expect(price).toHaveJSProperty('required', false)
    await expect(price).toHaveValue('')
    await price.fill('1.2500')
    await expect(price).toHaveValue('1.2500')
    const protocol = model.getByLabel('原生协议选项 1', { exact: true })
    await protocol.fill('{\n  "temperature": 0.5\n}')
    await expect(protocol).toHaveValue('{\n  "temperature": 0.5\n}')
    const description = agent.getByPlaceholder('用途说明')
    await description.fill('第一行\n第二行')
    await expect(description).toHaveValue('第一行\n第二行')
    // 不只检查 class：所有共享单行输入的实际高度、统一 1px 焦点环与禁用样式。
    expect(await page.locator('.ui-text-input').evaluateAll((inputs) =>
      inputs.every((input) => input.getBoundingClientRect().height === 32))).toBe(true)
    await password.focus()
    await expect(password).toHaveCSS('outline-style', 'solid')
    await expect(password).toHaveCSS('outline-width', '1px')
    await expect(password).toHaveCSS('outline-color', await tokenColor(page, '--focus'))
    await expect(model.getByLabel('提供商', { exact: true })).toHaveCSS('cursor', 'not-allowed')
    expect(await page.evaluate(() =>
      document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true)
    await page.screenshot({ path: test.info().outputPath(`resource-forms-${width}.png`), fullPage: true })
    await model.locator('section[aria-labelledby="model-pricing-heading"]').screenshot({
      path: test.info().outputPath(`resource-pricing-${width}.png`),
    })
    await model.locator('.variant-stack').screenshot({
      path: test.info().outputPath(`resource-variants-${width}.png`),
    })
    await agent.screenshot({ path: test.info().outputPath(`resource-agent-${width}.png`) })
  }
  expect(unexpectedRequests).toEqual([])
})
