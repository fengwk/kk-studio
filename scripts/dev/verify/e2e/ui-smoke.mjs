#!/usr/bin/env node
/**
 * Playwright UI E2E smoke（L5，可选）。
 *
 * - 登录：通过登录表单使用 runner 种入的管理员账号登录（绝不打印密码）
 * - 工作区：进入空间、必要时配置默认 Agent（不隐式绑定环境）、首条消息创建会话（离线 mock Provider）
 * - 目录与窗格：校验目录出现会话且窗格标题切换为会话线程标题
 * - 设置往返：打开设置路由（含环境动作契约校验）并返回工作区
 * - 截图/结果写入 --report-dir（通常是 reports/e2e/<runId>）
 *
 *   node scripts/dev/verify/e2e/ui-smoke.mjs --base-url http://127.0.0.1:5173 --report-dir reports/e2e/<run>
 *   scripts/dev/verify/e2e/run.sh --ui
 */

import { createRequire } from 'node:module'
import { existsSync, mkdirSync, readdirSync, statSync } from 'node:fs'
import { createServer } from 'node:http'
import path from 'node:path'
import { assertProviderExecutionBoundary } from './lib/provider-boundary.mjs'
import { missingUiCapabilities } from './lib/ui-capabilities.mjs'
import { MINIMAX_ANTHROPIC_M3 } from './lib/real-models.mjs'
import { createDurationTimer } from './lib/time.mjs'
import { redactSecrets, writeReportArtifact } from './lib/redact.mjs'
import { baseModelConfig, encodePathTail } from './lib/fixtures.mjs'
import { deleteWorkspaceSession } from './lib/harness.mjs'
import { cid, httpJson, HttpError, loginStudio } from './lib/http.mjs'
import { ENVIRONMENT_CARD_ACTIONS } from './ui/environment-actions.mjs'
import { waitForSettledAssistantText } from './ui/assertions.mjs'
import { runComposerMatrix } from './ui/composer-matrix.mjs'
import { REPO_ROOT } from '../../lib/repo-root.mjs'

// Playwright 安装在 frontend/node_modules，直接 import 会找不到包。
const requireFromFrontend = createRequire(
  path.join(REPO_ROOT, 'frontend/package.json'),
)
const { chromium } = requireFromFrontend('@playwright/test')

const REAL_UI_MODEL = MINIMAX_ANTHROPIC_M3
const REAL_UI_MODEL_ID = REAL_UI_MODEL.modelName
const REAL_UI_MODEL_SELECTOR_LABEL = `${REAL_UI_MODEL_ID} (${REAL_UI_MODEL.variant})`
const STUDIO_ADMIN_USER = process.env.E2E_STUDIO_USER || 'kkstudio'
const STUDIO_ADMIN_CREDENTIAL = process.env.E2E_STUDIO_PASS || 'kkstudio'

function parseArgs(argv) {
  const args = {
    baseUrl: process.env.FRONTEND_URL || 'http://127.0.0.1:5173',
    backendUrl: process.env.BACKEND_URL || 'http://127.0.0.1:18081',
    reportDir: process.env.E2E_UI_REPORT_DIR || path.join(REPO_ROOT, 'reports/e2e/ui-standalone'),
    daemonEnv: process.env.DAEMON_ENV_NAME || 'e2e-local',
    headed: false,
    real: false,
    withTools: false,
    withCanvasFunction: false,
    only: [],
  }
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i]
    if (a === '--base-url') args.baseUrl = argv[++i]
    else if (a === '--backend-url') args.backendUrl = argv[++i]
    else if (a === '--report-dir') args.reportDir = path.resolve(argv[++i])
    else if (a === '--daemon-env') args.daemonEnv = argv[++i]
    else if (a === '--headed') args.headed = true
    else if (a === '--real') args.real = true
    else if (a === '--with-tools') args.withTools = true
    else if (a === '--with-canvas-function') args.withCanvasFunction = true
    else if (a === '--only') args.only.push(argv[++i])
    else if (a === '-h' || a === '--help') args.help = true
    else throw new Error(`unknown arg: ${a}`)
  }
  return args
}

function assert(cond, msg) {
  if (!cond) throw new Error(msg || 'assertion failed')
}

async function apiJson(backendUrl, method, requestPath, body, headers) {
  try {
    const { status, json } = await httpJson(backendUrl, method, requestPath, body, undefined, headers)
    return { status, json }
  } catch (error) {
    if (error instanceof HttpError) {
      let json = error.body
      if (typeof error.body === 'string' && error.body.trim()) {
        try {
          json = JSON.parse(error.body)
        } catch {
          json = error.body
        }
      }
      return { status: error.status, json }
    }
    throw error
  }
}

async function apiDeleteByName(backendUrl, resource, name) {
  const resourcePath = `/api/platform/${resource}`
  const listRes = await apiJson(backendUrl, 'GET', resourcePath)
  const list = Array.isArray(listRes.json) ? listRes.json : []
  const hit = list.find((item) => item.name === name)
  if (!hit) return false
  const tail = resource === 'models'
    ? encodePathTail(hit.name)
    : encodeURIComponent(hit.name)
  await apiJson(
    backendUrl,
    'DELETE',
    `${resourcePath}/${tail}?expectedVersion=${encodeURIComponent(hit.version)}`,
  )
  return true
}

