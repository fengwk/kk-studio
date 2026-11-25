#!/usr/bin/env node
/**
 * 矩阵化 API/链路 E2E runner（Node，零额外依赖）。
 *
 * 结构：
 * - scripts/dev/verify/e2e/run-matrix.mjs   CLI + 报告
 * - scripts/dev/verify/e2e/lib/*            HTTP/fixtures
 * - scripts/dev/verify/e2e/cases/*          用例数组
 *
 * 报告（gitignore）：
 *   reports/e2e/<runId>/{report.md,summary.json,cases/,artifacts/,logs/}
 *   reports/e2e/latest/report.md
 *
 * npm：
 *   npm --prefix frontend run e2e
 *   npm --prefix frontend run e2e:matrix
 */

import { execFileSync } from 'node:child_process'
import {
  cpSync,
  existsSync,
  mkdirSync,
  readdirSync,
  readFileSync,
  rmSync,
  statSync,
  writeFileSync,
} from 'node:fs'
import path from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { randomUUID } from 'node:crypto'
import { createHttpClient, httpJson, listResults, loginStudio, setStudioCookie, studioAuthHeaders, openStudioWebSocket } from './lib/http.mjs'
import { assertProviderExecutionBoundary } from './lib/provider-boundary.mjs'
import { copyDistributedAppLogs, createBaseUrls, createNodeCall, runDistributedCommand } from './lib/distributed.mjs'
import { redactSecrets, writeReportArtifact } from './lib/redact.mjs'
import { resolveRealModelSelection } from './lib/real-models.mjs'
import { findCase } from './lib/registry.mjs'
import { createDurationTimer } from './lib/time.mjs'
import { REPO_ROOT } from '../../lib/repo-root.mjs'
import { cases as seedCases } from './cases/seed-and-harness.mjs'
import { cases as crudCases } from './cases/crud.mjs'
import { cases as i18nCases } from './cases/i18n.mjs'
import { cases as configMatrixCases } from './cases/config-matrix.mjs'
import { cases as systemSettingsCases } from './cases/system-settings.mjs'
import { cases as environmentCardCases } from './cases/environment-install-config.mjs'
import { cases as configSyncCases } from './cases/config-sync.mjs'
import { cases as runtimeIdeCases } from './cases/environment-runtime-ide.mjs'
import { cases as appEventsCases } from './cases/app-events.mjs'
import { cases as modelAttemptCases } from './cases/model-attempt-visibility.mjs'
import { cases as sessionAttachmentCases } from './cases/session-attachment.mjs'
import { cases as canvasStorageCases } from './cases/canvas-storage.mjs'
import { cases as canvasFunctionCases } from './cases/canvas-function.mjs'
import { workspaceCases } from './cases/workspace.mjs'
import { cases as canvasApiCases } from './cases/canvas-api.mjs'
import { cases as interactionCases } from './cases/interaction.mjs'
import { cases as queuedBatchCases } from './cases/thread-queued-batch.mjs'
import { cases as realCases } from './cases/real.mjs'
import { cases as distributedCases } from './cases/distributed.mjs'

const __filename = fileURLToPath(import.meta.url)
const __dirname = path.dirname(__filename)

// 卡片用例先于配置同步注册，保证配置同步引用的卡片 fixture/断言先就绪。
export const ALL_CASES = [
  ...seedCases, ...crudCases, ...i18nCases, ...configMatrixCases,
  ...systemSettingsCases, ...environmentCardCases, ...configSyncCases, ...runtimeIdeCases,
  ...appEventsCases, ...modelAttemptCases, ...canvasFunctionCases,
  ...canvasApiCases, ...canvasStorageCases, ...sessionAttachmentCases, ...interactionCases, ...queuedBatchCases,
  ...workspaceCases,
  ...realCases, ...distributedCases,
]
export const getCase = (id) => findCase(ALL_CASES, id)

