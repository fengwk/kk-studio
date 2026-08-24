#!/usr/bin/env node

import { spawnSync } from 'node:child_process'
import { cpSync, existsSync, lstatSync, mkdirSync, rmSync, writeFileSync } from 'node:fs'
import { randomUUID } from 'node:crypto'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const THIS_FILE = fileURLToPath(import.meta.url)
export const REPO_ROOT = path.resolve(path.dirname(THIS_FILE), '../..')
export const DEFAULT_BASE_URL = 'http://127.0.0.1:18088'
export const DEFAULT_DURATION_SECONDS = 10
export const MIN_DURATION_SECONDS = 1
export const MAX_DURATION_SECONDS = 120
export const WARMUP_SECONDS = 1
export const REQUEST_TIMEOUT_MS = 5_000
export const STARTUP_TIMEOUT_MS = 120_000
export const MIN_SAMPLES = 20

export const THRESHOLDS = Object.freeze({
  health: Object.freeze({
    maxErrors: 0,
    maxP95Ms: 250,
    minThroughputRps: 50,
    minSamples: MIN_SAMPLES,
  }),
  catalog: Object.freeze({
    maxErrors: 0,
    maxP95Ms: 500,
    minThroughputRps: 25,
    minSamples: MIN_SAMPLES,
  }),
  canvas: Object.freeze({
    maxErrors: 0,
    maxP95Ms: 1_500,
    minThroughputRps: 5,
    minSamples: MIN_SAMPLES,
  }),
})

export const SCENARIOS = Object.freeze([
  Object.freeze({
    id: 'health',
    name: 'Health',
    concurrency: 16,
    path: '/actuator/health',
  }),
  Object.freeze({
    id: 'catalog',
    name: 'Catalog models',
    concurrency: 16,
    path: '/api/ai/catalog/models?pageNumber=1&pageSize=20',
  }),
  Object.freeze({
    id: 'canvas',
    name: 'Canvas transaction write',
    concurrency: 4,
    path: '/api/canvases',
  }),
])

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
const DECIMAL_PATTERN = /^(0|[1-9][0-9]*)$/

export class BenchmarkError extends Error {
  constructor(message, details = {}) {
    super(message)
    this.name = 'BenchmarkError'
    this.details = details
  }
}

class InterruptedError extends BenchmarkError {
  constructor(signal) {
    super(`benchmark interrupted by ${signal}`)
    this.name = 'InterruptedError'
    this.signal = signal
  }
}

class RequestTimeoutError extends BenchmarkError {
  constructor(method, requestPath, durationMs) {
    super(`timeout ${method} ${requestPath}`)
    this.name = 'RequestTimeoutError'
    this.durationMs = durationMs
  }
}

class RequestFailureError extends BenchmarkError {
  constructor(method, requestPath, durationMs, cause) {
    super(`request failed ${method} ${requestPath}: ${cause?.message || cause}`)
    this.name = 'RequestFailureError'
    this.durationMs = durationMs
    this.cause = cause
  }
}

export function parseArgs(argv, { cwd = process.cwd(), repoRoot = REPO_ROOT } = {}) {
  const args = {
    help: false,
    skipBuild: false,
    durationSeconds: DEFAULT_DURATION_SECONDS,
    reportRoot: path.resolve(repoRoot, 'reports/performance'),
  }

  for (let index = 0; index < argv.length; index += 1) {
    const token = argv[index]
    if (token === '--help' || token === '-h') {
      args.help = true
    } else if (token === '--skip-build') {
      args.skipBuild = true
    } else if (token === '--duration-seconds') {
      args.durationSeconds = parseDuration(requireValue(argv, ++index, token))
    } else if (token === '--report-root') {
      args.reportRoot = path.resolve(cwd, requireValue(argv, ++index, token))
    } else {
      throw new Error(`unknown argument: ${token}`)
    }
  }

  args.reportRoot = validateReportRoot(args.reportRoot, { repoRoot })
  return args
}

export function parseDuration(value) {
  if (!/^(0|[1-9][0-9]*)$/.test(value)) {
    throw new Error(
      `--duration-seconds must be an integer between ${MIN_DURATION_SECONDS} and ${MAX_DURATION_SECONDS}: ${value}`,
    )
  }
  const durationSeconds = Number(value)
  if (
    !Number.isSafeInteger(durationSeconds) ||
    durationSeconds < MIN_DURATION_SECONDS ||
    durationSeconds > MAX_DURATION_SECONDS
  ) {
    throw new Error(
      `--duration-seconds must be an integer between ${MIN_DURATION_SECONDS} and ${MAX_DURATION_SECONDS}: ${value}`,
    )
  }
  return durationSeconds
}