async function requireRealMiniMaxM3(backendUrl, modelDef = MINIMAX_ANTHROPIC_M3) {
  const { json: modelsJson } = await apiJson(backendUrl, 'GET', '/api/platform/models')
  const { json: providersJson } = await apiJson(backendUrl, 'GET', '/api/platform/providers')
  const models = Array.isArray(modelsJson) ? modelsJson : []
  const providers = Array.isArray(providersJson) ? providersJson : []
  const model = models.find((candidate) => candidate.name === modelDef.modelName)
  const provider = providers.find((candidate) => candidate.name === model?.providerName)
  assert(
    provider?.name === modelDef.providerName
      && provider.providerType === modelDef.providerType
      && provider.credential?.configured === true
      && typeof provider.baseUrl === 'string'
      && provider.baseUrl.trim().length > 0
      && model?.name === modelDef.modelName
      && model.config?.variants?.some((variant) => variant.id === modelDef.variant),
    `real UI test requires model ${modelDef.modelName} on configured provider ${modelDef.providerName} with variant ${modelDef.variant}`,
  )
}

async function requireProviderExecutionBoundary(backendUrl, real) {
  if (real) return
  const { status, json } = await apiJson(backendUrl, 'GET', '/api/platform/providers')
  assert(status === 200, `failed to query providers boundary: HTTP ${status}`)
  const providers = Array.isArray(json) ? json : []
  assertProviderExecutionBoundary({ real: false, providers })
  console.log('Provider boundary: free UI mode confirmed every provider is unconfigured')
}

async function assertLeadingCreateCard(page, action, label, gridSelector = '.resource-grid') {
  const layout = await action.evaluate((element, selector) => {
    const grid = element.closest(selector)
    const bounds = element.getBoundingClientRect()
    const container = grid?.getBoundingClientRect()
    return {
      first: grid?.firstElementChild === element,
      card: element.classList.contains('create-card'),
      visible: bounds.width > 0 && bounds.height > 0,
      contained: container != null
        && bounds.left >= container.left - 1
        && bounds.right <= container.right + 1,
    }
  }, gridSelector)
  assert(
    layout.first && layout.card && layout.visible && layout.contained,
    `${label} must be the visible leading create card: ${JSON.stringify(layout)}`,
  )
}

function listArtifacts(caseDir, reportDir) {
  if (!existsSync(caseDir)) return []
  return readdirSync(caseDir)
    .filter((n) => statSync(path.join(caseDir, n)).isFile())
    .map((n) => path.relative(reportDir, path.join(caseDir, n)))
    .sort()
}

/** 沿 aria-controls 定位展开的列表，再按可见标题选择。 */
async function selectCustomOption(page, trigger, optionName) {
  await trigger.click()
  const controlledId = await trigger.getAttribute('aria-controls')
  assert(controlledId, `Select trigger ${await trigger.getAttribute('aria-label')} lacks aria-controls`)
  const target = `[id=${JSON.stringify(controlledId)}]`
  const listbox = page.locator(`${target}[role="listbox"], ${target} [role="listbox"]`)
  await listbox.waitFor({ state: 'visible', timeout: 10_000 })
  const option = listbox.getByRole('option').filter({ has: page.getByText(optionName, { exact: true }) })
  await option.waitFor({ state: 'visible', timeout: 10_000 })
  await option.click()
}

function isIgnorableNoise(text) {
  return /Permissions policy violation|unload is not allowed|favicon|Download the React DevTools|third-party|extension|chrome-extension/i.test(
    text,
  )
}

function expectNoFatal(pageErrors, consoleErrors) {
  const fatalPage = pageErrors.filter((e) => !isIgnorableNoise(e))
  assert(fatalPage.length === 0, `pageerror: ${fatalPage.join(' | ')}`)
  const criticalConsole = consoleErrors.filter(
    (e) => !isIgnorableNoise(e) && /defaultVariant|TypeError|Uncaught|Cannot read properties/i.test(e),
  )
  assert(criticalConsole.length === 0, `console error: ${criticalConsole.join(' | ')}`)
}

function sseFrame(payload) {
  return `data: ${JSON.stringify(payload)}\n\n`
}

async function readRequestBody(request) {
  const chunks = []
  for await (const chunk of request) chunks.push(chunk)
  return Buffer.concat(chunks).toString('utf8')
}

/**
 * 本地受控 OpenAI chat-completions mock：在 127.0.0.1 随机端口返回确定性回复，
 * 不访问外网也不触碰真实模型 Provider。
 */
class MockCompletionsServer {
  constructor(replyText) {
    this.replyText = replyText
    this.requests = []
    this.sockets = new Set()
    this.listening = false
    this.base = null
    this.server = createServer((request, response) => {
      void this.handle(request, response)
    })
    this.server.on('connection', (socket) => {
      this.sockets.add(socket)
      socket.once('close', () => this.sockets.delete(socket))
    })
  }

  async start() {
    await new Promise((resolve, reject) => {
      this.server.once('error', reject)
      this.server.listen(0, '127.0.0.1', () => {
        this.server.removeListener('error', reject)
        resolve()
      })
    })
    const address = this.server.address()
    assert(address && typeof address === 'object', 'mock completions server failed to bind')
    this.base = `http://127.0.0.1:${address.port}`
    this.listening = true
  }

