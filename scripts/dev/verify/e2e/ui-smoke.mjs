#!/usr/bin/env node
/**
 * Playwright UI E2E 矩阵（L5，可选）。
 *
 * - 页面 smoke：路由可达、列表渲染、打开创建卡片、无致命 pageerror
 * - 交互矩阵：高价值用户路径、状态组合与浏览器布局/Selection 边界
 * - 截图/结果写入 --report-dir（通常是 reports/e2e/<runId>）
 *
 *   node scripts/dev/verify/e2e/ui-smoke.mjs --base-url http://127.0.0.1:5173 --report-dir reports/e2e/<run>
 *   scripts/dev/verify/e2e/run.sh --ui
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
import { assertProviderExecutionBoundary } from './lib/provider-boundary.mjs'
import { missingUiCapabilities } from './lib/ui-capabilities.mjs'
import { MINIMAX_ANTHROPIC_M3 } from './lib/real-models.mjs'
import { createDurationTimer } from './lib/time.mjs'
import {
  assertReadOnlyZeroFooter,
  waitForSettledAssistantText,
} from './ui/assertions.mjs'
import { runComposerMatrix } from './ui/composer-matrix.mjs'
import { REPO_ROOT } from '../../lib/repo-root.mjs'

// Playwright 安装在 frontend/node_modules，直接 import 会找不到包。
const requireFromFrontend = createRequire(
  path.join(REPO_ROOT, 'frontend/package.json'),
)
const { chromium } = requireFromFrontend('@playwright/test')
const PI_MODEL_NAMES = JSON.parse(
  readFileSync(
    path.join(
      REPO_ROOT,
      'platform/src/test/resources/fun/fengwk/kkstudio/platform/harness/persistence/postgresql/pi-model-catalog.json',
    ),
    'utf8',
  ),
).map((model) => model.name)
const REAL_UI_MODEL = MINIMAX_ANTHROPIC_M3
const REAL_UI_MODEL_ID = `${REAL_UI_MODEL.providerName}/${REAL_UI_MODEL.modelName}`
const REAL_UI_MODEL_SELECTOR_LABEL = `${REAL_UI_MODEL_ID} · ${REAL_UI_MODEL.variant}`

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
  const resourcePath = resource === 'chats' ? '/api/ai/chats' : `/api/ai/catalog/${resource}`
  const listPath = resource === 'chats' ? resourcePath : `${resourcePath}?pageNumber=1&pageSize=100`
  const { json } = await apiJson(backendUrl, 'GET', listPath)
  const list = json?.data?.results || json?.data || []
  const key = resource === 'chats' ? 'title' : 'name'
  const hit = list.find((item) => item[key] === name)
  if (!hit) return false
  let deletePath
  if (resource === 'chats') {
    deletePath =
      `${resourcePath}/${encodeURIComponent(hit.id)}`
      + `?expectedVersion=${encodeURIComponent(hit.version)}`
  } else if (resource === 'models') {
    deletePath =
      `${resourcePath}?providerName=${encodeURIComponent(hit.providerName)}`
      + `&modelName=${encodeURIComponent(hit.name)}`
      + `&expectedVersion=${encodeURIComponent(hit.version)}`
  } else {
    deletePath =
      `${resourcePath}/${encodeURIComponent(hit.name)}`
      + `?expectedVersion=${encodeURIComponent(hit.version)}`
  }
  await apiJson(backendUrl, 'DELETE', deletePath)
  return true
}

async function requireRealMiniMaxM3(backendUrl, modelDef = MINIMAX_ANTHROPIC_M3) {
  const { json: modelsJson } = await apiJson(backendUrl, 'GET', '/api/ai/catalog/models?pageNumber=1&pageSize=50')
  const { json: providersJson } = await apiJson(backendUrl, 'GET', '/api/ai/catalog/providers?pageNumber=1&pageSize=50')
  const models = modelsJson?.data?.results || []
  const providers = providersJson?.data?.results || []
  const model = models.find(
    (candidate) =>
      candidate.providerName === modelDef.providerName && candidate.name === modelDef.modelName,
  )
  const provider = providers.find((candidate) => candidate.name === model?.providerName)
  assert(
    provider?.name === modelDef.providerName
      && provider.providerType === modelDef.providerType
      && provider.configured === true
      && typeof provider.baseUrl === 'string'
      && provider.baseUrl.trim().length > 0
      && model?.name === modelDef.modelName
      && model.config?.variants?.some((variant) => variant.id === modelDef.variant),
    `real UI test requires configured ${modelDef.providerName}/${modelDef.modelName} with variant ${modelDef.variant}`,
  )
}

async function requireProviderExecutionBoundary(backendUrl, real) {
  if (real) return
  const { json } = await apiJson(
    backendUrl,
    'GET',
    '/api/ai/catalog/providers?pageNumber=1&pageSize=50',
  )
  const providers = json?.data?.results || []
  assertProviderExecutionBoundary({ real: false, providers })
  console.log('Provider boundary: free UI mode confirmed every provider is unconfigured')
}

function assert(cond, msg) {
  if (!cond) throw new Error(msg || 'assertion failed')
}

function escapeRegExp(value) {
  return String(value).replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

function normalizeBox(box) {
  if (!box) return null
  return {
    left: box.x,
    top: box.y,
    right: box.x + box.width,
    bottom: box.y + box.height,
  }
}

function assertBoxWithin(inner, outer, label) {
  const tolerance = 0.5
  const innerEdges = normalizeBox(inner)
  const outerEdges = normalizeBox(outer)
  assert(innerEdges && outerEdges, `${label} bounding box is missing`)
  assert(
    innerEdges.left >= outerEdges.left - tolerance
      && innerEdges.top >= outerEdges.top - tolerance
      && innerEdges.right <= outerEdges.right + tolerance
      && innerEdges.bottom <= outerEdges.bottom + tolerance,
    `${label} bounding box escapes its option card`,
  )
}

function assertBoxesDoNotOverlap(left, right, label) {
  const tolerance = 0.5
  const leftEdges = normalizeBox(left)
  const rightEdges = normalizeBox(right)
  assert(leftEdges && rightEdges, `${label} bounding box is missing`)
  const overlaps =
    leftEdges.left < rightEdges.right - tolerance
    && leftEdges.right > rightEdges.left + tolerance
    && leftEdges.top < rightEdges.bottom - tolerance
    && leftEdges.bottom > rightEdges.top + tolerance
  assert(!overlaps, `${label} bounding boxes overlap`)
}

async function assertLeadingCreateCard(page, action, label, gridSelector = '.cards-grid') {
  // Resource creation belongs to the first card in the content grid, not the subbar.
  // 内容网格的容器类按页面不同（资源页 .cards-grid、Canvas 库 .project-grid），因此由调用方给出真实容器。
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
  assert(layout.first && layout.card && layout.visible && layout.contained,
    `${label} must be the visible leading create card: ${JSON.stringify(layout)}`)
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

/**
 * 当前自定义 Select 通用选择 helper：trigger 是 .ui-select-trigger button，
 * aria-controls 指向 role=listbox。点击 trigger 展开，解析 aria-controls 找到对应
 * listbox，再按 accessible name 精确点击 option。禁止调用 native selectOption。
 */
