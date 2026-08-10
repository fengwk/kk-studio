import assert from 'node:assert/strict'
import test from 'node:test'

import {
  CASES,
  WRITE_PROOF,
  WRITE_PROOF_META,
  WRITE_PROOF_PATH,
} from '../matrix.mjs'
import {
  ReliabilityError,
  aggregateUsage,
  assertRequiredFirstOrder,
  assertStatusAllowlist,
  extractDurableTrace,
  replaceExactlyOnce,
  validateAnswerOracle,
  validateBashCommand,
  validateToolIsolation,
  validateToolPolicy,
} from '../policy.mjs'

const piRepair = CASES.find((testCase) => testCase.id === 'm27-pi-repair')
const piInvestigate = CASES.find((testCase) => testCase.id === 'm27-pi-investigate')
const piBaseInvestigate = CASES.find((testCase) => testCase.id === 'm27-pi-base-investigate')

test('required tool order uses first occurrences and rejects inversions', () => {
  assert.doesNotThrow(() =>
    assertRequiredFirstOrder(
      ['find', 'find', 'grep', 'read', 'edit', 'bash', 'write'],
      ['find', 'grep', 'read', 'edit', 'bash', 'write'],
    ),
  )
  assert.throws(
    () => assertRequiredFirstOrder(['grep', 'find', 'read'], ['find', 'grep', 'read']),
    ReliabilityError,
  )
})

test('bash policy allows only target test and narrow Git checks', () => {
  assert.deepEqual(validateBashCommand(piRepair.targetTestCommand, piRepair), {
    ok: true,
    reason: null,
  })
  assert.equal(validateBashCommand('git status --short && git diff --check', piRepair).ok, true)
  assert.equal(validateBashCommand('cat packages/coding-agent/src/core/session-manager.ts', piRepair).ok, false)
  assert.equal(validateBashCommand('rg buildContextEntries .', piRepair).ok, false)
  assert.equal(validateBashCommand('node -e "write file"', piRepair).ok, false)
  assert.equal(validateBashCommand('npm test --workspaces', piRepair).ok, false)
})

test('durable trace summarizes write content by byte length and SHA-256', () => {
  const entries = [
    messageEntry('1', {
      role: 'ASSISTANT',
      contents: [
        {
          type: 'tool_call',
          toolCallId: 'write-1',
          toolName: 'write',
          rendererKey: 'write',
          argumentsJson: JSON.stringify({ path: WRITE_PROOF_PATH, content: WRITE_PROOF }),
        },
      ],
    }, metadata(10, 2, 3, '0.125')),
    messageEntry('2', {
      role: 'TOOL',
      contents: [
        {
          type: 'tool_result',
          toolCallId: 'write-1',
          toolName: 'write',
          rendererKey: 'write',
          contents: [{ type: 'text', text: 'Created successfully.' }],
          error: false,
          detailsJson: '{}',
        },
      ],
    }),
  ]
  const trace = extractDurableTrace(entries)
  const contentSummary = trace.toolCalls[0].arguments.content
  assert.deepEqual(contentSummary, WRITE_PROOF_META)
  assert.equal(JSON.stringify(trace).includes(WRITE_PROOF), false)
  assert.equal(JSON.stringify(trace).includes('RELIABILITY_WRITE_PROOF_V1'), false)
  assert.equal(trace.writeDiagnostics[0].content.sha256, WRITE_PROOF_META.sha256)
  assert.equal(trace.usage.inputTokens, 10)
  assert.equal(trace.usage.cacheReadTokens, 3)
  assert.equal(trace.usage.costTotal, 0.125)
  assert.equal(trace.costKnown, true)
})

