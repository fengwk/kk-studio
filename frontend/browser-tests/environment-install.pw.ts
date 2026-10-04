import { expect, test, type Page } from '@playwright/test'
import type { EnvironmentCardDTO, EnvironmentInstallConfigDTO } from '../src/shared/api/contracts/ai-environment'

const token = 'browser-private-token'
const initialConfig: EnvironmentInstallConfigDTO = {
  operatingSystem: 'macos', javaHome: '/Library/Java/汉字',
  daemon: { studioUrl: 'https://saved.example.com', note: 'saved note', bashExecutable: '/bin/bash',
    lsp: { servers: { ts: { command: ['~/ts-server', "quote'$`"], extensions: ['.ts'] } } } },
}

async function api(page: Page) {
  let card: EnvironmentCardDTO = { id: 'env-1', name: 'host-one', version: '7', status: 'OFFLINE', ready: false,
    lastSeen: null, capabilities: [], createTime: '', updateTime: '', installConfig: initialConfig }
  let conflict = false
  const calls: string[] = []
  await page.route('**/api/harness/environments**', async route => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    const method = request.method()
    calls.push(`${method} ${path}`)
    if (method === 'PUT') {
      const body = request.postDataJSON()
      expect(Object.keys(body).sort()).toEqual(['expectedVersion', 'installConfig'])
      if (conflict || body.expectedVersion !== card.version) {
        conflict = false
        card = { ...card, version: String(Number(card.version) + 1) }
        return route.fulfill({ status: 409, json: { status: 409, code: 'CONFLICT', message: 'conflict', errors: { reason: 'stale_version' } } })
      }
      card = { ...card, installConfig: body.installConfig, version: String(Number(card.version) + 1) }
      return route.fulfill({ json: card })
    }
    if (path.endsWith('/token')) return route.fulfill({ json: { id: card.id, version: card.version, registrationToken: token } })
    if (path.endsWith('/registration-token')) {
      card = { ...card, version: String(Number(card.version) + 1) }
      return route.fulfill({ json: { ...card, registrationToken: token } })
    }
    if (method === 'POST') {
      card = { ...card, name: request.postDataJSON().name, installConfig: null, version: '0' }
      return route.fulfill({ json: { ...card, registrationToken: token } })
    }
    return route.fulfill({ json: path.endsWith('/env-1') ? card : [card] })
  })
  return { calls, conflictNext: () => { conflict = true } }
}

for (const width of [1280, 390]) {
  test(`saved form, safe copy, reopen and uninstall at ${width}px`, async ({ page }, testInfo) => {
    await page.setViewportSize({ width, height: 900 })
    const mock = await api(page)
    await page.goto('/browser-tests/environment-install-harness.html')
    await expect(page.getByRole('button', { name: '安装 / 覆盖 host-one' })).toBeVisible()
    await page.screenshot({ path: testInfo.outputPath(`cards-${width}.png`), fullPage: true })
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width)
    await page.getByRole('button', { name: '安装 / 覆盖 host-one' }).click()
    const modal = page.getByRole('dialog', { name: '安装 / 覆盖' })
    await expect(modal.getByLabel('Studio 地址')).toHaveValue('https://saved.example.com')
    await expect(modal.getByRole('combobox')).toHaveValue('macos')
    await expect(modal.getByText(/HTTP\(S\) base 主机/)).toBeVisible()
    await modal.getByText(/可选：Java/).click()
    await expect(modal.getByLabel('备注')).toHaveValue('saved note')
    expect(await modal.getByLabel('LSP servers (JSON)').inputValue()).toContain('~/ts-server')
    await modal.getByText(/可选：Java/).focus()
    await modal.getByText(/可选：Java/).press('Enter')
    await expect(modal.getByLabel('LSP servers (JSON)')).toBeHidden()
    await modal.getByText(/可选：Java/).press('Enter')
    await expect(modal.getByLabel('LSP servers (JSON)')).toBeVisible()
    await modal.getByLabel('备注').fill("draft 汉字 '$`")
    await page.screenshot({ path: testInfo.outputPath(`install-${width}.png`), fullPage: true })
    const box = await modal.boundingBox()
    expect(box!.x).toBeGreaterThanOrEqual(0)
    expect(box!.x + box!.width).toBeLessThanOrEqual(width)
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width)
    await modal.getByRole('button', { name: '保存并复制安装命令' }).click()
    await expect(modal.getByRole('status')).toContainText('配置已保存，命令已复制')
    const command = await page.evaluate(() => navigator.clipboard.readText())
    expect(command).toContain("bash <<'KK_STUDIO_INSTALL'")
    expect(command).toContain('daemon.token')
    expect(command).toContain(token)
    expect(mock.calls.filter(c => c.endsWith('/token'))).toHaveLength(1)
    expect(await page.locator('body').innerText()).not.toContain(token)
    expect(await page.evaluate(() => JSON.stringify(localStorage))).not.toContain(token)
    await modal.getByRole('button', { name: '关闭', exact: true }).last().click()
    await page.getByRole('button', { name: '安装 / 覆盖 host-one' }).click()
    await modal.getByText(/可选：Java/).click()
    await expect(modal.getByLabel('备注')).toHaveValue("draft 汉字 '$`")
    await modal.getByRole('button', { name: '关闭', exact: true }).last().click()
    const before = mock.calls.length
    await page.getByRole('button', { name: '卸载 host-one', exact: true }).click()
    const uninstall = page.getByRole('dialog', { name: '卸载' })
    await expect(uninstall).toContainText('不删除任何 Studio 环境记录')
    await uninstall.getByRole('combobox').selectOption('windows')
    await uninstall.getByRole('button', { name: '复制卸载命令' }).click()
    await expect(uninstall.getByRole('status')).toContainText('卸载命令已复制')
    expect(await page.evaluate(() => navigator.clipboard.readText())).not.toContain(token)
    expect(mock.calls.slice(before)).toEqual([])
    await page.screenshot({ path: testInfo.outputPath(`uninstall-${width}.png`), fullPage: true })
  })
}

