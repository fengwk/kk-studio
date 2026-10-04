import { expect, test, type Page } from '@playwright/test'
import type { EnvironmentCardDTO, EnvironmentInstallConfigDTO } from '../src/shared/api/contracts/ai-environment'

// 全量 layout 回归也会收集本文件，剪贴板读写权限由本文件自持，不依赖专属 install 配置。
test.use({ permissions: ['clipboard-read', 'clipboard-write'] })

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

for (const width of [1280, 390, 320]) {
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
    await expect(modal.getByRole('button', { name: '操作系统' })).toHaveAttribute('data-value', 'macos')
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
    await expect(page.getByRole('status')).toContainText('配置已保存，命令已复制')
    await expect(page.getByRole('status')).toHaveCSS('opacity', '1')
    await expect(modal.getByRole('status')).toHaveCount(0)
    await page.screenshot({ path: testInfo.outputPath(`install-toast-${width}.png`), fullPage: true })
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
    await uninstall.getByRole('button', { name: '操作系统' }).click()
    await page.getByRole('option', { name: 'Windows', exact: true }).click()
    await uninstall.getByRole('button', { name: '复制卸载命令' }).click()
    await expect(page.getByRole('status')).toContainText('卸载命令已复制')
    await expect(page.getByRole('status')).toHaveCSS('opacity', '1')
    await expect(uninstall.getByRole('status')).toHaveCount(0)
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

for (const viewport of [
  { width: 1280, height: 900 },
  { width: 390, height: 700 },
  { width: 320, height: 568 },
  { width: 390, height: 360 },
]) {
  test(`modal scroll, help and unclipped menus at ${viewport.width}x${viewport.height}`, async ({ page }, testInfo) => {
    await page.setViewportSize(viewport)
    await api(page)
    await page.goto('/browser-tests/environment-install-harness.html')
    await page.getByRole('button', { name: '安装 / 覆盖 host-one' }).click()
    const modal = page.getByRole('dialog', { name: '安装 / 覆盖' })
    await expect(modal.getByLabel('Studio 地址')).toHaveValue('https://saved.example.com')
    const trigger = modal.getByRole('button', { name: '操作系统' })
    for (const name of ['Linux', 'macOS', 'Windows']) {
      await trigger.click()
      const option = page.getByRole('option', { name, exact: true })
      await option.scrollIntoViewIfNeeded()
      expect(await option.evaluate(element => {
        const rect = element.getBoundingClientRect()
        return element.contains(document.elementFromPoint(rect.x + rect.width / 2, rect.y + rect.height / 2))
      })).toBe(true)
      await option.click()
    }
    await trigger.click()
    await page.screenshot({ path: testInfo.outputPath('install-menu.png'), fullPage: true })
    await page.keyboard.press('Tab')
    await expect(page.getByRole('listbox')).toHaveCount(0)
    await expect(modal.getByLabel('Studio 地址')).toBeFocused()
    await modal.getByText(/可选：Java/).click()
    const editor = modal.getByLabel('LSP servers (JSON)')
    await editor.scrollIntoViewIfNeeded()
    const help = modal.locator('#install-lsp-help')
    const bounds = await modal.evaluate(element => {
      const editor = element.querySelector('textarea')!.getBoundingClientRect()
      const help = element.querySelector('#install-lsp-help')!.getBoundingClientRect()
      const footer = element.querySelector('.modal-footer')!.getBoundingClientRect()
      return { bottom: editor.bottom, helpTop: help.top, height: editor.height, footerTop: footer.top, footerBottom: footer.bottom }
    })
    expect(bounds.height).toBeGreaterThanOrEqual(160)
    expect(bounds.helpTop).toBeGreaterThanOrEqual(bounds.bottom + 6)
    expect(bounds.footerTop).toBeGreaterThanOrEqual(0)
    expect(bounds.footerBottom).toBeLessThanOrEqual(viewport.height)
    await help.scrollIntoViewIfNeeded()
    await page.screenshot({ path: testInfo.outputPath('lsp-help.png'), fullPage: true })
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(viewport.width)
    await modal.getByRole('button', { name: '关闭', exact: true }).last().click()
    await page.getByRole('button', { name: '卸载 host-one', exact: true }).click()
    const uninstall = page.getByRole('dialog', { name: '卸载' })
    for (const name of ['Linux', 'macOS', 'Windows']) {
      await uninstall.getByRole('button', { name: '操作系统' }).click()
      const option = page.getByRole('option', { name, exact: true })
      await option.scrollIntoViewIfNeeded()
      expect(await option.evaluate(element => {
        const rect = element.getBoundingClientRect()
        return element.contains(document.elementFromPoint(rect.x + rect.width / 2, rect.y + rect.height / 2))
      })).toBe(true)
      await option.click()
    }
    await uninstall.getByRole('button', { name: '操作系统' }).click()
    if (viewport.width === 1280) {
      // 浏览器实证旧 absolute 菜单放回 modal-body 后，末项被滚动容器裁剪。
      const legacyClipped = await page.getByRole('listbox').evaluate(element => {
        const menu = element as HTMLElement
        const parent = menu.parentElement!
        const savedStyle = menu.style.cssText
        document.querySelector('.environment-install-modal .ui-select')!.append(menu)
        menu.style.cssText = 'position:absolute;top:calc(100% + 6px);left:0;width:100%;z-index:130'
        const last = menu.querySelector('[role="option"]:last-child')!
        const rect = last.getBoundingClientRect()
        const clipped = !last.contains(document.elementFromPoint(rect.x + rect.width / 2, rect.y + rect.height / 2))
        parent.append(menu)
        menu.style.cssText = savedStyle
        return clipped
      })
      expect(legacyClipped).toBe(true)
    }
    await page.screenshot({ path: testInfo.outputPath('uninstall-menu.png'), fullPage: true })
    await page.keyboard.press('Escape')
    await expect(uninstall.getByRole('button', { name: '操作系统' })).toBeFocused()
    await uninstall.getByRole('button', { name: '复制卸载命令' }).click()
    const toast = page.getByRole('status')
    await expect(toast).toContainText('卸载命令已复制')
    await expect(toast).toHaveCSS('opacity', '1')
    expect(await toast.evaluate(element => element.closest('[role="dialog"]'))).toBeNull()
    expect(await page.locator('body').innerText()).not.toContain(token)
    expect(await page.evaluate(() => JSON.stringify(localStorage))).not.toContain(token)
    await page.screenshot({ path: testInfo.outputPath('uninstall-toast.png'), fullPage: true })
  })
}

test('normal and compact portal menus flip, stay clickable, and keep native adjacent Tab order', async ({ page }, testInfo) => {
  await page.setViewportSize({ width: 320, height: 240 })
  await page.goto('/browser-tests/environment-install-harness.html?controls')
  for (const label of ['Normal', 'Compact']) {
    const trigger = page.getByRole('button', { name: label, exact: true })
    for (const name of ['linux', 'macos', 'windows']) {
      await trigger.click()
      const option = page.getByRole('option', { name, exact: true })
      const box = (await page.getByRole('listbox').boundingBox())!
      expect(box.x).toBeGreaterThanOrEqual(0)
      expect(box.x + box.width).toBeLessThanOrEqual(320)
      expect(box.y).toBeGreaterThanOrEqual(0)
      expect(box.y + box.height).toBeLessThanOrEqual(240)
      await option.scrollIntoViewIfNeeded()
      expect(await option.evaluate(element => {
        const rect = element.getBoundingClientRect()
        return element.contains(document.elementFromPoint(rect.x + rect.width / 2, rect.y + rect.height / 2))
      })).toBe(true)
      await option.click()
    }
    await trigger.click()
    await page.keyboard.press('Shift+Tab')
    await expect(page.getByRole('button', { name: label === 'Normal' ? 'Before' : 'Normal', exact: true })).toBeFocused()
    await trigger.click()
    await page.keyboard.press('Tab')
    await expect(page.getByRole('button', { name: label === 'Normal' ? 'Compact' : 'After', exact: true })).toBeFocused()
    await trigger.click()
    await page.screenshot({ path: testInfo.outputPath(`${label}-flip.png`), fullPage: true })
    await page.keyboard.press('Escape')
  }
})

test('larger fonts preserve the editor help gap and reachable footer on a narrow screen', async ({ page }, testInfo) => {
  await page.setViewportSize({ width: 320, height: 568 })
  await api(page)
  await page.goto('/browser-tests/environment-install-harness.html')
  await page.getByRole('button', { name: '安装 / 覆盖 host-one' }).click()
  const modal = page.getByRole('dialog', { name: '安装 / 覆盖' })
  await expect(modal.getByLabel('Studio 地址')).toHaveValue('https://saved.example.com')
  await page.addStyleTag({ content: '.environment-install-modal {font-size:20px} .environment-install-modal .form-group, .environment-install-modal .field-help, .environment-install-modal .confirm-modal-description, .environment-install-modal button {font-size:inherit;line-height:1.5}' })
  await modal.getByText(/可选：Java/).click()
  await modal.locator('#install-lsp-help').scrollIntoViewIfNeeded()
  const bounds = await modal.evaluate(element => ({
    editorBottom: element.querySelector('textarea')!.getBoundingClientRect().bottom,
    helpTop: element.querySelector('#install-lsp-help')!.getBoundingClientRect().top,
    footerBottom: element.querySelector('.modal-footer')!.getBoundingClientRect().bottom,
    scrollWidth: element.scrollWidth,
    clientWidth: element.clientWidth,
  }))
  expect(bounds.helpTop).toBeGreaterThan(bounds.editorBottom)
  expect(bounds.footerBottom).toBeLessThanOrEqual(568)
  expect(bounds.scrollWidth).toBeLessThanOrEqual(bounds.clientWidth)
  await expect(modal.getByRole('button', { name: '保存并复制安装命令' })).toBeVisible()
  await page.screenshot({ path: testInfo.outputPath('large-fonts.png'), fullPage: true })
})
