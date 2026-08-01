#!/usr/bin/env node
/**
 * Playwright UI smoke（L5，可选）。
 *
 * - 稳定路径：路由可达、列表渲染、打开创建卡片、无致命 pageerror
 * - 截图/结果写入 --report-dir（通常是 reports/e2e/<runId>）
 *
 *   node scripts/e2e/ui-smoke.mjs --base-url http://127.0.0.1:5173 --report-dir reports/e2e/<run>
 *   ./scripts/e2e.sh --ui
 */

import { createRequire } from 'node:module'
import {
  existsSync,
  mkdirSync,
  readFileSync,
  readdirSync,
  statSync,
  writeFileSync,
} from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

// Playwright 安装在 frontend/node_modules，从仓库根/scripts 直接 import 会找不到包。
const __dirname = path.dirname(fileURLToPath(import.meta.url))
const requireFromFrontend = createRequire(
  path.resolve(__dirname, '../../frontend/package.json'),
)
const { chromium } = requireFromFrontend('@playwright/test')
const PI_MODEL_NAMES = JSON.parse(
  readFileSync(
    path.resolve(
      __dirname,
      '../../core/src/test/resources/fun/fengwk/kkstudio/core/ai/runtime/persistence/postgresql/pi-model-catalog.json',
    ),
    'utf8',
  ),
).map((model) => model.name)

function parseArgs(argv) {
  const args = {
    baseUrl: process.env.FRONTEND_URL || 'http://127.0.0.1:5173',
    backendUrl: process.env.BACKEND_URL || 'http://127.0.0.1:18081',
    reportDir: process.env.E2E_UI_REPORT_DIR || path.resolve('reports/e2e/ui-standalone'),
    headed: false,
    real: false,
  }
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i]
    if (a === '--base-url') args.baseUrl = argv[++i]
    else if (a === '--backend-url') args.backendUrl = argv[++i]
    else if (a === '--report-dir') args.reportDir = path.resolve(argv[++i])
    else if (a === '--headed') args.headed = true
    else if (a === '--real') args.real = true
    else if (a === '-h' || a === '--help') args.help = true
    else throw new Error(`unknown arg: ${a}`)
  }
  return args
}

