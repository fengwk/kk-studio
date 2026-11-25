import type { Page, WebSocketRoute } from '@playwright/test'
import { makeSettingsDto, makeSettingsSchema } from '@/test-support/settings-test-fixtures'
import { test, expect } from './fixture'

const harness = '/browser-tests/account-settings-harness.html'
const admin = { id: '1', username: 'kkstudio', role: 'ADMIN', enabled: true, createdAt: 0, profile: {} }
const reader = { ...admin, id: '2', username: 'chen', role: 'USER' }

async function mockServer(page: Page, initial: typeof admin | null = admin) {
  let current = initial
  let users = [admin, reader]
  let writes = 0
  const sockets: WebSocketRoute[] = []
  await page.addInitScript(() => localStorage.setItem('kk-studio.locale', 'zh-CN'))
  await page.routeWebSocket('**/ws/events', (socket) => sockets.push(socket))
  await page.route('**/api/**', async (route) => {
    const req = route.request()
    const path = new URL(req.url()).pathname
    const problem = (status: number, reason: string) => route.fulfill({ status, contentType: 'application/problem+json', body: JSON.stringify({ status, reason, title: reason }) })
    if (path === '/api/auth/login') {
      const { username } = req.postDataJSON()
      current = users.find((item) => item.username === username && item.enabled) ?? null
      return current ? route.fulfill({ json: current }) : problem(401, 'INVALID_CREDENTIALS')
    }
    if (path === '/api/auth/password') writes++
    if (!current) return problem(401, 'NOT_AUTHENTICATED')
    if (path === '/api/auth/me') return route.fulfill({ json: current })
    if (path === '/api/auth/password') return problem(400, 'WRONG_PASSWORD')
    if (path === '/api/auth/logout') { current = null; return route.fulfill({ status: 204 }) }
    if (path === '/api/platform/settings/schema') return route.fulfill({ json: makeSettingsSchema() })
    if (path === '/api/platform/settings') return route.fulfill({ json: makeSettingsDto() })
    if (path === '/api/users' && req.method() === 'GET') return route.fulfill({ json: users })
    if (path.startsWith('/api/users')) {
      const body = req.postDataJSON()
      const old = users.find((entry) => path.endsWith(`/${entry.id}`))
      if (users.some((entry) => entry.username === body.username && entry.id !== old?.id)) return problem(409, 'USERNAME_EXISTS')
      const item = { ...(old ?? { ...reader, id: String(users.length + 1) }), username: body.username, role: body.role, enabled: body.enabled ?? true }
      const nextUsers = [...users.filter((entry) => entry.id !== item.id), item]
      if (!nextUsers.some((entry) => entry.role === 'ADMIN' && entry.enabled)) return problem(409, 'LAST_ADMIN')
      users = nextUsers
      if (current?.id === item.id) current = item.enabled ? item : null
      return route.fulfill({ json: item })
    }
    return route.fulfill({ status: 404 })
  })
  return { expire: () => { current = null }, writes: () => writes, sockets }
}

async function login(page: Page, username: string) {
  await page.getByLabel('用户名', { exact: true }).fill(username)
  await page.getByLabel('密码', { exact: true }).fill('password123')
  await page.getByRole('button', { name: '登录', exact: true }).click()
}

test('login, HTTP expiry retaining drafts, WS expiry switching accounts, and password field error', async ({ page }) => {
  const server = await mockServer(page, null)
  await page.goto(harness)
  await login(page, 'unknown')
  await expect(page.getByRole('alert')).toHaveText('用户名或密码错误。')
  await login(page, admin.username)
  const draft = page.getByRole('textbox', { name: '草稿' })
  await draft.fill('unsent')
  await page.getByRole('button', { name: admin.username }).click()
  await page.getByRole('menuitem', { name: '修改密码' }).click()
  const editor = page.getByRole('dialog', { name: '修改密码' })
  await editor.getByLabel('当前密码').fill('bad')
  await editor.getByLabel('新密码', { exact: true }).fill('password123')
  await editor.getByLabel('确认新密码').fill('password123')
  await editor.getByRole('button', { name: '保存' }).click()
  await expect(editor.getByLabel('当前密码')).toHaveAttribute('aria-invalid', 'true')
  await expect(editor.getByRole('alert')).toHaveText('当前密码错误。')
  await editor.getByRole('button', { name: '取消' }).click()

  server.expire()
  await page.getByRole('button', { name: '测试写入' }).click()
  const reauth = page.getByRole('dialog', { name: '登录已失效' })
  await expect(reauth).toBeVisible()
  await login(page, admin.username)
  await expect(draft).toHaveValue('unsent')
  expect(server.writes()).toBe(2)
  await expect.poll(() => server.sockets.length).toBe(2)
  server.expire()
  await server.sockets[1].close({ code: 4001 })
  await expect(reauth).toBeVisible()
  await login(page, reader.username)
  await expect(page.getByRole('button', { name: reader.username })).toBeVisible()
  await expect(draft).toHaveValue('')
})

test('regular user only sees preferences and a direct admin address redirects', async ({ page }) => {
  await mockServer(page, reader)
  await page.goto(`${harness}#/settings/users`)
  await expect(page).toHaveURL(/settings\/preferences$/)
  const nav = page.getByRole('navigation', { name: '设置分类' })
  await expect(nav.getByRole('link')).toHaveCount(1)
  await expect(nav.getByRole('link')).toHaveText('偏好')
})

