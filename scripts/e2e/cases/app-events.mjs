import { assert } from '../lib/http.mjs'
import { registerCase } from '../lib/registry.mjs'

registerCase({
  id: 'events.heartbeat_keepalive',
  level: 'L1',
  title: '应用事件 WebSocket 周期 heartbeat 保活',
  docs: '原生 WebSocket 直连 /api/events/v1，不建立资源订阅；等待连接级 heartbeat，严格断言 {version:1,type:"heartbeat"} 且连接保持打开',
  async run(ctx) {
    const socket = new WebSocket(applicationEventUrl(ctx.baseUrl))
    const startedAt = Date.now()
    try {
      const frame = await waitForHeartbeat(socket, 25_000)
      const elapsedMs = Date.now() - startedAt
      assert(
        socket.readyState === WebSocket.OPEN,
        `event socket closed before heartbeat validation: ${socket.readyState}`,
      )
      assert(
        frame
        && typeof frame === 'object'
        && !Array.isArray(frame)
        && Object.keys(frame).sort().join(',') === 'type,version'
        && frame.version === 1
        && frame.type === 'heartbeat',
        `invalid heartbeat frame: ${JSON.stringify(frame)}`,
      )
      ctx.writeArtifact(
        'heartbeat.json',
        `${JSON.stringify({ elapsedMs, frame }, null, 2)}\n`,
      )
    } finally {
      socket.close(1000, 'E2E complete')
    }
  },
})

function applicationEventUrl(baseUrl) {
  const url = new URL(baseUrl)
  url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:'
  url.pathname = '/api/events/v1'
  url.search = ''
  url.hash = ''
  return url.toString()
}

async function waitForHeartbeat(socket, timeoutMs) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      cleanup()
      reject(new Error(`timed out waiting ${timeoutMs}ms for application heartbeat`))
    }, timeoutMs)
    const handleMessage = (event) => {
      let frame
      try {
        frame = JSON.parse(String(event.data))
      } catch {
        return
      }
      if (frame?.type !== 'heartbeat') {
        return
      }
      cleanup()
      resolve(frame)
    }
    const handleError = () => {
      cleanup()
      reject(new Error('application event WebSocket failed before heartbeat'))
    }
    const handleClose = (event) => {
      cleanup()
      reject(
        new Error(
          `application event WebSocket closed before heartbeat: ${event.code} ${event.reason}`,
        ),
      )
    }
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
