import { expect, test, type Page } from '@playwright/test'
import { tokenColor } from './fixture'
import { url } from '@/shared/api/client'
import type { EnvironmentDTO } from '@/shared/api/generated/contracts'
import {
  testDaemonConfig,
  testEnvironment,
  testRuntimeStatus,
} from '@/test-support/environment-test-fixtures'

// 全量 layout 回归也会收集本文件，剪贴板读写权限由本文件自持，不依赖专属 install 配置。
test.use({ permissions: ['clipboard-read', 'clipboard-write'] })

const token = 'browser-private-token'
const savedConfig = testDaemonConfig({
  studioUrl: 'https://saved.example.com',
  bashExecutable: '/bin/bash',
  lsp: { servers: { ts: { command: ['~/ts-server', "quote'$`"], extensions: ['.ts'] } } },
  proxyUrl: null,
})

async function api(page: Page) {
  let card: EnvironmentDTO = testEnvironment({
    id: 'env-1',
    name: 'host-one',
    note: 'saved note',
    version: '7',
    status: 'OFFLINE',
    ready: false,
    daemonConfig: savedConfig,
  })
  let conflict = false
  const calls: string[] = []
  const updateBodies: Record<string, unknown>[] = []
  const createBodies: Record<string, unknown>[] = []
  await page.route('**/api/platform/environments**', async route => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    const method = request.method()
    calls.push(`${method} ${path}`)
    if (method === 'PUT') {
      const body = request.postDataJSON()
      updateBodies.push(body)
      expect(Object.keys(body).sort()).toEqual(['daemonConfig', 'expectedVersion', 'note'])
      if (conflict || body.expectedVersion !== card.version) {
        conflict = false
        card = { ...card, version: String(BigInt(card.version) + 1n) }
        return route.fulfill({
          status: 409,
          contentType: 'application/problem+json',
          json: { type: 'about:blank', status: 409, code: 'version_conflict', title: 'conflict' },
        })
      }
      card = {
        ...card,
        note: body.note,
        daemonConfig: body.daemonConfig,
        version: String(BigInt(card.version) + 1n),
      }
      return route.fulfill({ json: card })
    }
    if (path.endsWith('/install-codes')) {
      expect(request.postDataJSON()).toEqual({ expectedVersion: card.version })
      return route.fulfill({
        json: { code: 'browser-install-code', expiresAt: '2026-10-05T09:05:00.000Z' },
      })
    }
    if (path.endsWith('/rotate-token')) {
      card = { ...card, version: String(BigInt(card.version) + 1n) }
      return route.fulfill({ json: { ...card, registrationToken: token } })
    }
    if (method === 'POST') {
      const body = request.postDataJSON()
      createBodies.push(body)
      expect(Object.keys(body).sort()).toEqual(['daemonConfig', 'name', 'note'])
      card = testEnvironment({
        id: 'env-new',
        name: body.name,
        note: body.note,
        daemonConfig: body.daemonConfig,
        version: '0',
        status: 'OFFLINE',
        ready: false,
      })
      return route.fulfill({ json: { ...card, registrationToken: token } })
    }
    if (method === 'GET' && path === '/api/platform/environments') {
      return route.fulfill({ json: [card] })
    }
    if (method === 'GET' && path === '/api/platform/environments/env-1') {
      return route.fulfill({ json: card })
    }
    if (method === 'GET' && path === '/api/platform/environments/env-new') {
      return route.fulfill({ json: card })
    }
    return route.fulfill({ status: 404, json: { code: 'not_found' } })
  })
  return {
    calls,
    updateBodies,
    createBodies,
    conflictNext: () => {
      conflict = true
    },
  }
}