async function apiJson(backendUrl, method, requestPath, body) {
  const res = await fetch(`${backendUrl.replace(/\/$/, '')}${requestPath}`, {
    method,
    headers: body === undefined ? undefined : { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  const text = await res.text()
  let json = null
  try {
    json = text ? JSON.parse(text) : null
  } catch {
    json = text
  }
  return { status: res.status, json }
}

async function apiDeleteByName(backendUrl, resource, name) {
  const resourcePath = resource === 'chats' ? '/api/ai/chat' : `/api/ai/catalog/${resource}`
  const listPath = resource === 'chats' ? resourcePath : `${resourcePath}?pageNumber=1&pageSize=100`
  const { json } = await apiJson(backendUrl, 'GET', listPath)
  const list = json?.data?.results || json?.data || []
  const key = resource === 'chats' ? 'title' : 'name'
  const hit = list.find((item) => item[key] === name)
  if (!hit) return false
  await apiJson(backendUrl, 'DELETE', `${resourcePath}/${hit.id}`)
  return true
}

async function requireRealMiniMaxM27(backendUrl) {
  const { json: agentsJson } = await apiJson(backendUrl, 'GET', '/api/ai/catalog/agents?pageNumber=1&pageSize=50')
  const { json: modelsJson } = await apiJson(backendUrl, 'GET', '/api/ai/catalog/models?pageNumber=1&pageSize=50')
  const { json: providersJson } = await apiJson(backendUrl, 'GET', '/api/ai/catalog/providers?pageNumber=1&pageSize=50')
  const agents = agentsJson?.data?.results || []
  const models = modelsJson?.data?.results || []
  const providers = providersJson?.data?.results || []
  const agent = agents.find((candidate) => candidate.name === 'default-assistant')
  const model = models.find((candidate) => Number(candidate.id) === 1)
  const provider = providers.find((candidate) => String(candidate.id) === String(model?.providerId))
  assert(
    provider?.name === 'minimax'
      && model?.name === 'MiniMax-M2.7'
      && String(agent?.modelId) === String(model.id),
    `real UI test must use default-assistant with minimax/MiniMax-M2.7: ${JSON.stringify({ agent, model, provider })}`,
  )
}

function assert(cond, msg) {
  if (!cond) throw new Error(msg || 'assertion failed')
}

function listArtifacts(caseDir, reportDir) {
  if (!existsSync(caseDir)) return []
  return readdirSync(caseDir)
    .filter((n) => statSync(path.join(caseDir, n)).isFile())
    .map((n) => path.relative(reportDir, path.join(caseDir, n)))
    .sort()
}

async function expectVisibleText(page, text) {
  await page.getByText(text).first().waitFor({ state: 'visible', timeout: 30_000 })
}

async function resourceCardTitle(page, name) {
  const title = page.locator('.info-card h3').filter({ hasText: name }).first()
  await title.waitFor({ state: 'visible', timeout: 20_000 })
  const text = (await title.textContent())?.trim()
  assert(text, `resource card title is empty for ${name}`)
  return text
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

async function main(argv) {
  const args = parseArgs(argv)
  if (args.help) {
    console.log('Usage: node scripts/e2e/ui-smoke.mjs --base-url URL --report-dir DIR [--headed]')
    return 0
  }

  const reportDir = args.reportDir
  const artRoot = path.join(reportDir, 'artifacts')
  mkdirSync(path.join(reportDir, 'cases'), { recursive: true })
  mkdirSync(artRoot, { recursive: true })
  if (args.real) await requireRealMiniMaxM27(args.backendUrl)

  const browser = await chromium.launch({ headless: !args.headed })
  const context = await browser.newContext({ viewport: { width: 1440, height: 960 } })
  await context.addInitScript(() => {
    if (!localStorage.getItem('kk-studio.locale')) {
      localStorage.setItem('kk-studio.locale', 'zh-CN')
    }
  })
  const page = await context.newPage()
  const pageErrors = []
  const consoleErrors = []
  page.on('pageerror', (err) => pageErrors.push(String(err)))
  page.on('console', (msg) => {
    if (msg.type() === 'error') consoleErrors.push(msg.text())
  })

  const base = args.baseUrl.replace(/\/$/, '')
  const results = []

  async function goto(p) {
    pageErrors.length = 0
    await page.goto(`${base}${p}`, { waitUntil: 'networkidle', timeout: 30_000 })
    await page.waitForTimeout(400)
  }

  async function shot(caseArt, name) {
    const file = path.join(caseArt, `${name}.png`)
    await page.screenshot({ path: file, fullPage: true })
  }

  async function run(id, title, fn) {
    const t0 = Date.now()
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
        durationMs: Date.now() - t0,
        error: null,
        traceback: null,
        artifactPaths: listArtifacts(caseArt, reportDir),
      }
      results.push(result)
      writeFileSync(path.join(reportDir, 'cases', `${id}.json`), `${JSON.stringify(result, null, 2)}\n`)
      console.log(`PASS ${id}`)
    } catch (err) {
      writeFileSync(path.join(caseArt, 'error.txt'), `${err?.message || err}\n${err?.stack || ''}\n`)
      const result = {
        id,
        level: 'L5',
        title,
        status: 'fail',
        durationMs: Date.now() - t0,
        error: String(err?.message || err),
        traceback: err?.stack || null,
        artifactPaths: listArtifacts(caseArt, reportDir),
      }
      results.push(result)
      writeFileSync(path.join(reportDir, 'cases', `${id}.json`), `${JSON.stringify(result, null, 2)}\n`)
      console.error(`FAIL ${id}: ${result.error}`)
    }
  }

  await run('ui.i18n.language_switch', '右上角切换 English/中文并持久化', async (caseArt) => {
    await goto('/settings')
    await expectVisibleText(page, '设置')
    await page.locator('.topbar-right .locale-selector button', { hasText: 'English' }).click()
    await expectVisibleText(page, 'Setting')
    assert(
      await page.evaluate(() => localStorage.getItem('kk-studio.locale') === 'en-US'),
      'English locale was not persisted',
    )

    await page.reload({ waitUntil: 'networkidle', timeout: 30_000 })
    await expectVisibleText(page, 'Setting')
    await page.locator('.topbar-right .locale-selector button', { hasText: '中文' }).click()
    await expectVisibleText(page, '设置')
    assert(
      await page.evaluate(() => localStorage.getItem('kk-studio.locale') === 'zh-CN'),
      'Chinese locale was not persisted',
    )
    await shot(caseArt, 'language-selector')
    expectNoFatal(pageErrors, consoleErrors)
  })

  await run('ui.chats.page_loads', 'Chats 页可打开且显示新建入口', async (caseArt) => {
    await goto('/chats')
    await shot(caseArt, 'chats')
    expectNoFatal(pageErrors, consoleErrors)
    await expectVisibleText(page, '新建 Chat')
  })

  await run('ui.models.page_loads', 'Models 页渲染全部 Pi seed 模型', async (caseArt) => {
    await goto('/models')
    expectNoFatal(pageErrors, consoleErrors)
    await expectVisibleText(page, '新建 Model')
    for (const modelName of PI_MODEL_NAMES) {
      await expectVisibleText(page, modelName)
    }
    await shot(caseArt, 'models')
  })

  await run('ui.models.open_create_modal', '点击新建 Model 打开编辑模态', async (caseArt) => {
    await goto('/models')
    await page.getByText('新建 Model', { exact: true }).click()
    await page.waitForTimeout(300)
    await shot(caseArt, 'model-create-modal')
    expectNoFatal(pageErrors, consoleErrors)
    // 模态仍包含标题文案
    await expectVisibleText(page, '新建 Model')
  })

  await run('ui.agents.page_loads', 'Agents 页渲染 default-assistant', async (caseArt) => {
    await goto('/agents')
    await shot(caseArt, 'agents')
    expectNoFatal(pageErrors, consoleErrors)
    await expectVisibleText(page, '新建 Agent')
    await expectVisibleText(page, 'default-assistant')
  })

  await run('ui.providers.page_loads', 'Providers 页可打开', async (caseArt) => {
    await goto('/providers')
    await shot(caseArt, 'providers')
    expectNoFatal(pageErrors, consoleErrors)
    await expectVisibleText(page, '新建 Provider')
  })

  await run('ui.environments.page_loads', 'Environments 页可打开', async (caseArt) => {
    await goto('/environments')
    await shot(caseArt, 'environments')
    expectNoFatal(pageErrors, consoleErrors)
    const body = await page.locator('body').innerText()
    assert(body.trim().length > 20, 'environments page body empty')
  })

  await run('ui.harness_settings.page_loads', 'Harness 设置页渲染两张全局策略卡片', async (caseArt) => {
    await goto('/settings')
    expectNoFatal(pageErrors, consoleErrors)
    await expectVisibleText(page, '自动重试')
    await expectVisibleText(page, '实时流缓存')
    const maxLength = page.getByLabel('最大保留事件数')
    await maxLength.waitFor({ state: 'visible', timeout: 30_000 })
    assert((await maxLength.inputValue()) === '5000', 'expected seed realtime Stream maxLength=5000')
    await shot(caseArt, 'harness-settings')
  })

  await run('ui.nav.roundtrip', '主导航往返无崩溃', async (caseArt) => {
    for (const p of ['/chats', '/agents', '/models', '/providers', '/environments', '/chats']) {
      await goto(p)
      expectNoFatal(pageErrors, consoleErrors)
    }
    await shot(caseArt, 'nav-end')
  })

  const stamp = Date.now().toString(36)

  await run('ui.chat.create_flow', 'UI 创建 Chat 并出现在列表', async (caseArt) => {
    const title = `e2e-ui-chat-${stamp}`
    await goto('/chats')
    await page.getByText('新建 Chat', { exact: true }).click()
    await page.getByLabel('Name').fill(title)
    await page.getByRole('button', { name: '确认创建' }).click()
    await page.getByText(title).first().waitFor({ state: 'visible', timeout: 15_000 })
    await shot(caseArt, 'chat-created')
    expectNoFatal(pageErrors, consoleErrors)
    // 清理：API 删除，避免污染后续 run
    await apiDeleteByName(args.backendUrl, 'chats', title)
  })

  await run('ui.model.create_edit_delete_flow', 'UI 创建/编辑/删除 Model', async (caseArt) => {
    const name = `e2e-ui-model-${stamp}`
    const renamed = `${name}-upd`
    await goto('/models')
    await page.getByText('新建 Model', { exact: true }).click()
    await page.getByLabel('Name').fill(name)
    await page.getByRole('button', { name: '确认创建' }).click()
    const modelRef = await resourceCardTitle(page, name)
    await shot(caseArt, 'model-created')

    // 编辑
    await page.getByRole('button', { name: `编辑 ${modelRef}` }).click()
    await page.getByLabel('Name').fill(renamed)
    await page.getByRole('button', { name: '保存修改' }).click()
    const renamedModelRef = await resourceCardTitle(page, renamed)
    await shot(caseArt, 'model-updated')

    // 删除确认
    await page.getByRole('button', { name: `删除 ${renamedModelRef}` }).click()
    await page.getByRole('button', { name: '确认删除' }).click()
    await page.waitForTimeout(800)
    await shot(caseArt, 'model-deleted')
    const body = await page.locator('body').innerText()
    assert(!body.includes(renamedModelRef), `model still visible after delete: ${renamedModelRef}`)
    expectNoFatal(pageErrors, consoleErrors)
    // best-effort cleanup if UI delete failed
    await apiDeleteByName(args.backendUrl, 'models', renamed)
    await apiDeleteByName(args.backendUrl, 'models', name)
  })

  await run('ui.agent.create_edit_delete_flow', 'UI 创建/编辑/删除 Agent', async (caseArt) => {
    const name = `e2e-ui-agent-${stamp}`
    const renamed = `${name}-upd`
    await goto('/agents')
    await page.getByText('新建 Agent', { exact: true }).click()
    await page.getByLabel('Name').fill(name)
    await page.locator('label.form-group', { hasText: 'System Prompt' }).locator('textarea').fill('ui e2e agent')
    await page.getByRole('button', { name: '确认创建' }).click()
    await page.getByText(name, { exact: true }).first().waitFor({ state: 'visible', timeout: 20_000 })
    await shot(caseArt, 'agent-created')

    await page.getByRole('button', { name: `编辑 ${name}` }).click()
    await page.getByLabel('Name').fill(renamed)
    await page.getByRole('button', { name: '保存修改' }).click()
    await page.getByText(renamed, { exact: true }).first().waitFor({ state: 'visible', timeout: 20_000 })
    await shot(caseArt, 'agent-updated')

    await page.getByRole('button', { name: `删除 ${renamed}` }).click()
    await page.getByRole('button', { name: '确认删除' }).click()
    await page.waitForTimeout(800)
    await shot(caseArt, 'agent-deleted')
    const body = await page.locator('body').innerText()
    assert(!body.includes(renamed), `agent still visible after delete: ${renamed}`)
    expectNoFatal(pageErrors, consoleErrors)
    await apiDeleteByName(args.backendUrl, 'agents', renamed)
    await apiDeleteByName(args.backendUrl, 'agents', name)
  })

  await run('ui.model.validation_empty_name', 'Model 空名称前端校验显示错误', async (caseArt) => {
    await goto('/models')
    // 关闭可能残留的模态
    await page.keyboard.press('Escape')
    await page.waitForTimeout(200)
    await page.locator('.cards-grid').getByText('新建 Model', { exact: true }).click()
    await page.locator('form.modal-card, .modal-card').first().waitFor({ state: 'visible', timeout: 10_000 })
    const nameInput = page.getByLabel('Name')
    await nameInput.fill('')
    await page.getByRole('button', { name: '确认创建' }).click()
    await page.waitForTimeout(400)
    await shot(caseArt, 'model-name-error')
    const hasError =
      (await page.locator('.field-error').count()) > 0 ||
      (await page.locator('label.form-group.is-error').count()) > 0 ||
      (await page.locator('.modal-card .is-error, .modal-error, .state-block').count()) > 0
    assert(hasError, 'expected field/modal error for empty model name')
    expectNoFatal(pageErrors, consoleErrors)
  })

  await run('ui.provider.create_edit_delete_flow', 'UI 创建/编辑/删除 Provider', async (caseArt) => {
    const name = `e2e-ui-provider-${stamp}`
    const renamed = `${name}-upd`
    await goto('/providers')
    await page.keyboard.press('Escape')
    await page.locator('.cards-grid').getByText('新建 Provider', { exact: true }).click()
    await page.getByLabel('Name').fill(name)
    await page.getByLabel('Base URL').fill('https://example.com/v1')
    await page.getByLabel('API Key（可选）').fill('sk-e2e-ui-test')
    await page.getByRole('button', { name: '确认创建' }).click()
    await page.getByText(name, { exact: true }).first().waitFor({ state: 'visible', timeout: 20_000 })
    await shot(caseArt, 'provider-created')

    await page.getByRole('button', { name: `编辑 ${name}` }).click()
    await page.getByLabel('Name').fill(renamed)
    // 编辑时不改 key（空 credential 保留）
    await page.getByRole('button', { name: '保存修改' }).click()
    await page.getByText(renamed, { exact: true }).first().waitFor({ state: 'visible', timeout: 20_000 })
    await shot(caseArt, 'provider-updated')

    await page.getByRole('button', { name: `删除 ${renamed}` }).click()
    await page.getByRole('button', { name: '确认删除' }).click()
    await page.waitForTimeout(800)
    await shot(caseArt, 'provider-deleted')
    const body = await page.locator('body').innerText()
    assert(!body.includes(renamed), `provider still visible after delete: ${renamed}`)
    expectNoFatal(pageErrors, consoleErrors)
    await apiDeleteByName(args.backendUrl, 'providers', renamed)
    await apiDeleteByName(args.backendUrl, 'providers', name)
  })

  await run('ui.chat.blank_workspace_shell', '进入 Chat 空白工作区并校验 blank pane shell', async (caseArt) => {
    const title = `e2e-ui-blank-${stamp}`
    // 创建带默认 agent 的 chat，便于 blank 首发
    await goto('/chats')
    await page.getByText('新建 Chat', { exact: true }).click()
    await page.getByLabel('Name').fill(title)
    // 选 default-assistant
    const agentSelect = page.getByLabel('Default Agent')
    await agentSelect.selectOption({ label: 'default-assistant' }).catch(async () => {
      // FormSelect 可能是 native select
      await agentSelect.selectOption({ index: 1 })
    })
    await page.getByRole('button', { name: '确认创建' }).click()
    // createChat 成功后会直接 navigate 到 /chats/:id 空白工作区
    await page.getByText('新对话').first().waitFor({ state: 'visible', timeout: 15_000 })
    await page.getByLabel('给 AI 发送消息').waitFor({ state: 'visible', timeout: 10_000 })
    await shot(caseArt, 'blank-workspace')
    expectNoFatal(pageErrors, consoleErrors)

    // 仅断言 blank shell；真正发消息走可选 real case
    const composer = page.getByLabel('给 AI 发送消息')
    await composer.fill('e2e blank shell probe (not sent if empty then clear)')
    await composer.fill('')
    await apiDeleteByName(args.backendUrl, 'chats', title)
  })

  if (args.real) {
    await run('ui.chat.blank_first_send_real', 'Blank pane 首发真实消息并出现用户气泡', async (caseArt) => {
      const title = `e2e-ui-send-${stamp}`
      await goto('/chats')
      await page.getByText('新建 Chat', { exact: true }).click()
      await page.getByLabel('Name').fill(title)
      const agentSelect = page.getByLabel('Default Agent')
      await agentSelect.selectOption({ label: 'default-assistant' }).catch(async () => {
        await agentSelect.selectOption({ index: 1 })
      })
      await page.getByRole('button', { name: '确认创建' }).click()
      await page.getByText('新对话').first().waitFor({ state: 'visible', timeout: 15_000 })
      const composer = page.getByLabel('给 AI 发送消息')
      await composer.fill('只回复单词 OK，不要调用工具。')
      await page.getByRole('button', { name: '发送消息' }).click()
      // 用户消息应进入 timeline；assistant 成功与否取决于 Provider
      await page.getByText('只回复单词 OK，不要调用工具。').first().waitFor({ state: 'visible', timeout: 30_000 })
      const status = page.getByLabel('会话状态')
      await status.getByText('agent:default-assistant', { exact: true }).waitFor({ state: 'visible', timeout: 30_000 })
      await status.getByText('minimax/MiniMax-M2.7 · high', { exact: true }).waitFor({ state: 'visible', timeout: 30_000 })
      await shot(caseArt, 'first-send-user')
      // 等待一轮结束（最长 90s）
      let assistantText = ''
      for (let i = 0; i < 90; i++) {
        const assistantTurns = page.locator('.thread-turn-assistant')
        const count = await assistantTurns.count()
        if (count > 0) {
          assistantText = (await assistantTurns.last().innerText()).trim()
          if (/\bOK\b/i.test(assistantText)) {
            break
          }
          if (/助手回复失败|FAILED|失败/i.test(assistantText)) {
            throw new Error(`real assistant response failed: ${assistantText}`)
          }
        }
        await page.waitForTimeout(1000)
      }
      await shot(caseArt, 'first-send-done')
      assert(/\bOK\b/i.test(assistantText), `expected assistant reply containing OK, got: ${assistantText || '(empty)'}`)
      expectNoFatal(pageErrors, consoleErrors)
      await apiDeleteByName(args.backendUrl, 'chats', title)
    })
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
  writeFileSync(path.join(reportDir, 'ui-summary.json'), `${JSON.stringify(summary, null, 2)}\n`)
  const lines = [
    '# UI Smoke Results',
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
  writeFileSync(path.join(reportDir, 'ui-report.md'), `${lines.join('\n')}\n`)
  console.log(`\nUI report: ${path.join(reportDir, 'ui-report.md')}`)
  console.log(`total=${results.length} pass=${summary.totals.pass} fail=${summary.totals.fail}`)
  return failed.length ? 1 : 0
}

main(process.argv.slice(2)).then(
  (code) => process.exit(code),
  (err) => {
    console.error(err)
    process.exit(2)
  },
)
