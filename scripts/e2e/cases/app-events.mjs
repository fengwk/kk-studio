import { assert, cid, envelopeData, pageResults, sleep } from '../lib/http.mjs'
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

registerCase({
  id: 'events.project_cloud_files_invalidation',
  level: 'L1',
  title: 'Project 与 Cloud Files 全局失效事件',
  docs: '订阅无 synthetic id 的 projects/cloud-files 全局资源；数据库提交后的 Project 创建发 changed(projectId)，CloudFS 目录创建发 resync',
  async run(ctx) {
    const socket = new WebSocket(applicationEventUrl(ctx.baseUrl))
    const frames = []
    let project
    const cloudPath = `/uploads/e2e-event-${cid()}`
    const collect = (event) => {
      try {
        frames.push(JSON.parse(String(event.data)))
      } catch {
        // Ignore non-JSON frames; expected protocol frames are asserted below.
      }
    }
    socket.addEventListener('message', collect)
    try {
      await waitForSocketOpen(socket, 5_000)
      const agents = pageResults(
        (await ctx.call('GET', '/api/ai/catalog/agents?pageNumber=1&pageSize=1')).json,
      )
      assert(agents.length > 0 && typeof agents[0].name === 'string', 'Agent seed required')

      socket.send(
        JSON.stringify({ version: 1, type: 'subscribe', resource: { kind: 'projects' } }),
      )
      const projectsAck = await takeFrame(
        frames,
        (frame) => frame?.type === 'subscribed' && frame?.resource?.kind === 'projects',
        5_000,
      )
      assertGlobalSubscriptionAck(projectsAck, 'projects')

      socket.send(
        JSON.stringify({ version: 1, type: 'subscribe', resource: { kind: 'cloud-files' } }),
      )
      const cloudFilesAck = await takeFrame(
        frames,
        (frame) => frame?.type === 'subscribed' && frame?.resource?.kind === 'cloud-files',
        5_000,
      )
      assertGlobalSubscriptionAck(cloudFilesAck, 'cloud-files')

      project = envelopeData(
        (
          await ctx.call('POST', '/api/projects', {
            title: `Event Project ${cid().slice(0, 8)}`,
            description: 'Database notification E2E',
            coordinatorAgentName: agents[0].name,
          })
        ).json,
      )
      const projectEvent = await takeFrame(
        frames,
        (frame) =>
          frame?.type === 'event'
          && frame?.resource?.kind === 'projects'
          && frame?.name === 'changed'
          && frame?.data?.projectId === project.id,
        10_000,
      )
      assertExactKeys(projectEvent, ['version', 'type', 'resource', 'name', 'data'], 'project event')
      assertExactKeys(projectEvent.resource, ['kind'], 'project event resource')
      assertExactKeys(projectEvent.data, ['projectId'], 'project event data')
      assert(projectEvent.version === 1, JSON.stringify(projectEvent))

      await ctx.call('POST', '/api/cloud/directories', {
        path: cloudPath,
        recursive: true,
      })
      const cloudFilesEvent = await takeFrame(
        frames,
        (frame) => frame?.type === 'resync' && frame?.resource?.kind === 'cloud-files',
        10_000,
      )
      assertExactKeys(cloudFilesEvent, ['version', 'type', 'resource'], 'cloud-files event')
      assertExactKeys(cloudFilesEvent.resource, ['kind'], 'cloud-files event resource')
      assert(cloudFilesEvent.version === 1, JSON.stringify(cloudFilesEvent))

      ctx.writeArtifact(
        'global-invalidation.json',
        `${JSON.stringify({ projectsAck, cloudFilesAck, projectEvent, cloudFilesEvent }, null, 2)}\n`,
      )
    } finally {
      socket.removeEventListener('message', collect)
      socket.close(1000, 'E2E complete')
      if (project?.id) {
        const current = envelopeData(
          (await ctx.call('GET', `/api/projects/${project.id}`)).json,
        )
        await ctx.call(
          'DELETE',
          `/api/projects/${project.id}?expectedVersion=${encodeURIComponent(current.version)}`,
        )
      }
      try {
        const snapshot = envelopeData(
          (await ctx.call('GET', `/api/cloud/files?path=${encodeURIComponent(cloudPath)}`)).json,
        )
        await ctx.call(
          'DELETE',
          `/api/cloud/nodes?path=${encodeURIComponent(cloudPath)}`
            + `&expectedVersion=${encodeURIComponent(snapshot.node.version)}`,
        )
      } catch {
        // The directory may not have been created if the test failed earlier.
      }
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

async function waitForSocketOpen(socket, timeoutMs) {
  if (socket.readyState === WebSocket.OPEN) {
    return
  }
  await new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      cleanup()
      reject(new Error(`timed out waiting ${timeoutMs}ms for application event WebSocket open`))
    }, timeoutMs)
    const handleOpen = () => {
      cleanup()
      resolve()
    }
    const handleError = () => {
      cleanup()
      reject(new Error('application event WebSocket failed before open'))
    }
    function cleanup() {
      clearTimeout(timer)
      socket.removeEventListener('open', handleOpen)
      socket.removeEventListener('error', handleError)
    }
    socket.addEventListener('open', handleOpen)
    socket.addEventListener('error', handleError)
  })
}

async function takeFrame(frames, predicate, timeoutMs) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    const index = frames.findIndex(predicate)
    if (index >= 0) {
      return frames.splice(index, 1)[0]
    }
    await sleep(25)
  }
  throw new Error(`timed out waiting ${timeoutMs}ms for application event frame`)
}

function assertGlobalSubscriptionAck(frame, kind) {
  assertExactKeys(frame, ['version', 'type', 'resource', 'cursor'], `${kind} subscribed frame`)
  assertExactKeys(frame.resource, ['kind'], `${kind} subscribed resource`)
  assert(
    frame.version === 1
      && frame.type === 'subscribed'
      && frame.resource.kind === kind
      && frame.cursor === '0',
    JSON.stringify(frame),
  )
}

function assertExactKeys(value, expected, label) {
  assert(value && typeof value === 'object' && !Array.isArray(value), `${label} must be an object`)
  const actual = Object.keys(value).sort()
  const sortedExpected = [...expected].sort()
  assert(
    actual.length === sortedExpected.length
      && actual.every((field, index) => field === sortedExpected[index]),
    `${label} fields must be ${sortedExpected.join(',')}, got ${actual.join(',')}`,
  )
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