export function validateReportRoot(input, { repoRoot = REPO_ROOT } = {}) {
  if (typeof input !== 'string' || input.length === 0) {
    throw new Error('--report-root requires a non-empty path')
  }
  const resolved = path.resolve(input)
  const root = path.parse(resolved).root
  const resolvedRepoRoot = path.resolve(repoRoot)
  if (resolved === root) {
    throw new Error(`unsafe report root: refusing filesystem root ${resolved}`)
  }
  if (resolved === resolvedRepoRoot) {
    throw new Error(`unsafe report root: refusing repository root ${resolved}`)
  }
  assertNoSymlinkInPath(resolved)
  const existing = tryLstat(resolved)
  if (existing && !existing.isDirectory()) {
    throw new Error(`report root is not a directory: ${resolved}`)
  }
  return resolved
}

export function assertNoSymlinkInPath(target) {
  let current = path.resolve(target)
  while (true) {
    const stats = tryLstat(current)
    if (stats?.isSymbolicLink()) {
      throw new Error(`unsafe report path: symlink component is not allowed: ${current}`)
    }
    const parent = path.dirname(current)
    if (parent === current) return
    current = parent
  }
}

export function nearestRankPercentile(values, percentile) {
  if (!Array.isArray(values) || values.length === 0) {
    throw new Error('percentile requires at least one value')
  }
  if (!Number.isFinite(percentile) || percentile <= 0 || percentile > 100) {
    throw new Error(`percentile must be in (0, 100]: ${percentile}`)
  }
  const sorted = values
    .map((value) => {
      const numeric = Number(value)
      if (!Number.isFinite(numeric) || numeric < 0) {
        throw new Error(`percentile values must be finite and non-negative: ${value}`)
      }
      return numeric
    })
    .sort((left, right) => left - right)
  const rank = Math.ceil((percentile / 100) * sorted.length)
  return sorted[Math.max(0, rank - 1)]
}

export const percentile = nearestRankPercentile

export function summarizeSamples(samples, durationSeconds) {
  if (!Number.isFinite(durationSeconds) || durationSeconds <= 0) {
    throw new Error(`durationSeconds must be positive: ${durationSeconds}`)
  }
  if (!Array.isArray(samples)) {
    throw new Error('samples must be an array')
  }

  const requests = samples.length
  const latencies = samples.map((sample) => {
    const durationMs = Number(sample?.durationMs ?? sample?.latencyMs)
    if (!Number.isFinite(durationMs) || durationMs < 0) {
      throw new Error(`sample duration must be finite and non-negative: ${durationMs}`)
    }
    return durationMs
  })
  const success = samples.filter((sample) => sample?.ok === true || sample?.success === true).length
  const error = requests - success
  const errorRate = requests === 0 ? 1 : error / requests
  const seconds = durationSeconds

  return {
    sampleCount: requests,
    durationSeconds,
    requests,
    success,
    error,
    errorRate,
    requestsPerSecond: requests / seconds,
    throughputRps: success / seconds,
    p50Ms: latencies.length > 0 ? nearestRankPercentile(latencies, 50) : null,
    p95Ms: latencies.length > 0 ? nearestRankPercentile(latencies, 95) : null,
    p99Ms: latencies.length > 0 ? nearestRankPercentile(latencies, 99) : null,
    maxMs: latencies.length > 0 ? Math.max(...latencies) : null,
  }
}

export function evaluateScenario(scenarioId, metrics) {
  const threshold = THRESHOLDS[scenarioId]
  if (!threshold) {
    throw new Error(`unknown performance scenario: ${scenarioId}`)
  }

  const failures = []
  if (metrics.requests < threshold.minSamples) {
    failures.push(`samples ${metrics.requests} < minimum ${threshold.minSamples}`)
  }
  if (metrics.error > threshold.maxErrors) {
    failures.push(`errors ${metrics.error} > maximum ${threshold.maxErrors}`)
  }
  if (metrics.p95Ms == null || metrics.p95Ms > threshold.maxP95Ms) {
    failures.push(`p95 ${formatMetric(metrics.p95Ms)}ms > ${threshold.maxP95Ms}ms`)
  }
  if (
    metrics.throughputRps == null ||
    !Number.isFinite(metrics.throughputRps) ||
    metrics.throughputRps < threshold.minThroughputRps
  ) {
    failures.push(
      `throughput ${formatMetric(metrics.throughputRps)}rps < ${threshold.minThroughputRps}rps`,
    )
  }

  return {
    passed: failures.length === 0,
    failures,
    threshold,
  }
}

export function validateHealthResponse(response) {
  requireStatus(response, 200, 'health')
  requireObject(response.json, 'health response')
  if (response.json.status !== 'UP') {
    throw new BenchmarkError(`health response status must be UP, got ${JSON.stringify(response.json.status)}`)
  }
  return response.json
}