class CaseContext {
  constructor({
    baseUrl,
    baseUrlB,
    daemonEnv,
    real,
    realModel,
    withTools,
    withBranch,
    withCanvasStorage,
    withCanvasFunction,
    reportDir,
    resultsOnly,
    client,
  }) {
    this.baseUrl = baseUrl
    this.request = client?.request ?? ((path, { method = 'GET', body, timeoutMs, headers } = {}) =>
      httpJson(baseUrl, method, path, body, timeoutMs, headers))
    this.openWebSocket = client?.openWebSocket ?? ((path) => openStudioWebSocket(baseUrl, path))
    this.daemonEnv = daemonEnv
    this.real = real
    this.realModel = realModel
    this.withTools = withTools
    this.withBranch = withBranch
    this.withCanvasStorage = withCanvasStorage
    this.withCanvasFunction = withCanvasFunction
    this.reportDir = reportDir
    this.resultsOnly = resultsOnly
    this.caseId = null
    this.vars = {}
    // distributed capability：只在同时给出两个节点 URL 时可用。
    this.baseUrls = createBaseUrls(baseUrl, baseUrlB)
    if (this.baseUrls) {
      this.callNode = createNodeCall(this.baseUrls)
      this.runDistributedCommand = (command) => runDistributedCommand(command)
    }
  }

  artifactsDir() {
    if (!this.reportDir || !this.caseId) throw new Error('reportDir/caseId not initialized')
    const dir = path.join(this.reportDir, 'artifacts', this.caseId.replaceAll('.', '_'))
    mkdirSync(dir, { recursive: true })
    return dir
  }

  writeArtifact(name, content) {
    if (this.resultsOnly) return
    const file = path.join(this.artifactsDir(), name)
    writeReportArtifact(file, content)
    return file
  }

  async call(method, requestPath, body, timeoutMs) {
    return this.request(requestPath, { method, body, timeoutMs })
  }

  async createSpace(title = `E2E ${randomUUID()}`) {
    const { json } = await this.request('/api/chat-spaces', {
      method: 'POST', headers: { 'Idempotency-Key': randomUUID() },
      body: { title, rootFolderName: 'E2E', defaultAgentName: 'default-assistant' },
    })
    return json.id
  }

  async createUser() {
    const username = `e2e-${randomUUID().slice(0, 16)}`
    const password = randomUUID()
    await this.request('/api/users', { method: 'POST', body: { username, password, role: 'USER' } })
    const client = createHttpClient({ baseUrl: this.baseUrl })
    await client.login(username, password)
    return new CaseContext({ baseUrl: this.baseUrl, client })
  }
}

function defaultReportRoot() {
  if (process.env.E2E_REPORT_ROOT) return path.resolve(process.env.E2E_REPORT_ROOT)
  return path.join(REPO_ROOT, 'reports', 'e2e')
}

function createRunDir(reportRoot, resultsOnly) {
  const runId = `${new Date().toISOString().replace(/[:.]/g, '-').replace('T', '-').slice(0, 19)}-${randomUUID().slice(0, 8)}`
  const runDir = path.join(reportRoot, runId)
  mkdirSync(path.join(runDir, 'cases'), { recursive: true })
  if (!resultsOnly) {
    mkdirSync(path.join(runDir, 'artifacts'), { recursive: true })
    mkdirSync(path.join(runDir, 'logs'), { recursive: true })
  }
  return { runId, runDir }
}

function collectArtifactPaths(runDir, caseId) {
  const art = path.join(runDir, 'artifacts', caseId.replaceAll('.', '_'))
  if (!existsSync(art)) return []
  const out = []
  const walk = (dir) => {
    for (const name of readdirSync(dir)) {
      const p = path.join(dir, name)
      if (statSync(p).isDirectory()) walk(p)
      else out.push(path.relative(runDir, p))
    }
  }
  walk(art)
  return out.sort()
}

function writeCaseResult(runDir, result) {
  writeReportArtifact(
    path.join(runDir, 'cases', `${result.id}.json`),
    JSON.stringify(result),
  )
}

function writeSummaryAndReport(
  runDir,
  runId,
  args,
  results,
  startedAt,
  finishedAt,
  durationMs,
) {
  const { summary, report } = buildSummary(runId, args, results, startedAt, finishedAt, durationMs)
  writeReportArtifact(path.join(runDir, 'summary.json'), JSON.stringify(summary))
  const reportPath = path.join(runDir, 'report.md')
  writeReportArtifact(reportPath, report)
  return reportPath
}

/**
 * 一次运行的计数口径：totals 与 By Level 共用同一组 pass/fail/skip 状态，skip 不并入 fail。
 * 状态只来自本 runner（pass/fail/skip），报告与终端摘要都从这里取值。
 */
function countResults(results) {
  const totals = { total: results.length, pass: 0, fail: 0, skip: 0, byLevel: {} }
  for (const r of results) {
    const level = (totals.byLevel[r.level] ||= { total: 0, pass: 0, fail: 0, skip: 0 })
    level.total++
    level[r.status]++
    totals[r.status]++
  }
  return totals
}

