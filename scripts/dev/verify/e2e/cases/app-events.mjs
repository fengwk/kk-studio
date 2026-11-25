import { assert } from '../lib/http.mjs'
import { createCaseAdder } from '../lib/registry.mjs'

export const cases = []
const registerCase = createCaseAdder(cases)

registerCase({
  id: 'events.heartbeat_keepalive',
  level: 'L1',
  title: '应用事件 WebSocket 周期 heartbeat 保活',
  docs: '通过工作区 WebSocket contract 连接 /ws/events，不建立资源订阅；验证 connected 身份与 heartbeatIntervalMillis，再等待严格 heartbeat 帧且连接保持打开',
  async run(ctx) {
    const socket = ctx.openWebSocket('/ws/events')
    const startedAt = Date.now()
    try {
      const frame = await waitForHeartbeat(socket, 25_000)
      const elapsedMs = Date.now() - startedAt
      assert(socket.readyState === 1, `event socket closed before heartbeat validation: ${socket.readyState}`)
      assert(
        frame && typeof frame === 'object' && !Array.isArray(frame)
          && Object.keys(frame).sort().join(',') === 'type' && frame.type === 'heartbeat',
        `invalid heartbeat frame: ${JSON.stringify(frame)}`,
      )
      ctx.writeArtifact('heartbeat.json', `${JSON.stringify({ elapsedMs, frame }, null, 2)}\n`)
    } finally {
      socket.close(1000, 'E2E complete')
    }
  },
})

export async function waitForSocketOpen(socket, timeoutMs) {
  if (socket.readyState === 1) return
  await new Promise((resolve, reject) => {
    const timer = setTimeout(() => { cleanup(); reject(new Error(`timed out waiting ${timeoutMs}ms for application event WebSocket open`)) }, timeoutMs)
    const onOpen = () => { cleanup(); resolve() }
    const onError = () => { cleanup(); reject(new Error('application event WebSocket failed before open')) }
    function cleanup() { clearTimeout(timer); socket.removeEventListener('open', onOpen); socket.removeEventListener('error', onError) }
    socket.addEventListener('open', onOpen)
    socket.addEventListener('error', onError)
  })
}

export async function takeFrame(frames, predicate, timeoutMs) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    const index = frames.findIndex(predicate)
    if (index >= 0) return frames.splice(index, 1)[0]
    await new Promise((resolve) => setTimeout(resolve, 25))
  }
  throw new Error(`timed out waiting ${timeoutMs}ms for application event frame`)
}

export function assertExactKeys(value, expected, label) {
  assert(value && typeof value === 'object' && !Array.isArray(value), `${label} must be an object`)
  const actual = Object.keys(value).sort()
  const sortedExpected = [...expected].sort()
  assert(actual.length === sortedExpected.length && actual.every((field, index) => field === sortedExpected[index]),
    `${label} fields must be ${sortedExpected.join(',')}, got ${actual.join(',')}`)
}

async function waitForHeartbeat(socket, timeoutMs) {
  return new Promise((resolve, reject) => {
    let connected = false
    const timer = setTimeout(() => {
      cleanup()
      reject(new Error(`timed out waiting ${timeoutMs}ms for application heartbeat`))
    }, timeoutMs)
    const handleMessage = ({ data }) => {
      let frame
      try { frame = JSON.parse(String(data)) } catch { return }
      if (!connected) {
        try {
          assertExactKeys(frame, ['type', 'eventNodeId', 'connectionId', 'heartbeatIntervalMillis'], 'connected')
          const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
          assert(frame.type === 'connected' && uuid.test(frame.eventNodeId) && uuid.test(frame.connectionId), 'invalid connected identity')
          assert(typeof frame.heartbeatIntervalMillis === 'string' && /^[1-9][0-9]*$/.test(frame.heartbeatIntervalMillis),
            `invalid connected heartbeatIntervalMillis: ${JSON.stringify(frame.heartbeatIntervalMillis)}`)
          connected = true
        } catch (error) { cleanup(); reject(error) }
        return
      }
      if (frame?.type === 'heartbeat') { cleanup(); resolve(frame) }
    }
    const handleError = (error) => { cleanup(); reject(error instanceof Error ? error : new Error('application event WebSocket failed before heartbeat')) }
    const handleClose = ({ code, reason }) => { cleanup(); reject(new Error(`application event WebSocket closed before heartbeat: ${code} ${reason}`)) }
    function cleanup() {
      clearTimeout(timer)
      socket.removeEventListener('message', handleMessage)
      socket.removeEventListener('error', handleError)
      socket.removeEventListener('close', handleClose)
    }
    socket.addEventListener('message', handleMessage)
    socket.addEventListener('error', handleError)
    socket.addEventListener('close', handleClose)
  })
}