export function validateCatalogResponse(response) {
  requireStatus(response, 200, 'catalog')
  requireObject(response.json, 'catalog response')
  if (!Object.prototype.hasOwnProperty.call(response.json, 'data')) {
    throw new BenchmarkError('catalog response is missing the data envelope')
  }
  const data = response.json.data
  requireObject(data, 'catalog data')
  if (!Array.isArray(data.results)) {
    throw new BenchmarkError('catalog data.results must be an array')
  }
  if (data.results.length === 0) {
    throw new BenchmarkError('catalog data.results must not be empty')
  }
  if (data.results.length > 20) {
    throw new BenchmarkError(`catalog data.results exceeds requested page size: ${data.results.length}`)
  }

  for (const [index, model] of data.results.entries()) {
    requireObject(model, `catalog result ${index}`)
    requireText(model.providerName, `catalog result ${index}.providerName`)
    requireText(model.name, `catalog result ${index}.name`)
    requireObject(model.config, `catalog result ${index}.config`)
    requireText(model.config.defaultVariant, `catalog result ${index}.config.defaultVariant`)
    if (!Array.isArray(model.config.variants) || model.config.variants.length === 0) {
      throw new BenchmarkError(`catalog result ${index}.config.variants must be a non-empty array`)
    }
    requireObject(model.config.limit, `catalog result ${index}.config.limit`)
    const context = Number(model.config.limit.context)
    if (!Number.isFinite(context) || context <= 0) {
      throw new BenchmarkError(`catalog result ${index}.config.limit.context must be positive`)
    }
    for (const forbidden of [
      'id',
      'providerId',
      'modelId',
      'configJson',
      'capabilitiesJson',
      'threadId',
    ]) {
      if (Object.prototype.hasOwnProperty.call(model, forbidden)) {
        throw new BenchmarkError(`catalog result ${index} exposes forbidden field ${forbidden}`)
      }
    }
  }
  return data.results
}

export function validateCanvasCreateResponse(response) {
  if (!response || response.status < 200 || response.status >= 300) {
    throw new BenchmarkError(`canvas create expected a 2xx response, got ${response?.status}`)
  }
  requireObject(response.json, 'canvas create response')
  if (!Object.prototype.hasOwnProperty.call(response.json, 'data')) {
    throw new BenchmarkError('canvas create response is missing the data envelope')
  }
  const canvas = response.json.data
  requireObject(canvas, 'canvas create data')
  if (typeof canvas.id !== 'string' || !UUID_PATTERN.test(canvas.id)) {
    throw new BenchmarkError(`canvas create id must be a canonical UUID: ${JSON.stringify(canvas.id)}`)
  }
  if (typeof canvas.version !== 'string' || !DECIMAL_PATTERN.test(canvas.version)) {
    throw new BenchmarkError(`canvas create version must be a canonical decimal string`)
  }
  if (Object.prototype.hasOwnProperty.call(canvas, 'threadId')) {
    throw new BenchmarkError('canvas create response must not expose threadId')
  }
  if (Object.prototype.hasOwnProperty.call(canvas, 'graphVersion')) {
    throw new BenchmarkError('canvas create response must not expose graphVersion')
  }
  return canvas
}

export function validateCanvasDeleteResponse(response) {
  if (!response || response.status < 200 || response.status >= 300) {
    throw new BenchmarkError(`canvas delete expected a 2xx response, got ${response?.status}`)
  }
}

export function validateCanvasListResponse(response) {
  requireStatus(response, 200, 'canvas list')
  requireObject(response.json, 'canvas list response')
  if (!Array.isArray(response.json.data)) {
    throw new BenchmarkError('canvas list data must be an array')
  }
  return response.json.data
}

export function usage() {
  return [
    'Usage: ./scripts/performance.sh [options]',
    '',
    'Run the free, machine-local performance baseline in the isolated deploy/test stack.',
    'The shell entrypoint owns Docker build/compose lifecycle; the Node runner uses Node built-in fetch.',
    '',
    'Options:',
    `  --duration-seconds N  Measurement duration per scenario (${MIN_DURATION_SECONDS}..${MAX_DURATION_SECONDS}, default: ${DEFAULT_DURATION_SECONDS}).`,
    '  --report-root DIR     Report directory (default: reports/performance).',
    '  --skip-build          Reuse the performance-baseline application image.',
    '  --help, -h            Show this help.',
  ].join('\n')
}