/** 汇总一次运行：summary.json 与 report.md 用同一组计数，totals 与 By Level 表不会口径不一。 */
export function buildSummary(runId, args, results, startedAt, finishedAt, durationMs) {
  const totals = countResults(results)
  const failed = results.filter((r) => r.status === 'fail')
  if (args.resultsOnly) {
    const lines = [
      '# E2E Results',
      '',
      `- Totals: total=${totals.total} pass=${totals.pass} fail=${totals.fail} skip=${totals.skip}`,
      '',
      '| Status | Case | Duration |',
      '| --- | --- | ---: |',
      ...results.map((r) => `| ${r.status.toUpperCase()} | \`${r.id}\` | ${r.durationMs}ms |`),
      '',
    ]
    return { summary: { runId, durationMs, totals, results }, report: lines.join('\n') }
  }
  const summary = {
    runId,
    startedAt,
    finishedAt,
    durationMs,
    baseUrl: args.baseUrl,
    frontendUrl: args.frontendUrl || null,
    topology: args.distributed ? 'distributed-a-b' : 'single',
    flags: {
      real: args.real,
      withTools: args.withTools,
      withBranch: args.withBranch,
      withCanvasStorage: args.withCanvasStorage,
      withCanvasFunction: args.withCanvasFunction,
      distributed: args.distributed,
      only: args.only,
      level: args.level,
    },
    totals,
    failedCaseIds: failed.map((r) => r.id),
    results,
    reportMarkdown: 'report.md',
  }

  const lines = []
  lines.push(`# E2E Report \`${runId}\``)
  lines.push('')
  lines.push(`- Started: \`${startedAt}\``)
  lines.push(`- Finished: \`${finishedAt}\``)
  lines.push(`- Topology: \`${args.distributed ? 'distributed-a-b' : 'single'}\``)
  lines.push(`- Backend: \`${args.baseUrl}\``)
  if (args.distributed) lines.push(`- Backend B: \`${args.baseUrlB}\``)
  if (args.frontendUrl) lines.push(`- Frontend: \`${args.frontendUrl}\``)
  lines.push(
    `- Flags: real=${args.real} tools=${args.withTools} branch=${args.withBranch} distributed=${args.distributed}`,
  )
  lines.push(
    `- Totals: total=${totals.total} pass=${totals.pass} fail=${totals.fail} skip=${totals.skip}`,
  )
  lines.push(`- Result: **${failed.length ? 'FAIL' : 'PASS'}**`)
  lines.push('')
  lines.push('## By Level')
  lines.push('')
  lines.push('| Level | Total | Pass | Fail | Skip |')
  lines.push('| --- | ---: | ---: | ---: | ---: |')
  for (const level of Object.keys(totals.byLevel).sort()) {
    const x = totals.byLevel[level]
    lines.push(`| ${level} | ${x.total} | ${x.pass} | ${x.fail} | ${x.skip} |`)
  }
  lines.push('')
  lines.push('## Case Matrix')
  lines.push('')
  lines.push('| Status | Level | Case | Duration | Error |')
  lines.push('| --- | --- | --- | ---: | --- |')
  for (const r of results) {
    let err = (r.error || '').replaceAll('|', '\\|').replaceAll('\n', ' ')
    if (err.length > 120) err = `${err.slice(0, 117)}...`
    lines.push(
      `| ${r.status.toUpperCase()} | ${r.level} | \`${r.id}\` ${r.title} | ${r.durationMs}ms | ${err || '-'} |`,
    )
  }
  lines.push('')
  if (failed.length) {
    lines.push('## Failures')
    lines.push('')
    for (const r of failed) {
      lines.push(`### \`${r.id}\``)
      lines.push('')
      lines.push(`- Title: ${r.title}`)
      lines.push(`- Error: \`${r.error}\``)
      if (r.artifactPaths?.length) {
        lines.push('- Artifacts:')
        for (const p of r.artifactPaths) lines.push(`  - \`${p}\``)
      }
      if (r.traceback) {
        lines.push('')
        lines.push('```text')
        lines.push(r.traceback.trimEnd())
        lines.push('```')
      }
      lines.push('')
    }
    lines.push('## How to triage')
    lines.push('')
    lines.push('1. `reports/e2e/latest/report.md` + `summary.json`')
    lines.push('2. `cases/<id>.json` + `artifacts/<id>/`')
    lines.push('3. `logs/` or `runtime/e2e/`')
    lines.push('4. Re-run: `scripts/dev/verify/e2e/run.sh --only <caseId>`')
    lines.push('')
  } else {
    lines.push('## Notes')
    lines.push('')
    lines.push('All selected cases passed.')
    lines.push('')
  }
  return { summary, report: `${lines.join('\n')}\n` }
}

