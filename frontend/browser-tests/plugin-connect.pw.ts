import { expect, test, type Page } from './fixture'
import type { PluginDTO } from '@/shared/api/contracts/ai-plugin'

const deepLinkPlugin: PluginDTO = {
  pluginId: 'offline-deeplink',
  name: 'Offline DeepLink Plugin',
  version: '1.0.0',
  authKind: { type: 'DEEP_LINK', regionCandidates: ['CN', 'EN'] },
  status: 'NOT_CONNECTED',
  region: null,
  expiresAt: null,
  nextRefreshAt: null,
  lastRefreshedAt: null,
  lastRefreshError: null,
  configuration: {
    revision: '1',
    valuesJson:
      '{"enabled":true,"bigInt":9007199254740993123456789,"decimal":1.230000,"exp":1e1000}',
    valuesSchema: JSON.stringify({
      type: 'object',
      description: 'Plugin configuration schema',
      properties: {
        enabled: { type: 'boolean', description: 'Enable plugin' },
        bigInt: { type: 'integer', description: 'Large integer' },
        decimal: { type: 'number', description: 'Precision decimal' },
        exp: { type: 'number', description: 'Scientific notation' },
      },
      required: ['enabled'],
      additionalProperties: false,
    }),
    defaultsJson: '{"enabled":true}',
    secrets: {
      apiSecret: {
        description: 'DeepLink API Secret',
        configured: true,
      },
    },
  },
  desiredRevision: '1',
  appliedRevision: '1',
  connectionCheckSupported: false,
  harnessToolNames: ['search_tool'],
  canvasFunctionNames: ['draw_func'],
  applicationError: null,
}

const staticKeyPlugin: PluginDTO = {
  pluginId: 'offline-statickey',
  name: 'Offline StaticKey Plugin',
  version: '2.0.0',
  authKind: { type: 'STATIC_KEY', secretField: 'token' },
  status: 'CONNECTED',
  region: null,
  expiresAt: null,
  nextRefreshAt: null,
  lastRefreshedAt: null,
  lastRefreshError: null,
  configuration: {
    revision: '2',
    valuesJson: '{"timeout":30}',
    valuesSchema: JSON.stringify({
      type: 'object',
      properties: {
        timeout: { type: 'integer' },
      },
      required: [],
      additionalProperties: false,
    }),
    defaultsJson: '{"timeout":30}',
    secrets: {
      token: {
        description: 'Static Access Token',
        configured: true,
      },
    },
  },
  desiredRevision: '2',
  appliedRevision: '2',
  connectionCheckSupported: false,
  harnessToolNames: ['static_tool'],
  canvasFunctionNames: [],
  applicationError: null,
}

const noAuthPlugin: PluginDTO = {
  pluginId: 'offline-noauth',
  name: 'Offline NoAuth Plugin',
  version: '0.9.0',
  authKind: null,
  status: null,
  region: null,
  expiresAt: null,
  nextRefreshAt: null,
  lastRefreshedAt: null,
  lastRefreshError: null,
  configuration: {
    revision: '0',
    valuesJson: '{"debug":false}',
    valuesSchema: JSON.stringify({
      type: 'object',
      properties: {
        debug: { type: 'boolean' },
      },
      required: [],
      additionalProperties: false,
    }),
    defaultsJson: '{"debug":false}',
    secrets: {},
  },
  desiredRevision: '0',
  appliedRevision: null,
  connectionCheckSupported: false,
  harnessToolNames: [],
  canvasFunctionNames: [],
  applicationError: 'configuration is saved but this node has not applied it yet',
}

const checkPlugin: PluginDTO = {
  pluginId: 'offline-check',
  name: 'Offline Check Plugin',
  version: '3.1.0',
  authKind: { type: 'STATIC_KEY', secretField: 'token' },
  status: 'CONNECTED',
  region: null,
  expiresAt: null,
  nextRefreshAt: null,
  lastRefreshedAt: null,
  lastRefreshError: null,
  configuration: {
    revision: '3',
    valuesJson: '{"timeout":30}',
    valuesSchema: JSON.stringify({
      type: 'object',
      properties: {
        timeout: { type: 'integer' },
      },
      required: [],
      additionalProperties: false,
    }),
    defaultsJson: '{"timeout":30}',
    secrets: {
      token: {
        description: 'Static Access Token',
        configured: true,
      },
    },
  },
  desiredRevision: '3',
  appliedRevision: '3',
  connectionCheckSupported: true,
  harnessToolNames: [],
  canvasFunctionNames: [],
  applicationError: null,
}