export async function runBenchmark({
  baseUrl = DEFAULT_BASE_URL,
  durationSeconds = DEFAULT_DURATION_SECONDS,
  reportRoot = path.join(REPO_ROOT, 'reports/performance'),
  skipBuild = false,
  startupTimeoutMs = STARTUP_TIMEOUT_MS,
  runtimeMetadata = null,
  now = () => new Date(),
} = {}) {
  const normalizedDuration = parseDuration(String(durationSeconds))
  const safeReportRoot = validateReportRoot(reportRoot)
  const runId = createRunId(now())
  const startedAt = now().toISOString()
  const stopState = { requested: false, signal: null }
  const manager = createRequestManager(baseUrl, stopState)
  const state = {
    canvasTitlePrefix: `performance-${runId}-`,
    canvasSequence: 0,
    createdCanvasIds: new Set(),
  }
  const scenarioResults = []
  let runError = null
  let finalCleanup = { attempted: 0, errors: [], remaining: [] }

  const onSignal = (signal) => {
    if (stopState.requested) return
    stopState.requested = true
    stopState.signal = signal
    manager.abortAll()
  }
  process.on('SIGINT', onSignal)
  process.on('SIGTERM', onSignal)

  try {
    await waitForHealthy(manager, stopState, startupTimeoutMs)
    for (const scenario of SCENARIOS) {
      if (stopState.requested) {
        runError = new InterruptedError(stopState.signal)
        break
      }

      let result
      try {
        result = await runScenario({
          scenario,
          manager,
          stopState,
          state,
          durationSeconds: normalizedDuration,
        })
      } catch (error) {
        if (!runError) runError = error
        result = failedScenarioResult(scenario, normalizedDuration, error)
      }

      if (scenario.id === 'canvas') {
        const cleanup = await cleanupCanvases({
          manager,
          state,
          scan:
            stopState.requested ||
            state.createdCanvasIds.size > 0 ||
            result.metrics.error > 0 ||
            result.warmup.error > 0,
        })
        result.cleanup = cleanup
        if (cleanup.errors.length > 0 || cleanup.remaining.length > 0) {
          result.gate.passed = false
          result.gate.failures.push(
            `canvas cleanup incomplete: errors=${cleanup.errors.length}, remaining=${cleanup.remaining.length}`,
          )
          result.status = 'fail'
        }
      }
      scenarioResults.push(result)

      if (stopState.requested) {
        runError ||= new InterruptedError(stopState.signal)
        break
      }
    }
  } catch (error) {
    runError = error
  } finally {
    if (stopState.requested) {
      manager.abortAll()
    }
    finalCleanup = await cleanupCanvases({
      manager,
      state,
      scan: stopState.requested || state.createdCanvasIds.size > 0,
    })
    process.off('SIGINT', onSignal)
    process.off('SIGTERM', onSignal)
  }

  for (const scenario of SCENARIOS) {
    if (!scenarioResults.some((result) => result.id === scenario.id)) {
      scenarioResults.push(
        failedScenarioResult(
          scenario,
          normalizedDuration,
          runError || new BenchmarkError('scenario was not run'),
        ),
      )
    }
  }
  scenarioResults.sort(
    (left, right) =>
      SCENARIOS.findIndex((scenario) => scenario.id === left.id) -
      SCENARIOS.findIndex((scenario) => scenario.id === right.id),
  )

  if (finalCleanup.errors.length > 0 || finalCleanup.remaining.length > 0) {
    runError ||= new BenchmarkError('canvas cleanup did not complete')
  }
  const finishedAt = now().toISOString()
  const summary = buildSummary({
    runId,
    startedAt,
    finishedAt,
    durationSeconds: normalizedDuration,
    reportRoot: safeReportRoot,
    baseUrl,
    skipBuild,
    scenarioResults,
    runError,
    finalCleanup,
    runtimeMetadata: runtimeMetadata || collectRuntimeMetadata(),
    interrupted: stopState.requested ? stopState.signal : null,
  })
  const report = writePerformanceReports({ reportRoot: safeReportRoot, summary })
  return { summary, ...report }
}

export function buildSummary({
  runId,
  startedAt,
  finishedAt,
  durationSeconds,
  reportRoot,
  baseUrl,
  skipBuild,
  scenarioResults,
  runError,
  finalCleanup,
  runtimeMetadata,
  interrupted,
}) {
  const status =
    !runError &&
    finalCleanup.errors.length === 0 &&
    finalCleanup.remaining.length === 0 &&
    scenarioResults.every((result) => result.status === 'pass')
      ? 'pass'
      : 'fail'
  return {
    schemaVersion: 1,
    runId,
    startedAt,
    finishedAt,
    durationMs: new Date(finishedAt) - new Date(startedAt),
    status,
    purpose: 'Machine-local free regression baseline; not capacity planning.',
    commit: runtimeMetadata.commit,
    runtime: {
      jdk: runtimeMetadata.jdk,
      node: runtimeMetadata.node,
      docker: runtimeMetadata.docker,
    },
    host: runtimeMetadata.host,
    image: runtimeMetadata.image,
    parameters: {
      baseUrl,
      durationSeconds,
      warmupSeconds: WARMUP_SECONDS,
      requestTimeoutMs: REQUEST_TIMEOUT_MS,
      reportRoot,
      skipBuild,
      concurrency: Object.fromEntries(
        SCENARIOS.map((scenario) => [scenario.id, scenario.concurrency]),
      ),
    },
    isolation: {
      composeFile: 'deploy/test/compose.yaml',
      profile: 'app',
      services: ['postgres', 'minio', 'minio-init', 'http-mock', 'app'],
      realProvider: false,
      daemon: false,
    },
    thresholds: THRESHOLDS,
    scenarios: scenarioResults,
    cleanup: finalCleanup,
    interrupted,
    runError: runError ? serializeError(runError) : null,
  }
}