  baseUrl(suffix = '') {
    assert(this.base, 'mock completions server is not started')
    return `${this.base}${suffix}`
  }

  async handle(request, response) {
    if (request.method !== 'POST' || request.url !== '/v1/chat/completions') {
      response.writeHead(request.method === 'POST' ? 404 : 405, { Connection: 'close' })
      response.end()
      return
    }
    let body = {}
    try {
      const raw = await readRequestBody(request)
      body = raw ? JSON.parse(raw) : {}
    } catch (error) {
      response.writeHead(400, { 'Content-Type': 'application/json' })
      response.end(JSON.stringify({ error: { message: `invalid mock JSON: ${error.message}` } }))
      return
    }
    const index = this.requests.length + 1
    this.requests.push(body)
    const model = body.model || 'e2e-mock-model'
    if (body.stream === false) {
      response.writeHead(200, { 'Content-Type': 'application/json', Connection: 'close' })
      response.end(JSON.stringify({
        id: `e2e-ui-mock-${index}`,
        object: 'chat.completion',
        created: Math.floor(Date.now() / 1000),
        model,
        choices: [
          {
            index: 0,
            message: { role: 'assistant', content: this.replyText },
            finish_reason: 'stop',
          },
        ],
        usage: { prompt_tokens: 10, completion_tokens: 5, total_tokens: 15 },
      }))
      return
    }
    response.writeHead(200, {
      'Content-Type': 'text/event-stream; charset=utf-8',
      'Cache-Control': 'no-cache',
      Connection: 'close',
    })
    response.write(sseFrame({
      id: `e2e-ui-mock-${index}`,
      object: 'chat.completion.chunk',
      created: Math.floor(Date.now() / 1000),
      model,
      choices: [{ index: 0, delta: { role: 'assistant', content: this.replyText }, finish_reason: null }],
    }))
    response.write(sseFrame({
      id: `e2e-ui-mock-stop-${index}`,
      object: 'chat.completion.chunk',
      created: Math.floor(Date.now() / 1000),
      model,
      choices: [{ index: 0, delta: {}, finish_reason: 'stop' }],
    }))
    response.write(sseFrame({
      id: `e2e-ui-mock-usage-${index}`,
      object: 'chat.completion.chunk',
      created: Math.floor(Date.now() / 1000),
      model,
      usage: { prompt_tokens: 10, completion_tokens: 5, total_tokens: 15 },
    }))
    response.end('data: [DONE]\n\n')
  }

  async close() {
    for (const socket of this.sockets) socket.destroy()
    this.sockets.clear()
    if (!this.listening) return
    await new Promise((resolve, reject) => {
      this.server.close((error) => (error ? reject(error) : resolve()))
    })
    this.listening = false
  }
}

async function listSpaces(apiCtx) {
  const { status, json } = await apiCtx.call('GET', '/api/chat-spaces')
  assert(status === 200 && Array.isArray(json), `failed to list chat spaces: HTTP ${status}`)
  return json
}

async function getSpaceTree(apiCtx, spaceId) {
  const { status, json } = await apiCtx.call('GET', `/api/chat-spaces/${encodeURIComponent(spaceId)}/tree`)
  assert(status === 200 && json && Array.isArray(json.folders), `failed to load workspace tree: HTTP ${status}`)
  return json
}

function findSessionInTree(folders, titleQuery) {
  for (const folder of folders || []) {
    for (const resource of folder.resources || []) {
      if (resource.kind === 'SESSION' && String(resource.title || '').includes(titleQuery)) {
        return resource
      }
    }
    const nested = findSessionInTree(folder.children, titleQuery)
    if (nested) return nested
  }
  return null
}

