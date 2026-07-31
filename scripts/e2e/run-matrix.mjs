#!/usr/bin/env node
/**
 * 矩阵化 API/链路 E2E runner（Node，零额外依赖）。
 *
 * 结构：
 * - scripts/e2e/run-matrix.mjs          CLI + 报告
 * - scripts/e2e/lib/*                   HTTP/fixtures/registry
 * - scripts/e2e/cases/*                 用例注册
 *
 * 报告（gitignore）：
 *   reports/e2e/<runId>/{report.md,summary.json,cases/,artifacts/,logs/}
 *   reports/e2e/latest/report.md
 *
 * npm：
 *   npm --prefix frontend run e2e
 *   npm --prefix frontend run e2e:matrix
 */

import {
  copyFileSync,
  cpSync,
  existsSync,
  mkdirSync,
  readdirSync,
  rmSync,
  statSync,
  writeFileSync,
} from 'node:fs'
import path from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { randomUUID } from 'node:crypto'
import { httpJson } from './lib/http.mjs'
import { ALL_CASES } from './lib/registry.mjs'

const __filename = fileURLToPath(import.meta.url)
const __dirname = path.dirname(__filename)
const REPO_ROOT = path.resolve(__dirname, '../..')

// 加载全部 case 模块（注册副作用）
await import('./cases/seed-and-harness.mjs')
await import('./cases/crud.mjs')
await import('./cases/i18n.mjs')
await import('./cases/config-matrix.mjs')
await import('./cases/real.mjs')

class CaseContext {
  constructor({ baseUrl, daemonEnv, real, withTools, withBranch, reportDir }) {
    this.baseUrl = baseUrl
    this.daemonEnv = daemonEnv
    this.real = real
    this.withTools = withTools
    this.withBranch = withBranch
    this.reportDir = reportDir
    this.caseId = null
    this.vars = {}
  }

  artifactsDir() {
    if (!this.reportDir || !this.caseId) throw new Error('reportDir/caseId not initialized')
    const dir = path.join(this.reportDir, 'artifacts', this.caseId.replaceAll('.', '_'))
    mkdirSync(dir, { recursive: true })
    return dir
  }

  writeArtifact(name, content) {
    const file = path.join(this.artifactsDir(), name)
    if (Buffer.isBuffer(content) || content instanceof Uint8Array) writeFileSync(file, content)
    else writeFileSync(file, String(content), 'utf8')
    return file
  }

  async call(method, requestPath, body, timeoutMs) {
    return httpJson(this.baseUrl, method, requestPath, body, timeoutMs)
  }
}

function defaultReportRoot() {
  if (process.env.E2E_REPORT_ROOT) return path.resolve(process.env.E2E_REPORT_ROOT)
  return path.join(REPO_ROOT, 'reports', 'e2e')
}

function createRunDir(reportRoot) {
  const runId = `${new Date().toISOString().replace(/[:.]/g, '-').replace('T', '-').slice(0, 19)}-${randomUUID().slice(0, 8)}`
  const runDir = path.join(reportRoot, runId)
  mkdirSync(path.join(runDir, 'cases'), { recursive: true })
  mkdirSync(path.join(runDir, 'artifacts'), { recursive: true })
  mkdirSync(path.join(runDir, 'logs'), { recursive: true })
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
  writeFileSync(
    path.join(runDir, 'cases', `${result.id}.json`),
    `${JSON.stringify(result, null, 2)}\n`,
    'utf8',
  )
}

