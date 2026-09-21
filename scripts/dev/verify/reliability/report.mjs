import { cpSync, existsSync, mkdirSync, rmSync, writeFileSync } from 'node:fs'
import path from 'node:path'

import {
  TOOL_NAMES,
  WRITE_PROOF,
  WRITE_PROOF_MARKER,
  WRITE_PROOF_META,
  sha256,
} from './matrix.mjs'
import { aggregateUsage, summarizeString } from './policy.mjs'

const SENSITIVE_KEY =
  /(?:credential|api.?key|authorization|gateway.?token|secret|password|base.?url|endpoint)/i

export function sanitizeForReport(value) {
  if (typeof value === 'string') return sanitizeString(value)
  if (Array.isArray(value)) return value.map(sanitizeForReport)
  if (value && typeof value === 'object') {
    const output = {}
    for (const [key, child] of Object.entries(value)) {
      output[key] = SENSITIVE_KEY.test(key) ? '[REDACTED]' : sanitizeForReport(child)
    }
    return output
  }
  return value
}

export function assertSafeReportString(content) {
  if (content.includes(WRITE_PROOF) || content.includes(WRITE_PROOF_MARKER)) {
    throw new Error('report contains the write proof payload or marker')
  }
  if (/https?:\/\/[^\s"'<>]+/i.test(content)) {
    throw new Error('report contains a complete HTTP endpoint')
  }
  if (/\bBearer\s+[A-Za-z0-9._~+/=-]+/i.test(content)) {
    throw new Error('report contains a bearer credential')
  }
  if (/\b(?:sk|key|token)-[A-Za-z0-9_-]{8,}\b/i.test(content)) {
    throw new Error('report contains a credential-like value')
  }
  if (
    /"(?:credential|apiKey|authorization|gatewayToken|secret|password|baseUrl|endpoint)"\s*:\s*"(?!\[REDACTED\])[^"]+"/i.test(
      content,
    )
  ) {
    throw new Error('report contains an unredacted sensitive JSON field')
  }
  if (
    /\b(?:TEST_MINIMAX_[A-Z_]+|credential|apiKey|gatewayToken|secret|password)=(?!\[REDACTED\])[^\s&]+/i.test(
      content,
    )
  ) {
    throw new Error('report contains an unredacted sensitive assignment')
  }
}

export function writeSafeJson(filePath, value) {
  const content = `${JSON.stringify(sanitizeForReport(value), null, 2)}\n`
  assertSafeReportString(content)
  mkdirSync(path.dirname(filePath), { recursive: true })
  writeFileSync(filePath, content, 'utf8')
}

export function writeSafeText(filePath, value) {
  const content = sanitizeString(String(value))
  assertSafeReportString(content)
  mkdirSync(path.dirname(filePath), { recursive: true })
  writeFileSync(filePath, content, 'utf8')
}

export function createRunDirectory(reportRoot, runId) {
  const runDir = path.join(reportRoot, runId)
  mkdirSync(path.join(runDir, 'cases'), { recursive: true })
  mkdirSync(path.join(runDir, 'artifacts'), { recursive: true })
  return runDir
}

export function writeCaseArtifacts(runDir, caseId, artifacts) {
  const artifactDir = path.join(runDir, 'artifacts', caseId)
  mkdirSync(artifactDir, { recursive: true })
  for (const [name, value] of Object.entries(artifacts)) {
    if (value == null) continue
    writeSafeJson(path.join(artifactDir, name), value)
  }
}

export function writeCaseResult(runDir, result) {
  writeSafeJson(path.join(runDir, 'cases', `${result.id}.json`), result)
}