export function writePerformanceReports({ reportRoot, summary }) {
  const safeReportRoot = validateReportRoot(reportRoot)
  mkdirSync(safeReportRoot, { recursive: true })
  assertNoSymlinkInPath(safeReportRoot)

  const runDir = path.join(safeReportRoot, summary.runId)
  mkdirSync(runDir)
  writeFileSync(path.join(runDir, 'summary.json'), `${JSON.stringify(summary, null, 2)}\n`, 'utf8')
  writeFileSync(path.join(runDir, 'report.md'), renderReport(summary), 'utf8')

  const latestDir = path.join(safeReportRoot, 'latest')
  assertNoSymlinkInPath(latestDir)
  if (existsSync(latestDir)) {
    rmSync(latestDir, { recursive: true, force: true })
  }
  cpSync(runDir, latestDir, { recursive: true })
  writeFileSync(path.join(safeReportRoot, 'LATEST_RUN.txt'), `${summary.runId}\n`, 'utf8')
  return { runDir, latestDir }
}

export function renderReport(summary) {
  const lines = [
    `# Free Performance Baseline \`${summary.runId}\``,
    '',
    `- Result: **${summary.status.toUpperCase()}**`,
    `- Purpose: ${summary.purpose}`,
    `- Commit: \`${summary.commit}\``,
    `- Started: \`${summary.startedAt}\``,
    `- Finished: \`${summary.finishedAt}\``,
    '',
    'This is a free, machine-local regression gate for the isolated test stack. It is not a capacity-planning result.',
    'The run does not start a real Provider or a daemon.',
    '',
    '## Runtime and host',
    '',
    '| Item | Value |',
    '| --- | --- |',
    `| JDK | ${escapeMarkdown(summary.runtime.jdk)} |`,
    `| Node | ${escapeMarkdown(summary.runtime.node)} |`,
    `| Docker | ${escapeMarkdown(summary.runtime.docker)} |`,
    `| Image | ${escapeMarkdown(summary.image.reference)} |`,
    `| Image ID | ${escapeMarkdown(summary.image.id)} |`,
    `| CPU | ${escapeMarkdown(`${summary.host.cpuCount} × ${summary.host.cpuModel}`)} |`,
    `| Memory | ${escapeMarkdown(`${summary.host.totalMemoryBytes} bytes total; ${summary.host.freeMemoryBytes} bytes free at report time`)} |`,
    '',
    '## Parameters',
    '',
    `- Base URL: \`${summary.parameters.baseUrl}\``,
    `- Measurement duration per scenario: ${summary.parameters.durationSeconds}s`,
    `- Warmup per scenario: ${summary.parameters.warmupSeconds}s`,
    `- Per-request timeout: ${summary.parameters.requestTimeoutMs}ms`,
    `- Report root: \`${summary.parameters.reportRoot}\``,
    `- Application image build skipped: \`${summary.parameters.skipBuild}\``,
    `- Concurrency: ${Object.entries(summary.parameters.concurrency)
      .map(([id, value]) => `${id}=${value}`)
      .join(', ')}`,
    '',
    '## Fixed thresholds',
    '',
    '| Scenario | Max errors | Max p95 (ms) | Min throughput (RPS) | Min samples |',
    '| --- | ---: | ---: | ---: | ---: |',
    ...Object.entries(summary.thresholds).map(
      ([id, threshold]) =>
        `| ${id} | ${threshold.maxErrors} | ${threshold.maxP95Ms} | ${threshold.minThroughputRps} | ${threshold.minSamples} |`,
    ),
    '',
    'Latency percentiles use the nearest-rank algorithm: sort all request latencies ascending and choose the value at `ceil(p / 100 × n)` using one-based rank. Errors remain in the latency sample.',
    '',
    '## Scenario results',
    '',
    '| Scenario | Requests | Success | Error | Error rate | Requests/s | Throughput RPS | p50 (ms) | p95 (ms) | p99 (ms) | Max (ms) | Samples | Gate |',
    '| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |',
    ...summary.scenarios.map((scenario) => {
      const metrics = scenario.metrics
      return `| ${scenario.id} | ${metrics.requests} | ${metrics.success} | ${metrics.error} | ${formatPercentage(metrics.errorRate)} | ${formatMetric(metrics.requestsPerSecond)} | ${formatMetric(metrics.throughputRps)} | ${formatMetric(metrics.p50Ms)} | ${formatMetric(metrics.p95Ms)} | ${formatMetric(metrics.p99Ms)} | ${formatMetric(metrics.maxMs)} | ${metrics.sampleCount} | **${scenario.status.toUpperCase()}**${formatFailures(scenario.gate.failures)} |`
    }),
    '',
    '## Isolation and cleanup',
    '',
    `- Compose: \`${summary.isolation.composeFile}\`, profile \`${summary.isolation.profile}\`.`,
    `- Services: ${summary.isolation.services.map((service) => `\`${service}\``).join(', ')}.`,
    `- Real Provider: \`${summary.isolation.realProvider}\`; daemon: \`${summary.isolation.daemon}\`.`,
    `- Final canvas cleanup attempts: ${summary.cleanup.attempted}; remaining IDs: ${summary.cleanup.remaining.length}; cleanup errors: ${summary.cleanup.errors.length}.`,
    '',
    '## Failure details',
    '',
    summary.runError ? `- Runner: ${escapeMarkdown(summary.runError.message)}` : '- Runner: none.',
    ...(summary.scenarios.flatMap((scenario) =>
      scenario.gate.failures.length > 0
        ? [`- \`${scenario.id}\`: ${scenario.gate.failures.map(escapeMarkdown).join('; ')}`]
        : [],
    )),
    '',
  ]
  return lines.join('\n')
}