function writeSummaryAndReport(runDir, runId, args, results, startedAt, finishedAt) {
  const passed = results.filter((r) => r.status === 'pass')
  const failed = results.filter((r) => r.status === 'fail')
  const skipped = results.filter((r) => r.status === 'skip')
  const byLevel = {}
  for (const r of results) {
    byLevel[r.level] ||= { total: 0, pass: 0, fail: 0 }
    byLevel[r.level].total++
    byLevel[r.level][r.status === 'pass' ? 'pass' : 'fail']++
  }
  const summary = {
    runId,
    startedAt,
    finishedAt,
    durationMs: new Date(finishedAt) - new Date(startedAt),
    baseUrl: args.baseUrl,
    frontendUrl: args.frontendUrl || null,
    flags: {
      real: args.real,
      withTools: args.withTools,
      withBranch: args.withBranch,
      only: args.only,
      level: args.level,
    },
    totals: {
      total: results.length,
      pass: passed.length,
      fail: failed.length,
      skip: skipped.length,
      byLevel,
    },
    failedCaseIds: failed.map((r) => r.id),
    results,
    reportMarkdown: 'report.md',
  }
  writeFileSync(path.join(runDir, 'summary.json'), `${JSON.stringify(summary, null, 2)}\n`, 'utf8')

  const lines = []
  lines.push(`# E2E Report \`${runId}\``)
  lines.push('')
  lines.push(`- Started: \`${startedAt}\``)
  lines.push(`- Finished: \`${finishedAt}\``)
  lines.push(`- Backend: \`${args.baseUrl}\``)
  if (args.frontendUrl) lines.push(`- Frontend: \`${args.frontendUrl}\``)
  lines.push(`- Flags: real=${args.real} tools=${args.withTools} branch=${args.withBranch}`)
  lines.push(
    `- Totals: total=${results.length} pass=${passed.length} fail=${failed.length} skip=${skipped.length}`,
  )
  lines.push(`- Result: **${failed.length ? 'FAIL' : 'PASS'}**`)
  lines.push('')
  lines.push('## By Level')
  lines.push('')
  lines.push('| Level | Total | Pass | Fail |')
  lines.push('| --- | ---: | ---: | ---: |')
  for (const level of Object.keys(byLevel).sort()) {
    const x = byLevel[level]
    lines.push(`| ${level} | ${x.total} | ${x.pass} | ${x.fail} |`)
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
    lines.push('3. `logs/` or `target/e2e/`')
    lines.push('4. Re-run: `./scripts/e2e.sh --only <caseId>`')
    lines.push('')
  } else {
    lines.push('## Notes')
    lines.push('')
    lines.push('All selected cases passed.')
    lines.push('')
  }
  const reportPath = path.join(runDir, 'report.md')
  writeFileSync(reportPath, `${lines.join('\n')}\n`, 'utf8')
  return reportPath
}

function publishLatest(reportRoot, runDir) {
  const latest = path.join(reportRoot, 'latest')
  if (existsSync(latest)) rmSync(latest, { recursive: true, force: true })
  cpSync(runDir, latest, { recursive: true })
  return latest
}

function maybeCopyRuntimeLogs(runDir) {
  const candidates = [path.join(REPO_ROOT, 'target', 'e2e'), path.join(REPO_ROOT, 'target', 'dev')]
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
      if (existsSync(src)) copyFileSync(src, path.join(dest, `${path.basename(base)}-${name}`))
    }
  }
}