async function selectCustomOption(page, trigger, optionName) {
  await trigger.click()
  const listboxId = await trigger.getAttribute('aria-controls')
  assert(listboxId, `Select trigger ${await trigger.getAttribute('aria-label')} lacks aria-controls`)
  // 产品 Select.tsx 将 listboxId 固定为 `ui-select-listbox-` + sanitized [A-Za-z0-9_-] instanceId，
  // 该字符集无需 CSS.escape（Node 22 不保证 CSS global）。
  assert(
    /^ui-select-listbox-[A-Za-z0-9_-]+$/.test(listboxId),
    `Select aria-controls has unexpected shape: ${listboxId}`,
  )
  const listbox = page.locator(`#${listboxId}`)
  await listbox.waitFor({ state: 'visible', timeout: 10_000 })
  const option = listbox.getByRole('option', { name: optionName, exact: true })
  await option.waitFor({ state: 'visible', timeout: 10_000 })
  await option.click()
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
    console.log(
      'Usage: node scripts/dev/verify/e2e/ui-smoke.mjs --base-url URL --report-dir DIR'
        + ' [--headed] [--real] [--with-tools] [--with-canvas-function] [--daemon-env NAME] [--only CASE_ID]...',
    )
    return 0
  }

  await requireProviderExecutionBoundary(args.backendUrl, args.real)

  const reportDir = args.reportDir
  const artRoot = path.join(reportDir, 'artifacts')
  mkdirSync(path.join(reportDir, 'cases'), { recursive: true })
  mkdirSync(artRoot, { recursive: true })
  const apiCtx = {
    call: (method, requestPath, body) =>
      apiJson(args.backendUrl, method, requestPath, body),
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
        await page.waitForTimeout(400)
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
      writeFileSync(path.join(reportDir, 'cases', `${id}.json`), `${JSON.stringify(result, null, 2)}\n`)
      console.log(`PASS ${id}`)
    } catch (err) {
      try {
        await page.screenshot({
          path: path.join(caseArt, 'failure.png'),
          fullPage: true,
        })
        writeFileSync(
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
        writeFileSync(
          path.join(caseArt, 'failure-artifact-error.txt'),
          `${artifactError?.message || artifactError}\n`,
        )
      }
      writeFileSync(path.join(caseArt, 'error.txt'), `${err?.message || err}\n${err?.stack || ''}\n`)
      const result = {
        id,
        level: 'L5',
        title,
        status: 'fail',
        durationMs: elapsed(),
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
    await goto('/chats')
    await expectVisibleText(page, '新建 Chat')
    const localeTrigger = page.locator('.topbar-right').getByRole('button', {
      name: '语言: 中文',
    })
    await selectCustomOption(page, localeTrigger, 'English')
    await expectVisibleText(page, 'Create Chat')
    assert(
      await page.evaluate(() => localStorage.getItem('kk-studio.locale') === 'en-US'),
      'English locale was not persisted',
    )

    await page.reload({ waitUntil: 'networkidle', timeout: 30_000 })
    await expectVisibleText(page, 'Create Chat')
    await selectCustomOption(
      page,
      page.locator('.topbar-right').getByRole('button', {
        name: 'Language: English',
      }),
      '中文',
    )
    await expectVisibleText(page, '新建 Chat')
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
    await page.getByRole('button', { name: '编辑 default-assistant' }).click()
    const agentModal = page.locator('form.resource-modal-card')
    await agentModal.waitFor({ state: 'visible', timeout: 10_000 })
    const detailedOptions = agentModal.locator('.capability-option-detailed')
    await detailedOptions.first().waitFor({ state: 'visible', timeout: 10_000 })
    const capabilityContainers = agentModal.locator('.capability-options')
    const capabilityContainerCount = await capabilityContainers.count()
    let detailedOptionCount = 0

    for (let containerIndex = 0; containerIndex < capabilityContainerCount; containerIndex++) {
      const container = capabilityContainers.nth(containerIndex)
      const options = container.locator('.capability-option-detailed')
      const optionCount = await options.count()
      detailedOptionCount += optionCount
      const optionBoxes = []

      for (let optionIndex = 0; optionIndex < optionCount; optionIndex++) {
        const option = options.nth(optionIndex)
        const cardBox = await option.boundingBox()
        const headingBox = await option.locator('.capability-option-heading').boundingBox()
        assertBoxWithin(headingBox, cardBox, `capability option ${containerIndex}:${optionIndex} heading`)

        const description = option.locator('.capability-option-description')
        if (await description.count() > 0) {
          const descriptionBox = await description.boundingBox()
          assertBoxWithin(
            descriptionBox,
            cardBox,
            `capability option ${containerIndex}:${optionIndex} description`,
          )
        }
        optionBoxes.push(cardBox)
      }

      for (let optionIndex = 0; optionIndex < optionBoxes.length - 1; optionIndex++) {
        assertBoxesDoNotOverlap(
          optionBoxes[optionIndex],
          optionBoxes[optionIndex + 1],
          `adjacent capability options in container ${containerIndex} at indexes ${optionIndex} and ${optionIndex + 1}`,
        )
      }
    }
    assert(detailedOptionCount > 0, 'default-assistant edit modal has no detailed capability options')
    await shot(caseArt, 'agent-capability-modal')
    await agentModal.getByRole('button', { name: '关闭' }).click()
    await agentModal.waitFor({ state: 'detached', timeout: 10_000 })
  })

  await run('ui.providers.page_loads', 'Providers 页可打开', async (caseArt) => {
    await goto('/providers')
    await shot(caseArt, 'providers')
    expectNoFatal(pageErrors, consoleErrors)
    await expectVisibleText(page, '新建 Provider')
  })

  await run('ui.environments.page_loads', 'Environments 页可打开', async (caseArt) => {
    await goto('/environments')
    await page.locator('.environment-list').waitFor({
      state: 'visible',
      timeout: 15_000,
    })
    const createButton = page.getByRole('button', { name: /创建环境|Create Environment/ })
    await createButton.waitFor({ state: 'visible' })
    await assertLeadingCreateCard(page, createButton, 'Create Environment')
    const environmentCards = page.locator('.environment-card')
    assert(await environmentCards.count() > 0, 'environments page has no cards')
    for (const card of await environmentCards.all()) {
      const actions = card.locator('.chat-card-foot button')
      assert(await actions.count() === 4, 'environment card must expose Copy Token, Manage, Rotate Token and Delete')
      assert(
        await actions.filter({ hasText: /重新生成 Token|Rotate Token/ }).count() === 1,
        'environment card must expose one Rotate Token action',
      )
    }
    await shot(caseArt, 'environments')
    expectNoFatal(pageErrors, consoleErrors)
    const body = await page.locator('body').innerText()
    assert(body.trim().length > 20, 'environments page body empty')
  })

  await run('ui.canvas.page_loads', 'Canvas 库与编辑器可打开', async (caseArt) => {
    await goto('/canvas')
    await expectVisibleText(page, '你的画布')
    // Library 路由保留全局顶栏与 library view 语义容器。
    assert(
      await page.locator('#libraryView').isVisible(),
      'canvas library view must be visible',
    )
    assert(
      await page.locator('.library-view').isVisible(),
      'canvas library-view class must be visible',
    )
    assert(
      await page.locator('.topbar').isVisible(),
      'canvas library must keep the global topbar',
    )
    const createButton = page.getByRole('button', {
      name: /^(Create a new canvas|Create new canvas|创建新画布)$/,
    })
    await createButton.waitFor({ state: 'visible' })
    await assertLeadingCreateCard(page, createButton, 'Create Canvas', '.project-grid')
    await shot(caseArt, 'canvas-library')
    const existingCanvas = page.locator('.project-card:not(.create-card)').first()
    if (await existingCanvas.count() > 0) {
      await existingCanvas.click()
    } else {
      await createButton.click()
    }
    // 创建路径在 mutation 成功后导航，因此先等编辑器挂载再断言 URL。
    await page.locator('#canvasStage').waitFor({ state: 'visible', timeout: 15_000 })
    assert(
      /^\/canvas\/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(new URL(page.url()).pathname),
      `canvas editor pathname invalid: ${new URL(page.url()).pathname}`,
    )

    // 编辑器沉浸：无全局顶栏、挂 canvas-immersive class、返回键、保存状态与版本号。
    assert(
      await page.locator('#editorView').isVisible(),
      'canvas editor view must be visible',
    )
    assert(
      await page.locator('.topbar').count() === 0,
      'canvas editor must not render the global topbar',
    )
    assert(
      await page.locator('.app-frame.canvas-immersive').count() === 1,
      'canvas editor must carry the canvas-immersive root class',
    )
    const backButton = page.locator('.canvas-back-button.sidebar-icon-btn')
    assert(
      await backButton.count() === 1,
      'canvas back control must be rendered exactly once',
    )
    assert(
      await backButton.getAttribute('href') === '/canvas',
      'canvas back control href must point to /canvas',
    )
    assert(
      await page.locator('#saveState').isVisible(),
      'canvas editor save state indicator must be visible',
    )
    const versionText = (await page.locator('.version-pill').textContent())?.trim() ?? ''
    assert(
      /^v\d+$/.test(versionText),
      `canvas editor version pill invalid: ${versionText}`,
    )

    // 缩放控制真实交互：初始 readout 处于 25..200 整数，Zoom in 严格递增，Zoom out 严格递减，Fit all 有效，Reset 恢复 100%。
    const resetZoom = page.getByRole('button', {
      name: /^(Reset zoom to 100%|重置缩放为 100%)$/,
    })
    await resetZoom.waitFor({ state: 'visible' })
    const initialZoomText = (await resetZoom.textContent())?.trim() ?? ''
    const initialZoom = Number.parseInt(initialZoomText, 10)
    assert(
      Number.isInteger(initialZoom) && initialZoom >= 25 && initialZoom <= 200,
      `canvas initial zoom is invalid: ${initialZoomText}`,
    )

    const zoomIn = page.getByRole('button', {
      name: /^(Zoom in|放大)$/,
    })
    await zoomIn.click()
    await page.waitForFunction((prev) => {
      const button = document.querySelector(
        '[aria-label="Reset zoom to 100%"], [aria-label="重置缩放为 100%"]',
      )
      const current = Number.parseInt(button?.textContent?.trim() ?? '', 10)
      return Number.isInteger(current) && current > prev
    }, initialZoom)
    const zoomedInText = (await resetZoom.textContent())?.trim() ?? ''
    const zoomedInZoom = Number.parseInt(zoomedInText, 10)
    assert(
      zoomedInZoom > initialZoom,
      `zoom in failed to strictly increase readout: initial=${initialZoom}, after=${zoomedInZoom}`,
    )

    const zoomOut = page.getByRole('button', {
      name: /^(Zoom out|缩小)$/,
    })
    await zoomOut.click()
    await page.waitForFunction((prev) => {
      const button = document.querySelector(
        '[aria-label="Reset zoom to 100%"], [aria-label="重置缩放为 100%"]',
      )
      const current = Number.parseInt(button?.textContent?.trim() ?? '', 10)
      return Number.isInteger(current) && current < prev
    }, zoomedInZoom)
    const zoomedOutText = (await resetZoom.textContent())?.trim() ?? ''
    const zoomedOutZoom = Number.parseInt(zoomedOutText, 10)
    assert(
      zoomedOutZoom < zoomedInZoom,
      `zoom out failed to strictly decrease readout: before=${zoomedInZoom}, after=${zoomedOutZoom}`,
    )

    const fitAll = page.getByRole('button', {
      name: /^(Fit all content|适应全部内容)$/,
    })
    await fitAll.click()
    const fittedZoomText = (await resetZoom.textContent())?.trim() ?? ''
    const fittedZoom = Number.parseInt(fittedZoomText, 10)
    assert(
      Number.isInteger(fittedZoom) && fittedZoom >= 25 && fittedZoom <= 200,
      `canvas fitted zoom is invalid: ${fittedZoomText}`,
    )

    await resetZoom.click()
    await page.waitForFunction(() => {
      const button = document.querySelector(
        '[aria-label="Reset zoom to 100%"], [aria-label="重置缩放为 100%"]',
      )
      return button?.textContent?.trim() === '100%'
    })
    assert(
      (await resetZoom.textContent())?.trim() === '100%',
      'reset zoom button text must be 100%',
    )
    await shot(caseArt, 'canvas-controls')

    // Add 菜单行为：点击展开并包含 6 个候选 action，按 Escape 关闭。
    const addLauncher = page.getByRole('button', {
      name: /^(Add resource or Function|添加资源或 Function)$/,
    })
    await addLauncher.click()
    const addMenu = page.getByRole('menu')
    await addMenu.waitFor({ state: 'visible', timeout: 10_000 })
    const menuItems = addMenu.getByRole('menuitem')
    const actionList = await menuItems.evaluateAll((items) =>
      items.map((item) => item.getAttribute('data-add-action')),
    )
    const expectedActions = [
      'image-resource',
      'video-resource',
      'audio-resource',
      'text-resource',
      'image-function',
      'video-function',
    ]
    assert(
      JSON.stringify(actionList) === JSON.stringify(expectedActions),
      `Add menu actions mismatch: expected ${JSON.stringify(expectedActions)}, got ${JSON.stringify(actionList)}`,
    )
    await shot(caseArt, 'canvas-add-menu')
    await page.keyboard.press('Escape')
    await addMenu.waitFor({ state: 'hidden', timeout: 10_000 })

    // 确定性节点交互流：打开 Add 菜单 -> 点击 text-resource -> 打开非模态文本编辑浮层 -> 关闭浮层。
    await addLauncher.click()
    await addMenu.waitFor({ state: 'visible', timeout: 10_000 })
    const textResourceItem = addMenu.locator('[data-add-action="text-resource"]')
    await textResourceItem.click()
    const textEditor = page.locator('.canvas-text-editor.create')
    await textEditor.waitFor({ state: 'visible', timeout: 10_000 })
    const nameInput = textEditor.getByRole('textbox', {
      name: /^(Name|名称)$/,
    })
    const prefilledName = await nameInput.inputValue()
    assert(
      typeof prefilledName === 'string' && prefilledName.trim().length > 0,
      `text editor prefilled name is empty: "${prefilledName}"`,
    )
    const closeEditorButton = textEditor.getByRole('button', {
      name: /^(Close text editor|关闭文本编辑)$/,
    })
    await closeEditorButton.click()
    await textEditor.waitFor({ state: 'detached', timeout: 10_000 })
    assert(
      await page.locator('.canvas-text-editor').count() === 0,
      'canvas text editor overlay must be detached after closing',
    )
    assert(
      await page.locator('#canvasStage').isVisible(),
      'canvas stage must remain visible after closing text editor',
    )

    // 显式断言已移除的 Canvas Agent / Chat 面板与组件彻底不存在。
    assert(
      await page.locator('#agentPanel').count() === 0,
      'removed #agentPanel must not exist',
    )
    assert(
      await page.locator('.canvas-agent-dock').count() === 0,
      'removed .canvas-agent-dock must not exist',
    )
    assert(
      await page.locator('[data-testid="agent-dock"]').count() === 0,
      'removed agent-dock testid must not exist',
    )
    assert(
      await page.locator('.thread-panel').count() === 0,
      'removed .thread-panel must not exist in canvas',
    )
    assert(
      await page.locator('.chat-shell.thread-panel').count() === 0,
      'removed .chat-shell.thread-panel must not exist in canvas',
    )
    assert(
      await page.getByRole('button', {
        name: /^(Toggle the Chat panel|切换对话面板)$/,
      }).count() === 0,
      'removed Chat panel toggle button must not exist',
    )
    assert(
      await page.locator('#editorView').getByLabel('给 AI 发送消息').count() === 0,
      'canvas editor must not contain chat composer',
    )
    assert(
      await page.locator('#editorView .thread-dock').count() === 0,
      'canvas editor must not contain thread-dock',
    )

    // 页面重载与历史导航回退/前进验证。
    await page.reload({ waitUntil: 'networkidle' })
    await page.locator('#canvasStage').waitFor({ state: 'visible', timeout: 15_000 })
    await page.waitForFunction(() => {
      const button = document.querySelector(
        '[aria-label="Reset zoom to 100%"], [aria-label="重置缩放为 100%"]',
      )
      return button?.textContent?.trim() === '100%'
    })
    assert(
      /^\/canvas\/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(new URL(page.url()).pathname),
      `canvas reload pathname invalid: ${new URL(page.url()).pathname}`,
    )
    await page.goBack({ waitUntil: 'networkidle' })
    assert(new URL(page.url()).pathname === '/canvas', `canvas back pathname invalid: ${page.url()}`)
    await expectVisibleText(page, '你的画布')
    await page.goForward({ waitUntil: 'networkidle' })
    assert(
      /^\/canvas\/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(new URL(page.url()).pathname),
      `canvas forward pathname invalid: ${new URL(page.url()).pathname}`,
    )
    await page.locator('#canvasStage').waitFor({ state: 'visible', timeout: 15_000 })
    await shot(caseArt, 'canvas-editor')
    expectNoFatal(pageErrors, consoleErrors)
  })

  await run(
    'ui.canvas.function_fake_flow',
    'Canvas：编辑保存 Function 参数→重开读回→fake 运行产出资源→第二节点引用→重开校验引用与参数→再次运行',
    async (caseArt) => {
      // 付费边界：本 case 只允许绑定 fake-image，提交任何运行前都必须按真实快照断言函数身份，
      // 绝不触碰 gpt-image-2 / seedance2.0* 等付费 Function。
      const catalog = await apiJson(args.backendUrl, 'GET', '/api/canvas-functions')
      const fakeImage = (catalog.json?.data || []).find((item) => item.name === 'fake-image')
      assert(
        fakeImage?.available === true,
        `fake-image Canvas Function must be available (run with --with-canvas-function): ${JSON.stringify(catalog.json?.data?.map((f) => [f.name, f.available]))}`,
      )

      let canvasId = null
      const flowStamp = Date.now().toString(36)
      const snapshot = async () => {
        const { status, json } = await apiJson(args.backendUrl, 'GET', `/api/canvases/${canvasId}`)
        assert(status === 200, `canvas snapshot failed: ${status}`)
        return json.data
      }
      const nodeById = (snap, nodeId) => snap.nodes.find((node) => node.id === nodeId)
      const nodeElement = (nodeId) => page.locator(`.react-flow__node[data-id="${nodeId}"]`)
      const panel = page.locator('.generation-panel')
      const promptInput = panel.locator('textarea.generation-prompt-input')
      const modelSelect = panel.locator('select[aria-label="模型"], select[aria-label="Model"]')
      const ratioSelect = panel.locator('select[aria-label="ratio"]')

      const addImageFunctionNode = async (expectedNodes) => {
        await page.locator('.canvas-tool-rail .dock-add').click()
        const menu = page.getByRole('menu')
        await menu.locator('[data-add-action="image-function"]').click()
        await page.waitForFunction(
          (count) => document.querySelectorAll('.react-flow__node').length === count,
          expectedNodes,
          { timeout: 15_000 },
        )
      }
      const selectNode = async (nodeId) => {
        await nodeElement(nodeId).click()
        await panel.waitFor({ state: 'visible', timeout: 10_000 })
      }
      // 运行入口只有真实右键菜单；提交前断言绑定的是 fake-image。
      const runNode = async (nodeId) => {
        const bound = nodeById(await snapshot(), nodeId)
        assert(
          bound?.function?.name === 'fake-image',
          `refusing to run non-fake Canvas Function: ${JSON.stringify(bound?.function)}`,
        )
        await nodeElement(nodeId).click({ button: 'right' })
        const menu = page.locator('.canvas-context-menu')
        await menu.waitFor({ state: 'visible', timeout: 10_000 })
        await menu.getByRole('menuitem', { name: /^(Run|运行)$/ }).click()
      }
      const awaitRunTerminal = async (nodeId) => {
        for (let attempt = 0; attempt < 120; attempt += 1) {
          const run = nodeById(await snapshot(), nodeId)?.run
          if (run && ['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(run.status)) {
            return run
          }
          await page.waitForTimeout(500)
        }
        throw new Error(`Canvas Function run did not settle for node ${nodeId}`)
      }
      // 资源槽位由成功发布后的快照驱动渲染，等待 DOM 出现即证明 UI 收敛链路可用。
      const awaitResourceSlot = async (nodeId, alias) => {
        await nodeElement(nodeId)
          .locator(`.canvas-resource-slot[data-resource-kind="IMAGE"][aria-label="${alias}"]`)
          .waitFor({ state: 'visible', timeout: 30_000 })
      }

      try {
        await goto('/canvas')
        await page.getByRole('button', { name: /^(Create a new canvas|Create new canvas|创建新画布)$/ }).click()
        await page.locator('#canvasStage').waitFor({ state: 'visible', timeout: 15_000 })
        canvasId = new URL(page.url()).pathname.split('/').pop()
        assert(
          /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(canvasId),
          `canvas id is not canonical: ${canvasId}`,
        )

        // 1. 创建图片生成节点：必须绑定 fake-image，UI 与真实快照一致。
        await addImageFunctionNode(1)
        let snap = await snapshot()
        const nodeAId = snap.nodes[0].id
        const nodeAName = snap.nodes[0].name
        await selectNode(nodeAId)
        assert(await modelSelect.inputValue() === 'fake-image', 'Canvas image node must bind fake-image')
        assert(nodeById(snap, nodeAId).function.name === 'fake-image', JSON.stringify(nodeById(snap, nodeAId).function))

        // 2. 编辑 prompt/ratio 并等待自动保存落库。
        const promptText = `e2e-canvas-prompt-${flowStamp}`
        await promptInput.fill(promptText)
        await ratioSelect.selectOption('16:9')
        await page.waitForFunction(
          (text) => document.querySelector('#saveState')?.textContent?.trim() !== '保存中…',
          promptText,
          { timeout: 15_000 },
        )
        let saved = null
        for (let attempt = 0; attempt < 40; attempt += 1) {
          saved = nodeById(await snapshot(), nodeAId)
          if (saved?.function?.args?.prompt === promptText && saved.function.args.ratio === '16:9') {
            break
          }
          await page.waitForTimeout(250)
        }
        assert(
          saved?.function?.args?.prompt === promptText && saved.function.args.ratio === '16:9',
          `Canvas Function args were not persisted: ${JSON.stringify(saved?.function)}`,
        )
        assert(
          /^v[1-9]\d*$/.test((await page.locator('.version-pill').textContent())?.trim() ?? ''),
          'Canvas revision did not advance after editing',
        )

        // 3. 重开画布读回参数。
        await page.reload({ waitUntil: 'networkidle' })
        await page.locator('#canvasStage').waitFor({ state: 'visible', timeout: 15_000 })
        await selectNode(nodeAId)
        assert(await promptInput.inputValue() === promptText, 'prompt did not survive reload')
        assert(await ratioSelect.inputValue() === '16:9', 'ratio did not survive reload')

        // 4. 运行首个节点并验证成功资源。
        await runNode(nodeAId)
        const runA = await awaitRunTerminal(nodeAId)
        assert(runA.status === 'SUCCEEDED', `first Canvas run failed: ${JSON.stringify(runA)}`)
        const resourceAliasA = `@${nodeAName}_0`
        await awaitResourceSlot(nodeAId, resourceAliasA)
        saved = nodeById(await snapshot(), nodeAId)
        assert(
          saved.resources.length === 1
            && saved.resources[0].kind === 'IMAGE'
            && typeof saved.resources[0].blobId === 'string',
          `first run did not publish an IMAGE resource: ${JSON.stringify(saved.resources)}`,
        )
        await shot(caseArt, 'canvas-first-run')

        // 5. 重开后新增第二节点，引用首个节点的资源。
        await page.reload({ waitUntil: 'networkidle' })
        await page.locator('#canvasStage').waitFor({ state: 'visible', timeout: 15_000 })
        await addImageFunctionNode(2)
        snap = await snapshot()
        assert(snap.nodes.length === 2, `expected two nodes after adding the second: ${snap.nodes.length}`)
        const nodeB = snap.nodes.find((node) => node.id !== nodeAId)
        await selectNode(nodeB.id)
        await page.getByRole('button', { name: `插入参考 ${resourceAliasA}` }).click()
        assert(
          await page.locator('.generation-attached-chip').count() === 1,
          'reference chip was not rendered after inserting the reference',
        )
        for (let attempt = 0; attempt < 40; attempt += 1) {
          const current = nodeById(await snapshot(), nodeB.id)
          const refs = current?.function?.args?.references
          if (Array.isArray(refs) && refs.length === 1) {
            break
          }
          await page.waitForTimeout(250)
        }
        const referenced = nodeById(await snapshot(), nodeB.id)
        assert(
          referenced.function.args.references?.length === 1
            && referenced.function.args.references[0].type === 'resource'
            && referenced.function.args.references[0].nodeId === nodeAId
            && referenced.function.args.references[0].index === 0,
          `reference was not persisted as a canonical resource reference: ${JSON.stringify(referenced.function.args)}`,
        )

        // 6. 重开画布校验引用 UUID 与参数，并确认引用边仍存在。
        await page.reload({ waitUntil: 'networkidle' })
        await page.locator('#canvasStage').waitFor({ state: 'visible', timeout: 15_000 })
        snap = await snapshot()
        const reference = snap.references.find((item) => item.targetNodeId === nodeB.id)
        assert(
          snap.references.length === 1
            && reference?.sourceNodeId === nodeAId
            && reference?.targetNodeId === nodeB.id
            && reference?.index === 0
            && reference?.canvasId === canvasId,
          `canonical reference projection is wrong after reload: ${JSON.stringify(snap.references)}`,
        )
        const reloadedB = nodeById(snap, nodeB.id)
        assert(
          reloadedB.function.name === 'fake-image'
            && reloadedB.function.args.ratio === 'AUTO'
            && reloadedB.function.args.references?.[0]?.nodeId === nodeAId,
          `second node args did not survive reload: ${JSON.stringify(reloadedB.function.args)}`,
        )
        await selectNode(nodeB.id)
        assert(
          (await page.locator('.generation-attached-chip').allInnerTexts()).some((text) => text.includes(resourceAliasA)),
          'reference chip is missing after reload',
        )
        await page.locator(`.react-flow__edge[data-id="${nodeAId}->${nodeB.id}"]`).waitFor({
          state: 'visible',
          timeout: 10_000,
        })
        await shot(caseArt, 'canvas-reference-reload')

        // 7. 运行第二节点，验证带引用的 fake 运行成功产出资源。
        await runNode(nodeB.id)
        const runB = await awaitRunTerminal(nodeB.id)
        assert(runB.status === 'SUCCEEDED', `referenced Canvas run failed: ${JSON.stringify(runB)}`)
        await awaitResourceSlot(nodeB.id, `@${nodeB.name}_0`)
        await page.reload({ waitUntil: 'networkidle' })
        await page.locator('#canvasStage').waitFor({ state: 'visible', timeout: 15_000 })
        snap = await snapshot()
        const finalA = nodeById(snap, nodeAId)
        const finalB = nodeById(snap, nodeB.id)
        assert(
          finalA.resources.length === 1 && finalB.resources.length === 1,
          `both nodes must keep exactly one published resource: ${JSON.stringify(snap.nodes.map((n) => [n.name, n.resources.length]))}`,
        )
        assert(
          finalB.function.args.references?.[0]?.nodeId === nodeAId
            && finalB.function.args.prompt === '描述要生成的图片',
          `second node parameters changed after the referenced run: ${JSON.stringify(finalB.function.args)}`,
        )
        await shot(caseArt, 'canvas-reference-run')
        expectNoFatal(pageErrors, consoleErrors)
      } finally {
        if (canvasId) {
          await apiJson(args.backendUrl, 'DELETE', `/api/canvases/${canvasId}`)
        }
      }
    },
    { requiresCanvasFunction: true },
  )

  await run('ui.nav.roundtrip', '主导航往返无崩溃', async (caseArt) => {
    for (const p of ['/chats', '/canvas', '/agents', '/models', '/providers', '/environments', '/mcp-servers', '/chats']) {
      await goto(p)
      if (p === '/mcp-servers') {
        const createButton = page.getByRole('button', {
          name: /创建 MCP 服务|Create MCP Server/,
        })
        await createButton.waitFor({ state: 'visible' })
        await assertLeadingCreateCard(page, createButton, 'Create MCP Server')
      }
      expectNoFatal(pageErrors, consoleErrors)
    }
    await shot(caseArt, 'nav-end')
  })

  const stamp = Date.now().toString(36)

  await run('ui.chat.create_flow', 'UI 创建 Chat 并出现在列表', async (caseArt) => {
    const title = `e2e-ui-chat-${stamp}`
    await goto('/chats')
    await page.getByText('新建 Chat', { exact: true }).click()
    await page.getByRole('textbox', { name: 'Name', exact: true }).fill(title)
    await page.getByRole('button', { name: '确认创建' }).click()
    await page.getByText(title).first().waitFor({ state: 'visible', timeout: 15_000 })
    await shot(caseArt, 'chat-created')
    expectNoFatal(pageErrors, consoleErrors)
    // 清理：API 删除，避免污染后续 run
    await apiDeleteByName(args.backendUrl, 'chats', title)
  })

  await run('ui.model.create_edit_delete_flow', 'UI 创建/编辑/删除 Model', async (caseArt) => {
    const name = `e2e-ui-model-${stamp}`
    await goto('/models')
    await page.getByText('新建 Model', { exact: true }).click()
    await page.getByRole('textbox', { name: 'Name', exact: true }).fill(name)
    await page.getByRole('textbox', { name: 'Model ID', exact: true }).fill(name)
    await page.getByRole('button', { name: '确认创建' }).click()
    const modelRef = await resourceCardTitle(page, name)
    await shot(caseArt, 'model-created')

    // 编辑
    await page.getByRole('button', { name: `编辑 ${modelRef}` }).click()
    await page.getByRole('textbox', { name: 'Description', exact: true }).fill('updated by UI E2E')
    await page.getByRole('button', { name: '保存修改' }).click()
    const updatedModelRef = await resourceCardTitle(page, name)
    await shot(caseArt, 'model-updated')

    // 删除确认
    await page.getByRole('button', { name: `删除 ${updatedModelRef}` }).click()
    await page.getByRole('button', { name: '确认删除' }).click()
    await page.waitForTimeout(800)
    await shot(caseArt, 'model-deleted')
    const body = await page.locator('body').innerText()
    assert(!body.includes(updatedModelRef), `model still visible after delete: ${updatedModelRef}`)
    expectNoFatal(pageErrors, consoleErrors)
    // best-effort cleanup if UI delete failed
    await apiDeleteByName(args.backendUrl, 'models', name)
  })

  await run('ui.agent.create_edit_delete_flow', 'UI 创建/编辑/删除 Agent', async (caseArt) => {
    const name = `e2e-ui-agent-${stamp}`
    const systemPrompt = () =>
      page.getByRole('textbox', { name: /^(System Prompt|系统提示词)$/ })
    await goto('/agents')
    await page.getByText('新建 Agent', { exact: true }).click()
    await page.getByRole('textbox', { name: 'Name', exact: true }).fill(name)
    await systemPrompt().fill('ui e2e agent')
    await page.getByRole('button', { name: '确认创建' }).click()
    await page.getByText(name, { exact: true }).first().waitFor({ state: 'visible', timeout: 20_000 })
    await shot(caseArt, 'agent-created')

    await page.getByRole('button', { name: `编辑 ${name}` }).click()
    await systemPrompt().fill('updated ui e2e agent')
    await page.getByRole('button', { name: '保存修改' }).click()
    await page.getByText(name, { exact: true }).first().waitFor({ state: 'visible', timeout: 20_000 })
    await shot(caseArt, 'agent-updated')

    await page.getByRole('button', { name: `删除 ${name}` }).click()
    await page.getByRole('button', { name: '确认删除' }).click()
    await page.waitForTimeout(800)
    await shot(caseArt, 'agent-deleted')
    const body = await page.locator('body').innerText()
    assert(!body.includes(name), `agent still visible after delete: ${name}`)
    expectNoFatal(pageErrors, consoleErrors)
    await apiDeleteByName(args.backendUrl, 'agents', name)
  })

  await run('ui.model.validation_empty_name', 'Model 空名称前端校验显示错误', async (caseArt) => {
    await goto('/models')
    // 关闭可能残留的模态
    await page.keyboard.press('Escape')
    await page.waitForTimeout(200)
    await page.locator('.cards-grid').getByText('新建 Model', { exact: true }).click()
    await page.locator('form.modal-card, .modal-card').first().waitFor({ state: 'visible', timeout: 10_000 })
    const nameInput = page.getByRole('textbox', { name: 'Name', exact: true })
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
    await goto('/providers')
    await page.keyboard.press('Escape')
    await page.locator('.cards-grid').getByText('新建 Provider', { exact: true }).click()
    await page.getByRole('textbox', { name: 'Name', exact: true }).fill(name)
    await page.getByLabel('Base URL').fill('https://example.com/v1')
    await page.getByLabel('API Key（可选）').fill('sk-e2e-ui-test')
    await page.getByRole('button', { name: '确认创建' }).click()
    await page.getByText(name, { exact: true }).first().waitFor({ state: 'visible', timeout: 20_000 })
    await shot(caseArt, 'provider-created')

    await page.getByRole('button', { name: `编辑 ${name}` }).click()
    await page.getByLabel('Base URL').fill('https://example.com/v2')
    // 编辑时不改 key（空 credential 保留）
    await page.getByRole('button', { name: '保存修改' }).click()
    await page.getByText(name, { exact: true }).first().waitFor({ state: 'visible', timeout: 20_000 })
    await shot(caseArt, 'provider-updated')

    await page.getByRole('button', { name: `删除 ${name}` }).click()
    await page.getByRole('button', { name: '确认删除' }).click()
    await page.waitForTimeout(800)
    await shot(caseArt, 'provider-deleted')
    const body = await page.locator('body').innerText()
    assert(!body.includes(name), `provider still visible after delete: ${name}`)
    expectNoFatal(pageErrors, consoleErrors)
    await apiDeleteByName(args.backendUrl, 'providers', name)
  })

  await run('ui.chat.blank_workspace_shell', '进入 Chat 空白工作区并校验 blank pane shell', async (caseArt) => {
    const title = `e2e-ui-blank-${stamp}`
    // 创建带 Agent 的 Chat，便于 blank 首发
    await goto('/chats')
    await page.getByText('新建 Chat', { exact: true }).click()
    await page.getByRole('textbox', { name: 'Name', exact: true }).fill(title)
    // 选 default-assistant
    await selectCustomOption(
      page,
      page.getByRole('button', { name: 'Agent' }),
      'default-assistant',
    )
    await page.getByRole('button', { name: '确认创建' }).click()
    // createChat 成功后会直接 navigate 到 /chats/:id 空白工作区
    await page.getByLabel('给 AI 发送消息').waitFor({ state: 'visible', timeout: 15_000 })
    assert(
      await page.locator('.chat-workspace .locale-selector').count() === 0,
      'chat workspace must not contain a locale selector',
    )
    await shot(caseArt, 'blank-workspace')
    expectNoFatal(pageErrors, consoleErrors)

    // Blank 场景也必须使用共享双层 Composer。
    const composer = page.getByLabel('给 AI 发送消息')
    const dock = composer.locator('xpath=ancestor::*[contains(@class,"thread-dock")]')
    const editorBox = await composer.boundingBox()
    const controlsBox = await dock.locator('.thread-dock-controls').boundingBox()
    assert(
      editorBox && controlsBox && editorBox.y + editorBox.height <= controlsBox.y + 2,
      `blank Composer is not rendered as input + control rows: ${JSON.stringify({
        editorBox,
        controlsBox,
      })}`,
    )
    await page.getByRole('button', { name: '权限模式' }).waitFor({ state: 'visible' })
    await page.getByRole('button', { name: 'Model 与 Variant' }).waitFor({ state: 'visible' })
    await assertReadOnlyZeroFooter(page, 'blank Footer')
    await composer.fill('e2e blank shell probe (not sent if empty then clear)')
    await composer.fill('')
    await apiDeleteByName(args.backendUrl, 'chats', title)
  })

  await run(
    'ui.chat.create_agent_no_implicit_environment',
    'Create Chat 选择 Agent 不隐式绑定 Environment，Agent 与 Chat 不持有环境字段',
    async (caseArt) => {
      const title = `e2e-ui-create-env-${stamp}`
      const tempAgentName = `e2e-ui-agent-${stamp}`

      // Environment 由运行分支显式绑定，不属于 Agent Definition。
      const agentCreateRes = await apiJson(args.backendUrl, 'POST', '/api/ai/catalog/agents', {
        name: tempAgentName,
        description: `Temporary e2e agent for ${args.daemonEnv}`,
        systemPrompt: 'You are an e2e test agent.',
        model: REAL_UI_MODEL_ID,
        variant: REAL_UI_MODEL.variant,
        config: { tools: [], skills: [], subagents: [], inheritParentEnvironment: true },
      })
      assert(
        agentCreateRes.status === 201,
        `failed to create temporary agent '${tempAgentName}' (expected 201, got ${agentCreateRes.status}): ${JSON.stringify(agentCreateRes.json)}`,
      )
      assert(
        !Object.hasOwn(agentCreateRes.json.data, 'environmentId')
          && !Object.hasOwn(agentCreateRes.json.data, 'branchSettings'),
        'Agent Definition must not own branch environment settings',
      )

      try {
        await goto('/chats')
        await page.getByText('新建 Chat', { exact: true }).click()
        // Create Chat 只允许选择 Name 与 Agent；不存在 Workspace 路径选择器。
        await page.getByRole('textbox', { name: 'Name', exact: true }).fill(title)
        const workspaceShell = page.locator(
          'button[aria-label="工作区路径"], button[aria-label="Workspace Path"], button.environment-binding-trigger',
        )
        assert(
          await workspaceShell.count() === 0,
          'Create Chat still rendered a workspace path selector',
        )
        await selectCustomOption(
          page,
          page.getByRole('button', { name: 'Agent' }),
          tempAgentName,
        )
        await page.getByRole('button', { name: '确认创建' }).click()
        // createChat 成功后会直接 navigate 到 /chats/:id 空白工作区
        await page.getByLabel('给 AI 发送消息').waitFor({ state: 'visible', timeout: 15_000 })

        // 空白工作区没有分支环境，不得猜测宿主或默认 Environment。
        const footer = page.getByLabel('会话状态')
        await footer.waitFor({ state: 'visible', timeout: 15_000 })
        const environmentText = await footer
          .locator('.thread-status-environment .thread-status-seg')
          .innerText()
        assert(
          environmentText === 'none env',
          `Footer inferred an implicit Environment: ${environmentText}`,
        )

        // 4. Chat 持久化不得携带任何 workspace / environment 字段。
        const { json } = await apiJson(args.backendUrl, 'GET', '/api/ai/chats')
        const created = (json?.data || []).find((chat) => chat.title === title)
        assert(created != null, `Create Chat did not persist the chat: ${JSON.stringify(json)}`)
        for (const field of ['workspacePath', 'environment', 'environmentId']) {
          assert(
            !Object.hasOwn(created, field),
            `Chat leaked removed workspace/environment field '${field}': ${JSON.stringify(created)}`,
          )
        }
        assert(created.agentName === tempAgentName, JSON.stringify(created))
        await shot(caseArt, 'create-chat-agent-no-implicit-environment')
        expectNoFatal(pageErrors, consoleErrors)
      } finally {
        await apiDeleteByName(args.backendUrl, 'chats', title)
        await apiDeleteByName(args.backendUrl, 'agents', tempAgentName)
      }
    },
    { requiresTools: true },
  )

  await runComposerMatrix({
    apiCtx,
    consoleErrors,
    daemonEnv: args.daemonEnv,
    expectNoFatal,
    goto,
    page,
    pageErrors,
    run,
    shot,
    stamp,
  })

  if (args.real) {
    await run('ui.chat.blank_first_send_real', 'Blank pane 首发真实消息并出现用户气泡', async (caseArt) => {
      const title = `e2e-ui-send-${stamp}`
      const realAgentName = `e2e-ui-real-agent-${stamp}`
      const agentCreateRes = await apiJson(args.backendUrl, 'POST', '/api/ai/catalog/agents', {
        name: realAgentName,
        description: 'Temporary real UI E2E agent.',
        systemPrompt: 'Reply concisely and never call tools.',
        model: REAL_UI_MODEL_ID,
        variant: REAL_UI_MODEL.variant,
        config: { tools: [], skills: [], subagents: [], inheritParentEnvironment: true },
      })
      assert(
        agentCreateRes.status === 201,
        `failed to create real UI agent '${realAgentName}' (expected 201, got ${agentCreateRes.status})`,
      )
      try {
        await goto('/chats')
        await page.getByText('新建 Chat', { exact: true }).click()
        await page.getByRole('textbox', { name: 'Name', exact: true }).fill(title)
        await selectCustomOption(
          page,
          page.getByRole('button', { name: 'Agent' }),
          realAgentName,
        )
        await page.getByRole('button', { name: '确认创建' }).click()
        // createChat 成功后会直接 navigate 到 /chats/:id 空白工作区
        const composer = page.getByLabel('给 AI 发送消息')
        await composer.waitFor({ state: 'visible', timeout: 15_000 })
        await composer.fill('只回复单词 OK，不要调用工具。')
        await page.getByRole('button', { name: '发送消息' }).click()
        await page.getByText('只回复单词 OK，不要调用工具。').first().waitFor({ state: 'visible', timeout: 30_000 })
        await page.getByRole('button', { name: '权限模式' }).waitFor({ state: 'visible', timeout: 30_000 })
        await page.getByRole('button', { name: 'Model 与 Variant' })
          .filter({ hasText: REAL_UI_MODEL_SELECTOR_LABEL })
          .waitFor({ state: 'visible', timeout: 30_000 })
        const status = page.getByLabel('会话状态')
        if (await status.count() > 0) {
          assert(await status.getByRole('button').count() === 0, 'Footer must remain readonly')
          const statusText = await status.innerText()
          assert(!statusText.includes('agent:'), `Footer leaked Agent: ${statusText}`)
          assert(!statusText.includes(REAL_UI_MODEL.modelName), `Footer leaked Model: ${statusText}`)
          assert(!statusText.includes('notify:'), `Footer leaked Notification: ${statusText}`)
        }
        await shot(caseArt, 'first-send-user')
        // 只读取最终正文；thinking 可能复述提示词中的 OK，不能作为完成证据。
        const assistantText = await waitForSettledAssistantText(page)
        await shot(caseArt, 'first-send-done')
        if (/助手回复失败|FAILED|失败/i.test(assistantText)) {
          throw new Error(`real assistant response failed: ${assistantText}`)
        }
        assert(/\bOK\b/i.test(assistantText), `expected assistant reply containing OK, got: ${assistantText || '(empty)'}`)
        expectNoFatal(pageErrors, consoleErrors)
      } finally {
        await apiDeleteByName(args.backendUrl, 'chats', title)
        await apiDeleteByName(args.backendUrl, 'agents', realAgentName)
      }
    })
  }

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
  writeFileSync(path.join(reportDir, 'ui-summary.json'), `${JSON.stringify(summary, null, 2)}\n`)
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