function createRequestManager(baseUrl, stopState) {
  const activeControllers = new Set()
  const normalizedBaseUrl = baseUrl.replace(/\/$/, '')

  return {
    async request(method, requestPath, body, { allowAfterStop = false } = {}) {
      if (stopState.requested && !allowAfterStop) {
        throw new InterruptedError(stopState.signal)
      }
      const controller = new AbortController()
      activeControllers.add(controller)
      let timedOut = false
      const startedAt = performance.now()
      const timer = setTimeout(() => {
        timedOut = true
        controller.abort()
      }, REQUEST_TIMEOUT_MS)
      try {
        const headers = {
          Accept: 'application/json',
          ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
        }
        const response = await fetch(`${normalizedBaseUrl}${requestPath}`, {
          method,
          headers,
          body: body === undefined ? undefined : JSON.stringify(body),
          signal: controller.signal,
        })
        const text = await response.text()
        let json = null
        if (text.length > 0) {
          try {
            json = JSON.parse(text)
          } catch {
            json = null
          }
        }
        return {
          status: response.status,
          json,
          text,
          durationMs: performance.now() - startedAt,
        }
      } catch (error) {
        const durationMs = performance.now() - startedAt
        if (timedOut) {
          throw new RequestTimeoutError(method, requestPath, durationMs)
        }
        if (stopState.requested && !allowAfterStop) {
          throw new InterruptedError(stopState.signal)
        }
        throw new RequestFailureError(method, requestPath, durationMs, error)
      } finally {
        clearTimeout(timer)
        activeControllers.delete(controller)
      }
    },
    abortAll() {
      for (const controller of activeControllers) {
        controller.abort()
      }
    },
  }
}

async function waitForHealthy(manager, stopState, timeoutMs) {
  const deadline = performance.now() + timeoutMs
  let lastError = null
  while (performance.now() < deadline) {
    if (stopState.requested) throw new InterruptedError(stopState.signal)
    try {
      validateHealthResponse(await manager.request('GET', '/actuator/health'))
      return
    } catch (error) {
      if (error instanceof InterruptedError) throw error
      lastError = error
      const remaining = deadline - performance.now()
      if (remaining <= 0) break
      await sleep(Math.min(500, remaining))
    }
  }
  throw new BenchmarkError(
    `application did not become healthy within ${timeoutMs}ms: ${serializeError(lastError).message}`,
  )
}

async function runScenario({ scenario, manager, stopState, state, durationSeconds }) {
  const operation = operationFor(scenario.id, manager, state)
  const warmup = await runPhase({
    operation,
    concurrency: scenario.concurrency,
    durationMs: WARMUP_SECONDS * 1_000,
    stopState,
    collectSamples: false,
  })
  const measurement = await runPhase({
    operation,
    concurrency: scenario.concurrency,
    durationMs: durationSeconds * 1_000,
    stopState,
    collectSamples: true,
  })
  const metrics = summarizeSamples(measurement.samples, durationSeconds)
  const gate = evaluateScenario(scenario.id, metrics)
  return {
    id: scenario.id,
    name: scenario.name,
    path: scenario.path,
    concurrency: scenario.concurrency,
    durationSeconds,
    warmup: {
      durationSeconds: WARMUP_SECONDS,
      requests: warmup.requestsStarted,
      success: warmup.success,
      error: warmup.errors,
    },
    metrics,
    threshold: gate.threshold,
    gate: {
      passed: gate.passed,
      failures: [...gate.failures],
    },
    status: gate.passed ? 'pass' : 'fail',
  }
}