function parseArgs(argv) {
  const args = {
    baseUrl: 'http://127.0.0.1:18081',
    frontendUrl: '',
    daemonEnv: 'tool-e2e',
    real: false,
    withTools: false,
    withBranch: false,
    only: [],
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
      case '--frontend-url':
        args.frontendUrl = next()
        break
      case '--daemon-env':
        args.daemonEnv = next()
        break
      case '--real':
        args.real = true
        break
      case '--with-tools':
        args.withTools = true
        break
      case '--with-branch':
        args.withBranch = true
        args.real = true
        break
      case '--only':
        args.only.push(next())
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
  return args
}

function caseEnabled(c, args) {
  if (c.id === 'frontend.proxy_model_contract' && !args.frontendUrl) return false
  if (c.requires.has('real') && !args.real) return false
  if (c.requires.has('tools') && !args.withTools) return false
  if (c.requires.has('branch') && !args.withBranch) return false
  if (args.only.length && !args.only.includes(c.id)) return false
  if (args.level.length && !args.level.includes(c.level)) return false
  return true
}

async function main(argv) {
  const args = parseArgs(argv)
  if (args.help) {
    console.log(`Usage: node scripts/e2e/run-matrix.mjs [options]
  --base-url --frontend-url --real --with-tools --with-branch
  --only <id> --level L1 --list --docs --report-root DIR`)
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

  const selected = ALL_CASES.filter((c) => caseEnabled(c, args))
  if (!selected.length) {
    console.error('No cases selected')
    return 2
  }

  const startedAt = new Date().toISOString()
  let runId = ''
  let runDir = null
  if (!args.noReport) {
    mkdirSync(args.reportRoot, { recursive: true })
    ;({ runId, runDir } = createRunDir(args.reportRoot))
    console.log(`Report dir: ${runDir}`)
  }

  const ctx = new CaseContext({
    baseUrl: args.baseUrl,
    daemonEnv: args.daemonEnv,
    real: args.real,
    withTools: args.withTools,
    withBranch: args.withBranch,
    reportDir: runDir,
  })
  if (args.frontendUrl) ctx.vars.frontendUrl = args.frontendUrl.replace(/\/$/, '')

  console.log(`Running ${selected.length}/${ALL_CASES.length} cases against ${args.baseUrl}`)
  const results = []
  for (const c of selected) {
    console.log(`\n==> [${c.level}] ${c.id}: ${c.title}`)
    ctx.caseId = c.id
    const t0 = Date.now()
    try {
      await c.run(ctx)
      const result = {
        id: c.id,
        level: c.level,
        title: c.title,
        status: 'pass',
        durationMs: Date.now() - t0,
        error: null,
        traceback: null,
        artifactPaths: runDir ? collectArtifactPaths(runDir, c.id) : [],
      }
      results.push(result)
      if (runDir) writeCaseResult(runDir, result)
      console.log(`PASS ${c.id}`)
    } catch (err) {
      const tb = err?.stack || String(err)
      const result = {
        id: c.id,
        level: c.level,
        title: c.title,
        status: 'fail',
        durationMs: Date.now() - t0,
        error: String(err?.message || err),
        traceback: tb,
        artifactPaths: [],
      }
      if (runDir) {
        ctx.writeArtifact('error.txt', `${result.error}\n\n${tb}`)
        result.artifactPaths = collectArtifactPaths(runDir, c.id)
        writeCaseResult(runDir, result)
      }
      results.push(result)
      console.error(`FAIL ${c.id}: ${result.error}`)
    }
  }

  const finishedAt = new Date().toISOString()
  const failed = results.filter((r) => r.status === 'fail').map((r) => r.id)
  console.log(`\n${'='.repeat(60)}`)
  console.log(
    `total=${results.length} pass=${results.length - failed.length} fail=${failed.length}`,
  )
  if (failed.length) console.log(`failed: ${failed.join(', ')}`)

  if (runDir) {
    maybeCopyRuntimeLogs(runDir)
    const reportPath = writeSummaryAndReport(runDir, runId, args, results, startedAt, finishedAt)
    const latest = publishLatest(args.reportRoot, runDir)
    // machine-friendly pointer for agents
    writeFileSync(
      path.join(args.reportRoot, 'LATEST_RUN.txt'),
      `${runDir}\n${path.join(latest, 'report.md')}\n`,
      'utf8',
    )
    console.log(`Report: ${reportPath}`)
    console.log(`Summary: ${path.join(runDir, 'summary.json')}`)
    console.log(`Latest: ${path.join(latest, 'report.md')}`)
  }
  return failed.length ? 1 : 0
}

// keep import for potential dynamic loaders
void pathToFileURL

main(process.argv.slice(2)).then(
  (code) => process.exit(code),
  (err) => {
    console.error(err)
    process.exit(2)
  },
)
