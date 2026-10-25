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
    messageEntry(
      '1',
      {
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
      },
      metadata(10, 2, 3),
      { currency: 'USD', amount: '0.125' },
    ),
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
  // 每条输入的 reasoning 都是 output 的合法子集，聚合不会重复加入输出。
  const total = aggregateUsage([
    {
      inputTokens: 10,
      outputTokens: 2,
      cacheReadTokens: 3,
      cacheWriteTokens: 4,
      reasoningTokens: 2,
      providerTotalTokens: 12,
      costTotal: 0.1,
      assistantMessages: 1,
    },
    {
      inputTokens: 7,
      outputTokens: 8,
      cacheReadTokens: 9,
      cacheWriteTokens: 1,
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
    reasoningTokens: 5,
    providerTotalTokens: 27,
    costTotal: 0.30000000000000004,
    assistantMessages: 3,
  })
})

test('durable cost is known only when every assistant row carries USD usageCost projection', () => {
  const missing = extractDurableTrace([
    messageEntry('1', {
      role: 'ASSISTANT',
      contents: [{ type: 'text', text: 'done' }],
    }),
  ])
  assert.equal(missing.costKnown, false)
  assert.equal(missing.usage.costTotal, null)

  assert.throws(
    () =>
      extractDurableTrace([
        messageEntry(
          '1',
          { role: 'ASSISTANT', contents: [{ type: 'text', text: 'done' }] },
          metadata(1, 1, 0),
          { currency: 'EUR', amount: '0.01' },
        ),
      ]),
    /unexpected cost currency/,
  )
})

test('usage contract preserves null reasoningTokens and providerTotalTokens without coercing to zero', () => {
  const trace = extractDurableTrace([
    messageEntry(
      '1',
      { role: 'ASSISTANT', contents: [{ type: 'text', text: 'hello' }] },
      metadata(20, 10, 5, { reasoningTokens: null, providerTotalTokens: null }),
      { currency: 'USD', amount: '0.005' },
    ),
  ])
  assert.equal(trace.usage.reasoningTokens, null)
  assert.equal(trace.usage.providerTotalTokens, null)
  assert.equal(trace.usage.inputTokens, 20)
  assert.equal(trace.usage.outputTokens, 10)
  assert.equal(trace.usage.cacheReadTokens, 5)
  assert.equal(trace.usage.cacheWriteTokens, 0)
  assert.equal(trace.usage.costTotal, 0.005)
  assert.equal(trace.costKnown, true)
})

test('missing entire usage cannot produce known zero cost or speed', () => {
  const missing = extractDurableTrace([
    messageEntry(
      '1',
      { role: 'ASSISTANT', contents: [{ type: 'text', text: 'hello' }] },
      { stopReason: 'COMPLETE' },
      { currency: 'USD', amount: '0' },
    ),
  ])
  assert.equal(missing.costKnown, false)
  assert.equal(missing.usage.costTotal, null)
  assert.equal(missing.usage.inputTokens, null)
  assert.equal(missing.usage.outputTokens, null)
  assert.equal(missing.usage.cacheReadTokens, null)
  assert.equal(missing.usage.cacheWriteTokens, null)
})

test('missing mandatory token field does not default to zero and prevents known cost', () => {
  const trace = extractDurableTrace([
    messageEntry(
      '1',
      { role: 'ASSISTANT', contents: [{ type: 'text', text: 'hello' }] },
      {
        stopReason: 'COMPLETE',
        usage: {
          outputTokens: 50,
          cacheReadTokens: 10,
          cacheWriteTokens: 0,
        },
      },
      { currency: 'USD', amount: '0.01' },
    ),
  ])
  assert.equal(trace.usage.inputTokens, null)
  assert.equal(trace.usage.outputTokens, 50)
  assert.equal(trace.costKnown, false)
  assert.equal(trace.usage.costTotal, null)
})

test('aggregating unknown mandatory tokens produces null without masquerading as zero', () => {
  const aggregated = aggregateUsage([
    {
      inputTokens: null,
      outputTokens: 20,
      cacheReadTokens: 5,
      cacheWriteTokens: 0,
      reasoningTokens: null,
      providerTotalTokens: null,
      costTotal: 0.01,
      assistantMessages: 1,
    },
    {
      inputTokens: 100,
      outputTokens: 30,
      cacheReadTokens: 0,
      cacheWriteTokens: 0,
      reasoningTokens: null,
      providerTotalTokens: null,
      costTotal: 0.02,
      assistantMessages: 1,
    },
  ])
  assert.equal(aggregated.inputTokens, null)
  assert.equal(aggregated.outputTokens, 50)
  assert.equal(aggregated.cacheReadTokens, 5)
  assert.equal(aggregated.cacheWriteTokens, 0)
  assert.equal(aggregated.costTotal, 0.03)
  assert.equal(aggregated.assistantMessages, 2)
})