async function runPhase({ operation, concurrency, durationMs, stopState, collectSamples }) {
  const deadline = performance.now() + durationMs
  const samples = []
  let requestsStarted = 0
  let success = 0
  let errors = 0

  const worker = async (workerIndex) => {
    while (!stopState.requested && performance.now() < deadline) {
      requestsStarted += 1
      const startedAt = performance.now()
      try {
        await operation(workerIndex)
        success += 1
        if (collectSamples) {
          samples.push({ ok: true, durationMs: performance.now() - startedAt })
        }
      } catch (error) {
        if (error instanceof InterruptedError) throw error
        if (stopState.requested) throw new InterruptedError(stopState.signal)
        errors += 1
        if (collectSamples) {
          samples.push({
            ok: false,
            durationMs: Number.isFinite(error?.durationMs)
              ? error.durationMs
              : performance.now() - startedAt,
            error: error?.name || 'Error',
          })
        }
      }
    }
  }

  const settled = await Promise.allSettled(
    Array.from({ length: concurrency }, (_, workerIndex) => worker(workerIndex)),
  )
  const interrupted = settled.find(
    (result) => result.status === 'rejected' && result.reason instanceof InterruptedError,
  )
  if (interrupted) throw interrupted.reason
  const unexpected = settled.find((result) => result.status === 'rejected')
  if (unexpected) throw unexpected.reason

  return {
    samples,
    requestsStarted,
    success,
    errors,
  }
}

function operationFor(scenarioId, manager, state) {
  if (scenarioId === 'health') {
    return async () => {
      validateHealthResponse(await manager.request('GET', '/actuator/health'))
    }
  }
  if (scenarioId === 'catalog') {
    return async () => {
      validateCatalogResponse(
        await manager.request('GET', '/api/ai/catalog/models?pageNumber=1&pageSize=20'),
      )
    }
  }
  if (scenarioId === 'canvas') {
    return async (workerIndex) => {
      const title = `${state.canvasTitlePrefix}${workerIndex}-${state.canvasSequence++}`
      const createResponse = await manager.request('POST', '/api/canvases', { title })
      const candidateId = candidateCanvasId(createResponse)
      if (candidateId) state.createdCanvasIds.add(candidateId)
      const canvas = validateCanvasCreateResponse(createResponse)
      const deleteResponse = await manager.request(
        'DELETE',
        `/api/canvases/${canvas.id}`,
        undefined,
      )
      validateCanvasDeleteResponse(deleteResponse)
      state.createdCanvasIds.delete(canvas.id)
    }
  }
  throw new Error(`unknown scenario operation: ${scenarioId}`)
}

async function cleanupCanvases({ manager, state, scan }) {
  const ids = new Set(state.createdCanvasIds)
  const errors = []
  if (scan) {
    try {
      const rows = validateCanvasListResponse(
        await manager.request('GET', '/api/canvases', undefined, { allowAfterStop: true }),
      )
      for (const row of rows) {
        if (
          row &&
          typeof row === 'object' &&
          typeof row.id === 'string' &&
          typeof row.title === 'string' &&
          row.title.startsWith(state.canvasTitlePrefix)
        ) {
          ids.add(row.id)
        }
      }
    } catch (error) {
      errors.push({ operation: 'list', message: serializeError(error).message })
    }
  }

  for (const id of ids) {
    try {
      const response = await manager.request(
        'DELETE',
        `/api/canvases/${id}`,
        undefined,
        { allowAfterStop: true },
      )
      validateCanvasDeleteResponse(response)
      state.createdCanvasIds.delete(id)
    } catch (error) {
      errors.push({ operation: 'delete', id, message: serializeError(error).message })
    }
  }
  return {
    attempted: ids.size,
    errors,
    remaining: [...state.createdCanvasIds],
  }
}

function failedScenarioResult(scenario, durationSeconds, error) {
  const metrics = emptyMetrics(durationSeconds)
  const message = serializeError(error).message
  return {
    id: scenario.id,
    name: scenario.name,
    path: scenario.path,
    concurrency: scenario.concurrency,
    durationSeconds,
    warmup: {
      durationSeconds: WARMUP_SECONDS,
      requests: 0,
      success: 0,
      error: 0,
    },
    metrics,
    threshold: THRESHOLDS[scenario.id],
    gate: {
      passed: false,
      failures: [`scenario did not complete: ${message}`],
    },
    status: 'fail',
  }
}

function emptyMetrics(durationSeconds) {
  return {
    sampleCount: 0,
    requests: 0,
    success: 0,
    error: 0,
    errorRate: 1,
    requestsPerSecond: 0,
    throughputRps: 0,
    p50Ms: null,
    p95Ms: null,
    p99Ms: null,
    maxMs: null,
    durationSeconds,
  }
}

