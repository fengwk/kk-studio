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
    statusExpiresAt: null, lastSeen: null, capabilities: [], createTime: '', updateTime: '', installConfig: initialConfig }
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
    if (path.endsWith('/install-code')) {
      return route.fulfill({ json: { code: 'browser-install-code', expiresAt: '2026-10-05T09:05:00.000Z' } })
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
    const modal = page.getByRole('dialog', { name: '安装/覆盖环境' })
    await expect(modal.getByLabel('Studio 地址')).toHaveValue('https://saved.example.com')
    await expect(modal.getByRole('button', { name: '操作系统' })).toHaveAttribute('data-value', 'macos')
    await expect(modal.getByText('不含路径或查询参数。')).toBeVisible()
    await expect(modal.locator('details, summary')).toHaveCount(0)
    await expect(modal.getByText(/可选：Java/)).toHaveCount(0)
    await expect(modal.getByLabel('Java home (JDK 21)')).toHaveValue('/Library/Java/汉字')
    await expect(modal.getByLabel('Bash 可执行文件')).toHaveValue('/bin/bash')
    await expect(modal.getByLabel('备注')).toHaveValue('saved note')
    expect(await modal.getByLabel('LSP servers (JSON)').inputValue()).toContain('~/ts-server')
    await modal.getByLabel('备注').fill("draft 汉字 '$`")
    await page.screenshot({ path: testInfo.outputPath(`install-${width}.png`), fullPage: true })
    const box = await modal.boundingBox()
    expect(box!.x).toBeGreaterThanOrEqual(0)
    expect(box!.x + box!.width).toBeLessThanOrEqual(width)
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width)
    await modal.getByRole('button', { name: '保存并复制安装命令' }).click()
    await expect(page.getByRole('status')).toContainText('安装命令已复制，5分钟内有效')
    await expect(page.getByRole('status')).toHaveCSS('opacity', '1')
    await expect(modal.getByRole('status')).toHaveCount(0)
    await page.screenshot({ path: testInfo.outputPath(`install-toast-${width}.png`), fullPage: true })
    const command = await page.evaluate(() => navigator.clipboard.readText())
    const pageOrigin = new URL(page.url()).origin
    const url = `${pageOrigin}/api/harness/environments/env-1/install?code=browser-install-code`
    expect(command).toBe(`(set -o pipefail; curl -fsSL $'${url}' | bash)`)
    expect(command).not.toContain(token)
    expect(mock.calls.filter(c => c.endsWith('/install-code'))).toHaveLength(1)
    expect(mock.calls.filter(c => c.endsWith('/token'))).toHaveLength(0)
    expect(await page.locator('body').innerText()).not.toContain(token)
    expect(await page.evaluate(() => JSON.stringify(localStorage))).not.toContain(token)
    await modal.getByRole('button', { name: '关闭', exact: true }).last().click()
    await page.getByRole('button', { name: '安装 / 覆盖 host-one' }).click()
    await expect(modal.getByLabel('备注')).toHaveValue("draft 汉字 '$`")
    await modal.getByRole('button', { name: '关闭', exact: true }).last().click()
    const before = mock.calls.length
    await page.getByRole('button', { name: '卸载 host-one', exact: true }).click()
    const uninstall = page.getByRole('dialog', { name: '卸载环境' })
    await expect(uninstall).toContainText('Studio 中的环境记录不会删除')
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