test('create opens form without exposing credentials; rotate does not fetch token', async ({ page }) => {
  const mock = await api(page)
  await page.goto('/browser-tests/environment-install-harness.html')
  await page.getByRole('button', { name: '创建环境', exact: true }).click()
  await page.getByLabel(/环境名称/).fill('new-host')
  await page.getByRole('button', { name: '确认', exact: true }).click()
  const install = page.getByRole('dialog', { name: '安装 / 覆盖' })
  await expect(install).toBeVisible()
  await expect(install.getByRole('button', { name: '保存并复制安装命令' })).toBeEnabled()
  expect(await page.locator('body').innerText()).not.toContain(token)
  await install.getByRole('button', { name: '关闭', exact: true }).last().click()
  await page.getByRole('button', { name: '重新生成 Token new-host' }).click()
  await page.getByRole('alertdialog').getByRole('button', { name: '重新生成 Token', exact: true }).click()
  await expect(page.getByRole('alertdialog')).toHaveCount(0)
  expect(mock.calls.some(c => c.endsWith('/token'))).toBe(false)
  expect(await page.locator('body').innerText()).not.toContain(token)
})

test('CAS conflict preserves draft; clipboard rejection explicitly says saved, not copied', async ({ page }) => {
  const mock = await api(page)
  await page.goto('/browser-tests/environment-install-harness.html')
  await page.getByRole('button', { name: '安装 / 覆盖 host-one' }).click()
  const modal = page.getByRole('dialog', { name: '安装 / 覆盖' })
  await expect(modal.getByRole('button', { name: '保存并复制安装命令' })).toBeEnabled()
  await modal.getByLabel('Studio 地址').fill('https://draft.example.com')
  mock.conflictNext()
  await modal.getByRole('button', { name: '保存并复制安装命令' }).click()
  await expect(page.getByRole('alertdialog', { name: '数据已发生变化' })).toBeVisible()
  expect(mock.calls.some(c => c.endsWith('/token'))).toBe(false)
  await page.getByRole('alertdialog').getByRole('button', { name: '刷新', exact: true }).click()
  await expect(page.getByRole('alertdialog')).toHaveCount(0)
  await expect(modal.getByLabel('Studio 地址')).toHaveValue('https://draft.example.com')
  await page.evaluate(() => {
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText: () => Promise.reject(new Error('denied')) } })
    document.execCommand = () => false
  })
  await modal.getByRole('button', { name: '保存并复制安装命令' }).click()
  await expect(modal.getByRole('alert')).toContainText('配置已保存，但命令生成或复制失败')
  expect(await page.locator('body').innerText()).not.toContain(token)
})