function collectRuntimeMetadata() {
  const javaCommand = firstExecutable([
    process.env.JAVA_HOME_21 ? path.join(process.env.JAVA_HOME_21, 'bin/java') : null,
    process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin/java') : null,
    'java',
  ])
  const dockerVersion =
    commandVersion('docker', ['version', '--format', '{{.Server.Version}}']) ||
    commandVersion('docker', ['--version']) ||
    'unavailable'
  const imageReference =
    process.env.PERFORMANCE_IMAGE ||
    process.env.CANVAS_TEST_APP_IMAGE ||
    'kk-studio-app:performance-baseline'
  return {
    commit: commandOutput('git', ['rev-parse', 'HEAD'], REPO_ROOT) || 'unavailable',
    jdk: javaCommand ? commandVersion(javaCommand, ['-version']) || 'unavailable' : 'unavailable',
    node: process.version,
    docker: dockerVersion,
    host: {
      cpuCount: os.cpus().length,
      cpuModel: os.cpus()[0]?.model || 'unknown',
      totalMemoryBytes: os.totalmem(),
      freeMemoryBytes: os.freemem(),
    },
    image: {
      reference: imageReference,
      id:
        commandOutput('docker', ['image', 'inspect', '--format', '{{.Id}}', imageReference]) ||
        'unavailable',
    },
  }
}

function firstExecutable(candidates) {
  for (const candidate of candidates) {
    if (!candidate) continue
    if (candidate === 'java') return candidate
    try {
      if (lstatSync(candidate).isFile()) return candidate
    } catch {
      // Try the next configured JDK path.
    }
  }
  return null
}

function commandVersion(command, args) {
  const result = spawnSync(command, args, {
    encoding: 'utf8',
    maxBuffer: 16 * 1024,
  })
  if (result.error || result.status !== 0) return null
  return `${result.stdout || ''}${result.stderr || ''}`.trim().split('\n').slice(0, 2).join(' ')
}

function commandOutput(command, args, cwd) {
  const result = spawnSync(command, args, {
    cwd,
    encoding: 'utf8',
    maxBuffer: 16 * 1024,
  })
  if (result.error || result.status !== 0) return null
  return String(result.stdout || '').trim() || null
}

function createRunId(date) {
  const timestamp = date
    .toISOString()
    .replace(/[-:]/g, '')
    .replace(/\.\d{3}Z$/, 'Z')
  return `${timestamp}-${randomUUID().slice(0, 8)}`
}

function serializeError(error) {
  return {
    name: error?.name || 'Error',
    message: String(error?.message || error).replace(/https?:\/\/[^\s"'<>]+/gi, '[URL]'),
  }
}

function requireStatus(response, expected, label) {
  if (!response || response.status !== expected) {
    throw new BenchmarkError(`${label} expected HTTP ${expected}, got ${response?.status}`)
  }
}

function requireObject(value, label) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    throw new BenchmarkError(`${label} must be an object`)
  }
}

function requireText(value, label) {
  if (typeof value !== 'string' || value.trim().length === 0) {
    throw new BenchmarkError(`${label} must be a non-empty string`)
  }
}

function candidateCanvasId(response) {
  const id = response?.json?.data?.id
  return typeof id === 'string' && UUID_PATTERN.test(id) ? id : null
}

function tryLstat(target) {
  try {
    return lstatSync(target)
  } catch (error) {
    if (error?.code === 'ENOENT') return null
    throw error
  }
}

function sleep(milliseconds) {
  return new Promise((resolve) => setTimeout(resolve, milliseconds))
}

function requireValue(argv, index, option) {
  const value = argv[index]
  if (value == null || value.startsWith('--')) {
    throw new Error(`${option} requires a value`)
  }
  return value
}

function formatMetric(value) {
  return value == null || !Number.isFinite(Number(value)) ? '-' : Number(value).toFixed(2)
}

function formatPercentage(value) {
  return value == null || !Number.isFinite(Number(value))
    ? '-'
    : `${(Number(value) * 100).toFixed(2)}%`
}

function formatFailures(failures) {
  return failures.length > 0 ? ` (${failures.map((failure) => escapeMarkdown(failure)).join('; ')})` : ''
}

function escapeMarkdown(value) {
  return String(value ?? '-').replaceAll('|', '\\|').replaceAll('\n', ' ')
}

async function main(argv) {
  try {
    const args = parseArgs(argv)
    if (args.help) {
      console.log(usage())
      return 0
    }
    const result = await runBenchmark(args)
    console.log(`Performance baseline: ${result.summary.status.toUpperCase()}`)
    console.log(`Report: ${result.runDir}`)
    return result.summary.status === 'pass' ? 0 : 1
  } catch (error) {
    console.error(`ERROR: ${serializeError(error).message}`)
    return 2
  }
}

if (path.resolve(process.argv[1] || '') === THIS_FILE) {
  const exitCode = await main(process.argv.slice(2))
  process.exitCode = exitCode
}
