import assert from 'node:assert/strict'
import { createServer } from 'node:http'
import {
  existsSync,
  mkdtempSync,
  readFileSync,
  rmSync,
  symlinkSync,
} from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'
import test from 'node:test'
import { randomUUID } from 'node:crypto'

import {
  MIN_SAMPLES,
  SCENARIOS,
  THRESHOLDS,
  WARMUP_SECONDS,
  evaluateScenario,
  nearestRankPercentile,
  parseArgs,
  runBenchmark,
  runPhase,
  summarizeSamples,
  validateCanvasCreateResponse,
  validateCanvasDeleteResponse,
  validateCatalogResponse,
  validateHealthResponse,
  validateReportRoot,
  writePerformanceReports,
} from '../runner.mjs'

test('nearest-rank percentile is deterministic at the frozen boundary', () => {
  // The report states one-based ceil(p / 100 × n), so p50 of four values is rank two.
  assert.equal(nearestRankPercentile([40, 10, 30, 20], 50), 20)
  assert.equal(nearestRankPercentile([40, 10, 30, 20], 95), 40)
  assert.equal(nearestRankPercentile([40, 10, 30, 20], 99), 40)
  assert.throws(() => nearestRankPercentile([], 95), /at least one value/)
})

test('sample statistics distinguish attempted request rate from successful throughput', () => {
  const metrics = summarizeSamples(
    [
      { ok: true, durationMs: 10 },
      { ok: true, durationMs: 20 },
      { ok: false, durationMs: 30 },
      { ok: true, durationMs: 40 },
    ],
    2,
  )

  assert.equal(metrics.requests, 4)
  assert.equal(metrics.success, 3)
  assert.equal(metrics.error, 1)
  assert.equal(metrics.errorRate, 0.25)
  assert.equal(metrics.requestsPerSecond, 2)
  assert.equal(metrics.throughputRps, 1.5)
  assert.equal(metrics.elapsedSeconds, 2)
  assert.equal(metrics.p50Ms, 20)
  assert.equal(metrics.p95Ms, 40)
  assert.equal(metrics.p99Ms, 40)
  assert.equal(metrics.maxMs, 40)
})

test('measurement rates use elapsed time after inflight workers settle', async () => {
  // Intent: an inflight request beyond the configured window must lower RPS rather than be hidden.
  const configuredDurationMs = 1
  const phase = await runPhase({
    operation: async () => {
      await new Promise((resolve) => setTimeout(resolve, 20))
    },
    concurrency: 1,
    durationMs: configuredDurationMs,
    stopState: { requested: false, signal: null },
    collectSamples: true,
  })

  assert.equal(phase.requestsStarted, 1)
  assert.ok(phase.elapsedMs > configuredDurationMs)
  const elapsedSeconds = phase.elapsedMs / 1_000
  const metrics = summarizeSamples(phase.samples, elapsedSeconds)
  const configuredRate = 1 / (configuredDurationMs / 1_000)
  assert.equal(metrics.elapsedSeconds, elapsedSeconds)
  assert.ok(metrics.requestsPerSecond < configuredRate)
  assert.ok(metrics.throughputRps < configuredRate)
})

test('frozen threshold values fail closed for errors, latency, throughput, and small samples', () => {
  assert.deepEqual(THRESHOLDS, {
    health: { maxErrors: 0, maxP95Ms: 250, minThroughputRps: 50, minSamples: MIN_SAMPLES },
    catalog: { maxErrors: 0, maxP95Ms: 500, minThroughputRps: 25, minSamples: MIN_SAMPLES },
    canvas: { maxErrors: 0, maxP95Ms: 1500, minThroughputRps: 5, minSamples: MIN_SAMPLES },
  })

  const healthy = summarizeSamples(
    Array.from({ length: MIN_SAMPLES }, () => ({ ok: true, durationMs: 1 })),
    1,
  )
  assert.equal(evaluateScenario('health', healthy).passed, false)
  assert.match(evaluateScenario('health', healthy).failures.join(' '), /throughput/)

  const slow = summarizeSamples(
    Array.from({ length: MIN_SAMPLES }, () => ({ ok: true, durationMs: 251 })),
    1,
  )
  assert.equal(evaluateScenario('health', slow).passed, false)
  assert.match(evaluateScenario('health', slow).failures.join(' '), /p95/)

  const errored = summarizeSamples(
    Array.from({ length: MIN_SAMPLES }, (_, index) => ({
      ok: index !== 0,
      durationMs: 1,
    })),
    1,
  )
  assert.equal(evaluateScenario('health', errored).passed, false)
  assert.match(evaluateScenario('health', errored).failures.join(' '), /errors/)

  const tooFew = summarizeSamples([{ ok: true, durationMs: 1 }], 1)
  assert.equal(evaluateScenario('canvas', tooFew).passed, false)
  assert.match(evaluateScenario('canvas', tooFew).failures.join(' '), /samples/)
})

