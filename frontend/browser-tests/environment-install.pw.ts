import { join } from 'node:path'
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

const HARNESS_URL = '/browser-tests/environment-install-harness.html'

const token = 'browser-private-token'
const savedConfig = testDaemonConfig({
  studioUrl: 'https://saved.example.com',
  bashExecutable: '/bin/bash',
  lsp: { servers: { ts: { command: ['~/ts-server', "quote'$`"], extensions: ['.ts'] } } },
  proxyUrl: null,
})

/** 卡片与管理弹窗共用的英文/中文文案在窄屏也不能产生横向溢出。 */
async function expectNoHorizontalOverflow(page: Page, width: number): Promise<void> {
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width)
}

/** 卡片上唯一的入口是「管理 <name>」：读取会话、配置与全部动作都在弹窗里。 */
function manageButton(page: Page, name: string) {
  return page.getByRole('button', { name: `管理 ${name}`, exact: true })
}

async function openManage(page: Page, name: string) {
  await manageButton(page, name).click()
  const dialog = page.getByRole('dialog', { name: `管理环境：${name}` })
  await expect(dialog).toBeVisible()
  return dialog
}

/** 管理弹窗的动作只给纯动作名；点选后先关闭弹窗，再进入原有子流程。 */
async function pickManageAction(page: Page, name: string, label: string) {
  const dialog = await openManage(page, name)
  await dialog.getByRole('button', { name: label, exact: true }).click()
  await expect(dialog).toHaveCount(0)
}

async function api(page: Page) {
  let card: EnvironmentDTO = testEnvironment({
    id: 'env-1',
    name: 'host-one',
    note: 'saved note',
    version: '7',
    // 曾经安装过、当前离线：卡片只有管理入口，安装/覆盖仍可从弹窗进入。
    status: 'OFFLINE',
    ready: false,
    daemonVersion: '1.0.0',
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
    await page.goto(HARNESS_URL)
    await expect(manageButton(page, 'host-one')).toBeVisible({ timeout: 15000 })
    // 卡片不再平铺动作：已安装过的卡片只有管理入口。
    const card = page.locator('.environment-card')
    await expect(card.locator('.resource-card-actions button')).toHaveCount(1)
    await expect(page.getByRole('button', { name: '安装 / 覆盖 host-one', exact: true })).toHaveCount(0)
    await expect(card.getByText('上次 Daemon 版本', { exact: true })).toBeVisible()
    await expect(card).not.toContainText('已应用')
    await page.screenshot({ path: testInfo.outputPath(`cards-${width}.png`), fullPage: true })
    await expectNoHorizontalOverflow(page, width)

    const manage = await openManage(page, 'host-one')
    // 弹窗动作按原文案顺序平铺，不带卡片上的 aria 名称后缀。
    await expect(manage.locator('.environment-management-actions button')).toHaveText([
      '编辑配置',
      '安装 / 覆盖',
      '更新 Daemon',
      '卸载',
      '重新生成 Token',
      '删除环境',
    ])
    // 诊断详情默认折叠：展开前既不渲染宿主/配置/事件，也不读事件端点。
    const diagnostics = manage.locator('.environment-diagnostics')
    await expect(diagnostics.locator('summary')).toHaveText('诊断详情')
    await expect(manage.locator('.env-mgmt-section')).toHaveCount(0)
    expect(mock.calls.filter(call => call.endsWith('/events'))).toHaveLength(0)
    await page.screenshot({ path: testInfo.outputPath(`manage-${width}.png`), fullPage: true })
    const manageBox = await manage.boundingBox()
    expect(manageBox!.x).toBeGreaterThanOrEqual(0)
    expect(manageBox!.x + manageBox!.width).toBeLessThanOrEqual(width)

    await manage.getByRole('button', { name: '编辑配置', exact: true }).click()
    await expect(manage).toHaveCount(0)
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
    await expectNoHorizontalOverflow(page, width)

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
    await pickManageAction(page, 'host-one', '安装 / 覆盖')
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
    await pickManageAction(page, 'host-one', '卸载')
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
    await page.goto(HARNESS_URL)
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
    await install.getByRole('button', { name: '关闭', exact: true }).last().click()

    // 从未安装过的离线卡片保留安装快捷入口，管理入口仍然存在；删除/轮换等危险动作只在弹窗里。
    const card = page.locator('.environment-card')
    await expect(manageButton(page, `fresh-${width}`)).toBeVisible()
    await expect(
      page.getByRole('button', { name: `安装 / 覆盖 fresh-${width}`, exact: true }),
    ).toBeVisible()
    await expect(card.getByRole('button', { name: /重新生成 Token|删除环境|卸载/ })).toHaveCount(0)
    await expectNoHorizontalOverflow(page, width)
  })
}

