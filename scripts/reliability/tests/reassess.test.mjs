import assert from 'node:assert/strict'
import test from 'node:test'

import { CASES, WRITE_PROOF_META, WRITE_PROOF_PATH } from '../matrix.mjs'
import { reassessResult } from '../reassess-agent-run.mjs'

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