test('reasoningTokens is a subset of outputTokens and never double counted', () => {
  const trace = extractDurableTrace([
    messageEntry(
      '1',
      { role: 'ASSISTANT', contents: [{ type: 'text', text: 'think' }] },
      metadata(100, 50, 10, { reasoningTokens: 20, providerTotalTokens: null }),
      { currency: 'USD', amount: '0.05' },
    ),
  ])
  assert.equal(trace.usage.outputTokens, 50)
  assert.equal(trace.usage.reasoningTokens, 20)
  assert.ok(trace.usage.reasoningTokens <= trace.usage.outputTokens)
})

test('token counts reject non-number types including false, empty string, and string numbers', () => {
  for (const badValue of [false, true, '', '12', 1.5, -1]) {
    assert.throws(
      () =>
        extractDurableTrace([
          messageEntry('1', { role: 'ASSISTANT', contents: [{ type: 'text', text: 'hi' }] }, {
            stopReason: 'COMPLETE',
            usage: {
              inputTokens: badValue,
              outputTokens: 10,
              cacheReadTokens: 0,
              cacheWriteTokens: 0,
            },
          }),
        ]),
      (err) => err instanceof ReliabilityError && err.category === 'model',
    )
  }
  // Also verify memory objects with NaN, Infinity, negative or float throw on aggregation
  for (const nonSafe of [NaN, Infinity, 1.5, -1, '12', false]) {
    assert.throws(
      () =>
        aggregateUsage([
          {
            inputTokens: nonSafe,
            outputTokens: 10,
            cacheReadTokens: 0,
            cacheWriteTokens: 0,
          },
        ]),
      (err) => err instanceof ReliabilityError && err.category === 'model',
    )
  }
  // Also verify optional reasoningTokens rejects non-numbers
  assert.throws(
    () =>
      extractDurableTrace([
        messageEntry('1', { role: 'ASSISTANT', contents: [{ type: 'text', text: 'hi' }] }, {
          stopReason: 'COMPLETE',
          usage: {
            inputTokens: 10,
            outputTokens: 10,
            cacheReadTokens: 0,
            cacheWriteTokens: 0,
            reasoningTokens: '12',
          },
        }),
      ]),
    (err) => err instanceof ReliabilityError && err.category === 'model',
  )
})

test('token count addition overflow exceeding MAX_SAFE_INTEGER throws ReliabilityError', () => {
  // 单条最大计数合法，且零输出不带推理；只在累加第二条后命中指定字段的溢出。
  const first = messageEntry(
    '1',
    { role: 'ASSISTANT', contents: [{ type: 'text', text: 'one' }] },
    metadata(Number.MAX_SAFE_INTEGER, 0, 0, { reasoningTokens: 0, providerTotalTokens: null }),
  )
  const second = messageEntry(
    '2',
    { role: 'ASSISTANT', contents: [{ type: 'text', text: 'two' }] },
    metadata(1, 0, 0, { reasoningTokens: 0, providerTotalTokens: null }),
  )
  assert.doesNotThrow(() => extractDurableTrace([first]))
  assert.throws(
    () => extractDurableTrace([first, second]),
    (err) => err instanceof ReliabilityError
      && err.category === 'model'
      && err.message === `token count addition overflow for inputTokens: ${Number.MAX_SAFE_INTEGER} + 1`,
  )

  // 2. aggregateUsage two MAX_SAFE_INTEGER items addition overflow
  assert.throws(
    () =>
      aggregateUsage([
        {
          inputTokens: Number.MAX_SAFE_INTEGER,
          outputTokens: 0,
          cacheReadTokens: 0,
          cacheWriteTokens: 0,
          reasoningTokens: null,
          providerTotalTokens: null,
          costTotal: 0,
          assistantMessages: 1,
        },
        {
          inputTokens: Number.MAX_SAFE_INTEGER,
          outputTokens: 0,
          cacheReadTokens: 0,
          cacheWriteTokens: 0,
          reasoningTokens: null,
          providerTotalTokens: null,
          costTotal: 0,
          assistantMessages: 1,
        },
      ]),
    (err) => err instanceof ReliabilityError
      && err.category === 'model'
      && err.message === `token count addition overflow for inputTokens: ${Number.MAX_SAFE_INTEGER} + ${Number.MAX_SAFE_INTEGER}`,
  )

  // 3. assistantMessages addition overflow
  assert.throws(
    () =>
      aggregateUsage([
        {
          inputTokens: 0,
          outputTokens: 0,
          cacheReadTokens: 0,
          cacheWriteTokens: 0,
          reasoningTokens: null,
          providerTotalTokens: null,
          costTotal: 0,
          assistantMessages: Number.MAX_SAFE_INTEGER,
        },
        {
          inputTokens: 0,
          outputTokens: 0,
          cacheReadTokens: 0,
          cacheWriteTokens: 0,
          reasoningTokens: null,
          providerTotalTokens: null,
          costTotal: 0,
          assistantMessages: 1,
        },
      ]),
    (err) => err instanceof ReliabilityError
      && err.category === 'model'
      && err.message === `token count addition overflow for assistantMessages: ${Number.MAX_SAFE_INTEGER} + 1`,
  )
})