test('keeps event history and config details inside the collapsible diagnostics', async ({
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
  const calls: string[] = []
  await page.route('**/api/platform/environments**', async route => {
    const path = new URL(route.request().url()).pathname
    calls.push(`${route.request().method()} ${path}`)
    if (path.endsWith('/events')) return route.fulfill({ json: history })
    if (path.endsWith('/runtime')) return route.fulfill({ json: runtime })
    if (path.endsWith('/env-1')) return route.fulfill({ json: card })
    return route.fulfill({ json: [card] })
  })

  await page.goto(HARNESS_URL)
  const cardLocator = page.locator('.environment-card')
  await expect(cardLocator).toBeVisible({ timeout: 15000 })
  await expect(cardLocator.getByText('READY', { exact: true })).toBeVisible()
  await expect(cardLocator.getByText('当前 Daemon 版本', { exact: true })).toBeVisible()
  await expect(cardLocator.getByText('1.2.3', { exact: true })).toBeVisible()
  await expect(cardLocator.getByText('无进行中的更新', { exact: true })).toBeVisible()
  // 配置态与事件历史都离开卡片：卡片不冒充已应用，也不展示历史。
  await expect(cardLocator.getByText('已应用', { exact: true })).toHaveCount(0)
  await expect(cardLocator.getByText('直连', { exact: true })).toHaveCount(0)
  await expect(cardLocator).not.toContainText('daemon connection lost')
  expect(calls.filter(call => call.endsWith('/events'))).toHaveLength(0)
  await page.screenshot({ path: testInfo.outputPath('card-config-status.png'), fullPage: true })

  const modal = await openManage(page, 'recovered-box')
  const diagnostics = modal.locator('.environment-diagnostics')
  await expect(diagnostics.locator('summary')).toHaveText('诊断详情')
  await expect(modal.locator('.env-mgmt-section')).toHaveCount(0)
  expect(calls.filter(call => call.endsWith('/events'))).toHaveLength(0)
  await page.screenshot({ path: testInfo.outputPath('management-collapsed.png'), fullPage: true })

  await diagnostics.locator('summary').click()
  await expect(modal.locator('.env-mgmt-section[aria-label="宿主信息"]')).toBeVisible()
  const configSection = modal.locator('.env-mgmt-section[aria-label="配置"]')
  await expect(configSection.getByText('已应用', { exact: true })).toBeVisible()
  const configCells = configSection.locator('.env-host-meta-card')
  await expect(configCells.filter({ hasText: '已应用版本' }).locator('.val')).toHaveText('3')
  await expect(configCells.filter({ hasText: 'Daemon 代理' }).locator('.val')).toHaveText('直连')
  const events = modal.locator('.env-mgmt-section[aria-label="事件记录"]')
  await expect(events.getByText('daemon connection lost')).toBeVisible()
  await expect(events.getByText('environment ready')).toBeVisible()
  // 事件按发生时间倒序呈现，最新一条在前。
  await expect(events.locator('.env-event-item')).toHaveText([
    /READY/,
    /DISCONNECTED/,
  ])
  expect(calls.filter(call => call.endsWith('/events'))).toHaveLength(1)
  await page.screenshot({ path: testInfo.outputPath('management-diagnostics.png'), fullPage: true })
  await expectNoHorizontalOverflow(page, 1280)

  // 展开后的诊断在窄屏重排为单列，弹窗仍不越出视口。
  await page.setViewportSize({ width: 390, height: 900 })
  await expectNoHorizontalOverflow(page, 390)
  const narrowBox = await modal.boundingBox()
  expect(narrowBox!.x).toBeGreaterThanOrEqual(0)
  expect(narrowBox!.x + narrowBox!.width).toBeLessThanOrEqual(390)
  await expect(configCells.filter({ hasText: 'Daemon 代理' }).locator('.val')).toHaveText('直连')
  await page.screenshot({ path: testInfo.outputPath('management-diagnostics-390.png'), fullPage: true })

  await diagnostics.locator('summary').click()
  await expect(modal.locator('.env-mgmt-section')).toHaveCount(0)
})

test('rotates the token and deletes the card through plain modal labels', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 })
  let card = testEnvironment({
    id: 'env-danger',
    name: 'danger-box',
    version: '3',
    status: 'READY',
    ready: true,
  })
  let deleted = false
  const runtime = testRuntimeStatus({ revision: '3' })
  const rotateBodies: unknown[] = []
  const deletes: string[] = []
  await page.route('**/api/platform/environments**', async route => {
    const request = route.request()
    const pathAndQuery = new URL(request.url())
    const path = pathAndQuery.pathname
    if (path.endsWith('/rotate-token')) {
      rotateBodies.push(request.postDataJSON())
      card = { ...card, version: String(BigInt(card.version) + 1n) }
      return route.fulfill({ json: { ...card, registrationToken: token } })
    }
    if (request.method() === 'DELETE') {
      deletes.push(`${path}?${pathAndQuery.searchParams.toString()}`)
      deleted = true
      return route.fulfill({ status: 204, body: '' })
    }
    if (path.endsWith('/runtime')) return route.fulfill({ json: runtime })
    if (path.endsWith('/env-danger')) return route.fulfill({ json: card })
    return route.fulfill({ json: deleted ? [] : [card] })
  })

  await page.goto(HARNESS_URL)
  await expect(page.locator('.environment-card')).toBeVisible({ timeout: 15000 })

  await pickManageAction(page, 'danger-box', '重新生成 Token')
  const rotate = page.getByRole('alertdialog', { name: '重新生成 Token' })
  await expect(rotate.getByText(/为环境「danger-box」重新生成 Token/)).toBeVisible()
  await expect(rotate.getByRole('button', { name: '取消', exact: true })).toBeVisible()
  // 轮换成功后权威列表回读完成后，删除才会带上新的 expectedVersion。
  const refreshed = page.waitForResponse(response =>
    new URL(response.url()).pathname === '/api/platform/environments'
    && response.request().method() === 'GET',
  )
  await rotate.getByRole('button', { name: '重新生成 Token' }).click()
  await refreshed
  await expect(rotate).toHaveCount(0)
  expect(rotateBodies).toEqual([{ expectedVersion: '3' }])
  // 新 token 只在一次性响应里，界面不得回显或落盘。
  expect(await page.locator('body').innerText()).not.toContain(token)
  expect(await page.evaluate(() => JSON.stringify(localStorage))).not.toContain(token)

  await pickManageAction(page, 'danger-box', '删除环境')
  const remove = page.getByRole('alertdialog', { name: '删除环境' })
  await expect(remove.getByText('确认删除环境「danger-box」？')).toBeVisible()
  await expect(remove.getByRole('button', { name: '取消', exact: true })).toBeVisible()
  await remove.getByRole('button', { name: '删除环境' }).click()
  await expect(remove).toHaveCount(0)
  await expect(page.locator('.environment-card')).toHaveCount(0)
  await expect(page.getByRole('button', { name: '创建环境', exact: true })).toBeVisible()
  expect(deletes).toEqual(['/api/platform/environments/env-danger?expectedVersion=4'])
})

