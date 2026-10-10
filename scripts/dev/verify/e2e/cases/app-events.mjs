import { assert, cid, envelopeData } from '../lib/http.mjs'
import { EventProbe } from '../lib/event-probe.mjs'
import { registerCase } from '../lib/registry.mjs'

registerCase({
  id: 'events.heartbeat_keepalive',
  level: 'L1',
  title: '应用事件 WebSocket 周期 heartbeat 保活',
  docs: '原生 WebSocket 直连 /api/events/v1，不建立资源订阅；共享 carrier 重组后等待连接级 heartbeat，严格断言 {version:2,type:"heartbeat"} 且连接保持打开',
  async run(ctx) {
    const probe = new EventProbe(ctx.baseUrl)
    const startedAt = Date.now()
    try {
      await probe.until(() => probe.frames.some((frame) => frame.type === 'heartbeat'), 25_000)
      const elapsedMs = Date.now() - startedAt
      const frame = probe.frames.find((candidate) => candidate.type === 'heartbeat')
      assert(
        probe.socket.readyState === WebSocket.OPEN,
        `event socket closed before heartbeat validation: ${probe.socket.readyState}`,
      )
      assertExactKeys(frame, ['version', 'type'], 'heartbeat frame')
      assert(frame.version === 2 && frame.type === 'heartbeat', `invalid heartbeat frame: ${JSON.stringify(frame)}`)
      ctx.writeArtifact('heartbeat.json', `${JSON.stringify({ elapsedMs, frame }, null, 2)}\n`)
    } finally {
      await probe.close()
    }
  },
})

registerCase({
  id: 'events.project_invalidation',
  level: 'L1',
  title: 'Project 全局失效事件',
  docs: '订阅无 synthetic id 的 projects 全局资源；数据库提交后的 Project 创建发 changed(projectId)',
  async run(ctx) {
    const probe = new EventProbe(ctx.baseUrl)
    let project
    try {
      const projectsAck = await probe.subscribe({ kind: 'projects' })
      assertGlobalSubscriptionAck(projectsAck, 'projects')

      project = envelopeData(
        (
          await ctx.call('POST', '/api/projects', {
            title: `Event Project ${cid().slice(0, 8)}`,
            description: 'Database notification E2E',
          })
        ).json,
      )
      await probe.until(
        () =>
          probe.count(
            (frame) =>
              frame.type === 'event'
              && frame.resource?.kind === 'projects'
              && frame.name === 'changed'
              && frame.data?.projectId === project.id,
          ) >= 1,
        10_000,
      )
      const projectEvent = probe.frames.find(
        (frame) =>
          frame.type === 'event'
          && frame.resource?.kind === 'projects'
          && frame.name === 'changed'
          && frame.data?.projectId === project.id,
      )
      assertExactKeys(projectEvent, ['version', 'type', 'resource', 'name', 'data'], 'project event')
      assertExactKeys(projectEvent.resource, ['kind'], 'project event resource')
      assertExactKeys(projectEvent.data, ['projectId'], 'project event data')
      assert(projectEvent.version === 2, JSON.stringify(projectEvent))

      ctx.writeArtifact(
        'global-invalidation.json',
        `${JSON.stringify({ projectsAck, projectEvent }, null, 2)}\n`,
      )
    } finally {
      await probe.close()
      if (project?.id) {
        const current = envelopeData(
          (await ctx.call('GET', `/api/projects/${project.id}`)).json,
        )
        await ctx.call(
          'DELETE',
          `/api/projects/${project.id}?expectedVersion=${encodeURIComponent(current.version)}`,
        )
      }
    }
  },
})

function assertGlobalSubscriptionAck(frame, kind) {
  assertExactKeys(frame, ['version', 'type', 'resource', 'cursor'], `${kind} subscribed frame`)
  assertExactKeys(frame.resource, ['kind'], `${kind} subscribed resource`)
  assert(
    frame.version === 2
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