test('response validators enforce health, catalog envelope, and canvas DTO contracts', () => {
  assert.doesNotThrow(() => validateHealthResponse({ status: 200, json: { status: 'UP' } }))
  assert.throws(
    () => validateHealthResponse({ status: 200, json: { status: 'DOWN' } }),
    /must be UP/,
  )

  const catalogResponse = {
    status: 200,
    json: {
      code: 'success',
      data: {
        results: [
          {
            providerName: 'offline',
            name: 'acceptance-stub',
            config: {
              defaultVariant: 'default',
              variants: [{ id: 'default' }],
              limit: { context: 4096 },
            },
          },
        ],
      },
    },
  }
  assert.equal(validateCatalogResponse(catalogResponse).length, 1)
  assert.throws(
    () => validateCatalogResponse({ status: 200, json: { data: [] } }),
    /catalog data must be an object/,
  )
  assert.throws(
    () =>
      validateCatalogResponse({
        status: 200,
        json: { data: { results: [{ providerName: 'offline', name: 'bad', config: {} }] } },
      }),
    /defaultVariant/,
  )

  const id = '00000000-0000-4000-8000-000000000001'
  assert.doesNotThrow(() =>
    validateCanvasCreateResponse({
      status: 201,
      json: { data: { id, title: 'baseline', version: '0' } },
    }),
  )
  assert.throws(
    () =>
      validateCanvasCreateResponse({
        status: 201,
        json: { data: { id, title: 'baseline', version: '0', threadId: id } },
      }),
    /threadId/,
  )
  assert.doesNotThrow(() => validateCanvasDeleteResponse({ status: 204 }))
})

test('CLI rejects illegal duration, threshold overrides, unsafe roots, and symlink paths', () => {
  const repoRoot = mkdtempSync(path.join(tmpdir(), 'performance-cli-repo-'))
  const outside = mkdtempSync(path.join(tmpdir(), 'performance-cli-outside-'))
  const link = path.join(repoRoot, 'link')
  symlinkSync(outside, link, 'dir')
  try {
    assert.equal(parseArgs(['--duration-seconds', '5'], { cwd: repoRoot, repoRoot }).durationSeconds, 5)
    assert.equal(
      parseArgs(['--skip-build'], { cwd: repoRoot, repoRoot }).skipBuild,
      true,
    )
    assert.throws(
      () => parseArgs(['--duration-seconds', '0'], { cwd: repoRoot, repoRoot }),
      /between 1 and 120/,
    )
    assert.throws(
      () => parseArgs(['--duration-seconds', '121'], { cwd: repoRoot, repoRoot }),
      /between 1 and 120/,
    )
    assert.throws(
      () => parseArgs(['--duration-seconds', '1.5'], { cwd: repoRoot, repoRoot }),
      /integer/,
    )
    assert.throws(
      () => parseArgs(['--threshold', 'health.p95=999'], { cwd: repoRoot, repoRoot }),
      /unknown argument/,
    )
    assert.throws(() => validateReportRoot('/', { repoRoot }), /filesystem root/)
    assert.throws(() => validateReportRoot(repoRoot, { repoRoot }), /repository root/)
    assert.throws(() => validateReportRoot(link, { repoRoot }), /symlink/)
  } finally {
    rmSync(repoRoot, { recursive: true, force: true })
    rmSync(outside, { recursive: true, force: true })
  }
})

test('report writer creates timestamped and latest summary/report files', () => {
  const reportRoot = mkdtempSync(path.join(tmpdir(), 'performance-report-'))
  const summary = {
    runId: '20260825T000000Z-test',
    status: 'pass',
    purpose: 'Machine-local free regression baseline; not capacity planning.',
    commit: 'abc123',
    startedAt: '2026-08-25T00:00:00.000Z',
    finishedAt: '2026-08-25T00:00:01.000Z',
    runtime: { jdk: 'JDK 21', node: 'v24', docker: 'Docker' },
    host: {
      cpuCount: 1,
      cpuModel: 'test CPU',
      totalMemoryBytes: 100,
      freeMemoryBytes: 50,
    },
    image: { reference: 'test:image', id: 'sha256:test' },
    parameters: {
      baseUrl: 'http://127.0.0.1:18088',
      durationSeconds: 1,
      warmupSeconds: WARMUP_SECONDS,
      requestTimeoutMs: 5000,
      reportRoot,
      skipBuild: true,
      concurrency: { health: 16, catalog: 16, canvas: 4 },
    },
    isolation: {
      composeFile: 'deploy/test/compose.yaml',
      profile: 'app',
      services: ['postgres', 'minio', 'minio-init', 'http-mock', 'app'],
      realProvider: false,
      daemon: false,
    },
    thresholds: THRESHOLDS,
    scenarios: SCENARIOS.map((scenario) => ({
      ...scenario,
      durationSeconds: 1,
      actualMeasurementSeconds: 1.25,
      metrics: {
        sampleCount: MIN_SAMPLES,
        requests: MIN_SAMPLES,
        success: MIN_SAMPLES,
        error: 0,
        errorRate: 0,
        requestsPerSecond: 100,
        throughputRps: 100,
        p50Ms: 1,
        p95Ms: 1,
        p99Ms: 1,
        maxMs: 1,
        durationSeconds: 1.25,
        elapsedSeconds: 1.25,
      },
      gate: { passed: true, failures: [] },
      status: 'pass',
    })),
    cleanup: { attempted: 0, errors: [], remaining: [] },
    interrupted: null,
    runError: null,
  }
  try {
    const report = writePerformanceReports({ reportRoot, summary })
    assert.ok(existsSync(path.join(report.runDir, 'summary.json')))
    assert.ok(existsSync(path.join(report.runDir, 'report.md')))
    assert.ok(existsSync(path.join(report.latestDir, 'summary.json')))
    assert.equal(readFileSync(path.join(report.latestDir, 'summary.json'), 'utf8').includes('"status": "pass"'), true)
    assert.match(
      readFileSync(path.join(report.runDir, 'report.md'), 'utf8'),
      /Configured \(s\).*Actual measurement \(s\)/,
    )
  } finally {
    rmSync(reportRoot, { recursive: true, force: true })
  }
})