test('an initial list failure offers an in-place retry instead of a stale-data notice', async ({
  page,
}, testInfo) => {
  await page.setViewportSize({ width: 1280, height: 900 })
  const card = testEnvironment({ id: 'env-1', name: 'first-load', status: 'OFFLINE', ready: false })
  let listCalls = 0
  await page.route('**/api/platform/environments**', async route => {
    const path = new URL(route.request().url()).pathname
    if (path.endsWith('/env-1')) return route.fulfill({ json: card })
    listCalls += 1
    if (listCalls === 1) {
      return route.fulfill({
        status: 500,
        contentType: 'application/problem+json',
        json: { type: 'about:blank', status: 500, code: 'internal_error', title: 'boom' },
      })
    }
    return route.fulfill({ json: [card] })
  })

  await page.goto(HARNESS_URL)
  const failure = page.locator('.environment-feedback')
  await expect(failure).toBeVisible({ timeout: 15000 })
  await expect(failure.getByRole('status')).toHaveText('加载 Environment 失败')
  await expect(failure).toHaveCSS('color', await tokenColor(page, '--fg-muted'))
  const icon = failure.locator('svg')
  await expect(icon).toBeVisible()
  expect(await icon.evaluate(element => element.getBoundingClientRect().width)).toBe(16)
  // 首次加载失败没有可保留的缓存，因此不出现「显示上次数据」的刷新反馈。
  await expect(page.locator('.environment-refresh-slot').getByText(/显示上次数据/)).toHaveCount(0)
  await expect(page.locator('.environment-card')).toHaveCount(0)
  const retry = failure.getByRole('button', { name: '重试', exact: true })
  await expect(retry).toBeEnabled()
  await page.screenshot({ path: testInfo.outputPath('list-initial-failure.png'), fullPage: true })
  await expectNoHorizontalOverflow(page, 1280)

  await retry.click()
  await expect(page.locator('.environment-feedback')).toHaveCount(0)
  await expect(page.locator('.environment-card')).toBeVisible()
  await expect(manageButton(page, 'first-load')).toBeVisible()
  expect(listCalls).toBe(2)
})