for (const width of [1280, 390, 320]) {
  test(`fresh install shows a gray LSP example placeholder at ${width}px`, async ({ page }, testInfo) => {
    await page.setViewportSize({ width, height: width === 1280 ? 900 : 700 })
    await api(page)
    await page.goto('/browser-tests/environment-install-harness.html')
    await page.getByRole('button', { name: '创建环境', exact: true }).click()
    await page.getByLabel(/环境名称/).fill(`fresh-${width}`)
    await page.getByRole('button', { name: '确认', exact: true }).click()
    const modal = page.getByRole('dialog', { name: '安装/覆盖环境' })
    await expect(modal.getByRole('heading', { name: '安装/覆盖环境' })).toBeVisible()
    await expect(modal).not.toContainText('host-one')
    await expect(modal.getByRole('button', { name: '保存并复制安装命令' })).toBeEnabled()
    await page.screenshot({ path: testInfo.outputPath(`install-initial-${width}.png`), animations: 'disabled' })
    await expect(modal.locator('details, summary')).toHaveCount(0)
    await expect(modal.getByLabel('Java home (JDK 21)')).toBeVisible()
    await expect(modal.getByLabel('Java home (JDK 21)')).toHaveValue('')
    await expect(modal.getByLabel('Java home (JDK 21)')).toHaveAttribute('placeholder', '/usr/lib/jvm/java-21-openjdk')
    await expect(modal.getByLabel('Bash 可执行文件')).toHaveAttribute('placeholder', '/bin/bash')
    await expect(modal.getByLabel('备注')).toHaveAttribute('placeholder', '例如：开发工作站')
    const gray = 'rgb(157, 168, 159)'
    for (const label of ['Java home (JDK 21)', 'Bash 可执行文件', '备注']) {
      const field = modal.getByLabel(label)
      expect(await field.evaluate(element => element.matches(':placeholder-shown'))).toBe(true)
      expect(await field.evaluate(element => getComputedStyle(element, '::placeholder').color)).toBe(gray)
    }
    // 字段只使用 modal-body 的统一 row-gap，不能再叠加相邻 margin。
    const spacing = await modal.evaluate(element => {
      const body = element.querySelector('.modal-body')!
      const labels = ['Java home (JDK 21)', 'Bash 可执行文件', '备注']
      const groups = labels.map(label => [...element.querySelectorAll('.form-group')]
        .find(group => group.querySelector(':scope > span')?.textContent === label)!)
      const gap = Number.parseFloat(getComputedStyle(body).rowGap)
      return {
        gap,
        margins: groups.map(group => Number.parseFloat(getComputedStyle(group).marginTop)),
        distances: groups.slice(1).map((group, index) => group.getBoundingClientRect().top - groups[index]!.getBoundingClientRect().bottom),
      }
    })
    expect(spacing.gap).toBe(18)
    expect(spacing.margins).toEqual([0, 0, 0])
    expect(spacing.distances.every(distance => Math.abs(distance - spacing.gap) <= 1)).toBe(true)
    const lsp = modal.locator('label.ui-checkbox', { hasText: '启用 LSP servers' })
    await expect(lsp).toBeVisible()
    await lsp.scrollIntoViewIfNeeded()
    await lsp.click()
    await expect(modal.getByRole('checkbox')).toBeChecked()
    const editor = modal.getByLabel('LSP servers (JSON)')
    await expect(editor).toHaveValue('')
    expect(await editor.evaluate(element => element.matches(':placeholder-shown'))).toBe(true)
    const example = await editor.getAttribute('placeholder')
    expect(example).toContain('"jdtls"')
    expect(example).toContain('pom.xml')
    await expect(modal.locator('#install-lsp-help')).toHaveText('请先在目标主机安装对应的语言服务器。')
    await expect(modal.locator('#install-lsp-help')).not.toContainText('rootMarkers')
    const color = await editor.evaluate(element => getComputedStyle(element, '::placeholder').color)
    expect(color).toBe('rgb(157, 168, 159)')
    await editor.scrollIntoViewIfNeeded()
    await page.screenshot({ path: testInfo.outputPath(`lsp-placeholder-${width}.png`), animations: 'disabled' })
    if (width === 1280) {
      await modal.locator('.confirm-modal-description').scrollIntoViewIfNeeded()
      await page.screenshot({ path: testInfo.outputPath('install-copy-1280.png'), animations: 'disabled' })
    }
    const os = modal.getByRole('button', { name: '操作系统' })
    await os.click()
    await page.getByRole('option', { name: 'Windows', exact: true }).click()
    await expect(modal.getByLabel('Java home (JDK 21)')).toHaveAttribute('placeholder', 'C:\\Program Files\\Java\\jdk-21')
    await expect(modal.getByLabel('Bash 可执行文件')).toHaveAttribute('placeholder', 'C:\\Program Files\\Git\\bin\\bash.exe')
    await expect(modal.getByLabel('Java home (JDK 21)')).toHaveValue('')
    await modal.getByLabel('Java home (JDK 21)').fill('D:\\custom\\jdk')
    await modal.getByLabel('备注').fill('我的工作站')
    await os.click()
    await page.getByRole('option', { name: 'macOS', exact: true }).click()
    await expect(modal.getByLabel('Java home (JDK 21)')).toHaveAttribute(
      'placeholder', '/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home')
    await expect(modal.getByLabel('Bash 可执行文件')).toHaveAttribute('placeholder', '/bin/bash')
    await expect(modal.getByLabel('Java home (JDK 21)')).toHaveValue('D:\\custom\\jdk')
    await expect(modal.getByLabel('备注')).toHaveValue('我的工作站')
    await editor.fill('{"jdtls":{"command":["/usr/bin/jdtls"],"extensions":[".java"]}}')
    await expect(editor).toHaveValue(/\/usr\/bin\/jdtls/)
    await expect(editor).not.toHaveValue(/mason/)
    expect(await editor.evaluate(element => element.matches(':placeholder-shown'))).toBe(false)
    await page.screenshot({ path: testInfo.outputPath(`lsp-filled-${width}.png`), animations: 'disabled' })
    await modal.locator('.install-credential-note').scrollIntoViewIfNeeded()
    const overlap = await modal.evaluate(element => {
      const note = element.querySelector('.install-credential-note')!.getBoundingClientRect()
      const footer = element.querySelector('.modal-footer')!.getBoundingClientRect()
      return { noteBottom: note.bottom, footerTop: footer.top, footerBottom: footer.bottom }
    })
    expect(overlap.noteBottom).toBeLessThanOrEqual(overlap.footerTop + 1)
    expect(overlap.footerBottom).toBeLessThanOrEqual(width === 1280 ? 900 : 700)
  })
}

