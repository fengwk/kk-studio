import assert from 'node:assert/strict'
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'
import test from 'node:test'

import { CASES, WRITE_PROOF_META, WRITE_PROOF_PATH } from '../matrix.mjs'
import { main, reassessResult } from '../reassess-agent-run.mjs'

const repair = CASES.find((testCase) => testCase.id === 'm27-pi-repair')

test('offline reassessment accepts an intact case-local absolute write path', () => {
  const previous = {
    status: 'fail',
    failureCategory: 'model',
    error: 'old relative-only policy rejected the write path',
    errorDetails: null,
    turnStarted: true,
    tests: { precheck: 'expected-fail-confirmed', postcheck: 'pass', diffCheck: 'pass' },
  }
  const toolNames = ['find', 'grep', 'read', 'edit', 'bash', 'write']
  const trace = {
    events: [
      ...toolNames.map((toolName, index) => ({
        kind: 'tool_call',
        toolCallId: `call-${index}`,
        toolName,
        arguments:
          toolName === 'bash'
            ? { command: repair.targetTestCommand, workdir: repair.casePath }
            : toolName === 'write'
              ? { path: `${repair.casePath}/${WRITE_PROOF_PATH}` }
              : { path: '.', workdir: repair.casePath },
      })),
    ],
    writeDiagnostics: [
      {
        valid: true,
        path: `${repair.casePath}/${WRITE_PROOF_PATH}`,
        content: WRITE_PROOF_META,
        result: { error: false },
      },
    ],
    finalText:
      'Fixed duplicate compaction handling; test/session-manager/build-context.test.ts passed.',
  }

  const reassessed = reassessResult(repair, previous, trace)

  assert.equal(reassessed.status, 'pass')
  assert.equal(reassessed.failureCategory, null)
  assert.equal(reassessed.error, null)
})

test('offline reassessment exits nonzero when the reassessed matrix still fails', () => {
  const reportRoot = mkdtempSync(path.join(tmpdir(), 'kk-studio-reassess-'))
  const runId = '20260810T000000Z-deadbeef'
  const runDir = path.join(reportRoot, runId)
  const result = {
    id: repair.id,
    title: repair.title,
    model: repair.model.ref,
    variant: repair.model.variant,
    anchor: repair.anchor,
    taskClass: repair.taskClass,
    status: 'fail',
    failureCategory: 'oracle',
    error: 'old failure',
    errorDetails: null,
    turnStarted: true,
    costKnown: true,
    startedAt: '2026-08-10T00:00:00.000Z',
    finishedAt: '2026-08-10T00:00:01.000Z',
    durationMs: 1000,
    toolOrder: [],
    toolCounts: {},
    metrics: {
      inputTokens: 0,
      outputTokens: 0,
      cacheReadTokens: 0,
      cacheWriteTokens: 0,
      cacheWriteLongTokens: 0,
      reasoningTokens: 0,
      providerTotalTokens: 0,
      costTotal: 0,
      assistantMessages: 1,
    },
    tests: { precheck: 'expected-fail-confirmed', postcheck: 'pass', diffCheck: 'pass' },
  }
  try {
    mkdirSync(path.join(runDir, 'artifacts', repair.id), { recursive: true })
    writeJson(path.join(runDir, 'summary.json'), {
      startedAt: result.startedAt,
      finishedAt: result.finishedAt,
      configuration: {
        selectedCaseIds: [repair.id],
        daemonEnvironment: 'docker-reliability',
        maxCostUsd: 5,
        systemPrompt: { byteLength: 1, sha256: '0'.repeat(64) },
      },
      preflight: {},
      runError: null,
      results: [result],
    })
    writeJson(path.join(runDir, 'artifacts', repair.id, 'trace.json'), {
      events: [],
      writeDiagnostics: [],
      finalText: '',
    })

    assert.equal(main([runId, '--report-root', reportRoot]), 1)
  } finally {
    rmSync(reportRoot, { recursive: true, force: true })
  }
})

function writeJson(filePath, value) {
  writeFileSync(filePath, `${JSON.stringify(value)}\n`, 'utf8')
}