for (const width of [1280, 390]) {
  test(`a failed refresh keeps the cached cards and explains itself in the toolbar at ${width}px`, async ({
    page,
  }, testInfo) => {
    await page.setViewportSize({ width, height: 900 })
    // 卡片状态截止点触发一次权威回读；这次回读失败，缓存数据必须保留。
    const card = testEnvironment({
      id: 'env-1',
      name: 'cached-box',
      version: '2',
      status: 'READY',
      ready: true,
      daemonVersion: '0.3.0',
      statusExpiresAt: Date.now() + 400,
    })
    const runtime = testRuntimeStatus({ revision: '2', daemonVersion: '0.3.0' })
    let listCalls = 0
    await page.route('**/api/platform/environments**', async route => {
      const path = new URL(route.request().url()).pathname
      if (path.endsWith('/runtime')) return route.fulfill({ json: runtime })
      if (path !== '/api/platform/environments') {
        return route.fulfill({ status: 404, json: { code: 'not_found' } })
      }
      listCalls += 1
      if (listCalls === 2) {
        return route.fulfill({
          status: 500,
          contentType: 'application/problem+json',
          json: { type: 'about:blank', status: 500, code: 'internal_error', title: 'boom' },
        })
      }
      return route.fulfill({ json: [card] })
    })

    await page.goto(HARNESS_URL)
    const cards = page.locator('.environment-card')
    await expect(cards).toBeVisible({ timeout: 15000 })
    await expect(cards.getByText('READY', { exact: true })).toBeVisible()

    const wide = page.locator('.environment-refresh-wide')
    const narrow = page.locator('.environment-refresh-narrow')
    if (width > 900) {
      await expect(wide).toBeVisible({ timeout: 15000 })
      await expect(wide).toContainText('刷新失败 · 显示上次数据')
      await expect(narrow).toBeHidden()
      // 失败提示只是工具栏状态，不遮挡也不替换缓存卡片。
      await expect(cards).toHaveCount(1)
      await expect(cards.getByText('当前 Daemon 版本', { exact: true })).toBeVisible()
      await page.screenshot({ path: testInfo.outputPath(`refresh-failed-${width}.png`), fullPage: true })
      await expectNoHorizontalOverflow(page, width)
      await wide.getByRole('button', { name: '重试', exact: true }).click()
    } else {
      await expect(narrow).toBeVisible({ timeout: 15000 })
      await expect(narrow.locator('summary')).toHaveText('刷新失败')
      await expect(wide).toBeHidden()
      await expect(cards).toHaveCount(1)
      await page.screenshot({ path: testInfo.outputPath(`refresh-failed-${width}.png`), fullPage: true })
      await expectNoHorizontalOverflow(page, width)
      await narrow.locator('summary').click()
      const details = page.locator('.environment-refresh-details')
      await expect(details).toBeVisible()
      await expect(details).toContainText('刷新失败 · 显示上次数据')
      const detailsBox = await details.boundingBox()
      expect(detailsBox!.x).toBeGreaterThanOrEqual(0)
      expect(detailsBox!.x + detailsBox!.width).toBeLessThanOrEqual(width)
      await expectNoHorizontalOverflow(page, width)
      await page.screenshot({ path: testInfo.outputPath(`refresh-failed-details-${width}.png`), fullPage: true })
      await details.getByRole('button', { name: '重试', exact: true }).click()
    }

    await expect(wide).toHaveCount(0)
    await expect(narrow).toHaveCount(0)
    await expect(cards).toHaveCount(1)
    expect(listCalls).toBe(3)
  })
}