test('cost amount accepts finite numbers and decimal strings but rejects coerced types', () => {
  const accepted = extractDurableTrace([
    messageEntry('1', { role: 'ASSISTANT', contents: [{ type: 'text', text: 'a' }] }, metadata(1, 1, 0), { currency: 'USD', amount: '1.25e-1' }),
    messageEntry('2', { role: 'ASSISTANT', contents: [{ type: 'text', text: 'b' }] }, metadata(1, 1, 0), { currency: 'USD', amount: 0.5 }),
  ])
  assert.equal(accepted.costKnown, true)
  assert.equal(accepted.usage.costTotal, 0.625)
  for (const amount of [false, true, '', ' ', [], '1,25', '1e']) {
    assert.throws(
      () => extractDurableTrace([
        messageEntry('1', { role: 'ASSISTANT', contents: [{ type: 'text', text: 'bad' }] }, metadata(1, 1, 0), { currency: 'USD', amount }),
      ]),
      (error) => error instanceof ReliabilityError && error.category === 'model',
    )
  }
  assert.throws(
    () => aggregateUsage([
      { inputTokens: 0, outputTokens: 0, cacheReadTokens: 0, cacheWriteTokens: 0, reasoningTokens: null, providerTotalTokens: null, costTotal: Number.MAX_VALUE, assistantMessages: 0 },
      { inputTokens: 0, outputTokens: 0, cacheReadTokens: 0, cacheWriteTokens: 0, reasoningTokens: null, providerTotalTokens: null, costTotal: Number.MAX_VALUE, assistantMessages: 0 },
    ]),
    (error) => error instanceof ReliabilityError && /cost addition overflow/.test(error.message),
  )
})

test('usageCost recognizes only amount and ignores deprecated total property', () => {
  const legacyTotal = extractDurableTrace([
    messageEntry(
      '1',
      { role: 'ASSISTANT', contents: [{ type: 'text', text: 'hi' }] },
      metadata(10, 5, 0),
      { currency: 'USD', total: '0.01' },
    ),
  ])
  assert.equal(legacyTotal.costKnown, false)
  assert.equal(legacyTotal.usage.costTotal, null)

  const canonical = extractDurableTrace([
    messageEntry(
      '1',
      { role: 'ASSISTANT', contents: [{ type: 'text', text: 'hi' }] },
      metadata(10, 5, 0),
      { currency: 'USD', amount: '0.01' },
    ),
  ])
  assert.equal(canonical.costKnown, true)
  assert.equal(canonical.usage.costTotal, 0.01)
})

test('aggregateUsage preserves null reasoning, providerTotal, and unknown cost', () => {
  const aggregated = aggregateUsage([
    {
      inputTokens: 10,
      outputTokens: 5,
      cacheReadTokens: 0,
      cacheWriteTokens: 0,
      reasoningTokens: null,
      providerTotalTokens: null,
      costTotal: null,
      assistantMessages: 1,
    },
    {
      inputTokens: 20,
      outputTokens: 10,
      cacheReadTokens: 2,
      cacheWriteTokens: 1,
      reasoningTokens: null,
      providerTotalTokens: null,
      costTotal: 0.05,
      assistantMessages: 1,
    },
  ])
  assert.equal(aggregated.inputTokens, 30)
  assert.equal(aggregated.outputTokens, 15)
  assert.equal(aggregated.cacheReadTokens, 2)
  assert.equal(aggregated.cacheWriteTokens, 1)
  assert.equal(aggregated.reasoningTokens, null)
  assert.equal(aggregated.providerTotalTokens, null)
  assert.equal(aggregated.costTotal, null)
  assert.equal(aggregated.assistantMessages, 2)
})

test('providerTotalTokens is independent and not forced to match sum of inputs/outputs', () => {
  const trace = extractDurableTrace([
    messageEntry(
      '1',
      { role: 'ASSISTANT', contents: [{ type: 'text', text: 'gemini' }] },
      metadata(100, 50, 0, { reasoningTokens: 10, providerTotalTokens: 200 }),
      { currency: 'USD', amount: '0.01' },
    ),
  ])
  assert.equal(trace.usage.providerTotalTokens, 200)
  assert.notEqual(trace.usage.providerTotalTokens, trace.usage.inputTokens + trace.usage.outputTokens)
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

function messageEntry(entryId, message, assistantMetadata = null, usageCost = null) {
  return {
    entryId,
    entryType: 'MESSAGE',
    payloadJson: JSON.stringify({
      message,
      assistantMetadata,
      toolResultMetadata: null,
    }),
    usageCost,
  }
}

function metadata(
  inputTokens,
  outputTokens,
  cacheReadTokens,
  { reasoningTokens = 1, providerTotalTokens = inputTokens + outputTokens } = {},
) {
  return {
    stopReason: 'COMPLETE',
    usage: {
      inputTokens,
      outputTokens,
      cacheReadTokens,
      cacheWriteTokens: 0,
      reasoningTokens,
      providerTotalTokens,
    },
  }
}