test('durable trace uses resource previews, emits markers without previews, and redacts write proof', () => {
  const entries = [
    messageEntry('1', {
      role: 'TOOL',
      contents: [
        {
          type: 'tool_result',
          toolCallId: 'read-1',
          toolName: 'read',
          rendererKey: 'read',
          contents: [
            {
              type: 'resource',
              uri: 'file:///resources/preview',
              mediaType: 'text/plain',
              name: 'preview.txt',
              size: 9926,
              sha256: null,
              preview: 'x'.repeat(1000),
            },
          ],
          error: false,
          detailsJson: '{}',
        },
        {
          type: 'tool_result',
          toolCallId: 'bash-1',
          toolName: 'bash',
          rendererKey: 'bash',
          contents: [
            {
              type: 'resource',
              uri: 'file:///resources/no-preview',
              mediaType: 'application/octet-stream',
              name: 'artifact.bin',
              size: 12,
              sha256: null,
              preview: null,
            },
          ],
          error: false,
          detailsJson: '{}',
        },
        {
          type: 'tool_result',
          toolCallId: 'read-2',
          toolName: 'read',
          rendererKey: 'read',
          contents: [
            {
              type: 'resource',
              uri: 'file:///resources/write-proof',
              mediaType: 'text/plain',
              name: 'proof.txt',
              size: WRITE_PROOF_META.byteLength,
              sha256: WRITE_PROOF_META.sha256,
              preview: WRITE_PROOF.slice(0, 16_000),
            },
          ],
          error: false,
          detailsJson: '{}',
        },
      ],
    }),
  ]

  const trace = extractDurableTrace(entries)

  assert.equal(trace.toolResults[0].text, `${'x'.repeat(800)}…`)
  assert.equal(trace.toolResults[1].text, '[Resource artifact.bin bytes=12]')
  assert.match(trace.toolResults[2].text, /^\[REDACTED_WRITE_PROOF bytes=/)
  assert.equal(JSON.stringify(trace).includes(WRITE_PROOF), false)
  assert.equal(JSON.stringify(trace).includes('RELIABILITY_WRITE_PROOF_V1'), false)
})

test('repair policy distinguishes intact write arguments from missing/damaged arguments', () => {
  const valid = {
    toolCalls: ['find', 'grep', 'read', 'edit', 'bash', 'write'].map((toolName) => ({
      toolName,
      arguments:
        toolName === 'bash'
          ? { command: piRepair.targetTestCommand, workdir: piRepair.casePath }
          : toolName === 'write'
            ? { path: WRITE_PROOF_PATH, workdir: piRepair.casePath }
            : { path: '.', workdir: piRepair.casePath },
    })),
    toolResults: [],
    writeDiagnostics: [
      {
        valid: true,
        path: WRITE_PROOF_PATH,
        workdir: piRepair.casePath,
        content: WRITE_PROOF_META,
        result: { error: false },
      },
    ],
  }
  assert.doesNotThrow(() => validateToolPolicy(piRepair, valid))
  assert.doesNotThrow(() =>
    validateToolPolicy(piRepair, {
      ...valid,
      toolCalls: valid.toolCalls.map((call) =>
        call.toolName === 'write'
          ? {
              ...call,
              arguments: {
                path: `${piRepair.casePath}/${WRITE_PROOF_PATH}`,
                workdir: piRepair.casePath,
              },
            }
          : call,
      ),
      writeDiagnostics: [
        {
          valid: true,
          path: `${piRepair.casePath}/${WRITE_PROOF_PATH}`,
          workdir: piRepair.casePath,
          content: WRITE_PROOF_META,
          result: { error: false },
        },
      ],
    }),
  )
  assert.throws(
    () =>
      validateToolPolicy(piRepair, {
        ...valid,
        writeDiagnostics: [
          {
            valid: true,
            path: WRITE_PROOF_PATH,
            workdir: piRepair.casePath,
            content: { byteLength: 3, sha256: 'bad' },
            result: { error: false },
          },
        ],
      }),
    /arguments were damaged/,
  )
})

test('tool isolation rejects anchors, other cases, and unscoped relative paths', () => {
  assert.doesNotThrow(() =>
    validateToolIsolation(piInvestigate, [
      { toolName: 'find', arguments: { path: '.', workdir: piInvestigate.casePath } },
    ]),
  )
  assert.throws(
    () =>
      validateToolIsolation(piInvestigate, [
        { toolName: 'read', arguments: { path: '/workspace/anchors/pi/package.json' } },
      ]),
    /forbidden anchor/,
  )
  assert.throws(
    () =>
      validateToolIsolation(piInvestigate, [
        { toolName: 'read', arguments: { path: '/workspace/cases/m3-pi-investigate/README.md' } },
      ]),
    /another disposable case/,
  )
  assert.throws(
    () =>
      validateToolIsolation(piInvestigate, [
        { toolName: 'read', arguments: { path: 'README.md' } },
      ]),
    /without the disposable case workdir/,
  )
  assert.throws(
    () =>
      validateToolIsolation(piInvestigate, [
        {
          toolName: 'read',
          arguments: {
            path: '../m3-pi-investigate/README.md',
            workdir: piInvestigate.casePath,
          },
        },
      ]),
    /another disposable case/,
  )
  assert.throws(
    () =>
      validateToolIsolation(piInvestigate, [
        {
          toolName: 'read',
          arguments: {
            path: '@/workspace/cases/m27-pi-investigate/../../anchors/pi/package.json',
          },
        },
      ]),
    /forbidden anchor/,
  )
})

test('usage aggregation sums tokens, cache, assistant rows, and cost', () => {
  const total = aggregateUsage([
    {
      inputTokens: 10,
      outputTokens: 2,
      cacheReadTokens: 3,
      cacheWriteTokens: 4,
      cacheWriteLongTokens: 5,
      reasoningTokens: 6,
      providerTotalTokens: 12,
      costTotal: 0.1,
      assistantMessages: 1,
    },
    {
      inputTokens: 7,
      outputTokens: 8,
      cacheReadTokens: 9,
      cacheWriteTokens: 1,
      cacheWriteLongTokens: 2,
      reasoningTokens: 3,
      providerTotalTokens: 15,
      costTotal: 0.2,
      assistantMessages: 2,
    },
  ])
  assert.deepEqual(total, {
    inputTokens: 17,
    outputTokens: 10,
    cacheReadTokens: 12,
    cacheWriteTokens: 5,
    cacheWriteLongTokens: 7,
    reasoningTokens: 9,
    providerTotalTokens: 27,
    costTotal: 0.30000000000000004,
    assistantMessages: 3,
  })
})

test('durable cost is known only when every assistant row carries USD total metadata', () => {
  const missing = extractDurableTrace([
    messageEntry('1', {
      role: 'ASSISTANT',
      contents: [{ type: 'text', text: 'done' }],
    }),
  ])
  assert.equal(missing.costKnown, false)

  const wrongCurrency = metadata(1, 1, 0, '0.01')
  wrongCurrency.cost.currency = 'EUR'
  assert.throws(
    () =>
      extractDurableTrace([
        messageEntry(
          '1',
          { role: 'ASSISTANT', contents: [{ type: 'text', text: 'done' }] },
          wrongCurrency,
        ),
      ]),
    /unexpected cost currency/,
  )
})

test('investigation answer oracles require all frozen semantic facts', () => {
  assert.doesNotThrow(() =>
    validateAnswerOracle(
      piInvestigate,
      'buildContextEntries in packages/coding-agent/src/core/session-manager.ts selects the latest compaction, uses firstKeptEntryId, then appends path.slice(compactionIdx + 1).',
    ),
  )
  assert.doesNotThrow(() =>
    validateAnswerOracle(
      piBaseInvestigate,
      'createFindToolDefinition in src/find-tool.ts uses --full-path and an **/ prefix, then path.relative(searchPath, line).',
    ),
  )
  assert.throws(
    () => validateAnswerOracle(piInvestigate, 'buildContextEntries exists.'),
    /missing required facts/,
  )
  assert.doesNotThrow(() =>
    validateAnswerOracle(
      piRepair,
      'Fixed the duplicated latest compaction behavior. test/session-manager/build-context.test.ts passed.',
    ),
  )
})

test('exact-one replacement and git status allowlist fail closed', () => {
  assert.equal(replaceExactlyOnce('before target after', 'target', 'fixed'), 'before fixed after')
  assert.throws(() => replaceExactlyOnce('target target', 'target', 'fixed'), /found 2/)
  assert.throws(() => replaceExactlyOnce('none', 'target', 'fixed'), /found 0/)

  assert.deepEqual(
    assertStatusAllowlist(
      '?? .reliability-write-proof.txt\n',
      { '.reliability-write-proof.txt': ['??'] },
      { required: ['.reliability-write-proof.txt'] },
    ),
    [{ status: '??', path: '.reliability-write-proof.txt' }],
  )
  assert.throws(
    () =>
      assertStatusAllowlist(' M unexpected.ts\n', {
        '.reliability-write-proof.txt': ['??'],
      }),
    /unexpected git status entry/,
  )
})

function messageEntry(entryId, message, assistantMetadata = null) {
  return {
    entryId,
    entryType: 'MESSAGE',
    payloadJson: JSON.stringify({
      message,
      assistantMetadata,
      toolResultMetadata: null,
    }),
  }
}

function metadata(inputTokens, outputTokens, cacheReadTokens, total) {
  return {
    stopReason: 'TOOL_CALLS',
    usage: {
      inputTokens,
      outputTokens,
      cacheReadTokens,
      cacheWriteTokens: 0,
      cacheWriteLongTokens: 0,
      reasoningTokens: 1,
      providerTotalTokens: inputTokens + outputTokens,
    },
    cost: {
      currency: 'USD',
      input: '0',
      output: '0',
      cacheRead: '0',
      cacheWrite: '0',
      cacheWriteLong: '0',
      reasoning: '0',
      total,
    },
  }
}