for (const width of [1280, 390, 320]) {
  test(`edits the only card config, saves CAS, then copies install and uninstall commands at ${width}px`, async ({
    page,
  }, testInfo) => {
    await page.setViewportSize({ width, height: 900 })
    const mock = await api(page)
    await page.goto('/browser-tests/environment-install-harness.html')
    await expect(page.getByRole('button', { name: '安装 / 覆盖 host-one' })).toBeVisible()
    await page.screenshot({ path: testInfo.outputPath(`cards-${width}.png`), fullPage: true })
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width)

    await page.getByRole('button', { name: '编辑配置 host-one' }).click()
    const edit = page.getByRole('dialog', { name: '编辑环境：host-one' })
    await expect(edit.getByRole('textbox', { name: '环境名称' })).toBeDisabled()
    await expect(edit.getByRole('textbox', { name: 'Studio 地址' })).toHaveValue(
      'https://saved.example.com',
    )
    await expect(edit.getByRole('textbox', { name: '备注' })).toHaveValue('saved note')
    await expect(edit.getByRole('textbox', { name: 'Daemon 代理' })).toHaveValue('')
    await expect(edit.getByRole('checkbox')).toBeChecked()
    expect(await edit.getByRole('textbox', { name: 'LSP servers' }).inputValue()).toContain(
      '~/ts-server',
    )
    await expect(edit.getByRole('button', { name: /帮助：Daemon 代理/ })).toBeVisible()
    await edit.getByRole('button', { name: /帮助：Daemon 代理/ }).click()
    await expect(edit.getByText(/留空表示直连/).first()).toBeVisible()

    await edit.getByRole('textbox', { name: '备注' }).fill("draft 汉字 '$`")
    await edit
      .getByRole('textbox', { name: 'Daemon 代理' })
      .fill('http://127.0.0.1:7890')
    await page.screenshot({ path: testInfo.outputPath(`edit-${width}.png`), fullPage: true })
    const box = await edit.boundingBox()
    expect(box!.x).toBeGreaterThanOrEqual(0)
    expect(box!.x + box!.width).toBeLessThanOrEqual(width)
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width)

    mock.conflictNext()
    await edit.getByRole('button', { name: '保存配置' }).click()
    const conflict = page.getByRole('alertdialog', { name: '数据已发生变化' })
    await expect(conflict).toBeVisible()
    await expect(edit.getByRole('textbox', { name: '备注' })).toHaveValue("draft 汉字 '$`")
    await conflict.getByRole('button', { name: '刷新', exact: true }).click()
    await expect(conflict).toHaveCount(0)
    await expect(edit.getByRole('textbox', { name: 'Daemon 代理' })).toHaveValue(
      'http://127.0.0.1:7890',
    )
    await edit.getByRole('button', { name: '保存配置' }).click()
    await expect(edit).toHaveCount(0)
    expect(mock.updateBodies).toHaveLength(2)
    expect(mock.updateBodies[0]!.expectedVersion).toBe('7')
    expect(mock.updateBodies[1]!.expectedVersion).toBe('8')
    expect(mock.updateBodies[1]!.note).toBe("draft 汉字 '$`")
    expect(mock.updateBodies[1]!.daemonConfig).toMatchObject({
      studioUrl: 'https://saved.example.com',
      bashExecutable: '/bin/bash',
      proxyUrl: 'http://127.0.0.1:7890',
    })

    const beforeInstall = mock.calls.length
    await page.getByRole('button', { name: '安装 / 覆盖 host-one' }).click()
    const install = page.getByRole('dialog', { name: '安装/覆盖环境' })
    await expect(install.getByRole('textbox', { name: 'Studio 地址' })).toHaveCount(0)
    await expect(install.getByRole('textbox', { name: 'Bash 可执行文件' })).toHaveCount(0)
    await install.getByRole('button', { name: '操作系统' }).click()
    await page.getByRole('option', { name: 'macOS', exact: true }).click()
    await install.getByRole('button', { name: '复制安装命令' }).click()
    await expect(page.getByRole('status')).toContainText('安装命令已复制，5 分钟内有效')
    await expect(page.getByRole('status')).toHaveCSS('opacity', '1')
    const command = await page.evaluate(() => navigator.clipboard.readText())
    const pageOrigin = new URL(page.url()).origin
    const installUrl = `${pageOrigin}${url(
      'GET /api/platform/environments/{environmentId}/install-script',
      {
        path: { environmentId: 'env-1' },
        query: { code: 'browser-install-code', operatingSystem: 'macos' },
      },
    )}`
    expect(command).toBe(`(set -o pipefail; curl -fsSL $'${installUrl}' | bash)`)
    expect(command).not.toContain(token)
    expect(mock.calls.filter(c => c.endsWith('/install-codes'))).toHaveLength(1)
    expect(mock.calls.filter(c => c.endsWith('/rotate-token'))).toHaveLength(0)
    expect(mock.calls.slice(beforeInstall)).toEqual([
      'POST /api/platform/environments/env-1/install-codes',
    ])
    expect(await page.locator('body').innerText()).not.toContain(token)
    expect(await page.evaluate(() => JSON.stringify(localStorage))).not.toContain(token)
    await install.getByRole('button', { name: '关闭', exact: true }).last().click()

    const before = mock.calls.length
    await page.getByRole('button', { name: '卸载 host-one', exact: true }).click()
    const uninstall = page.getByRole('dialog', { name: '卸载环境' })
    await expect(uninstall).toContainText('Studio 中的环境卡片不会删除')
    await uninstall.getByRole('button', { name: '操作系统' }).click()
    await page.getByRole('option', { name: 'Windows', exact: true }).click()
    await uninstall.getByRole('button', { name: '复制卸载命令' }).click()
    await expect(page.getByRole('status')).toContainText('卸载命令已复制')
    const uninstallCommand = await page.evaluate(() => navigator.clipboard.readText())
    const uninstallUrl = `${pageOrigin}${url(
      'GET /api/platform/environments/{environmentId}/uninstall-script',
      { path: { environmentId: 'env-1' }, query: { operatingSystem: 'windows' } },
    )}`
    expect(uninstallCommand).toContain(uninstallUrl)
    expect(uninstallCommand).not.toContain(token)
    expect(mock.calls.slice(before)).toEqual([])
    await page.screenshot({ path: testInfo.outputPath(`uninstall-${width}.png`), fullPage: true })
  })
}