test('one failed runtime read masks only that card and stops presenting its cached live snapshot', async ({
  page,
}, testInfo) => {
  await page.setViewportSize({ width: 1280, height: 900 })
  const alpha = testEnvironment({
    id: 'env-alpha',
    name: 'alpha-box',
    version: '5',
    status: 'READY',
    ready: true,
    daemonVersion: '9.9.9',
  })
  const beta = testEnvironment({
    id: 'env-beta',
    name: 'beta-box',
    version: '4',
    status: 'READY',
    ready: true,
    daemonVersion: '0.9.0',
  })
  const alphaRuntime = testRuntimeStatus({
    revision: '5',
    daemonVersion: '9.9.9',
    configuration: { appliedVersion: '5', proxyUrl: null, error: null },
  })
  const betaRuntime = testRuntimeStatus({
    revision: '4',
    daemonVersion: '3.3.3',
    configuration: { appliedVersion: '4', proxyUrl: null, error: null },
  })
  let betaRuntimeFails = false
  const runtimeCalls: string[] = []
  await page.route('**/api/platform/environments**', async route => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    if (path.endsWith('/events')) return route.fulfill({ json: [] })
    if (path.endsWith('/runtime')) {
      runtimeCalls.push(path)
      if (path.includes('env-beta') && betaRuntimeFails) {
        return route.fulfill({
          status: 500,
          contentType: 'application/problem+json',
          json: { type: 'about:blank', status: 500, code: 'internal_error', title: 'boom' },
        })
      }
      return route.fulfill({ json: path.includes('env-beta') ? betaRuntime : alphaRuntime })
    }
    return route.fulfill({ json: [alpha, beta] })
  })

  await page.goto(HARNESS_URL)
  const cards = page.locator('.environment-card')
  await expect(cards).toHaveCount(2, { timeout: 15000 })
  const alphaCard = cards.filter({ hasText: 'alpha-box' })
  const betaCard = cards.filter({ hasText: 'beta-box' })
  // 卡片只给版本与更新状态；已应用的配置结论属于诊断详情。
  await expect(alphaCard.getByText('当前 Daemon 版本', { exact: true })).toBeVisible()
  await expect(alphaCard.getByText('9.9.9', { exact: true })).toBeVisible()
  await expect(alphaCard.getByText('无进行中的更新', { exact: true })).toBeVisible()
  await expect(betaCard.getByText('当前 Daemon 版本', { exact: true })).toBeVisible()
  await expect(betaCard.getByText('3.3.3', { exact: true })).toBeVisible()
  await expect(betaCard.getByText('无进行中的更新', { exact: true })).toBeVisible()
  await expect(cards.getByText('已应用', { exact: true })).toHaveCount(0)
  await page.screenshot({ path: testInfo.outputPath('runtime-isolated-live.png'), fullPage: true })

  // 诊断详情里的配置摘要读到同一份运行快照。
  const modal = await openManage(page, 'beta-box')
  await modal.locator('.environment-diagnostics > summary').click()
  const configSection = modal.locator('.env-mgmt-section[aria-label="配置"]')
  await expect(configSection.getByText('已应用', { exact: true })).toBeVisible()
  const betaCells = modal.locator('.env-host-meta-card')
  await expect(betaCells.filter({ hasText: '已应用版本' }).locator('.val')).toHaveText('4')
  await expect(betaCells.filter({ hasText: 'Daemon 代理' }).locator('.val')).toHaveText('直连')
  await page.screenshot({ path: testInfo.outputPath('runtime-isolated-before.png'), fullPage: true })
  await modal.getByRole('button', { name: '关闭', exact: true }).last().click()

  betaRuntimeFails = true
  const reopened = await openManage(page, 'beta-box')
  await reopened.locator('.environment-diagnostics > summary').click()
  const failedConfig = reopened.locator('.env-mgmt-section[aria-label="配置"]')
  await expect(failedConfig.getByRole('status')).toHaveText('运行状态读取失败', { timeout: 15000 })
  const failedCells = reopened.locator('.env-host-meta-card')
  await expect(failedCells.filter({ hasText: '已应用版本' }).locator('.val')).toHaveText('—')
  await expect(failedCells.filter({ hasText: 'Daemon 代理' }).locator('.val')).toHaveText('—')
  // 读不到当前快照就不能宣称已应用。
  await expect(failedConfig.getByText('已应用', { exact: true })).toHaveCount(0)
  await expect(failedConfig.getByText('未知', { exact: true })).toBeVisible()
  await page.screenshot({ path: testInfo.outputPath('runtime-isolated-after.png'), fullPage: true })
  await reopened.getByRole('button', { name: '关闭', exact: true }).last().click()

  // 缓存里的旧快照不再冒充当前状态，只作为「上次版本」保留。
  await expect(betaCard.getByText('运行状态读取失败', { exact: true })).toBeVisible()
  await expect(betaCard.getByText('上次 Daemon 版本', { exact: true })).toBeVisible()
  await expect(betaCard.getByText('当前 Daemon 版本', { exact: true })).toHaveCount(0)
  await expect(betaCard.getByText('已应用', { exact: true })).toHaveCount(0)
  // 另一个环境的运行快照不受影响。
  await expect(alphaCard.getByText('当前 Daemon 版本', { exact: true })).toBeVisible()
  await expect(alphaCard.getByText('9.9.9', { exact: true })).toBeVisible()
  await expect(alphaCard.getByText('无进行中的更新', { exact: true })).toBeVisible()
  expect(runtimeCalls.filter(call => call.includes('env-beta')).length).toBeGreaterThan(0)
  await page.screenshot({ path: testInfo.outputPath('runtime-isolated-cards.png'), fullPage: true })
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

/**
 * 切片自查截图目录：只有显式设置 KK_SLICE_SHOTS 时才写出图片。
 * 下方状态矩阵的断言始终执行，截图只是可选产物。
 */
const sliceShotsDir = process.env.KK_SLICE_SHOTS?.trim()

async function sliceShot(page: Page, name: string): Promise<void> {
  if (!sliceShotsDir) {
    return
  }
  await page.screenshot({ path: join(sliceShotsDir, `${name}.png`), fullPage: true })
}

/** 诊断事件分区的双语文案：分区名与三种状态都取产品文案，不在用例里拼英文。 */
const diagnosticsCopy = {
  'zh-CN': {
    manage: (name: string) => `管理 ${name}`,
    dialog: (name: string) => `管理环境：${name}`,
    summary: '诊断详情',
    eventsTitle: '事件记录',
    loading: '正在读取事件记录',
    failed: '事件记录读取失败',
    empty: '暂无事件记录',
    retry: '重试',
  },
  'en-US': {
    manage: (name: string) => `Manage ${name}`,
    dialog: (name: string) => `Manage Environment: ${name}`,
    summary: 'Diagnostics',
    eventsTitle: 'Events',
    loading: 'Loading events',
    failed: 'Failed to load events',
    empty: 'No events recorded',
    retry: 'Retry',
  },
} as const

for (const [locale, copy] of Object.entries(diagnosticsCopy)) {
  for (const viewport of [{ width: 1440, height: 900 }, { width: 390, height: 844 }]) {
    test(`keeps in-flight, failed and empty event reads distinct in ${locale} at ${viewport.width}px`, async ({
      page,
    }) => {
      await page.setViewportSize(viewport)
      const card = testEnvironment({
        id: 'env-1',
        name: 'diag-box',
        version: '3',
        status: 'READY',
        ready: true,
      })
      const runtime = testRuntimeStatus({ revision: '3' })
      // 事件响应由用例控制：先挂起，放行时以 HTTP 500 落定；重试后成功返回空列表。
      let eventsMode: 'hold' | 'empty' = 'hold'
      let releaseEvents: () => void = () => {}
      const eventsGate = new Promise<void>(resolve => {
        releaseEvents = resolve
      })
      let eventsCalls = 0
      await page.route('**/api/platform/environments**', async route => {
        const path = new URL(route.request().url()).pathname
        if (path.endsWith('/events')) {
          eventsCalls += 1
          if (eventsMode === 'hold') {
            await eventsGate
            return route.fulfill({
              status: 500,
              contentType: 'application/problem+json',
              json: { type: 'about:blank', status: 500, code: 'internal_error', title: 'boom' },
            })
          }
          return route.fulfill({ json: [] })
        }
        if (path.endsWith('/runtime')) return route.fulfill({ json: runtime })
        if (path.endsWith('/env-1')) return route.fulfill({ json: card })
        return route.fulfill({ json: [card] })
      })

      await page.goto(`${HARNESS_URL}?locale=${locale}`)
      const manage = page.getByRole('button', { name: copy.manage('diag-box'), exact: true })
      await expect(manage).toBeVisible({ timeout: 15000 })
      await manage.click()
      const modal = page.getByRole('dialog', { name: copy.dialog('diag-box') })
      await expect(modal).toBeVisible()
      await expect(modal.locator('.environment-diagnostics > summary')).toHaveText(copy.summary)
      await modal.locator('.environment-diagnostics > summary').click()
      const events = modal.locator(`.env-mgmt-section[aria-label="${copy.eventsTitle}"]`)
      await expect(events).toBeVisible()

      // 请求在途：分区给出读取中，不把「还没有结果」写成暂无事件记录。
      await expect(events.getByText(copy.loading, { exact: true })).toBeVisible()
      await expect(events.getByText(copy.empty, { exact: true })).toHaveCount(0)
      await events.getByText(copy.loading, { exact: true }).scrollIntoViewIfNeeded()
      await sliceShot(page, `environment-events-loading-${locale}-${viewport.width}`)

      // 读取以 HTTP 500 落定：同一分区就地给出失败与重试入口。
      releaseEvents()
      await expect(events.getByText(copy.failed, { exact: true })).toBeVisible({ timeout: 15000 })
      await expect(events.getByText(copy.empty, { exact: true })).toHaveCount(0)
      const retry = events.getByRole('button', { name: copy.retry, exact: true })
      await expect(retry).toBeEnabled()
      await retry.scrollIntoViewIfNeeded()
      await sliceShot(page, `environment-events-error-${locale}-${viewport.width}`)

      // 重试成功但确实没有事件：这时才是空态。
      eventsMode = 'empty'
      await retry.click()
      await expect(events.getByText(copy.empty, { exact: true })).toBeVisible({ timeout: 15000 })
      await expect(events.getByText(copy.failed, { exact: true })).toHaveCount(0)
      expect(eventsCalls).toBe(2)
      await sliceShot(page, `environment-events-empty-${locale}-${viewport.width}`)

      // 两种视口下弹窗都不越出视口，窄屏也不产生横向溢出。
      const box = await modal.boundingBox()
      expect(box!.x).toBeGreaterThanOrEqual(0)
      expect(box!.x + box!.width).toBeLessThanOrEqual(viewport.width)
      await expectNoHorizontalOverflow(page, viewport.width)
    })
  }
}
