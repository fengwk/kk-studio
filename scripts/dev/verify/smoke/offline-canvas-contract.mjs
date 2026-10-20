/** Pure payload builders and response assertions for the offline Canvas smoke. */

import assert from 'node:assert/strict'

const DECIMAL = /^(?:0|[1-9][0-9]*)$/
const FINGERPRINT = /^[0-9a-f]{64}$/

/** Assert an exact key set; the object must carry no more and no fewer fields. */
function assertKeys(value, expected, context) {
  assert.deepEqual(Object.keys(value).sort(), [...expected].sort(), JSON.stringify(context ?? value))
}

export function decimalRevision(value) {
  assert.ok(typeof value === 'string' && DECIMAL.test(value), String(value))
  return Number.parseInt(value, 10)
}

export function documentRevision(document) {
  assertKeys(document, ['id', 'title', 'revision', 'createdAt', 'updatedAt'])
  return decimalRevision(document.revision)
}

export function assertPatch(patch, previousRevision, replay = false) {
  assertKeys(patch, ['revision', 'groups', 'nodes'])
  const revision = decimalRevision(patch.revision)
  assert.equal(revision, previousRevision + (replay ? 0 : 1), JSON.stringify(patch))
  assert.ok(Array.isArray(patch.groups) && Array.isArray(patch.nodes), JSON.stringify(patch))
  if (replay) {
    assert.ok(patch.groups.length === 0 && patch.nodes.length === 0, JSON.stringify(patch))
  }
  return revision
}

export function resourceBatch(nodeId, blobId, idempotencyKey) {
  return {
    idempotencyKey,
    commands: [
      {
        type: 'CREATE_NODE',
        nodeId,
        name: 'uploaded',
        transform: { x: 0, y: 0, width: 320, height: 260 },
        resources: [{ kind: 'BLOB', name: 'tiny.png', blobId }],
      },
    ],
  }
}

export function functionBatch(nodeId, name, functionName, args, idempotencyKey) {
  return {
    idempotencyKey,
    commands: [
      {
        type: 'CREATE_NODE',
        nodeId,
        name,
        transform: { x: 100, y: 100, width: 320, height: 260 },
        resources: [],
      },
      {
        type: 'SET_NODE_FUNCTION',
        nodeId,
        expectedFunction: null,
        function: { name: functionName, args },
      },
    ],
  }
}

/**
 * 取某个函数目录项暴露的版本身份，作为 start 的 expectedVersion。
 *
 * 客户端看到的版本只能来自真实目录项，绝不硬编码 epoch 或 fingerprint。
 */
export function definitionVersion(definitions, name) {
  const definition = definitions.find((item) => item.name === name)
  assert.ok(definition, `function definition not found: ${name}`)
  const { version } = definition
  assertKeys(version, ['catalogEpoch', 'definitionFingerprint'])
  assert.ok(DECIMAL.test(version.catalogEpoch), JSON.stringify(version))
  assert.ok(FINGERPRINT.test(version.definitionFingerprint), JSON.stringify(version))
  return version
}

const REQUIRED_FUNCTIONS = [
  'fake-image',
  'gpt-image-2',
  'seedance2.0',
  'seedance2.0fast',
  'seedance2.0_vip',
  'seedance2.0fast_vip',
]

export function assertFunctionDefinitions(definitions) {
  const fields = [
    'name',
    'description',
    'argsSchema',
    'outputs',
    'referencePolicy',
    'available',
    'unavailableReason',
    'version',
  ]
  assert.ok(Array.isArray(definitions) && definitions.length > 0, JSON.stringify(definitions))
  for (const definition of definitions) {
    assertKeys(definition, fields)
    assert.equal(definition.argsSchema.type, 'object', JSON.stringify(definition))
    assert.equal(definition.argsSchema.additionalProperties, false, JSON.stringify(definition))
    assert.ok(DECIMAL.test(definition.version.catalogEpoch), JSON.stringify(definition))
    assert.ok(FINGERPRINT.test(definition.version.definitionFingerprint), JSON.stringify(definition))
  }
  for (const name of REQUIRED_FUNCTIONS) {
    const definition = definitions.find((item) => item.name === name)
    assert.ok(definition, `required function definition missing: ${name}`)
    assert.equal(definition.available, true, JSON.stringify(definition))
    assert.equal(definition.unavailableReason, null, JSON.stringify(definition))
  }
  assert.ok(definitions.every((item) => item.name !== 'seedance2.0mini'))
}

export function assertCompletedRun(run, nodeId, requestId) {
  assertKeys(run, ['nodeId', 'requestId', 'status', 'stage', 'error', 'updatedAt'])
  assert.ok(run.nodeId === nodeId && run.requestId === requestId, JSON.stringify(run))
  assert.ok(run.status === 'SUCCEEDED' && run.error === null, JSON.stringify(run))
}

export function assertRunRevision(document, patchRevision, checkpointCount = null) {
  const revision = documentRevision(document)
  // start、beginSubmit、confirmSubmitted、complete 各推进一次，checkpoint 逐次推进。
  assert.ok(revision >= patchRevision + 4, JSON.stringify(document))
  if (checkpointCount !== null) {
    assert.equal(revision, patchRevision + 4 + checkpointCount, JSON.stringify(document))
  }
  return revision
}