test('create opens form without exposing credentials; rotate does not fetch token', async ({ page }) => {
  const mock = await api(page)
  await page.goto('/browser-tests/environment-install-harness.html')
  await page.getByRole('button', { name: '创建环境', exact: true }).click()
  await page.getByLabel(/环境名称/).fill('new-host')
  await page.getByRole('button', { name: '确认', exact: true }).click()
  const install = page.getByRole('dialog', { name: '安装/覆盖环境' })
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
  const modal = page.getByRole('dialog', { name: '安装/覆盖环境' })
  await expect(modal.getByRole('button', { name: '保存并复制安装命令' })).toBeEnabled()
  await modal.getByLabel('Studio 地址').fill('https://draft.example.com')
  mock.conflictNext()
  await modal.getByRole('button', { name: '保存并复制安装命令' }).click()
  await expect(page.getByRole('alertdialog', { name: '数据已发生变化' })).toBeVisible()
  expect(mock.calls.filter(c => c.endsWith('/token'))).toHaveLength(0)
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
    const modal = page.getByRole('dialog', { name: '安装/覆盖环境' })
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
    const uninstall = page.getByRole('dialog', { name: '卸载环境' })
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
    expect(await page.getByRole('listbox').evaluate(element => element.closest('.modal-body'))).toBeNull()
    const menuBox = (await page.getByRole('listbox').boundingBox())!
    expect(menuBox.x).toBeGreaterThanOrEqual(0)
    expect(menuBox.y).toBeGreaterThanOrEqual(0)
    expect(menuBox.x + menuBox.width).toBeLessThanOrEqual(viewport.width)
    expect(menuBox.y + menuBox.height).toBeLessThanOrEqual(viewport.height)
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
  const modal = page.getByRole('dialog', { name: '安装/覆盖环境' })
  await expect(modal.getByLabel('Studio 地址')).toHaveValue('https://saved.example.com')
  await page.addStyleTag({ content: '.environment-install-modal {font-size:20px} .environment-install-modal .form-group, .environment-install-modal .field-help, .environment-install-modal .confirm-modal-description, .environment-install-modal button {font-size:inherit;line-height:1.5}' })
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

test('native Tab skips closed details inputs and disabled controls around both Select modes', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 })
  await page.goto('/browser-tests/environment-install-harness.html?controls&tab-boundaries')
  for (const label of ['Normal', 'Compact']) {
    const trigger = page.getByRole('button', { name: label, exact: true })
    await trigger.click()
    await page.keyboard.press('Shift+Tab')
    await expect(page.getByText(label === 'Normal' ? 'Before details' : 'Between details', { exact: true })).toBeFocused()
    await expect(page.getByRole('listbox')).toHaveCount(0)
    await trigger.click()
    await page.keyboard.press('Tab')
    await expect(page.getByText(label === 'Normal' ? 'Between details' : 'After details', { exact: true })).toBeFocused()
    await expect(page.getByRole('listbox')).toHaveCount(0)
  }
  // closed details 的子 input 在 DOM 中存在，但不是当前原生 Tab 顺序的一部分。
  await expect(page.getByLabel('Closed before')).toBeHidden()
  await expect(page.getByLabel('Closed between')).toBeHidden()
  await expect(page.getByLabel('Closed after')).toBeHidden()
  await expect(page.getByRole('button', { name: 'Disabled between' })).toBeDisabled()
})