async function transport(
  page: Page,
  pluginsList: PluginDTO[] = [deepLinkPlugin],
  blockStage?: 'prepare' | 'complete' | 'check',
) {
  let release!: () => void
  const gate = new Promise<void>((resolve) => {
    release = resolve
  })
  const requests: string[] = []

  await page.route('**/api/**', async (route) => {
    const url = new URL(route.request().url())
    const path = url.pathname

    if (path === '/api/plugins') {
      await route.fulfill({ json: { status: 200, data: pluginsList } })
    } else if (path.endsWith('/connection-check') && route.request().method() === 'POST') {
      requests.push(path)
      if (blockStage === 'check') {
        await gate
      }
      const postData = route.request().postDataJSON() as {
        expectedRevision: string
      }
      if (!postData || typeof postData.expectedRevision !== 'string') {
        throw new Error(`Connection check must submit the applied coordinates: ${path}`)
      }
      await route.fulfill({
        json: {
          status: 200,
          data: {
            pluginId: decodeURIComponent(path.split('/')[3] ?? ''),
            appliedRevision: postData.expectedRevision,
            checkedAt: 1772366400,
            reachability: 'REACHABLE',
            authentication: 'ACCEPTED',
            browserSession: 'UNKNOWN',
          },
        },
      })
    } else if (path.endsWith('/auth/prepare')) {
      requests.push(path)
      if (blockStage === 'prepare') {
        await gate
      }
      await route.fulfill({
        json: {
          status: 200,
          data: {
            authAttemptId: 'attempt-nonce-1',
            loginUrl: 'https://auth.example.com/login',
          },
        },
      })
    } else if (path.endsWith('/auth/complete')) {
      requests.push(path)
      if (blockStage === 'complete') {
        await gate
      }
      await route.fulfill({
        status: 400,
        json: { status: 400, message: 'Callback rejected' },
      })
    } else if (path.endsWith('/configuration') && route.request().method() === 'PUT') {
      requests.push(path)
      const postData = route.request().postDataJSON() as { valuesJson: string }
      await route.fulfill({
        status: 200,
        json: {
          status: 200,
          data: {
            ...deepLinkPlugin,
            configuration: {
              ...deepLinkPlugin.configuration,
              valuesJson: postData.valuesJson,
            },
          },
        },
      })
    } else {
      throw new Error(`Unexpected offline request: ${path}`)
    }
  })

  return { requests, release }
}

for (const stage of ['prepare', 'complete'] as const) {
  test(`${stage}: real shared controls at 320px lock every close path and other stage until settled`, async (
    { page },
    info,
  ) => {
    await page.setViewportSize({ width: 320, height: 800 })
    const network = await transport(page, [deepLinkPlugin], stage)
    await page.goto('/browser-tests/resource-card-harness.html?plugins')

    const opener = page.getByRole('button', { name: '连接 Offline DeepLink Plugin' })
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

    if (stage === 'prepare') {
      await input.fill('offline-callback')
      await login.click()
    } else {
      // Complete stage: prepare succeeds immediately, then fill callback and submit
      await login.click()
      await expect.poll(() => network.requests.length).toBe(1)
      await input.fill('offline-callback')
      await dialog.getByRole('button', { name: '完成连接' }).click()
    }

    await expect.poll(() => network.requests.length).toBe(stage === 'prepare' ? 1 : 2)
    await expect(input).toBeDisabled()
    await expect(dialog.getByRole('button', { name: '区域' })).toBeDisabled()
    const cancel = dialog.getByRole('button', { name: '取消' })
    await expect(cancel).toBeDisabled()
    await expect(close).toBeDisabled()
    await expect(dialog.locator('button[type=submit]')).toBeDisabled()

    await page.keyboard.press('Escape')
    await page.locator('.modal-backdrop').click({ position: { x: 1, y: 1 } })
    await close.dispatchEvent('click')
    await cancel.dispatchEvent('click')
    await dialog.locator('form').dispatchEvent('submit')
    await expect(dialog).toBeVisible()

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
  })
}