export function writeSummaryAndReport({
  runDir,
  runId,
  selectedCases,
  results,
  startedAt,
  finishedAt,
  args,
  preflight,
  runError,
  systemPromptMeta,
  reassessment = null,
}) {
  const usage = aggregateUsage(results.map((result) => result.metrics))
  const byModel = {}
  for (const result of results) {
    byModel[result.model] ||= []
    byModel[result.model].push(result.metrics)
  }
  const modelUsage = Object.fromEntries(
    Object.entries(byModel).map(([model, metrics]) => [model, aggregateUsage(metrics)]),
  )
  const totals = {
    total: results.length,
    pass: results.filter((result) => result.status === 'pass').length,
    fail: results.filter((result) => result.status === 'fail').length,
    skip: results.filter((result) => result.status === 'skip').length,
    realTurns: results.filter((result) => result.turnStarted).length,
    unknownCostCases: results.filter((result) => result.turnStarted && !result.costKnown).length,
  }
  if (totals.realTurns > selectedCases.length) {
    throw new Error(`real turn count ${totals.realTurns} exceeds selected cases ${selectedCases.length}`)
  }

  const summary = {
    runId,
    startedAt,
    finishedAt,
    durationMs: new Date(finishedAt) - new Date(startedAt),
    status: totals.fail > 0 || totals.skip > 0 || runError ? 'fail' : 'pass',
    configuration: {
      daemonEnvironment: args.daemonEnv,
      selectedCaseIds: selectedCases.map((testCase) => testCase.id),
      maxCostUsd: args.maxCostUsd,
      toolNames: [...TOOL_NAMES],
      systemPrompt: systemPromptMeta,
      writeProof: {
        path: '.reliability-write-proof.txt',
        byteLength: WRITE_PROOF_META.byteLength,
        sha256: WRITE_PROOF_META.sha256,
      },
    },
    preflight,
    totals,
    usage,
    modelUsage,
    runError: runError ? errorRecord(runError) : null,
    reassessment,
    results,
  }
  writeSafeJson(path.join(runDir, 'summary.json'), summary)

  const lines = []
  lines.push(`# Agent Reliability Report \`${runId}\``)
  lines.push('')
  lines.push(`- Started: \`${startedAt}\``)
  lines.push(`- Finished: \`${finishedAt}\``)
  lines.push(`- Environment: \`${args.daemonEnv}\``)
  lines.push(`- Models: \`minimax/MiniMax-M2.7\`, \`minimax/MiniMax-M3\`; variant \`high\``)
  lines.push(`- Model-visible tool names: \`${TOOL_NAMES.join(', ')}\``)
  lines.push(`- Cost cap: USD ${formatCost(args.maxCostUsd)}; incurred: USD ${formatCost(usage.costTotal)}`)
  lines.push(
    `- Totals: pass=${totals.pass} fail=${totals.fail} skip=${totals.skip} realTurns=${totals.realTurns}/${selectedCases.length} unknownCostCases=${totals.unknownCostCases}`,
  )
  lines.push(`- Result: **${summary.status.toUpperCase()}**`)
  if (reassessment) {
    lines.push(
      `- Reassessment: \`${reassessment.mode}\` at \`${reassessment.at}\`; model turns started: ${reassessment.modelTurnsStarted}.`,
    )
    lines.push(`- Reassessment reason: ${reassessment.reason}`)
  }
  lines.push('')
  lines.push('## Isolation')
  lines.push('')
  lines.push(
    '- Every case was reset from a read-only anchor snapshot into its own disposable `/workspace/cases/<id>` clone with remotes removed.',
  )
  lines.push(
    '- Repair dependencies were installed by the stack helper; the long-running Daemon remained on the internal network.',
  )
  lines.push(
    '- Reports are redacted and exclude credentials, Provider endpoints, container environment, and the full write-proof payload.',
  )
  lines.push(
    `- Repair write proof: \`.reliability-write-proof.txt\`, ${WRITE_PROOF_META.byteLength} bytes, SHA-256 \`${WRITE_PROOF_META.sha256}\`.`,
  )
  lines.push('')
  lines.push('## Matrix')
  lines.push('')
  lines.push(
    '| Status | Case | Model | Anchor | Task | Tools (count/order) | Tokens (in/out/cache R/W/reason) | Cost | Tests | Failure |',
  )
  lines.push('| --- | --- | --- | --- | --- | --- | --- | ---: | --- | --- |')
  for (const result of results) {
    const metrics = result.metrics
    const tools = result.toolOrder?.length
      ? `${Object.entries(result.toolCounts ?? {})
          .map(([name, count]) => `${name}×${count}`)
          .join(', ')}; order: ${result.toolOrder.join(' → ')}`
      : '-'
    const tokens =
      `${metrics.inputTokens}/${metrics.outputTokens}/`
      + `${metrics.cacheReadTokens}/${metrics.cacheWriteTokens + metrics.cacheWriteLongTokens}/`
      + `${metrics.reasoningTokens}${result.costKnown ? '' : ' (cost unknown)'}`
    const tests = result.tests
      ? `pre=${result.tests.precheck ?? '-'}, post=${result.tests.postcheck ?? '-'}, diff=${result.tests.diffCheck ?? '-'}`
      : '-'
    const failure = result.failureCategory
      ? `${result.failureCategory}: ${summarizeString(result.error ?? '', 160)}`
      : '-'
    lines.push(
      `| ${result.status.toUpperCase()} | \`${result.id}\` | \`${result.model}\` | ${result.anchor} | ${result.taskClass} | ${escapeCell(tools)} | ${tokens} | $${formatCost(metrics.costTotal)} | ${escapeCell(tests)} | ${escapeCell(failure)} |`,
    )
  }
  lines.push('')
  lines.push('## Usage by model')
  lines.push('')
  lines.push('| Model | Assistant metadata rows | Input | Output | Cache read | Cache write | Reasoning | Cost |')
  lines.push('| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |')
  for (const model of ['minimax/MiniMax-M2.7', 'minimax/MiniMax-M3']) {
    const metrics = modelUsage[model] ?? aggregateUsage([])
    lines.push(
      `| \`${model}\` | ${metrics.assistantMessages} | ${metrics.inputTokens} | ${metrics.outputTokens} | ${metrics.cacheReadTokens} | ${metrics.cacheWriteTokens + metrics.cacheWriteLongTokens} | ${metrics.reasoningTokens} | $${formatCost(metrics.costTotal)} |`,
    )
  }
  lines.push('')
  lines.push('## Failure classification')
  lines.push('')
  lines.push('- `setup`: catalog, Environment, case reset, dependency install, seed, or precheck failure.')
  lines.push('- `model`: model invocation/error or damaged long-write arguments.')
  lines.push('- `tool`: durable tool error, missing tool result, or forbidden bash behavior.')
  lines.push('- `oracle`: required tool order or final-answer facts were not satisfied.')
  lines.push('- `postcheck`: repository state, exact source restoration, evidence hash, test, or diff check failed.')
  lines.push('- `cost-cap`: later case was not started because the configured cap had already been reached.')
  lines.push('')
  lines.push('## Artifacts')
  lines.push('')
  lines.push('- `summary.json`: run, model, token/cache, cost, and result totals.')
  lines.push('- `cases/<id>.json`: per-case classification and oracle outcome.')
  lines.push('- `artifacts/<id>/trace.json`: durable tool/text trace with long write content reduced to byte length and SHA-256.')
  lines.push('- `artifacts/<id>/precheck.json`, `postcheck.json`, `git-diff-summary.json`: preparation and repository evidence.')
  lines.push('')
  const markdown = `${lines.join('\n')}\n`
  writeSafeText(path.join(runDir, 'report.md'), markdown)
  return summary
}

