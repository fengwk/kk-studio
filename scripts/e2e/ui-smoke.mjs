#!/usr/bin/env node
/**
 * Playwright UI E2E 矩阵（L5，可选）。
 *
 * - 页面 smoke：路由可达、列表渲染、打开创建卡片、无致命 pageerror
 * - 交互矩阵：高价值用户路径、状态组合与浏览器布局/Selection 边界
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
import { createDurationTimer } from './lib/time.mjs'
import { assertReadOnlyZeroFooter } from './ui/assertions.mjs'
import { runComposerMatrix } from './ui/composer-matrix.mjs'

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
      '../../platform/src/test/resources/fun/fengwk/kkstudio/platform/harness/persistence/postgresql/pi-model-catalog.json',
    ),
    'utf8',
  ),
).map((model) => model.name)

function parseArgs(argv) {
  const args = {
    baseUrl: process.env.FRONTEND_URL || 'http://127.0.0.1:5173',
    backendUrl: process.env.BACKEND_URL || 'http://127.0.0.1:18081',
    reportDir: process.env.E2E_UI_REPORT_DIR || path.resolve('reports/e2e/ui-standalone'),
    daemonEnv: process.env.DAEMON_ENV_NAME || 'e2e-local',
    headed: false,
    real: false,
    withTools: false,
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
  const resourcePath = resource === 'chats' ? '/api/ai/chat' : `/api/ai/catalog/${resource}`
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

async function requireRealMiniMaxM27(backendUrl) {
  const { json: agentsJson } = await apiJson(backendUrl, 'GET', '/api/ai/catalog/agents?pageNumber=1&pageSize=50')
  const { json: modelsJson } = await apiJson(backendUrl, 'GET', '/api/ai/catalog/models?pageNumber=1&pageSize=50')
  const { json: providersJson } = await apiJson(backendUrl, 'GET', '/api/ai/catalog/providers?pageNumber=1&pageSize=50')
  const agents = agentsJson?.data?.results || []
  const models = modelsJson?.data?.results || []
  const providers = providersJson?.data?.results || []
  const agent = agents.find((candidate) => candidate.name === 'default-assistant')
  const model = models.find(
    (candidate) =>
      candidate.providerName === 'minimax' && candidate.name === 'MiniMax-M2.7',
  )
  const provider = providers.find((candidate) => candidate.name === model?.providerName)
  assert(
    provider?.name === 'minimax'
      && model?.name === 'MiniMax-M2.7'
      && agent?.model === `${model.providerName}/${model.name}`,
    `real UI test must use default-assistant with minimax/MiniMax-M2.7: ${JSON.stringify({ agent, model, provider })}`,
  )
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
      'Usage: node scripts/e2e/ui-smoke.mjs --base-url URL --report-dir DIR'
        + ' [--headed] [--real] [--with-tools] [--daemon-env NAME] [--only CASE_ID]...',
    )
    return 0
  }

  const reportDir = args.reportDir
  const artRoot = path.join(reportDir, 'artifacts')
  mkdirSync(path.join(reportDir, 'cases'), { recursive: true })
  mkdirSync(artRoot, { recursive: true })
  const apiCtx = {
    call: (method, requestPath, body) =>
      apiJson(args.backendUrl, method, requestPath, body),
  }
  if (args.real) await requireRealMiniMaxM27(args.backendUrl)

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

  async function run(id, title, fn) {
    registeredCaseIds.add(id)
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
    await shot(caseArt, 'environments')
    expectNoFatal(pageErrors, consoleErrors)
    const body = await page.locator('body').innerText()
    assert(body.trim().length > 20, 'environments page body empty')
  })

  await run('ui.canvas.page_loads', 'Canvas 库与编辑器可打开', async (caseArt) => {
    await goto('/canvas')
    await expectVisibleText(page, '你的画布')
    // Library 路由保留全局顶栏。
    assert(
      await page.locator('.topbar').isVisible(),
      'canvas library must keep the global topbar',
    )
    const createButton = page.getByRole('button', { name: '创建新画布' })
    await createButton.waitFor({ state: 'visible' })
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
    // 编辑器沉浸：无全局顶栏、挂 canvas-immersive class，Chat 面板默认收起。
    assert(
      await page.locator('.topbar').count() === 0,
      'canvas editor must not render the global topbar',
    )
    assert(
      await page.locator('.app-frame.canvas-immersive').count() === 1,
      'canvas editor must carry the canvas-immersive root class',
    )
    assert(
      await page.locator('#agentPanel').count() === 0,
      'Chat panel must be collapsed by default',
    )
    assert(
      await page.locator('.chat-shell.thread-panel').count() === 0,
      'collapsed Canvas must not render a thread panel',
    )
    assert(
      await page.locator('.canvas-back-button.sidebar-icon-btn').count() === 1,
      'canvas back control must reuse the Chat workspace icon-button grammar',
    )
    const resetZoom = page.getByRole('button', {
      name: /^(Reset zoom to 100%|重置缩放为 100%)$/,
    })
    await resetZoom.waitFor({ state: 'visible' })
    const fittedZoomText = (await resetZoom.textContent())?.trim() ?? ''
    const fittedZoom = Number.parseInt(fittedZoomText, 10)
    assert(
      Number.isInteger(fittedZoom) && fittedZoom >= 25 && fittedZoom <= 200,
      `canvas fitted zoom is invalid: ${await resetZoom.textContent()}`,
    )
    // header「Chat / 对话」toggle 展开/收起右侧 aside。
    const threadToggle = page.getByRole('button', {
      name: /^(Toggle the Chat panel|切换对话面板)$/,
    })
    await threadToggle.click()
    await page.locator('#agentPanel').waitFor({ state: 'visible', timeout: 10_000 })
    const canvasComposer = page.locator('#agentPanel').getByLabel('给 AI 发送消息')
    await canvasComposer.waitFor({ state: 'visible', timeout: 15_000 })
    await page.locator('#agentPanel').getByRole('button', { name: '权限模式' })
      .waitFor({ state: 'visible', timeout: 15_000 })
    await page.locator('#agentPanel').getByRole('button', { name: 'Model 与 Variant' })
      .waitFor({ state: 'visible', timeout: 15_000 })
    const canvasEditorBox = await canvasComposer.boundingBox()
    const canvasControlsBox = await page.locator('#agentPanel .thread-dock-controls').boundingBox()
    assert(
      canvasEditorBox
      && canvasControlsBox
      && canvasEditorBox.y + canvasEditorBox.height <= canvasControlsBox.y + 2,
      `Canvas blank Composer is not rendered as input + control rows: ${JSON.stringify({
        canvasEditorBox,
        canvasControlsBox,
      })}`,
    )
    await assertReadOnlyZeroFooter(
      page.locator('#agentPanel'),
      'Canvas blank Footer',
    )
    assert(
      (await threadToggle.getAttribute('aria-expanded')) === 'true',
      'thread toggle must report aria-expanded=true when the panel is open',
    )
    assert(
      (await resetZoom.textContent())?.trim() === fittedZoomText,
      'opening the Agent panel must not change canvas zoom',
    )
    const panel = page.locator('#agentPanel')
    const initialPanelBox = await panel.boundingBox()
    assert(
      initialPanelBox?.width >= 440,
      `Chat panel must default wider than the legacy 360px: ${initialPanelBox?.width}`,
    )
    const resizeHandle = page.getByRole('separator', {
      name: /^(Resize the Chat panel|调整对话面板宽度)$/,
    })
    const resizeBox = await resizeHandle.boundingBox()
    assert(resizeBox, 'Chat panel resize handle is missing')
    await page.mouse.move(resizeBox.x + resizeBox.width / 2, resizeBox.y + resizeBox.height / 2)
    await page.mouse.down()
    await page.mouse.move(resizeBox.x - 72, resizeBox.y + resizeBox.height / 2, { steps: 4 })
    await page.mouse.up()
    const resizedPanelBox = await panel.boundingBox()
    assert(
      resizedPanelBox?.width >= (initialPanelBox?.width ?? 0) + 60,
      `Chat panel did not grow from its left edge: ${JSON.stringify({ initialPanelBox, resizedPanelBox })}`,
    )
    assert(
      (await resetZoom.textContent())?.trim() === fittedZoomText,
      'resizing the Chat panel must not change canvas zoom',
    )
    await shot(caseArt, 'canvas-agent-panel')
    await threadToggle.click()
    await page.locator('#agentPanel').waitFor({ state: 'detached', timeout: 10_000 })
    assert(
      (await resetZoom.textContent())?.trim() === fittedZoomText,
      'closing the Chat panel must restore the same canvas zoom',
    )
    assert(
      await page.locator('.chat-shell.thread-panel').count() === 0,
      'closing Chat must not reveal a thread panel',
    )
    // 左侧居中 add launcher 仍可打开/关闭菜单。
    const addLauncher = page.getByRole('button', {
      name: /^(Add resource or Function|添加资源或 Function)$/,
    })
    await addLauncher.click()
    await page.getByRole('menu').waitFor({ state: 'visible', timeout: 10_000 })
    await shot(caseArt, 'canvas-add-menu')
    await page.keyboard.press('Escape')
    await page.getByRole('menu').waitFor({ state: 'hidden', timeout: 10_000 })
    await resetZoom.click()
    await page.waitForFunction(() => {
      const button = document.querySelector(
        '[aria-label="Reset zoom to 100%"], [aria-label="重置缩放为 100%"]',
      )
      return button?.textContent?.trim() === '100%'
    })
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

  await run('ui.nav.roundtrip', '主导航往返无崩溃', async (caseArt) => {
    for (const p of ['/chats', '/canvas', '/agents', '/models', '/providers', '/environments', '/chats']) {
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

  if (args.withTools) {
    await run(
      'ui.chat.create_environment_workspace',
      'Create Chat 使用 Environment → Workspace 两阶段 picker 提交完整 binding',
      async (caseArt) => {
        const title = `e2e-ui-create-env-${stamp}`
        await goto('/chats')
        await page.getByText('新建 Chat', { exact: true }).click()
        await page.getByRole('textbox', { name: 'Name', exact: true }).fill(title)
        await selectCustomOption(
          page,
          page.getByRole('button', { name: 'Agent' }),
          'default-assistant',
        )

        const environmentTrigger = page.getByRole('button', { name: 'Environment', exact: true })
        await environmentTrigger.click()
        const environments = page.getByRole('region', { name: '选择 Environment' })
        await environments.waitFor({ state: 'visible', timeout: 10_000 })
        await environments.getByRole('option', {
          name: new RegExp(`^${escapeRegExp(args.daemonEnv)}(?:\\s|$)`),
        }).click()
        const directory = page.getByRole('region', { name: `${args.daemonEnv} 目录` })
        await directory.waitFor({ state: 'visible', timeout: 10_000 })
        assert(
          (await environmentTrigger.innerText()).includes('（无）'),
          'selecting an Environment silently committed workspacePath="." before confirmation',
        )
        await directory.getByRole('button', { name: '使用当前 Workspace' }).click()
        assert(
          (await environmentTrigger.innerText()).includes(`${args.daemonEnv} · @/`),
          `complete Environment binding was not shown: ${await environmentTrigger.innerText()}`,
        )
        await page.getByRole('button', { name: '确认创建' }).click()
        // createChat 成功后会直接 navigate 到 /chats/:id 空白工作区
        await page.getByLabel('给 AI 发送消息').waitFor({ state: 'visible', timeout: 15_000 })

        const { json } = await apiJson(args.backendUrl, 'GET', '/api/ai/chat')
        const created = (json?.data || []).find((chat) => chat.title === title)
        assert(
          created?.environment?.name === args.daemonEnv
          && created?.environment?.workspacePath === '.',
          `Create Chat did not persist the confirmed binding: ${JSON.stringify(created)}`,
        )
        await shot(caseArt, 'create-chat-environment-workspace')
        expectNoFatal(pageErrors, consoleErrors)
        await apiDeleteByName(args.backendUrl, 'chats', title)
      },
    )
  }

  await runComposerMatrix({
    apiCtx,
    consoleErrors,
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
      await goto('/chats')
      await page.getByText('新建 Chat', { exact: true }).click()
      await page.getByRole('textbox', { name: 'Name', exact: true }).fill(title)
      await selectCustomOption(
        page,
        page.getByRole('button', { name: 'Agent' }),
        'default-assistant',
      )
      await page.getByRole('button', { name: '确认创建' }).click()
      // createChat 成功后会直接 navigate 到 /chats/:id 空白工作区
      const composer = page.getByLabel('给 AI 发送消息')
      await composer.waitFor({ state: 'visible', timeout: 15_000 })
      await composer.fill('只回复单词 OK，不要调用工具。')
      await page.getByRole('button', { name: '发送消息' }).click()
      // 用户消息应进入 timeline；assistant 成功与否取决于 Provider
      await page.getByText('只回复单词 OK，不要调用工具。').first().waitFor({ state: 'visible', timeout: 30_000 })
      await page.getByRole('button', { name: '权限模式' }).waitFor({ state: 'visible', timeout: 30_000 })
      await page.getByRole('button', { name: 'Model 与 Variant' })
        .filter({ hasText: 'minimax/MiniMax-M2.7 · high' })
        .waitFor({ state: 'visible', timeout: 30_000 })
      const status = page.getByLabel('会话状态')
      if (await status.count() > 0) {
        assert(await status.getByRole('button').count() === 0, 'Footer must remain readonly')
        const statusText = await status.innerText()
        assert(!statusText.includes('agent:'), `Footer leaked Agent: ${statusText}`)
        assert(!statusText.includes('MiniMax-M2.7'), `Footer leaked Model: ${statusText}`)
        assert(!statusText.includes('notify:'), `Footer leaked Notification: ${statusText}`)
      }
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