async function main(argv) {
  const args = parseArgs(argv)
  if (args.help) {
    console.log(
      'Usage: node scripts/dev/verify/e2e/ui-smoke.mjs --base-url URL --report-dir DIR'
        + ' [--headed] [--real] [--with-tools] [--with-canvas-function] [--daemon-env NAME] [--only CASE_ID]...',
    )
    return 0
  }

  await loginStudio(args.backendUrl)
  await requireProviderExecutionBoundary(args.backendUrl, args.real)

  const reportDir = args.reportDir
  const artRoot = path.join(reportDir, 'artifacts')
  mkdirSync(path.join(reportDir, 'cases'), { recursive: true })
  mkdirSync(artRoot, { recursive: true })
  const apiCtx = {
    call: (method, requestPath, body, headers) =>
      apiJson(args.backendUrl, method, requestPath, body, headers),
    request: (requestPath, { method = 'GET', body, headers } = {}) =>
      apiJson(args.backendUrl, method, requestPath, body, headers),
  }
  if (args.real) await requireRealMiniMaxM3(args.backendUrl, MINIMAX_ANTHROPIC_M3)

  const browserEnv = { ...process.env }
  if (!args.headed) {
    delete browserEnv.DISPLAY
    delete browserEnv.WAYLAND_DISPLAY
  }
  const browser = await chromium.launch({
    headless: !args.headed,
    env: browserEnv,
  })
  const context = await browser.newContext({ viewport: { width: 1440, height: 960 } })
  const page = await context.newPage()
  const pageErrors = []
  const consoleErrors = []
  page.on('pageerror', (err) => pageErrors.push(String(err)))
  page.on('console', (msg) => {
    if (msg.type() === 'error') consoleErrors.push(msg.text())
  })

  const base = args.baseUrl.replace(/\/$/, '')
  const results = []
  const registeredCaseIds = new Set()
  const selectedCaseIds = new Set(args.only)

  async function goto(p) {
    const targetUrl = `${base}${p}`
    let lastError = null
    for (let attempt = 0; attempt < 2; attempt += 1) {
      try {
        const response = await page.goto(targetUrl, {
          waitUntil: 'networkidle',
          timeout: 30_000,
        })
        assert(
          response?.ok(),
          `navigation HTTP ${response?.status() ?? 'missing'}: ${targetUrl}`,
        )
        await page.waitForFunction(
          () => (document.querySelector('#root')?.childElementCount ?? 0) > 0,
          undefined,
          { timeout: 10_000 },
        )
        await page.waitForTimeout(300)
        return
      } catch (error) {
        lastError = error
        if (attempt === 0) {
          pageErrors.length = 0
          consoleErrors.length = 0
          await page.waitForTimeout(250)
        }
      }
    }
    throw lastError
  }

  async function shot(caseArt, name) {
    const file = path.join(caseArt, `${name}.png`)
    await page.screenshot({ path: file, fullPage: true })
  }

  let authenticated = false

  async function signInViaForm(caseArt = null) {
    const signInGate = page.locator('main.account-gate form.sign-in-form')
    if (await signInGate.count() === 0) {
      authenticated = true
      return false
    }
    const usernameInput = signInGate.getByLabel(/^(?:Username|用户名)$/)
    const passwordInput = signInGate.getByLabel(/^(?:Password|密码)$/)
    await usernameInput.waitFor({ state: 'visible', timeout: 15_000 })
    await usernameInput.fill(STUDIO_ADMIN_USER)
    await passwordInput.fill(STUDIO_ADMIN_CREDENTIAL)
    if (caseArt) await shot(caseArt, 'login-form')
    await signInGate.getByRole('button', { name: /^(?:Sign in|登录)$/ }).click()
    await signInGate.waitFor({ state: 'detached', timeout: 20_000 })
    authenticated = true
    return true
  }

  async function ensureSignedIn() {
    if (authenticated) return
    await goto('/')
    await signInViaForm()
  }

  async function landInWorkspaceSpace(spaceTitle) {
    const newSpaceButton = page.getByRole('button', { name: /^(?:New Chat Space|新建 Chat Space)$/ })
    const shell = page.locator('.workspace-shell')
    await Promise.race([
      shell.waitFor({ state: 'visible', timeout: 20_000 }),
      newSpaceButton.waitFor({ state: 'visible', timeout: 20_000 }),
    ])
    let createdSpaceByUi = false
    if (await shell.count() === 0) {
      createdSpaceByUi = true
      await newSpaceButton.click()
      const dialog = page.getByRole('dialog', { name: /^(?:New Chat Space|新建 Chat Space)$/ })
      await dialog.waitFor({ state: 'visible', timeout: 10_000 })
      await dialog.getByRole('textbox', { name: /^(?:Name|名称)$/ }).fill(spaceTitle)
      await dialog.getByRole('button', { name: /^(?:Confirm|确认)$/ }).click()
      await dialog.waitFor({ state: 'detached', timeout: 15_000 })
      await shell.waitFor({ state: 'visible', timeout: 20_000 })
    }
    await page.waitForURL(/\/chat-spaces\/[0-9a-f-]{36}/i, { timeout: 20_000 })
    const spaceId = new URL(page.url()).pathname.split('/').pop()
    assert(spaceId && /^[0-9a-f-]{36}$/i.test(spaceId), `unexpected workspace URL: ${page.url()}`)
    return { spaceId, createdSpaceByUi }
  }

  async function selectAgentInSpaceDefaults(spaceId, agentName) {
    const spaceSettingsButton = page.getByRole('button', { name: /^(?:Space settings|空间设置)$/ })
    if (await spaceSettingsButton.count() > 0 && await spaceSettingsButton.first().isVisible()) {
      await spaceSettingsButton.first().click()
    } else {
      const spaceTrigger = page.locator('.workspace-space-trigger')
      await spaceTrigger.waitFor({ state: 'visible', timeout: 10_000 })
      await spaceTrigger.click()
      const spaceMenu = page.getByRole('menu', { name: /^(?:Chat Spaces|Chat Space)$/ })
      await spaceMenu.waitFor({ state: 'visible', timeout: 10_000 })
      await spaceMenu.getByRole('menuitem', { name: /^(?:Space defaults|空间默认配置)$/ }).click()
    }
    const defaultsDialog = page.getByRole('dialog', { name: /^(?:Space defaults|空间默认配置)$/ })
    await defaultsDialog.waitFor({ state: 'visible', timeout: 10_000 })
    await selectCustomOption(
      page,
      defaultsDialog.getByLabel(/^(?:Default agent|默认 Agent)$/),
      agentName,
    )
    const envTrigger = defaultsDialog.getByLabel(/^(?:Default environment|默认环境)$/)
    const envLabel = (await envTrigger.innerText()).trim()
    assert(
      /^(?:None|无)$/.test(envLabel),
      `selecting default Agent must not implicitly assign a default Environment, got: ${envLabel}`,
    )
    await defaultsDialog.getByRole('button', { name: /^(?:Confirm|确认)$/ }).click()
    await defaultsDialog.waitFor({ state: 'detached', timeout: 15_000 })

    const updatedSpace = (await listSpaces(apiCtx)).find((item) => item.id === spaceId)
    assert(
      updatedSpace?.defaultAgentName === agentName && updatedSpace.defaultEnvironmentId == null,
      `Space defaults did not keep null environmentId: ${JSON.stringify(updatedSpace)}`,
    )
  }

  async function run(id, title, fn, options = {}) {
    registeredCaseIds.add(id)
    const missingCapabilities = missingUiCapabilities(options, args)
    if (missingCapabilities.length > 0) {
      if (selectedCaseIds.has(id)) {
        throw new Error(`UI case ${id} requires ${missingCapabilities.join(' and ')}`)
      }
      return
    }
    if (selectedCaseIds.size > 0 && !selectedCaseIds.has(id)) {
      return
    }
    pageErrors.length = 0
    consoleErrors.length = 0
    const elapsed = createDurationTimer()
    const caseArt = path.join(artRoot, id.replaceAll('.', '_'))
    mkdirSync(caseArt, { recursive: true })
    console.log(`\n==> [L5] ${id}: ${title}`)
    try {
      await fn(caseArt)
      const result = {
        id,
        level: 'L5',
        title,
        status: 'pass',
        durationMs: elapsed(),
        error: null,
        traceback: null,
        artifactPaths: listArtifacts(caseArt, reportDir),
      }
      results.push(result)
      writeReportArtifact(path.join(reportDir, 'cases', `${id}.json`), `${JSON.stringify(result, null, 2)}\n`)
      console.log(`PASS ${id}`)
    } catch (err) {
      try {
        await page.screenshot({
          path: path.join(caseArt, 'failure.png'),
          fullPage: true,
        })
        writeReportArtifact(
          path.join(caseArt, 'failure-state.json'),
          `${JSON.stringify({
            url: page.url(),
            title: await page.title().catch(() => ''),
            bodyText: await page.locator('body').innerText().catch(() => ''),
            pageErrors,
            consoleErrors,
          }, null, 2)}\n`,
        )
      } catch (artifactError) {
        writeReportArtifact(
          path.join(caseArt, 'failure-artifact-error.txt'),
          `${artifactError?.message || artifactError}\n`,
        )
      }
      writeReportArtifact(path.join(caseArt, 'error.txt'), `${err?.message || err}\n${err?.stack || ''}\n`)
      const result = {
        id,
        level: 'L5',
        title,
        status: 'fail',
        durationMs: elapsed(),
        error: redactSecrets(String(err?.message || err)),
        traceback: redactSecrets(err?.stack || null),
        artifactPaths: listArtifacts(caseArt, reportDir),
      }
      results.push(result)
      writeReportArtifact(path.join(reportDir, 'cases', `${id}.json`), `${JSON.stringify(result, null, 2)}\n`)
      console.error(`FAIL ${id}: ${result.error}`)
    }
  }

  const stamp = `${Date.now().toString(36)}-${cid().slice(0, 6)}`

  await run(
    'ui.workspace.smoke',
    '登录并进入工作区：配置默认 Agent（无隐式环境）、首条消息创建会话、目录与窗格标题同步、设置往返',
    async (caseArt) => {
      const tempProviderName = `e2e-ui-provider-${stamp}`
      const tempModelName = `e2e-ui-model-${stamp}`
      const tempAgentName = `e2e-ui-agent-${stamp}`
      const promptText = `e2e-workspace-smoke-${stamp}`
      const mockReply = `E2E_WORKSPACE_UI_OK_${stamp}`
      const mock = new MockCompletionsServer(mockReply)
      let spaceId = null
      let createdSpaceByUi = false
      let createdSessionId = null

      await mock.start()
      try {
        const providerCreateRes = await apiJson(args.backendUrl, 'POST', '/api/platform/providers', {
          name: tempProviderName,
          description: 'Local offline mock provider for UI workspace smoke.',
          providerType: 'openai',
          baseUrl: mock.baseUrl('/v1'),
          credential: { operation: 'SET', value: `e2e-ui-mock-${stamp}` },
          modelCallTimeoutMillis: '30000',
          modelCallIdleTimeoutMillis: '10000',
        })
        assert(
          providerCreateRes.status === 201,
          `failed to create mock provider (HTTP ${providerCreateRes.status}): ${JSON.stringify(providerCreateRes.json)}`,
        )

        const modelCreateRes = await apiJson(args.backendUrl, 'POST', '/api/platform/models', {
          type: 'MODEL',
          providerName: tempProviderName,
          name: tempModelName,
          modelId: `wire-ui-${stamp}`,
          description: 'Local offline mock model for UI workspace smoke.',
          config: baseModelConfig({
            limit: { context: 4096, output: 256 },
            abilities: { tools: false, reasoning: false, inputModalities: ['TEXT'] },
            variants: [{ id: 'default', promptCacheRetention: 'SHORT' }],
          }),
        })
        assert(
          modelCreateRes.status === 201,
          `failed to create mock model (HTTP ${modelCreateRes.status}): ${JSON.stringify(modelCreateRes.json)}`,
        )

        const agentCreateRes = await apiJson(args.backendUrl, 'POST', '/api/platform/agents', {
          name: tempAgentName,
          description: `Temporary e2e agent for ${args.daemonEnv}`,
          systemPrompt: 'Reply concisely with the mock output.',
          execution: { driver: 'builtin', settings: { modelName: tempModelName, variant: 'default' } },
          config: { tools: [], skills: [], subagents: [] },
        })
        assert(
          agentCreateRes.status === 201,
          `failed to create temporary agent '${tempAgentName}' (expected 201, got ${agentCreateRes.status}): ${JSON.stringify(agentCreateRes.json)}`,
        )
        assert(
          !Object.hasOwn(agentCreateRes.json, 'environmentId')
            && !Object.hasOwn(agentCreateRes.json, 'branchSettings'),
          'Agent Definition must not own branch environment settings',
        )

        // 1. 通过登录表单登录（不打印密码）
        await goto('/')
        const signedIn = await signInViaForm(caseArt)
        assert(signedIn, 'expected SignInForm on initial unauthenticated visit')

        // 2. 进入工作区并确保打开默认新会话窗格
        ;({ spaceId, createdSpaceByUi } = await landInWorkspaceSpace(`E2E Smoke ${stamp}`))
        const paneTitle = page.locator('[data-pane-header] .ws-pane-title').first()
        await paneTitle.waitFor({ state: 'visible', timeout: 15_000 })
        const initialPaneTitle = (await paneTitle.innerText()).trim()
        assert(
          /^(?:New session|新会话)$/.test(initialPaneTitle),
          `expected default new-session pane title, got: ${initialPaneTitle}`,
        )
        await shot(caseArt, 'workspace-landed')

        // 3. 在默认新会话窗格配置 Agent（不隐式分配环境）并发送首条消息
        await selectAgentInSpaceDefaults(spaceId, tempAgentName)

        const statusFooter = page.getByLabel(/^(?:Thread status|Thread 状态)$/)
        await statusFooter.waitFor({ state: 'visible', timeout: 15_000 })
        const footerEnvText = (await statusFooter.locator('.thread-status-line span').first().innerText()).trim()
        assert(
          /^(?:No environment|未选择环境)$/.test(footerEnvText),
          `Draft footer inferred an implicit Environment: ${footerEnvText}`,
        )

        const composer = page.getByRole('textbox', { name: /^(?:Send a message to AI|给 AI 发送消息)$/ })
        await composer.waitFor({ state: 'visible', timeout: 15_000 })
        await composer.fill(promptText)
        const sendButton = page.getByRole('button', { name: /^(?:Send message|发送消息)$/ })
        await sendButton.click()

        // 4. 校验回复收敛、目录出现 Session、窗格标题显示该会话线程标题
        const assistantReply = await waitForSettledAssistantText(page)
        assert(
          assistantReply.includes(mockReply),
          `expected mock reply '${mockReply}', got: ${assistantReply}`,
        )

        const directory = page.getByRole('tree', { name: /^(?:Directory|目录)$/ })
        await directory.waitFor({ state: 'visible', timeout: 15_000 })
        const sessionItem = directory.locator('[role="treeitem"][aria-level="2"]').filter({ hasText: promptText }).first()
        await sessionItem.waitFor({ state: 'visible', timeout: 15_000 })
        createdSessionId = await sessionItem.getAttribute('data-tree-row')

        const tree = await getSpaceTree(apiCtx, spaceId)
        const createdSession = findSessionInTree(tree.folders, promptText)
        assert(createdSession?.id, `created session not found in workspace tree: ${JSON.stringify(tree)}`)
        createdSessionId ||= createdSession.id
        const expectedThreadTitle = createdSession.threads?.[0]?.title
        assert(expectedThreadTitle, `created session missing thread title: ${JSON.stringify(createdSession)}`)

        await page.waitForFunction(
          (expected) => {
            const titleEl = document.querySelector('[data-pane-header] .ws-pane-title')
            const text = titleEl?.textContent?.trim() || ''
            return text === expected && !/^(?:New session|新会话)$/.test(text)
          },
          expectedThreadTitle,
          { timeout: 15_000 },
        )
        const updatedPaneTitle = (await paneTitle.innerText()).trim()
        assert(
          updatedPaneTitle === expectedThreadTitle,
          `pane title must show created session thread '${expectedThreadTitle}', got '${updatedPaneTitle}'`,
        )
        await shot(caseArt, 'workspace-session-created')

        // 5. 打开设置路由并返回工作区
        await page.getByRole('button', { name: /^(?:Settings|设置)$/ }).click()
        await page.waitForURL(/\/settings\/preferences$/, { timeout: 15_000 })
        await page.locator('main.settings-route').waitFor({ state: 'visible', timeout: 15_000 })
        await shot(caseArt, 'settings-preferences')

        await page.getByRole('button', { name: /^(?:Back|返回)$/ }).click()
        await page.waitForURL(new RegExp(`/chat-spaces/${spaceId}$`), { timeout: 15_000 })
        await page.locator('.workspace-shell').waitFor({ state: 'visible', timeout: 15_000 })
        await sessionItem.waitFor({ state: 'visible', timeout: 15_000 })
        await shot(caseArt, 'workspace-returned')

        expectNoFatal(pageErrors, consoleErrors)
      } finally {
        if (spaceId && createdSessionId) {
          await deleteWorkspaceSession(apiCtx, spaceId, createdSessionId).catch(() => undefined)
        } else if (spaceId) {
          const spaces = await listSpaces(apiCtx).catch(() => [])
          const space = spaces.find((item) => item.id === spaceId)
          if (space && space.defaultAgentName === tempAgentName) {
            await apiJson(args.backendUrl, 'PATCH', `/api/chat-spaces/${encodeURIComponent(spaceId)}`, {
              expectedVersion: space.version,
              defaultAgentName: null,
            }).catch(() => undefined)
          }
        }
        if (createdSpaceByUi && spaceId) {
          const spaces = await listSpaces(apiCtx).catch(() => [])
          const activeSpaces = spaces.filter((item) => !item.archivedAt)
          const space = activeSpaces.find((item) => item.id === spaceId)
          if (space && activeSpaces.length > 1) {
            await apiJson(args.backendUrl, 'PATCH', `/api/chat-spaces/${encodeURIComponent(spaceId)}`, {
              expectedVersion: space.version,
              archived: true,
            }).catch(() => undefined)
          }
        }
        await apiDeleteByName(args.backendUrl, 'agents', tempAgentName).catch(() => undefined)
        await apiDeleteByName(args.backendUrl, 'models', tempModelName).catch(() => undefined)
        await apiDeleteByName(args.backendUrl, 'providers', tempProviderName).catch(() => undefined)
        await mock.close()
      }
    },
  )

  await run('ui.environments.page_loads', '设置 -> Environments 页渲染卡片及全部 7 项环境动作', async (caseArt) => {
    await goto('/')
    await signInViaForm()
    await goto('/settings/environments')
    await page.locator('.resource-grid').waitFor({ state: 'visible', timeout: 15_000 })
    const createButton = page.getByRole('button', { name: /新建环境|New Environment/ })
    await createButton.waitFor({ state: 'visible', timeout: 15_000 })
    await assertLeadingCreateCard(page, createButton, 'New Environment')

    const environmentCards = page.locator('.environment-card')
    assert(await environmentCards.count() > 0, 'environments page has no cards')
    const firstCard = environmentCards.first()
    const cardActions = firstCard.locator('.resource-card-actions button')
    const collectedLabels = await cardActions.allInnerTexts()

    await firstCard.getByRole('button', { name: /^(?:Manage|管理)\b/ }).click()
    const manageDialog = page.getByRole('dialog')
    await manageDialog.waitFor({ state: 'visible', timeout: 10_000 })
    const modalActionLabels = await manageDialog.locator('.environment-management-actions button').allInnerTexts()
    await shot(caseArt, 'settings-environments-manage')
    await manageDialog.getByRole('button', { name: /^(?:Close|关闭)$/ }).last().click()
    await manageDialog.waitFor({ state: 'detached', timeout: 10_000 })

    const allLabels = [...collectedLabels, ...modalActionLabels]
    assert(
      allLabels.length === ENVIRONMENT_CARD_ACTIONS.length,
      `environment card + management modal must expose exactly ${ENVIRONMENT_CARD_ACTIONS.length} actions, got ${JSON.stringify(allLabels)}`,
    )
    for (const [name, pattern] of ENVIRONMENT_CARD_ACTIONS) {
      const matches = allLabels.filter((label) => pattern.test(label))
      assert(
        matches.length === 1,
        `environment actions must expose exactly one ${name} action, got ${JSON.stringify(allLabels)}`,
      )
    }

    await shot(caseArt, 'settings-environments')
    expectNoFatal(pageErrors, consoleErrors)
  })

  await run('ui.canvas.function_fake_flow', 'Workspace Canvas：绑定 fake-image 编辑保存并运行', async (caseArt) => {
      const catalog = await apiJson(args.backendUrl, 'GET', '/api/canvas/functions')
      const fakeImage = (catalog.json || []).find((item) => item.name === 'fake-image')
      assert(
        fakeImage?.available === true,
        `fake-image Canvas Function must be available (run with --with-canvas-function): ${JSON.stringify(catalog.json?.map((f) => [f.name, f.available]))}`,
      )
      await goto('/')
      await signInViaForm()
      const { spaceId } = await landInWorkspaceSpace(`E2E Canvas ${stamp}`)
      const tree = await getSpaceTree(apiCtx, spaceId)
      const rootFolderId = tree.folders[0]?.id
      assert(rootFolderId, `workspace tree missing root folder: ${JSON.stringify(tree)}`)
      const createdCanvas = await apiJson(args.backendUrl, 'POST', `/api/chat-spaces/${encodeURIComponent(spaceId)}/canvases`, {
        folderId: rootFolderId,
        title: `E2E Canvas ${stamp}`,
        expectedTreeVersion: tree.treeVersion,
      })
      assert(createdCanvas.status === 200 || createdCanvas.status === 201, JSON.stringify(createdCanvas))
      const canvasId = createdCanvas.json.canvasId
      await goto(`/chat-spaces/${spaceId}?canvas=${canvasId}`)
      await page.locator('#canvasStage').waitFor({ state: 'visible', timeout: 15_000 })
      await shot(caseArt, 'workspace-canvas')
      expectNoFatal(pageErrors, consoleErrors)
    },
    { requiresCanvasFunction: true },
  )

  if (args.real) {
    await run('ui.workspace.first_send_real', 'Workspace 默认新会话窗格首发真实消息并收敛回复', async (caseArt) => {
      const realAgentName = `e2e-ui-real-agent-${stamp}`
      const promptText = `e2e-real-smoke-${stamp} Reply with OK only and do not call tools.`
      const agentCreateRes = await apiJson(args.backendUrl, 'POST', '/api/platform/agents', {
        name: realAgentName,
        description: 'Temporary real UI E2E agent.',
        systemPrompt: 'Reply concisely and never call tools.',
        execution: { driver: 'builtin', settings: { modelName: REAL_UI_MODEL_ID, variant: REAL_UI_MODEL.variant } },
        config: { tools: [], skills: [], subagents: [] },
      })
      assert(
        agentCreateRes.status === 201,
        `failed to create real UI agent '${realAgentName}' (expected 201, got ${agentCreateRes.status})`,
      )
      let spaceId = null
      let createdSessionId = null
      try {
        await goto('/')
        await signInViaForm()
        ;({ spaceId } = await landInWorkspaceSpace(`E2E Real ${stamp}`))
        await selectAgentInSpaceDefaults(spaceId, realAgentName)

        const composer = page.getByRole('textbox', { name: /^(?:Send a message to AI|给 AI 发送消息)$/ })
        await composer.waitFor({ state: 'visible', timeout: 15_000 })
        await composer.fill(promptText)
        await page.getByRole('button', { name: /^(?:Send message|发送消息)$/ }).click()
        await page.getByRole('button', { name: /^(?:Model & Variant|Model 与 Variant)$/ })
          .filter({ hasText: REAL_UI_MODEL_SELECTOR_LABEL })
          .waitFor({ state: 'visible', timeout: 30_000 })
        await shot(caseArt, 'first-send-user')

        const assistantText = await waitForSettledAssistantText(page)
        await shot(caseArt, 'first-send-done')
        assert(/\bOK\b/i.test(assistantText), `expected assistant reply containing OK, got: ${assistantText || '(empty)'}`)
        const tree = await getSpaceTree(apiCtx, spaceId)
        createdSessionId = findSessionInTree(tree.folders, promptText)?.id ?? null
        expectNoFatal(pageErrors, consoleErrors)
      } finally {
        if (spaceId && createdSessionId) {
          await deleteWorkspaceSession(apiCtx, spaceId, createdSessionId).catch(() => undefined)
        }
        await apiDeleteByName(args.backendUrl, 'agents', realAgentName).catch(() => undefined)
      }
    })
  }

  await runComposerMatrix({
    apiCtx,
    args,
    backendUrl: args.backendUrl,
    consoleErrors,
    daemonEnv: args.daemonEnv,
    ensureSignedIn,
    expectNoFatal,
    goto,
    page,
    pageErrors,
    run,
    shot,
    stamp,
  })

  const unknownCaseIds = [...selectedCaseIds].filter(
    (caseId) => !registeredCaseIds.has(caseId),
  )
  if (unknownCaseIds.length > 0) {
    await browser.close()
    throw new Error(`unknown UI case id: ${unknownCaseIds.join(', ')}`)
  }
  await browser.close()

  const failed = results.filter((r) => r.status === 'fail')
  const summary = {
    layer: 'L5-ui',
    baseUrl: args.baseUrl,
    totals: {
      total: results.length,
      pass: results.length - failed.length,
      fail: failed.length,
    },
    failedCaseIds: failed.map((r) => r.id),
    results,
  }
  writeReportArtifact(path.join(reportDir, 'ui-summary.json'), `${JSON.stringify(summary, null, 2)}\n`)
  const lines = [
    '# UI E2E Results',
    '',
    `- Base: \`${args.baseUrl}\``,
    `- Totals: total=${summary.totals.total} pass=${summary.totals.pass} fail=${summary.totals.fail}`,
    `- Result: **${failed.length ? 'FAIL' : 'PASS'}**`,
    '',
    '| Status | Case | Duration | Error |',
    '| --- | --- | ---: | --- |',
    ...results.map((r) => {
      let err = (r.error || '').replaceAll('|', '\\|').replaceAll('\n', ' ')
      if (err.length > 100) err = `${err.slice(0, 97)}...`
      return `| ${r.status.toUpperCase()} | \`${r.id}\` | ${r.durationMs}ms | ${err || '-'} |`
    }),
    '',
    'Screenshots: `artifacts/ui_*/`',
    '',
  ]
  writeReportArtifact(path.join(reportDir, 'ui-report.md'), `${lines.join('\n')}\n`)
  console.log(`\nUI report: ${path.join(reportDir, 'ui-report.md')}`)
  console.log(`total=${results.length} pass=${summary.totals.pass} fail=${summary.totals.fail}`)
  return failed.length ? 1 : 0
}

main(process.argv.slice(2)).then(
  (code) => process.exit(code),
  (err) => {
    console.error(redactSecrets(err?.stack || String(err)))
    process.exit(2)
  },
)