test('administrator creates, renames and disables users', async ({ page }) => {
  await mockServer(page)
  await page.goto(`${harness}#/settings/users`)
  await page.getByRole('button', { name: '新建用户' }).click()
  const editor = page.getByRole('dialog')
  await editor.getByLabel('用户名').fill('chen')
  await editor.getByLabel('密码', { exact: true }).fill('password123')
  await editor.getByRole('button', { name: '保存' }).click()
  await expect(editor.getByLabel('用户名')).toHaveAttribute('aria-invalid', 'true')
  await editor.getByLabel('用户名').fill('new-user')
  await editor.getByRole('button', { name: '保存' }).click()
  await page.getByRole('button', { name: '编辑 new-user' }).click()
  await editor.getByLabel('用户名').fill('renamed')
  await editor.getByLabel('账号状态').click()
  await page.getByRole('option', { name: '停用' }).click()
  await editor.getByRole('button', { name: '保存' }).click()
  const confirm = page.getByRole('alertdialog')
  await expect(confirm).toContainText('撤销登录并暂停定时任务')
  await confirm.getByRole('button', { name: '确认停用' }).click()
  await expect(page.getByRole('button', { name: '编辑 renamed' })).toBeVisible()
  await expect(page.getByText('已停用')).toBeVisible()
})

test('renaming the current account updates the avatar; disabling it returns to login', async ({ page }) => {
  await mockServer(page)
  await page.goto(`${harness}#/settings/users`)
  await page.getByRole('button', { name: '编辑 kkstudio' }).click()
  const editor = page.getByRole('dialog')
  await editor.getByLabel('用户名').fill('renamed-admin')
  await editor.getByRole('button', { name: '保存' }).click()
  await expect(page.getByRole('button', { name: '编辑 renamed-admin' })).toBeVisible()
  await page.getByRole('button', { name: '返回' }).click()
  await expect(page.getByRole('button', { name: 'renamed-admin' })).toHaveText('R')
  await page.getByRole('link', { name: '设置' }).click()
  await page.getByRole('link', { name: '用户' }).click()
  await page.getByRole('button', { name: '编辑 renamed-admin' }).click()
  await editor.getByLabel('账号状态').click()
  await page.getByRole('option', { name: '停用' }).click()
  await editor.getByRole('button', { name: '保存' }).click()
  await page.getByRole('button', { name: '确认停用' }).click()
  await expect(editor.getByLabel('账号状态')).toHaveAttribute('aria-invalid', 'true')
  await editor.getByRole('button', { name: '取消' }).click()
  await page.getByRole('button', { name: '编辑 chen' }).click()
  await editor.getByLabel('角色', { exact: true }).click()
  await page.getByRole('option', { name: '管理员', exact: true }).click()
  await editor.getByRole('button', { name: '保存' }).click()
  await expect(editor).toHaveCount(0)
  await page.getByRole('button', { name: '编辑 renamed-admin' }).click()
  await editor.getByLabel('账号状态').click()
  await page.getByRole('option', { name: '停用' }).click()
  await editor.getByRole('button', { name: '保存' }).click()
  await page.getByRole('button', { name: '确认停用' }).click()
  await expect(page.getByRole('button', { name: '登录', exact: true })).toBeVisible()
  await expect(page.getByRole('navigation', { name: '设置分类' })).toHaveCount(0)
})

test('settings back uses the shared confirmation and can return to the original address', async ({ page }) => {
  await mockServer(page)
  await page.goto(`${harness}#/?draft=1`)
  await page.getByRole('link', { name: '设置', exact: true }).click()
  await page.getByRole('link', { name: 'AI 运行时' }).click()
  const retries = page.getByLabel('最大重试次数')
  await retries.fill('4')
  const back = page.getByRole('button', { name: '返回' })
  await back.click()
  const confirmation = page.getByRole('alertdialog', { name: '放弃未保存的修改？' })
  await confirmation.getByRole('button', { name: '继续编辑' }).click()
  await expect(retries).toHaveValue('4')
  await back.click()
  await confirmation.getByRole('button', { name: '放弃修改' }).click()
  await expect(page).toHaveURL(/#\/\?draft=1$/)
})

test('capture login and user settings at acceptance widths', async ({ page }) => {
  await mockServer(page, null)
  for (const width of [1440, 390, 320]) {
    await page.setViewportSize({ width, height: width === 320 ? 720 : 900 })
    await page.goto(`${harness}#/settings/users`)
    if (width !== 1440) {
      await page.goto(harness)
      await page.getByRole('button', { name: admin.username }).click()
      await page.getByRole('menuitem', { name: '退出登录' }).click()
      await page.goto(`${harness}#/settings/users`)
    }
    await expect(page.getByRole('button', { name: '登录', exact: true })).toBeVisible()
    await page.screenshot({ path: `../reports/r130-settings/login-${width}.png` })
    await login(page, admin.username)
    await expect(page.getByRole('button', { name: '编辑 kkstudio' })).toBeVisible()
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(width)
    if (width < 640) expect((await page.getByRole('button', { name: '返回' }).boundingBox())?.width).toBe(44)
    await page.screenshot({ path: `../reports/r130-settings/users-${width}.png` })
  }
})
