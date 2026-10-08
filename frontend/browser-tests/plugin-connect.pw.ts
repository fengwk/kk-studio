import { expect, test, type Page } from './fixture'
import type { PluginDTO } from '@/shared/api/contracts/ai-plugin'

const plugin: PluginDTO = {
  pluginId: 'offline', name: 'Offline Plugin', version: '1',
  authKind: { type: 'DEEP_LINK', regionCandidates: ['CN', 'EN'] },
  status: 'NOT_CONNECTED', region: null, expiresAt: null,
  nextRefreshAt: null, lastRefreshedAt: null, lastRefreshError: null,
}

async function transport(page: Page) {
  let release!: () => void
  const gate = new Promise<void>((resolve) => { release = resolve })
  const requests: string[] = []
  await page.route('**/api/**', async (route) => {
    const path = new URL(route.request().url()).pathname
    if (path === '/api/plugins') {
      await route.fulfill({ json: { status: 200, data: [plugin] } })
    } else if (path.endsWith('/auth/prepare') || path.endsWith('/auth/complete')) {
      requests.push(path)
      await gate
      // 完成失败是 settled 可关闭且不能恢复凭据的真实网络边界。
      if (path.endsWith('/complete')) {
        await route.fulfill({ status: 400, json: { status: 400, message: 'Callback rejected' } })
      } else {
        await route.fulfill({ json: { status: 200, data: { loginUrl: '' } } })
      }
    } else throw new Error(`Unexpected offline request: ${path}`)
  })
  return { requests, release }
}

for (const stage of ['prepare', 'complete'] as const) {
  test(`${stage}: real shared controls at 320px lock every close path and other stage until settled`, async ({ page }, info) => {
    await page.setViewportSize({ width: 320, height: 800 })
    const network = await transport(page)
    await page.goto('/browser-tests/resource-card-harness.html?plugins')
    const opener = page.getByRole('button', { name: '连接 Offline Plugin' })
    await opener.click()
    const dialog = page.getByRole('dialog')
    const input = dialog.getByTestId('plugin-callback-input')
    await expect(input).toHaveCSS('height', '32px')
    await expect(input).toHaveAttribute('type', 'password')
    await expect(input).toHaveAttribute('autocomplete', 'off')
    const login = dialog.getByRole('button', { name: '打开官方登录页面' })
    await expect(login).toHaveCSS('height', '32px')
    const close = dialog.getByRole('button', { name: '关闭' })
    await expect(close).toHaveCSS('height', '32px')
    await input.fill('offline-callback')
    if (stage === 'prepare') await login.click()
    else await dialog.getByRole('button', { name: '完成连接' }).click()
    await expect.poll(() => network.requests.length).toBe(1)
    await expect(input).toBeDisabled()
    await expect(dialog.getByRole('button', { name: '区域' })).toBeDisabled()
    const cancel = dialog.getByRole('button', { name: '取消' })
    await expect(cancel).toBeDisabled()
    await expect(close).toBeDisabled()
    await expect(dialog.locator('button[type=submit]')).toBeDisabled()
    await page.keyboard.press('Escape')
    await page.locator('.modal-backdrop').click({ position: { x: 1, y: 1 } })
    // disabled 控件不可 actionability click；仍向原生节点派发事件验证关闭边界。
    await close.dispatchEvent('click')
    await cancel.dispatchEvent('click')
    await dialog.locator('form').dispatchEvent('submit')
    await expect(dialog).toBeVisible()
    expect(network.requests).toHaveLength(1)
    if (stage === 'complete') await expect(input).toHaveValue('')
    const overflow = await dialog.evaluate((el) => el.scrollWidth > el.clientWidth + 1)
    expect(overflow).toBe(false)
    await page.screenshot({ path: info.outputPath(`${stage}-pending-320.png`) })
    network.release()
    await expect(input).toBeEnabled()
    if (stage === 'complete') {
      await expect(input).toHaveValue('')
      await expect(dialog.getByRole('alert')).toHaveText('Callback rejected')
    }
    await page.keyboard.press('Escape')
    await expect(dialog).toHaveCount(0)
    await expect(opener).toBeFocused()
    await opener.click()
    await page.getByRole('button', { name: '取消', exact: true }).click()
    await expect(dialog).toHaveCount(0)
  })
}

test('English login and pending copy fit shared controls at 320px', async ({ page }, info) => {
  await page.setViewportSize({ width: 320, height: 800 })
  const network = await transport(page)
  await page.goto('/browser-tests/resource-card-harness.html?plugins&locale=en-US')
  await page.getByRole('button', { name: 'Connect Offline Plugin' }).click()
  const dialog = page.getByRole('dialog')
  await expect(dialog).toHaveAccessibleName('Connect Plugin: Offline Plugin')
  await expect(dialog.getByText('Open the login page first, then paste the callback link here.')).toBeVisible()
  await dialog.getByRole('button', { name: 'Open Login Page' }).click()
  await expect(dialog.getByRole('button', { name: 'Opening login…' })).toBeDisabled()
  await expect(dialog.getByRole('button', { name: 'Cancel' })).toBeDisabled()
  expect(await dialog.evaluate((el) => el.scrollWidth <= el.clientWidth + 1)).toBe(true)
  await page.screenshot({ path: info.outputPath('prepare-english-320.png') })
  network.release()
  await expect(dialog.getByTestId('plugin-callback-input')).toBeEnabled()
  await dialog.getByRole('button', { name: 'Close', exact: true }).click()
  await expect(dialog).toHaveCount(0)
})