function publishLatest(reportRoot, runDir) {
  const latest = path.join(reportRoot, 'latest')
  if (existsSync(latest)) rmSync(latest, { recursive: true, force: true })
  cpSync(runDir, latest, { recursive: true })
  return latest
}

function maybeCopyRuntimeLogs(runDir) {
  const candidates = [path.join(REPO_ROOT, 'runtime', 'e2e'), path.join(REPO_ROOT, 'runtime', 'dev')]
  const dest = path.join(runDir, 'logs')
  for (const base of candidates) {
    if (!existsSync(base)) continue
    for (const name of [
      'backend.log',
      'frontend.log',
      'daemon.log',
      'backend-e2e.log',
      'frontend-e2e.log',
      'daemon-e2e.log',
    ]) {
      const src = path.join(base, name)
      if (existsSync(src)) {
        writeReportArtifact(path.join(dest, `${path.basename(base)}-${name}`), readFileSync(src, 'utf8'))
      }
    }
  }
}

/** distributed 运行把双 app/daemon 容器日志复制进报告 logs/（只读容器，不影响栈）。 */
function maybeCopyDistributedContainerLogs(runDir) {
  const services = ['app-a', 'app-b', 'daemon-a', 'daemon-b']
  const dest = path.join(runDir, 'logs')
  mkdirSync(dest, { recursive: true })
  for (const service of services) {
    const logs = distributedContainerLogs(service)
    if (logs === null) continue
    writeReportArtifact(path.join(dest, `distributed-${service}.log`), logs)
  }
  copyDistributedAppLogs(runDir)
}

function distributedContainerLogs(service) {
  try {
    return execFileSync(
      'docker',
      [
        'compose',
        '-f',
        path.join(REPO_ROOT, 'deploy/distributed/compose.yaml'),
        'logs',
        '--no-color',
        '--no-log-prefix',
        service,
      ],
      { encoding: 'utf8', timeout: 30_000, stdio: ['ignore', 'pipe', 'ignore'] },
    )
  } catch {
    return null
  }
}

export function parseArgs(argv) {
  const args = {
    baseUrl: 'http://127.0.0.1:18081',
    baseUrlB: '',
    frontendUrl: '',
    daemonEnv: 'tool-e2e',
    real: false,
    withTools: false,
    withBranch: false,
    withCanvasStorage: false,
    withCanvasFunction: false,
    distributed: false,
    only: [],
    uiOnly: false,
    level: [],
    list: false,
    docs: false,
    reportRoot: defaultReportRoot(),
    noReport: false,
  }
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i]
    const next = () => argv[++i]
    switch (a) {
      case '--base-url':
        args.baseUrl = next()
        break
      case '--base-url-b':
        args.baseUrlB = next()
        break
      case '--frontend-url':
        args.frontendUrl = next()
        break
      case '--daemon-env':
        args.daemonEnv = next()
        break
      case '--results-only':
        args.resultsOnly = true
        break
      case '--real':
        args.real = true
        break
      case '--real-model':
        if (args.realModel) throw new Error('--real-model may only be specified once')
        args.realModel = resolveRealModelSelection(next())
        args.real = true
        break
      case '--with-tools':
        args.withTools = true
        break
      case '--with-branch':
        args.withBranch = true
        args.real = true
        break
      case '--with-canvas-storage':
        args.withCanvasStorage = true
        break
      case '--with-canvas-function':
        args.withCanvasFunction = true
        args.withCanvasStorage = true
        break
      case '--distributed':
        args.distributed = true
        break
      case '--only':
        args.only.push(next())
        break
      case '--ui-only':
        args.uiOnly = true
        break
      case '--level':
        args.level.push(next())
        break
      case '--list':
        args.list = true
        break
      case '--docs':
        args.docs = true
        break
      case '--report-root':
        args.reportRoot = path.resolve(next())
        break
      case '--no-report':
        args.noReport = true
        break
      case '-h':
      case '--help':
        args.help = true
        break
      default:
        throw new Error(`unknown arg: ${a}`)
    }
  }
  if (args.resultsOnly && (!args.real || args.distributed || args.noReport)) {
    throw new Error('--results-only requires a real, single-stack run with reports enabled')
  }
  // distributed 是正交 capability：显式开关 + 双 URL 缺一不可。
  if (args.distributed && args.realModel) {
    throw new Error('--distributed cannot be combined with --real-model')
  }
  if (args.distributed && !args.baseUrlB) {
    throw new Error('--distributed requires --base-url-b')
  }
  if (!args.distributed && args.baseUrlB) {
    throw new Error('--base-url-b requires --distributed')
  }
  if (args.uiOnly && (!args.only.length || !args.only.every((id) => id.startsWith('ui.')))) {
    throw new Error('--ui-only requires only UI case ids')
  }
  return args
}