for (const width of [1280, 390]) {
  test(`creates a full card config and then copies the install command at ${width}px`, async ({
    page,
  }, testInfo) => {
    await page.setViewportSize({ width, height: 900 })
    const mock = await api(page)
    await page.goto('/browser-tests/environment-install-harness.html')
    await page.getByRole('button', { name: '创建环境', exact: true }).click()
    const create = page.getByRole('dialog', { name: '创建环境' })
    await create.getByRole('textbox', { name: /环境名称/ }).fill(`fresh-${width}`)
    await create.getByRole('textbox', { name: '备注' }).fill('我的工作站')
    await expect(create.getByRole('textbox', { name: 'Studio 地址' })).toHaveValue(
      new URL(page.url()).origin,
    )
    await create.getByRole('textbox', { name: 'Daemon 代理' }).fill('')
    await expect(create.getByRole('button', { name: /帮助：保存后的配置无需重装 Daemon/ })).toBeVisible()
    await page.screenshot({ path: testInfo.outputPath(`create-${width}.png`), fullPage: true })
    await create.getByRole('button', { name: '创建环境', exact: true }).click()

    await expect(create).toHaveCount(0)
    expect(mock.createBodies).toHaveLength(1)
    expect(mock.createBodies[0]).toMatchObject({
      name: `fresh-${width}`,
      note: '我的工作站',
      daemonConfig: {
        studioUrl: new URL(page.url()).origin,
        bashExecutable: null,
        lsp: null,
        proxyUrl: null,
      },
    })
    const install = page.getByRole('dialog', { name: '安装/覆盖环境' })
    await expect(install).toBeVisible()
    await expect(install.getByRole('button', { name: '复制安装命令' })).toBeEnabled()
    await expect(install).not.toContainText(token)
    await install.getByRole('button', { name: '操作系统' }).click()
    await page.getByRole('option', { name: 'Linux', exact: true }).click()
    await install.getByRole('button', { name: '复制安装命令' }).click()
    await expect(page.getByRole('status')).toContainText('安装命令已复制，5 分钟内有效')
    expect(await page.evaluate(() => navigator.clipboard.readText())).toContain(
      '/api/platform/environments/env-new/install-script?code=browser-install-code&operatingSystem=linux',
    )
    expect(await page.locator('body').innerText()).not.toContain(token)
    expect(await page.evaluate(() => JSON.stringify(localStorage))).not.toContain(token)
  })
}

