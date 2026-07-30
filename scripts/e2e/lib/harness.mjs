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
  const { status, json } = await ctx.call('POST', '/api/ai/runtime/threads')
  assert(status === 201, `create thread status ${status}: ${JSON.stringify(json)}`)
  const thread = envelopeData(json)
  threadIdOf(thread)
  executionEpochOf(thread)
  return thread
}

export async function getThread(ctx, threadId) {
  const { json } = await ctx.call('GET', `/api/ai/runtime/threads/${encodeURIComponent(threadId)}`)
  const thread = envelopeData(json)
  threadIdOf(thread)
  executionEpochOf(thread)
  return thread
}

export async function getThreadSnapshot(ctx, threadId) {
  const { json } = await ctx.call('GET', `/api/ai/runtime/threads/${encodeURIComponent(threadId)}/snapshot`)
  const snapshot = envelopeData(json)
  assert(snapshot?.thread, `expected Thread snapshot: ${JSON.stringify(json)}`)
  threadIdOf(snapshot.thread)
  executionEpochOf(snapshot.thread)
  return snapshot
}

export async function bootstrapThread(ctx, unboundThread, options) {
  const threadId = threadIdOf(unboundThread)
  const agentDefinitionId = String(options?.agentDefinitionId || '')
  assert(/^\d+$/.test(agentDefinitionId), `expected decimal agentDefinitionId: ${agentDefinitionId}`)
  const { status, json } = await ctx.call(
    'POST',
    `/api/ai/runtime/threads/${encodeURIComponent(threadId)}/bootstrap`,
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
    `/api/ai/runtime/threads/${encodeURIComponent(threadId)}/head`,
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

export async function snapshotEntries(ctx, threadId) {
  const snapshot = await getThreadSnapshot(ctx, threadId)
  return snapshot.entries || []
}

export async function snapshotInputs(ctx, threadId) {
  const snapshot = await getThreadSnapshot(ctx, threadId)
  return snapshot.inputs || []
}

export async function listSessionEntries(ctx, sessionId) {
  const { json } = await ctx.call('GET', `/api/ai/runtime/sessions/${encodeURIComponent(sessionId)}/entries`)
  return listData(json, 'session entries')
}

/**
 * 先建立 Thread realtime SSE 连接，再执行 startWork，并等待当前 Thread 第一条非空模型文本增量。
 *
 * SSE 只提供调用 /stop 的时机，不是 durable 断言来源。无论成功、失败或超时，都会取消 reader 并中止连接。
 */
export async function waitForModelTextDeltaAfterSseConnected(
  ctx,
  threadId,
  startWork,
  { timeoutMs = 90_000 } = {},
) {
  const expectedThreadId = String(threadId || '')
  assert(/^\d+$/.test(expectedThreadId), `expected decimal threadId: ${expectedThreadId}`)
  assert(typeof startWork === 'function', 'startWork must be a function')
  assert(
    typeof ctx?.baseUrl === 'string' && ctx.baseUrl.trim(),
    'E2E context must expose a non-blank baseUrl',
  )
  assert(Number.isFinite(timeoutMs) && timeoutMs > 0, `invalid SSE timeout: ${timeoutMs}`)

  const controller = new AbortController()
  let timedOut = false
  const timer = setTimeout(() => {
    timedOut = true
    controller.abort()
  }, timeoutMs)
  let reader = null
  try {
    const baseUrl = ctx.baseUrl.replace(/\/$/, '')
    const requestPath =
      `/api/ai/runtime/threads/${encodeURIComponent(expectedThreadId)}/events/stream?afterRevision=0`
    const response = await fetch(`${baseUrl}${requestPath}`, {
      method: 'GET',
      headers: {
        Accept: 'text/event-stream',
        'Cache-Control': 'no-cache',
      },
      signal: controller.signal,
    })
    assert(response.ok, `SSE open status ${response.status} for ${requestPath}`)
    assert(
      String(response.headers.get('content-type') || '').toLowerCase().includes('text/event-stream'),
      `expected text/event-stream, got ${response.headers.get('content-type')}`,
    )
    assert(response.body, 'SSE response has no readable body')

    reader = response.body.getReader()
    const startResult = await startWork()
    const signal = await readModelTextDelta(reader, expectedThreadId)
    return { signal, startResult }
  } catch (error) {
    if (timedOut || error?.name === 'AbortError') {
      throw new Error(
        `timed out after ${timeoutMs}ms waiting for MODEL_DELTA/TEXT_DELTA on thread ${expectedThreadId}`,
      )
    }
    throw error
  } finally {
    clearTimeout(timer)
    if (reader != null) {
      try {
        await reader.cancel()
      } catch {
        // The timeout/remote close path may have already released the reader.
      }
    }
    controller.abort()
  }
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

async function readModelTextDelta(reader, expectedThreadId) {
  const decoder = new TextDecoder()
  let buffer = ''
  while (true) {
    const { done, value } = await reader.read()
    if (done) {
      throw new Error(
        `SSE ended before MODEL_DELTA/TEXT_DELTA arrived for thread ${expectedThreadId}`,
      )
    }
    buffer += decoder.decode(value, { stream: true })
    while (true) {
      const boundary = /\r?\n\r?\n/.exec(buffer)
      if (boundary == null) break
      const frame = buffer.slice(0, boundary.index)
      buffer = buffer.slice(boundary.index + boundary[0].length)
      const record = parseSseFrame(frame)
      const signal = parseModelTextDelta(record, expectedThreadId)
      if (signal != null) return signal
    }
  }
}

function parseSseFrame(frame) {
  let id = null
  let event = 'message'
  const data = []
  for (const line of frame.split(/\r?\n/)) {
    if (!line || line.startsWith(':')) continue
    const separator = line.indexOf(':')
    const field = separator < 0 ? line : line.slice(0, separator)
    let value = separator < 0 ? '' : line.slice(separator + 1)
    if (value.startsWith(' ')) value = value.slice(1)
    if (field === 'id') id = value
    else if (field === 'event') event = value
    else if (field === 'data') data.push(value)
  }
  return { id, event, data: data.join('\n') }
}

function parseModelTextDelta(record, expectedThreadId) {
  if (record.event !== 'realtime' || !record.data) return null
  let envelope
  try {
    envelope = JSON.parse(record.data)
  } catch {
    return null
  }
  if (!isRecord(envelope) || !isRecord(envelope.payload)) return null
  if (
    envelope.type !== 'MODEL_DELTA'
    || envelope.subjectKind !== 'MODEL_INVOCATION'
    || envelope.threadId !== expectedThreadId
    || typeof envelope.subjectId !== 'string'
    || !/^\d+$/.test(envelope.subjectId)
    || !Number.isSafeInteger(envelope.attempt)
    || envelope.attempt <= 0
    || typeof envelope.createdAt !== 'string'
    || !envelope.createdAt.trim()
    || !Number.isFinite(Date.parse(envelope.createdAt))
    || envelope.payload.kind !== 'TEXT_DELTA'
    || typeof envelope.payload.text !== 'string'
    || !envelope.payload.text.trim()
  ) {
    return null
  }
  return {
    eventId: record.id,
    threadId: envelope.threadId,
    invocationId: envelope.subjectId,
    attempt: envelope.attempt,
    text: envelope.payload.text,
    createdAt: envelope.createdAt,
  }
}

function isRecord(value) {
  return typeof value === 'object' && value != null && !Array.isArray(value)
}