export function caseEnabled(c, args) {
  if (c.id === 'frontend.proxy_model_contract' && !args.frontendUrl) return false
  if (c.requires.has('real') && !args.real) return false
  if (args.realModel && c.modelIdSuffix && c.modelIdSuffix !== args.realModel.idSuffix) return false
  if (c.requires.has('tools') && !args.withTools) return false
  if (c.requires.has('branch') && !args.withBranch) return false
  if (c.requires.has('canvas-storage') && !args.withCanvasStorage) return false
  if (c.requires.has('canvas-function') && !args.withCanvasFunction) return false
  if (c.requires.has('distributed') && !args.distributed) return false
  // host-mock 表示 case 依赖宿主 127.0.0.1 上自建的 mock server；单实例路径
  // （宿主 jar）天然满足，distributed（容器 App）无法回连宿主 loopback。
  if (c.requires.has('host-mock') && args.distributed) return false
  if (args.only.length && !args.only.includes(c.id)) return false
  if (args.level.length && !args.level.includes(c.level)) return false
  return true
}

async function verifyProviderExecutionBoundary(args) {
  if (args.real) return
  const { json } = await httpJson(
    args.baseUrl,
    'GET',
    '/api/platform/providers',
  )
  assertProviderExecutionBoundary({ real: false, providers: listResults(json) })
  console.log('Provider boundary: free mode confirmed every provider is unconfigured')
}

