#!/usr/bin/env node

import { readFileSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

import { CASES } from './matrix.mjs'
import { ReliabilityError, validateAnswerOracle, validateToolPolicy } from './policy.mjs'
import { publishLatest, writeCaseResult, writeSummaryAndReport } from './report.mjs'

const __filename = fileURLToPath(import.meta.url)
const __dirname = path.dirname(__filename)
const REPO_ROOT = path.resolve(__dirname, '../..')

export function main(argv = process.argv.slice(2)) {
  const args = parseArgs(argv)
  if (args.help) {
    console.log(usage())
    return 0
  }

  const runDir = path.join(args.reportRoot, args.runId)
  const previousSummary = readJson(path.join(runDir, 'summary.json'))
  const byId = new Map(CASES.map((testCase) => [testCase.id, testCase]))
  const selectedCases = previousSummary.configuration.selectedCaseIds.map((id) => {
    const testCase = byId.get(id)
    if (!testCase) throw new Error(`archived run contains an unknown case id: ${id}`)
    return testCase
  })
  const results = previousSummary.results.map((previous) => {
    const testCase = byId.get(previous.id)
    if (!testCase) throw new Error(`archived result contains an unknown case id: ${previous.id}`)
    const trace = readJson(path.join(runDir, 'artifacts', previous.id, 'trace.json'))
    const result = reassessResult(testCase, previous, trace)
    writeCaseResult(runDir, result)
    return result
  })
  const reassessment = {
    at: new Date().toISOString(),
    mode: 'offline-archived-trace',
    modelTurnsStarted: 0,
    reason:
      'Re-evaluated archived durable traces and existing postchecks with the current runner policy; no Agent or Provider call was made.',
  }
  const summary = writeSummaryAndReport({
    runDir,
    runId: args.runId,
    selectedCases,
    results,
    startedAt: previousSummary.startedAt,
    finishedAt: previousSummary.finishedAt,
    args: {
      daemonEnv: previousSummary.configuration.daemonEnvironment,
      maxCostUsd: previousSummary.configuration.maxCostUsd,
    },
    preflight: previousSummary.preflight,
    runError: restoreRunError(previousSummary.runError),
    systemPromptMeta: previousSummary.configuration.systemPrompt,
    reassessment,
  })
  publishLatest(args.reportRoot, runDir, args.runId)
  console.log(
    `Offline reassessment complete: ${path.join(runDir, 'report.md')} `
      + `result=${summary.status.toUpperCase()} pass=${summary.totals.pass} `
      + `fail=${summary.totals.fail} skip=${summary.totals.skip} modelTurnsStarted=0`,
  )
  return summary.status === 'pass' ? 0 : 1
}

export function reassessResult(testCase, previous, trace) {
  const result = structuredClone(previous)
  if (!result.turnStarted || result.status === 'skip') return result

  const traceData = {
    toolCalls: (trace.events ?? []).filter((event) => event.kind === 'tool_call'),
    toolResults: (trace.events ?? []).filter((event) => event.kind === 'tool_result'),
    writeDiagnostics: trace.writeDiagnostics ?? [],
    finalText: trace.finalText ?? '',
  }
  try {
    validateToolPolicy(testCase, traceData)
    validateAnswerOracle(testCase, traceData.finalText)
  } catch (error) {
    const normalized =
      error instanceof ReliabilityError
        ? error
        : new ReliabilityError('oracle', error?.message ?? String(error))
    result.status = 'fail'
    result.failureCategory = normalized.category
    result.error = normalized.message
    result.errorDetails = normalized.details
    return result
  }

  if (result.tests?.postcheck === 'pass' && result.tests?.diffCheck === 'pass') {
    result.status = 'pass'
    result.failureCategory = null
    result.error = null
    result.errorDetails = null
    return result
  }
  result.status = 'fail'
  result.failureCategory = 'postcheck'
  result.error = 'archived repository postcheck did not pass'
  result.errorDetails = null
  return result
}

function parseArgs(argv) {
  const args = {
    help: false,
    runId: null,
    reportRoot: path.join(REPO_ROOT, 'reports/reliability'),
  }
  for (let index = 0; index < argv.length; index++) {
    const token = argv[index]
    if (token === '--help' || token === '-h') {
      args.help = true
    } else if (token === '--report-root') {
      const value = argv[++index]
      if (!value) throw new Error('--report-root requires a value')
      args.reportRoot = path.resolve(REPO_ROOT, value)
    } else if (args.runId == null) {
      args.runId = token
    } else {
      throw new Error(`unexpected argument: ${token}`)
    }
  }
  if (!args.help && !/^[0-9]{8}T[0-9]{6}Z-[a-z0-9]{8}$/.test(args.runId ?? '')) {
    throw new Error('runId must match YYYYMMDDTHHMMSSZ-xxxxxxxx')
  }
  return args
}

function usage() {
  return [
    'Usage: node scripts/reliability/reassess-agent-run.mjs <runId> [options]',
    '',
    'Re-evaluate archived traces and postcheck facts with the current policy.',
    'This command never creates an Agent/Chat/Thread and never calls a Provider.',
    '',
    'Options:',
    '  --report-root <path>  Report root (default: reports/reliability).',
    '  --help, -h            Show this help.',
  ].join('\n')
}

function readJson(filePath) {
  return JSON.parse(readFileSync(filePath, 'utf8'))
}

function restoreRunError(record) {
  if (!record) return null
  const error = new Error(record.message)
  error.name = record.name ?? 'Error'
  error.category = record.category ?? null
  error.details = record.details ?? null
  return error
}

if (process.argv[1] && pathToFileURL(path.resolve(process.argv[1])).href === import.meta.url) {
  try {
    process.exitCode = main()
  } catch (error) {
    console.error(`ERROR: ${error.message}`)
    process.exitCode = 2
  }
}
