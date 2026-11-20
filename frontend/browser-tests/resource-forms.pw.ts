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
        automaticCache: false,
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
    const invalid = provider.getByPlaceholder('https://api.example.com/v1')
    await expect(invalid).toHaveAttribute('aria-invalid', 'true')
    await invalid.focus()
    await expect(invalid).toHaveCSS('border-top-color', 'rgb(240, 133, 133)')
    // 等到能力读取完成、Variant 控件可用，再测量布局与截图。
    await expect(model.getByRole('button', { name: '提示缓存留存 1' })).toBeEnabled()
    await expect(model.getByLabel('Provider', { exact: true })).toBeDisabled()
    await expect(model.getByRole('textbox', { name: 'Model ID' })).toHaveJSProperty('required', true)
    await expect(model.getByLabel('Reasoning Effort 1')).toBeDisabled()
    await expect(model.getByRole('button', { name: '删除 Variant' })).toBeDisabled()
    // 基础费率输入按标签定位（zh-CN 输入 / en-US Input）。
    const price = model.getByLabel(/^(输入|input)$/i)
    // 十进制编辑器保留文本精度与尾零，不经过原生 number 的浮点转换。
    await expect(price).toHaveAttribute('type', 'text')
    await expect(price).toHaveAttribute('inputmode', 'decimal')
    await expect(price).toHaveJSProperty('required', false)
    await expect(price).toHaveValue('')
    await price.fill('1.2500')
    await expect(price).toHaveValue('1.2500')
    await model.getByText('高级选项', { exact: true }).click()
    const protocol = model.getByLabel('Protocol Options 1')
    await protocol.fill('{\n  "temperature": 0.5\n}')
    await expect(protocol).toHaveValue('{\n  "temperature": 0.5\n}')
    const description = agent.getByPlaceholder('用途说明')
    await description.fill('第一行\n第二行')
    await expect(description).toHaveValue('第一行\n第二行')
    // 不只检查 class：所有共享单行输入的实际高度、焦点环与禁用样式。
    expect(await page.locator('.ui-text-input').evaluateAll((inputs) =>
      inputs.every((input) => input.getBoundingClientRect().height === 32))).toBe(true)
    await password.focus()
    await expect(password).toHaveCSS('outline-style', 'solid')
    await expect(password).toHaveCSS('outline-width', '2px')
    await expect(password).toHaveCSS('outline-color', await tokenColor(page, '--focus'))
    await expect(model.getByLabel('Reasoning Effort 1')).toHaveCSS('cursor', 'not-allowed')
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