/**
 * 卡片只呈现当前状态，历史事件只在管理弹窗的 Events 中可见。
 *
 * 通过离线路由 mock 渲染真实 EnvironmentsPage：同一环境有历史 WARN 且当前 READY 时，
 * 卡片不得内嵌任何事件投影，而管理弹窗仍展示完整历史（WARN 与后续 READY）。
 */
test('keeps event history out of the card and inside the management modal', async ({ page }, testInfo) => {
  await page.setViewportSize({ width: 1280, height: 900 })
  const history = [
    { time: '2026-07-20T00:00:00.000Z', level: 'WARN', type: 'DISCONNECTED', message: 'daemon connection lost' },
    { time: '2026-07-20T00:01:00.000Z', level: 'INFO', type: 'READY', message: 'environment ready' },
  ]
  const card: EnvironmentCardDTO = {
    id: 'env-1', name: 'recovered-box', version: '1', status: 'READY', ready: true,
    statusExpiresAt: null, lastSeen: '2026-07-20T00:01:00.000Z', capabilities: [], createTime: '', updateTime: '',
  }
  await page.route('**/api/harness/environments**', async route => {
    const path = new URL(route.request().url()).pathname
    if (path.endsWith('/events')) {
      return route.fulfill({ json: history })
    }
    return route.fulfill({ json: path.endsWith('/env-1') ? card : [card] })
  })

  await page.goto('/browser-tests/environment-install-harness.html')
  const cardLocator = page.locator('.environment-card')
  // 首次冷启动时 vite 需现编译模块图，给首个可见断言更宽的窗口。
  await expect(cardLocator).toBeVisible({ timeout: 15000 })
  await expect(cardLocator.getByText('READY', { exact: true })).toBeVisible()
  // 卡片不含任何历史事件区域，也不回显事件文本。
  await expect(page.locator('.env-last-event')).toHaveCount(0)
  await expect(cardLocator).not.toContainText('daemon connection lost')
  await page.screenshot({ path: testInfo.outputPath('card-without-history.png'), fullPage: true })

  // 管理弹窗仍展示完整历史：历史 WARN 与后续 READY。
  await cardLocator.getByRole('button', { name: /管理/ }).click()
  const modal = page.getByRole('dialog', { name: /管理环境/ })
  await expect(modal.getByText('daemon connection lost')).toBeVisible()
  await expect(modal.getByText('environment ready')).toBeVisible()
  await page.screenshot({ path: testInfo.outputPath('management-history.png'), fullPage: true })
})
