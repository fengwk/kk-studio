import assert from 'node:assert/strict'
import { mkdtempSync, readFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'
import test from 'node:test'

import { CASES, WRITE_PROOF, WRITE_PROOF_META, buildSystemPrompt, sha256 } from '../matrix.mjs'
import { aggregateUsage } from '../policy.mjs'
import {
  assertSafeReportString,
  createRunDirectory,
  sanitizeForReport,
  writeCaseArtifacts,
  writeSummaryAndReport,
} from '../report.mjs'

test('redaction removes sensitive fields, endpoints, credentials, and write proof', () => {
  const sanitized = sanitizeForReport({
    credential: 'super-secret',
    baseUrl: 'https://provider.example/v1',
    nested: {
      message: `Bearer abcdefghijklmnop ${WRITE_PROOF}`,
      error: '"credential":"raw-value" TEST_MINIMAX_API_KEY=raw-value',
    },
  })
  const json = JSON.stringify(sanitized)
  assert.equal(json.includes('super-secret'), false)
  assert.equal(json.includes('provider.example'), false)
  assert.equal(json.includes(WRITE_PROOF), false)
  assert.equal(json.includes('RELIABILITY_WRITE_PROOF_V1'), false)
  assert.equal(json.includes('raw-value'), false)
  assert.doesNotThrow(() => assertSafeReportString(json))
})

test('report artifacts never repeat the 12KiB payload', () => {
  const root = mkdtempSync(path.join(tmpdir(), 'reliability-report-'))
  try {
    const runId = 'test-run'
    const runDir = createRunDirectory(root, runId)
    writeCaseArtifacts(runDir, 'm27-pi-repair', {
      'trace.json': {
        arguments: {
          path: '.reliability-write-proof.txt',
          content: WRITE_PROOF,
        },
      },
    })
    const testCase = CASES.find((candidate) => candidate.id === 'm27-pi-repair')
    const metrics = aggregateUsage([])
    writeSummaryAndReport({
      runDir,
      runId,
      selectedCases: [testCase],
      results: [
        {
          id: testCase.id,
          model: testCase.model.ref,
          anchor: testCase.anchor,
          taskClass: testCase.taskClass,
          status: 'pass',
          failureCategory: null,
          error: null,
          turnStarted: true,
          costKnown: true,
          toolOrder: ['find', 'grep', 'read', 'edit', 'bash', 'write'],
          toolCounts: { find: 1, grep: 1, read: 1, edit: 1, bash: 1, write: 1 },
          metrics,
          tests: { precheck: 'expected-fail-confirmed', postcheck: 'pass', diffCheck: 'pass' },
        },
      ],
      startedAt: '2026-08-10T00:00:00.000Z',
      finishedAt: '2026-08-10T00:01:00.000Z',
      args: {
        daemonEnv: 'docker-reliability',
        maxCostUsd: 5,
      },
      preflight: {
        provider: { name: 'minimax', configured: true },
        models: ['minimax/MiniMax-M2.7', 'minimax/MiniMax-M3'],
      },
      runError: null,
      systemPromptMeta: {
        byteLength: Buffer.byteLength(buildSystemPrompt()),
        sha256: sha256(buildSystemPrompt()),
      },
      reassessment: {
        at: '2026-08-10T00:02:00.000Z',
        mode: 'offline-archived-trace',
        modelTurnsStarted: 0,
        reason: 'Archived facts only; no Provider call.',
      },
    })
    for (const file of [
      path.join(runDir, 'artifacts/m27-pi-repair/trace.json'),
      path.join(runDir, 'summary.json'),
      path.join(runDir, 'report.md'),
    ]) {
      const content = readFileSync(file, 'utf8')
      assert.equal(content.includes(WRITE_PROOF), false)
      assert.equal(content.includes('RELIABILITY_WRITE_PROOF_V1'), false)
      assert.match(content, new RegExp(WRITE_PROOF_META.sha256))
    }
    assert.match(readFileSync(path.join(runDir, 'report.md'), 'utf8'), /model turns started: 0/)
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})