test(
  'one-second local fake integration runs all fixed scenarios without Docker and cleans canvases',
  { timeout: 30_000 },
  async () => {
    const reportRoot = mkdtempSync(path.join(tmpdir(), 'performance-fake-report-'))
    const canvases = new Map()
    const server = createServer(async (request, response) => {
      const requestUrl = new URL(request.url, 'http://127.0.0.1')
      if (request.method === 'GET' && requestUrl.pathname === '/actuator/health') {
        sendJson(response, 200, { status: 'UP' })
        return
      }
      if (
        request.method === 'GET' &&
        requestUrl.pathname === '/api/ai/catalog/models' &&
        requestUrl.search === '?pageNumber=1&pageSize=20'
      ) {
        sendJson(response, 200, {
          code: 'success',
          data: {
            results: [
              {
                providerName: 'offline',
                name: 'acceptance-stub',
                config: {
                  defaultVariant: 'default',
                  variants: [{ id: 'default' }],
                  limit: { context: 4096 },
                },
              },
            ],
          },
        })
        return
      }
      if (request.method === 'GET' && requestUrl.pathname === '/api/canvases') {
        sendJson(response, 200, { code: 'success', data: [...canvases.values()] })
        return
      }
      if (request.method === 'POST' && requestUrl.pathname === '/api/canvases') {
        const body = JSON.parse(await readRequestBody(request))
        const id = randomUUID()
        const canvas = { id, title: body.title, version: '0' }
        canvases.set(id, canvas)
        sendJson(response, 201, { code: 'success', data: canvas })
        return
      }
      if (request.method === 'DELETE' && requestUrl.pathname.startsWith('/api/canvases/')) {
        canvases.delete(requestUrl.pathname.split('/').at(-1))
        response.writeHead(204)
        response.end()
        return
      }
      sendJson(response, 404, { code: 'not_found' })
    })

    await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
    const address = server.address()
    try {
      const result = await runBenchmark({
        baseUrl: `http://127.0.0.1:${address.port}`,
        durationSeconds: 1,
        reportRoot,
        startupTimeoutMs: 2_000,
        runtimeMetadata: {
          commit: 'fake-commit',
          jdk: 'fake-jdk',
          node: process.version,
          docker: 'fake-docker',
          host: {
            cpuCount: 1,
            cpuModel: 'fake CPU',
            totalMemoryBytes: 100,
            freeMemoryBytes: 50,
          },
          image: { reference: 'fake:image', id: 'fake-image' },
        },
      })
      assert.equal(result.summary.status, 'pass')
      assert.deepEqual([...canvases.values()], [])
      assert.equal(result.summary.scenarios.length, 3)
      for (const scenario of result.summary.scenarios) {
        assert.ok(scenario.metrics.requests >= MIN_SAMPLES, scenario.id)
        assert.equal(scenario.metrics.error, 0, scenario.id)
        assert.equal(scenario.status, 'pass', scenario.id)
        assert.equal(scenario.metrics.elapsedSeconds, scenario.actualMeasurementSeconds, scenario.id)
        assert.ok(
          scenario.actualMeasurementSeconds >= scenario.durationSeconds,
          scenario.id,
        )
      }
      assert.ok(existsSync(path.join(reportRoot, 'latest', 'report.md')))
    } finally {
      await new Promise((resolve, reject) => server.close((error) => (error ? reject(error) : resolve())))
      rmSync(reportRoot, { recursive: true, force: true })
    }
  },
)

function sendJson(response, status, payload) {
  const body = JSON.stringify(payload)
  response.writeHead(status, {
    'Content-Type': 'application/json',
    'Content-Length': Buffer.byteLength(body),
  })
  response.end(body)
}

function readRequestBody(request) {
  return new Promise((resolve, reject) => {
    let body = ''
    request.setEncoding('utf8')
    request.on('data', (chunk) => {
      body += chunk
    })
    request.on('end', () => resolve(body))
    request.on('error', reject)
  })
}
