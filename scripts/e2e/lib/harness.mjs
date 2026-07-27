import { assert, envelopeData, sleep } from './http.mjs'

function threadIdOf(thread) {
  const threadId = String(thread?.threadId || '')
  assert(/^\d+$/.test(threadId), `expected decimal threadId: ${JSON.stringify(thread)}`)
  return threadId
}

function executionEpochOf(thread) {
  const epoch = Number(thread?.executionEpoch)
  assert(
    Number.isSafeInteger(epoch) && epoch >= 0,
    `expected non-negative executionEpoch: ${JSON.stringify(thread)}`,
  )
  return epoch
}

function listData(json, description) {
  const data = envelopeData(json)
  assert(Array.isArray(data), `expected ${description} array: ${JSON.stringify(json)}`)
  return data
}

export async function createUnboundThread(ctx) {
  const { status, json } = await ctx.call('POST', '/api/threads')
  assert(status === 201, `create thread status ${status}: ${JSON.stringify(json)}`)
  const thread = envelopeData(json)
  threadIdOf(thread)
  executionEpochOf(thread)
  return thread
}

export async function getThread(ctx, threadId) {
  const { json } = await ctx.call('GET', `/api/threads/${encodeURIComponent(threadId)}`)
  const thread = envelopeData(json)
  threadIdOf(thread)
  executionEpochOf(thread)
  return thread
}

export async function bootstrapThread(ctx, unboundThread, options) {
  const threadId = threadIdOf(unboundThread)
  const agentDefinitionId = String(options?.agentDefinitionId || '')
  assert(/^\d+$/.test(agentDefinitionId), `expected decimal agentDefinitionId: ${agentDefinitionId}`)
  const { status, json } = await ctx.call(
    'POST',
    `/api/threads/${encodeURIComponent(threadId)}/bootstrap`,
    {
      title: options?.title,
      agentDefinitionId,
      yoloEnabled: Boolean(options?.yoloEnabled),
      expectedExecutionEpoch: executionEpochOf(unboundThread),
    },
  )
  assert(status === 201, `bootstrap status ${status}: ${JSON.stringify(json)}`)
  const result = envelopeData(json)
  assert(result?.session?.sessionId, `bootstrap missing session: ${JSON.stringify(result)}`)
  assert(threadIdOf(result?.thread) === threadId, `bootstrap changed thread: ${JSON.stringify(result)}`)
  return result
}

export async function createBootstrappedThread(ctx, options) {
  const unboundThread = await createUnboundThread(ctx)
  return bootstrapThread(ctx, unboundThread, options)
}

export async function updateThreadHead(ctx, thread, headEntryId) {
  const threadId = threadIdOf(thread)
  const { status, json } = await ctx.call(
    'PUT',
    `/api/threads/${encodeURIComponent(threadId)}/head`,
    {
      headEntryId: headEntryId == null ? null : String(headEntryId),
      expectedExecutionEpoch: executionEpochOf(thread),
    },
  )
  assert(status === 200, `update head status ${status}: ${JSON.stringify(json)}`)
  const updated = envelopeData(json)
  assert(threadIdOf(updated) === threadId, `head update changed thread: ${JSON.stringify(updated)}`)
  return updated
}

export async function listThreadEntries(ctx, threadId) {
  const { json } = await ctx.call('GET', `/api/threads/${encodeURIComponent(threadId)}/entries`)
  return listData(json, 'thread entries')
}

export async function listThreadInputs(ctx, threadId) {
  const { json } = await ctx.call('GET', `/api/threads/${encodeURIComponent(threadId)}/inputs`)
  return listData(json, 'thread inputs')
}

export async function listSessionEntries(ctx, sessionId) {
  const { json } = await ctx.call('GET', `/api/sessions/${encodeURIComponent(sessionId)}/entries`)
  return listData(json, 'session entries')
}

export async function waitForQuiescentThread(
  ctx,
  threadId,
  { timeoutMs = 30_000, intervalMs = 250 } = {},
) {
  const deadline = Date.now() + timeoutMs
  let last = null
  while (Date.now() <= deadline) {
    last = await getThread(ctx, threadId)
    if (last.status === 'IDLE' && !last.processing) {
      return last
    }
    await sleep(intervalMs)
  }
  throw new Error(`thread did not become IDLE: ${JSON.stringify(last)}`)
}

export async function rebindWhenQuiescent(ctx, threadId, headEntryId, options) {
  const thread = await waitForQuiescentThread(ctx, threadId, options)
  return updateThreadHead(ctx, thread, headEntryId)
}