export function publishLatest(reportRoot, runDir, runId) {
  const latest = path.join(reportRoot, 'latest-agent')
  if (existsSync(latest)) rmSync(latest, { recursive: true, force: true })
  cpSync(runDir, latest, { recursive: true })
  writeSafeText(path.join(reportRoot, 'LATEST_AGENT_RUN.txt'), `${runId}\n`)
}

export function errorRecord(error) {
  return {
    name: error?.name ?? 'Error',
    category: error?.category ?? null,
    message: sanitizeString(error?.message ?? String(error)),
    details: sanitizeForReport(error?.details ?? null),
  }
}

function sanitizeString(value) {
  const text = String(value)
  if (text.includes(WRITE_PROOF_MARKER)) {
    return `[REDACTED_WRITE_PROOF bytes=${Buffer.byteLength(text)} sha256=${sha256(text)} expectedBytes=${WRITE_PROOF_META.byteLength} expectedSha256=${WRITE_PROOF_META.sha256}]`
  }
  return text
    .replace(
      /("(?:credential|apiKey|authorization|gatewayToken|secret|password|baseUrl|endpoint)"\s*:\s*")([^"]*)"/gi,
      '$1[REDACTED]"',
    )
    .replace(
      /\b(TEST_MINIMAX_[A-Z_]+|credential|apiKey|gatewayToken|secret|password)=([^\s&]+)/gi,
      '$1=[REDACTED]',
    )
    .replace(/https?:\/\/[^\s"'<>]+/gi, '[REDACTED_URL]')
    .replace(/\bBearer\s+[A-Za-z0-9._~+/=-]+/gi, 'Bearer [REDACTED]')
    .replace(/\b(?:sk|key|token)-[A-Za-z0-9_-]{8,}\b/gi, '[REDACTED_CREDENTIAL]')
}

function formatCost(value) {
  return Number(value ?? 0).toFixed(6)
}

function escapeCell(value) {
  return String(value).replaceAll('|', '\\|').replaceAll('\n', ' ')
}