test('English login and pending copy fit shared controls at 320px', async (
  { page },
  info,
) => {
  await page.setViewportSize({ width: 320, height: 800 })
  const network = await transport(page, [deepLinkPlugin], 'prepare')
  await page.goto('/browser-tests/resource-card-harness.html?plugins&locale=en-US')

  await page.getByRole('button', { name: 'Connect Offline DeepLink Plugin' }).click()
  const dialog = page.getByRole('dialog')
  await expect(dialog).toHaveAccessibleName('Connect Plugin: Offline DeepLink Plugin')
  await expect(
    dialog.getByText('Open the login page first, then paste the callback link here.'),
  ).toBeVisible()
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

test('renders three plugin shapes (None, StaticKey, DeepLink) and opens configuration modal at wide and 360px viewports', async (
  { page },
  info,
) => {
  const network = await transport(page, [deepLinkPlugin, staticKeyPlugin, noAuthPlugin])

  // Wide screen validation
  await page.setViewportSize({ width: 1280, height: 800 })
  await page.goto('/browser-tests/resource-card-harness.html?plugins')

  // Check 3 plugin cards rendered with safe fields
  await expect(page.getByText('Offline DeepLink Plugin')).toBeVisible()
  await expect(page.getByText('Offline StaticKey Plugin')).toBeVisible()
  await expect(page.getByText('Offline NoAuth Plugin')).toBeVisible()

  // Capabilities rendered
  await expect(page.getByText('search_tool, draw_func')).toBeVisible()
  await expect(page.getByText('static_tool')).toBeVisible()

  // Application pending status on NoAuth plugin
  await expect(page.getByTestId('plugin-app-status-offline-noauth')).toHaveText('待应用')
  await expect(page.getByTestId('plugin-app-error-offline-noauth')).toBeVisible()

  await page.screenshot({ path: info.outputPath('plugins-wide-list.png') })

  // Open configuration modal on deepLinkPlugin
  const configBtn = page.getByRole('button', { name: '配置 Offline DeepLink Plugin' })
  await configBtn.click()

  const configDialog = page.getByRole('dialog')
  await expect(configDialog).toBeVisible()
  await expect(configDialog.getByText('配置插件：Offline DeepLink Plugin')).toBeVisible()
  await expect(configDialog.locator('input[value="9007199254740993123456789"]')).toBeVisible()
  await expect(configDialog.locator('input[value="1.230000"]')).toBeVisible()
  await expect(configDialog.locator('input[value="1e1000"]')).toBeVisible()
  const save = configDialog.getByRole('button', { name: '保存配置', exact: true })
  await expect(save).toBeInViewport()
  await expect(configDialog.getByRole('button', { name: '取消', exact: true })).toBeInViewport()
  await expect(configDialog.locator('.modal-body .modal-footer')).toHaveCount(0)

  await page.screenshot({ path: info.outputPath('plugin-config-wide.png') })

  // Check responsive layout at 360px
  await page.setViewportSize({ width: 360, height: 800 })
  const overflow = await configDialog.evaluate((el) => el.scrollWidth > el.clientWidth + 1)
  expect(overflow).toBe(false)
  await expect(save).toBeInViewport()
  await configDialog.locator('.modal-body').evaluate((el) => { el.scrollTop = el.scrollHeight })
  await expect(save).toBeInViewport()
  await page.screenshot({ path: info.outputPath('plugin-config-360px.png') })

  await save.click()
  await expect.poll(() => network.requests.filter((path) => path.endsWith('/configuration')).length).toBe(1)
  await expect(configDialog).toHaveCount(0)
})

test('connection check runs only on explicit click and reports the three typed dimensions', async (
  { page },
  info,
) => {
  await page.setViewportSize({ width: 320, height: 800 })
  const network = await transport(page, [checkPlugin], 'check')
  await page.goto('/browser-tests/resource-card-harness.html?plugins')

  const opener = page.getByRole('button', { name: '检查连接 Offline Check Plugin' })
  await expect(opener).toBeVisible()
  // 渲染与列表加载绝不触发探测：点击之前没有任何检查请求。
  expect(network.requests).toEqual([])

  await opener.click()
  await expect.poll(() => network.requests.length).toBe(1)
  const dialog = page.getByRole('dialog')
  await expect(dialog).toHaveAccessibleName('连接检查：Offline Check Plugin')
  await expect(dialog.getByTestId('plugin-check-pending')).toBeVisible()

  // 进行中：入口与关闭都被禁用，Escape 也不能关掉弹窗。
  await expect(opener).toBeDisabled()
  await expect(dialog.getByTestId('plugin-check-close')).toBeDisabled()
  await page.keyboard.press('Escape')
  await expect(dialog).toBeVisible()
  await page.screenshot({ path: info.outputPath('connection-check-pending-320.png') })

  network.release()
  await expect(dialog.getByTestId('plugin-check-reachability')).toHaveText('可达')
  await expect(dialog.getByTestId('plugin-check-authentication')).toHaveText('凭据已接受')
  // 浏览器登录态恒为未知，绝不冒充已登录。
  await expect(dialog.getByTestId('plugin-check-browser-session')).toHaveText(
    '未知（本检查不核验浏览器登录态）',
  )
  await expect(dialog.getByTestId('plugin-check-coordinates')).toHaveText('Rev 3')
  expect(await dialog.evaluate((el) => el.scrollWidth <= el.clientWidth + 1)).toBe(true)
  await page.screenshot({ path: info.outputPath('connection-check-result-320.png') })

  await dialog.getByTestId('plugin-check-close').click()
  await expect(dialog).toHaveCount(0)
  // 关闭后不自动重查。
  expect(network.requests.length).toBe(1)
})