async function main(argv) {
  const args = parseArgs(argv)
  if (args.help) {
    console.log(`Usage: node scripts/dev/verify/e2e/run-matrix.mjs [options]
  --base-url URL --base-url-b URL --frontend-url URL
  --real --real-model ID --with-tools --with-branch --with-canvas-storage --with-canvas-function
  --distributed
  --only CASE_ID (repeatable) --level L1/L2/L3/L4/L5 (repeatable)
  --daemon-env NAME   Daemon environment name for tool cases
  --report-root DIR   Report directory (default: reports/e2e)
  --no-report         Run cases without writing a report
  --results-only      Keep only case results and timings (requires real mode)
  --list              List registered cases with level, id and requirements
  --docs              List registered cases with each case's docs
  -h, --help          Show this help`)
    return 0
  }
  if (args.list || args.docs) {
    for (const c of ALL_CASES) {
      console.log(
        `[${c.level}] ${c.id}  ${c.title}  requires=${[...c.requires].join(',') || '-'}`,
      )
      if (args.docs) {
        console.log(c.docs)
        console.log('-'.repeat(60))
      }
    }
    console.log(`\nTotal registered: ${ALL_CASES.length}`)
    return 0
  }

  const unknownApiIds = args.only.filter(
    (id) => !id.startsWith('ui.') && !ALL_CASES.some((c) => c.id === id),
  )
  if (unknownApiIds.length) {
    throw new Error(`unknown API case id: ${unknownApiIds.join(', ')}`)
  }
  const selected = ALL_CASES.filter((c) => caseEnabled(c, args))
  if (!selected.length && !args.uiOnly) {
    console.error('No cases selected')
    return 2
  }

  await loginStudio(args.baseUrl)
  if (args.frontendUrl) {
    setStudioCookie(args.frontendUrl, studioAuthHeaders(args.baseUrl).Cookie)
  }
  if (args.distributed && selected.length) await loginStudio(args.baseUrlB)

  await verifyProviderExecutionBoundary(args)

  const runElapsed = createDurationTimer()
  const startedAt = new Date().toISOString()
  let runId = ''
  let runDir = null
  if (!args.noReport) {
    mkdirSync(args.reportRoot, { recursive: true })
    ;({ runId, runDir } = createRunDir(args.reportRoot, args.resultsOnly))
    if (!args.resultsOnly) console.log(`Report dir: ${runDir}`)
  }

  const ctx = new CaseContext({
    baseUrl: args.baseUrl,
    baseUrlB: args.distributed ? args.baseUrlB : '',
    daemonEnv: args.daemonEnv,
    real: args.real,
    realModel: args.realModel,
    withTools: args.withTools,
    withBranch: args.withBranch,
    withCanvasStorage: args.withCanvasStorage,
    withCanvasFunction: args.withCanvasFunction,
    reportDir: runDir,
    resultsOnly: args.resultsOnly,
  })
  if (args.frontendUrl) ctx.vars.frontendUrl = args.frontendUrl.replace(/\/$/, '')

  if (!args.resultsOnly) {
    console.log(
      `Running ${selected.length}/${ALL_CASES.length} cases against ${args.baseUrl}` +
        (args.distributed ? ` and ${args.baseUrlB} (distributed)` : ''),
    )
  }
  const results = []
  for (const c of selected) {
    if (!args.resultsOnly) console.log(`\n==> [${c.level}] ${c.id}: ${c.title}`)
    ctx.caseId = c.id
    const elapsed = createDurationTimer()
    try {
      await c.run(ctx)
      const result = { id: c.id, level: c.level, status: 'pass', durationMs: elapsed() }
      if (!args.resultsOnly) {
        Object.assign(result, {
          title: c.title,
          error: null,
          traceback: null,
          artifactPaths: runDir ? collectArtifactPaths(runDir, c.id) : [],
        })
      }
      results.push(result)
      if (runDir) writeCaseResult(runDir, result)
      console.log(`PASS ${c.id}`)
    } catch (err) {
      const result = { id: c.id, level: c.level, status: 'fail', durationMs: elapsed() }
      if (!args.resultsOnly) {
        Object.assign(result, {
          title: c.title,
          error: redactSecrets(String(err?.message || err)),
          traceback: redactSecrets(err?.stack || String(err)),
          artifactPaths: [],
        })
        if (runDir) {
          ctx.writeArtifact('error.txt', `${result.error}\n\n${result.traceback}`)
          result.artifactPaths = collectArtifactPaths(runDir, c.id)
        }
      }
      if (runDir) writeCaseResult(runDir, result)
      results.push(result)
      console.error(args.resultsOnly ? `FAIL ${c.id}` : `FAIL ${c.id}: ${result.error}`)
    }
  }

  const finishedAt = new Date().toISOString()
  const runDurationMs = runElapsed()
  const failed = results.filter((r) => r.status === 'fail').map((r) => r.id)
  const totals = countResults(results)
  console.log(`\n${'='.repeat(60)}`)
  console.log(
    `total=${totals.total} pass=${totals.pass} fail=${totals.fail} skip=${totals.skip}`,
  )
  if (failed.length) console.log(`failed: ${failed.join(', ')}`)

  if (runDir) {
    if (!args.resultsOnly) maybeCopyRuntimeLogs(runDir)
    if (args.distributed) maybeCopyDistributedContainerLogs(runDir)
    const reportPath = writeSummaryAndReport(
      runDir,
      runId,
      args,
      results,
      startedAt,
      finishedAt,
      runDurationMs,
    )
    const latest = publishLatest(args.reportRoot, runDir)
    // machine-friendly pointer for agents
    writeFileSync(
      path.join(args.reportRoot, 'LATEST_RUN.txt'),
      `${runDir}\n${path.join(latest, 'report.md')}\n`,
      'utf8',
    )
    if (!args.resultsOnly) {
      console.log(`Report: ${reportPath}`)
      console.log(`Summary: ${path.join(runDir, 'summary.json')}`)
      console.log(`Latest: ${path.join(latest, 'report.md')}`)
    }
  }
  return failed.length ? 1 : 0
}

// 只设置 exitCode 并交回事件循环自然退出：process.exit 会丢弃管道上尚未 drain 的异步
// stdout/stderr 写入（--docs 等大输出会被截断），自然退出会等待 pending 写入完成后按 exitCode 结束。
if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  const argv = process.argv.slice(2)
  main(argv).then(
    (code) => {
      process.exitCode = code
    },
    (err) => {
      console.error(argv.includes('--results-only') ? 'E2E matrix could not complete' : redactSecrets(err?.stack || String(err)))
      process.exitCode = 2
    },
  )
}