test('keeps event history out of the card and shows connection and config state separately', async ({
  page,
}, testInfo) => {
  await page.setViewportSize({ width: 1280, height: 900 })
  const history = [
    { occurredAt: Date.parse('2026-07-20T00:00:00.000Z'), level: 'WARN', type: 'DISCONNECTED', message: 'daemon connection lost' },
    { occurredAt: Date.parse('2026-07-20T00:01:00.000Z'), level: 'INFO', type: 'READY', message: 'environment ready' },
  ]
  const card = testEnvironment({
    id: 'env-1',
    name: 'recovered-box',
    version: '3',
    status: 'READY',
    ready: true,
    lastSeenAt: 1784505660000,
  })
  const runtime = testRuntimeStatus({
    revision: '3',
    configuration: { appliedVersion: '3', proxyUrl: null, error: null },
  })
  await page.route('**/api/platform/environments**', async route => {
    const path = new URL(route.request().url()).pathname
    if (path.endsWith('/events')) return route.fulfill({ json: history })
    if (path.endsWith('/runtime')) return route.fulfill({ json: runtime })
    if (path.endsWith('/env-1')) return route.fulfill({ json: card })
    return route.fulfill({ json: [card] })
  })

  await page.goto('/browser-tests/environment-install-harness.html')
  const cardLocator = page.locator('.environment-card')
  await expect(cardLocator).toBeVisible({ timeout: 15000 })
  await expect(cardLocator.getByText('READY', { exact: true })).toBeVisible()
  await expect(cardLocator.getByText('已应用', { exact: true })).toBeVisible()
  await expect(cardLocator.getByText('直连', { exact: true })).toBeVisible()
  await expect(cardLocator).not.toContainText('daemon connection lost')
  await page.screenshot({ path: testInfo.outputPath('card-config-status.png'), fullPage: true })

  await cardLocator.getByRole('button', { name: /管理/ }).click()
  const modal = page.getByRole('dialog', { name: /管理环境/ })
  await expect(modal.getByText('daemon connection lost')).toBeVisible()
  await expect(modal.getByText('environment ready')).toBeVisible()
  await expect(modal.getByText('配置', { exact: true })).toBeVisible()
  await page.screenshot({ path: testInfo.outputPath('management-config.png'), fullPage: true })
})

test('normal and compact portal menus flip, stay clickable, and keep native adjacent Tab order', async ({
  page,
}, testInfo) => {
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

test('native Tab skips closed details inputs and disabled controls around both Select modes', async ({
  page,
}) => {
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
  await expect(page.getByLabel('Closed before')).toBeHidden()
  await expect(page.getByLabel('Closed between')).toBeHidden()
  await expect(page.getByLabel('Closed after')).toBeHidden()
  await expect(page.getByRole('button', { name: 'Disabled between' })).toBeDisabled()
  // 维持既有 Select token 校验：普通/compact 都使用主题控件，不从系统原生菜单取色。
  expect(await tokenColor(page, '--fg-muted')).not.toBe('')
})
