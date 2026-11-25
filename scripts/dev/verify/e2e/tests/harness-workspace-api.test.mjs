import assert from 'node:assert/strict'
import test from 'node:test'

import {
  createWorkspaceBranch,
  createWorkspaceFork,
  createWorkspaceSpace,
  createWorkspaceSession,
  deleteWorkspaceSession,
} from '../lib/harness.mjs'
import { cid } from '../lib/http.mjs'

const created = {
  sessionId: cid(),
  threadId: cid(),
  commandId: cid(),
  treeVersion: '1',
}

test('workspace space creation can select a case-specific default agent', async () => {
  // Test intent: isolated mock-model cases configure their Agent on a Space rather than a Chat.
  const space = { id: cid(), title: 'mock space' }
  const calls = []
  const ctx = {
    async request(path, options) {
      calls.push({ path, options })
      return { json: space }
    },
  }
  assert.deepEqual(await createWorkspaceSpace(ctx, {
    title: space.title,
    defaultAgentName: 'mock-agent',
  }), space)
  assert.equal(calls[0].path, '/api/chat-spaces')
  assert.deepEqual(calls[0].options.body, {
    title: space.title,
    rootFolderName: 'E2E',
    defaultAgentName: 'mock-agent',
    defaultYoloEnabled: false,
  })
  assert.match(calls[0].options.headers['Idempotency-Key'], /^[0-9a-f-]{36}$/)
})

test('workspace session creation uses the Chat Space API and idempotency header', async () => {
  // Test intent: new sessions are Workspace-owned resources, not Harness owner-aware batches.
  const spaceId = cid()
  const key = cid()
  const calls = []
  const ctx = {
    async request(path, options) {
      calls.push({ path, options })
      return { status: 200, json: created }
    },
  }
  assert.deepEqual(await createWorkspaceSession(ctx, spaceId, {
    idempotencyKey: key,
    message: [{ type: 'TEXT', text: 'hello' }],
    modelSelection: { modelName: 'model', variant: 'default' },
  }), created)
  assert.equal(calls.length, 1)
  assert.equal(calls[0].path, `/api/chat-spaces/${spaceId}/sessions`)
  assert.equal(calls[0].options.method, 'POST')
  assert.deepEqual(calls[0].options.headers, { 'Idempotency-Key': key })
  assert.deepEqual(calls[0].options.body, {
    folderId: null,
    message: [{ type: 'TEXT', text: 'hello' }],
    modelSelection: { modelName: 'model', variant: 'default' },
    environmentId: null,
    yoloEnabled: null,
  })
})

test('workspace branch and fork use current Session turn-cut endpoints', async () => {
  // Test intent: branches/forks share the current turn-cut payload but target distinct public endpoints.
  const spaceId = cid()
  const sessionId = cid()
  const input = {
    idempotencyKey: cid(),
    sourceThreadId: cid(),
    turnEndEntryId: cid(),
    title: 'continued work',
  }
  const calls = []
  const ctx = {
    async request(path, options) {
      calls.push({ path, options })
      return { json: { sessionId: cid(), threadId: cid() } }
    },
  }
  await createWorkspaceBranch(ctx, spaceId, sessionId, input)
  await createWorkspaceFork(ctx, spaceId, sessionId, input)
  assert.equal(calls[0].path, `/api/chat-spaces/${spaceId}/sessions/${sessionId}/branches`)
  assert.equal(calls[1].path, `/api/chat-spaces/${spaceId}/sessions/${sessionId}/forks`)
  for (const call of calls) {
    assert.equal(call.options.method, 'POST')
    assert.deepEqual(call.options.headers, { 'Idempotency-Key': input.idempotencyKey })
    assert.deepEqual(call.options.body, {
      sourceThreadId: input.sourceThreadId,
      turnEndEntryId: input.turnEndEntryId,
      title: input.title,
    })
  }
})

test('workspace Session cleanup removes the resource and clears the temporary default Agent', async () => {
  // Test intent: deleting the Session alone leaves the temporary Agent referenced by its Space default.
  const spaceId = cid()
  const sessionId = cid()
  const calls = []
  const ctx = {
    async call(method, path, body) {
      calls.push({ method, path, body })
      if (method === 'GET' && path.endsWith('/tree')) return { json: { treeVersion: '2' } }
      if (method === 'GET' && path === '/api/chat-spaces') {
        return { json: [{ id: spaceId, version: '3', defaultAgentName: 'temp-agent' }] }
      }
      return { status: 200, json: {} }
    },
  }
  await deleteWorkspaceSession(ctx, spaceId, sessionId)
  assert.deepEqual(calls.map(({ method, path }) => [method, path]), [
    ['GET', `/api/chat-spaces/${spaceId}/tree`],
    ['DELETE', `/api/chat-spaces/${spaceId}/sessions/${sessionId}?expectedTreeVersion=2`],
    ['GET', '/api/chat-spaces'],
    ['PATCH', `/api/chat-spaces/${spaceId}`],
  ])
  assert.deepEqual(calls.at(-1).body, { expectedVersion: '3', defaultAgentName: null })
})

test('workspace creation helpers reject malformed identities before HTTP', async () => {
  // Test intent: malformed ids and empty messages are caller errors and must not produce requests.
  let calls = 0
  const ctx = { async request() { calls++ } }
  await assert.rejects(createWorkspaceSession(ctx, 'bad', {
    idempotencyKey: cid(),
    message: [{ type: 'TEXT', text: 'hello' }],
  }), /canonical UUID/)
  await assert.rejects(createWorkspaceSession(ctx, cid(), {
    idempotencyKey: cid(),
    message: [],
  }), /non-empty/)
  await assert.rejects(createWorkspaceFork(ctx, cid(), cid(), {
    idempotencyKey: cid(),
    sourceThreadId: cid(),
    turnEndEntryId: 'bad',
    title: 'fork',
  }), /canonical UUID/)
  assert.equal(calls, 0)
})
