import { expect, test } from './fixture'

test('resource forms preserve native semantics, shared geometry and controlled edits at wide and narrow widths', async ({ page }) => {
  // 真实表单仅编辑本地草稿；任何网络请求都是 harness 越界。
  const requests: string[] = []
  await page.route('**/api/**', async (route) => {
    requests.push(route.request().url())
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
    await expect(model.getByLabel('Provider', { exact: true })).toBeDisabled()
    await expect(model.getByLabel('Reasoning Effort 1')).toBeDisabled()
    await expect(model.getByRole('button', { name: '删除 Variant' })).toBeDisabled()
    const price = model.getByLabel('Input USD per million tokens')
    await expect(price).toHaveAttribute('type', 'number')
    await expect(price).toHaveAttribute('min', '0')
    await expect(price).toHaveAttribute('step', 'any')
    await price.fill('1.25')
    await expect(price).toHaveValue('1.25')
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
    await expect(password).toHaveCSS('box-shadow', /rgba\(113, 231, 154/)
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
  expect(requests).toEqual([])
})
