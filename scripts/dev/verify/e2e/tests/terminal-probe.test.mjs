import assert from 'node:assert/strict'
import test from 'node:test'
import { inputDigest, resizeDigest, TerminalProbe } from '../lib/terminal-probe.mjs'
import {
  NotificationPacket,
  carrierChunk,
  carrierCount,
  decodeNotificationCarrier,
  defaultNotificationLimits,
} from '../../../../../frontend/src/shared/notification/notification.mjs'

const LIMITS = defaultNotificationLimits()
const SERVER_PUBLISHER = 'ffffffff-ffff-4fff-8fff-000000000001'
const ENV = '33333333-3333-4333-8333-333333333333'
const VIEWER = '11111111-1111-4111-8111-111111111111'
const TERMINAL = '22222222-2222-4222-8222-222222222222'
const DAEMON = '44444444-4444-4444-8444-444444444444'
const STREAM = '55555555-5555-4555-8555-555555555555'
const encoder = new TextEncoder()
const decoder = new TextDecoder('utf-8', { fatal: true })
let frameSeq = 0

/** Real shared carrier fragments: server frames are never raw JSON. */
function serverFrames(logical, { topic = 'app.events.v2', target = null } = {}) {
  frameSeq += 1
  const packet = new NotificationPacket(
    SERVER_PUBLISHER,
    target,
    topic,
    `ffffffff-ffff-4fff-8fff-${String(frameSeq).padStart(12, '0')}`,
    encoder.encode(JSON.stringify(logical)),
  )
  return Array.from({ length: carrierCount(packet.byteLength()) }, (_, index) =>
    carrierChunk(packet, index).encode())
}

/** Fake native socket that both directions use real shared carriers, and can auto-respond on send. */
class MockSocket extends EventTarget {
  readyState = 1
  sent = []
  logical = []
  listeners = new Set()
  onSend = null

  addEventListener(type, listener, options) {
    this.listeners.add(listener)
    return super.addEventListener(type, listener, options)
  }

  removeEventListener(type, listener, options) {
    this.listeners.delete(listener)
    return super.removeEventListener(type, listener, options)
  }

  send(physical) {
    this.sent.push(physical)
    const carrier = decodeNotificationCarrier(physical, LIMITS, null)
    assert.ok(carrier, 'every sent frame must be a canonical carrier')
    assert.equal(carrier.topic(), 'app.events.v2')
    const logical = JSON.parse(decoder.decode(carrier.bytes()))
    this.logical.push(logical)
    this.onSend?.(logical)
  }

  deliver(logical, options) {
    for (const raw of serverFrames(logical, options)) {
      this.dispatchEvent(new MessageEvent('message', { data: raw }))
    }
  }

  close() {
    this.readyState = 3
    this.dispatchEvent(new Event('close'))
  }
}

function openProbe(options = {}) {
  const created = []
  class CapturingSocket extends MockSocket {
    constructor() {
      super()
      created.push(this)
    }
  }
  const probe = new TerminalProbe('http://a.test', {
    environmentId: ENV,
    viewerId: VIEWER,
    Socket: CapturingSocket,
    heartbeatMs: 0,
    ...options,
  })
  return { probe, socket: created[0] }
}

function lastCommand(socket, type) {
  const commands = socket.logical
    .filter((frame) => frame.type === 'shell.command')
    .map((frame) => frame.command)
  return type ? commands.filter((command) => command.type === type).at(-1) : commands.at(-1)
}

function attached(overrides = {}) {
  return {
    version: 2,
    type: 'shell.event',
    event: {
      version: 1,
      requestId: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
      environmentId: ENV,
      viewerId: VIEWER,
      identity: { daemonInstanceId: DAEMON, terminalId: TERMINAL },
      type: 'ATTACHED',
      payload: {
        streamId: STREAM,
        executable: 'bash',
        status: 'RUNNING',
        exitCode: null,
        inputModeRevision: 1,
        writer: {
          writerEpoch: null,
          lastWrittenSeq: 0,
          lastWrittenDigest: null,
          lastResolvedSeq: 0,
          lastResolvedDigest: null,
          lastResolvedOutcome: null,
          pendingSeq: 0,
          pendingDigest: null,
          frozen: false,
        },
      },
      ...overrides,
    },
  }
}

function inputModes() {
  return {
    applicationCursor: false,
    applicationKeypad: false,
    bracketedPaste: false,
    autoNewLine: false,
    altSendsEscape: false,
    mouseMode: 'NONE',
    mouseFormat: 'XTERM',
  }
}

function line(id, text, cols) {
  const slots = []
  for (let index = 0; index < cols; index++) {
    slots.push(index < text.length ? [0, text.charCodeAt(index), 0] : [1, 0, 0])
  }
  return [id, false, slots]
}

function viewEvent(update, requestId = null) {
  return {
    version: 2,
    type: 'shell.event',
    event: {
      version: 1,
      requestId,
      environmentId: ENV,
      viewerId: VIEWER,
      identity: { daemonInstanceId: DAEMON, terminalId: TERMINAL },
      type: 'VIEW_UPDATE',
      payload: { update },
    },
  }
}

function reset(texts, { cols = 20, version = 1, history = 0 } = {}) {
  return {
    type: 'RESET',
    terminalId: TERMINAL,
    streamId: STREAM,
    baseVersion: null,
    version,
    cols,
    rows: texts.length,
    alternate: false,
    history,
    cursor: { x: 0, y: 0, visible: true, shape: null },
    inputModeRevision: 1,
    inputModes: inputModes(),
    historyTrim: 0,
    historyAppend: [],
    screenRows: texts.map((text, row) => ({ row, line: line(100 + row, text, cols) })),
    styles: [[-1, -1, 0]],
  }
}

function patch(rowTexts, { cols = 20, baseVersion = 1, version = 2 } = {}) {
  return {
    type: 'PATCH',
    terminalId: TERMINAL,
    streamId: STREAM,
    baseVersion,
    version,
    cols,
    rows: 3,
    alternate: false,
    history: 0,
    cursor: { x: 0, y: 0, visible: true, shape: null },
    inputModeRevision: 1,
    inputModes: inputModes(),
    historyTrim: 0,
    historyAppend: [],
    screenRows: Object.entries(rowTexts).map(([row, text]) => ({ row: Number(row), line: line(200 + Number(row), text, cols) })),
    styles: [[-1, -1, 0]],
  }
}

test('requires an environment id', () => {
  // Test intent: a probe without a routing target must fail fast instead of opening a useless socket.
  assert.throws(() => new TerminalProbe('http://a.test', { Socket: MockSocket, heartbeatMs: 0 }), /environmentId/)
})

test('encodes commands as typed shell.command wrappers over real carrier fragments', () => {
  // Test intent: the probe must send the exact v2 wrapper plus a version=1 TerminalCommand, never raw JSON.
  const { probe, socket } = openProbe()
  const requestId = probe.open()
  assert.match(requestId, /^[0-9a-f-]{36}$/)
  const frame = socket.logical.at(-1)
  assert.deepEqual(Object.keys(frame).sort(), ['command', 'type', 'version'])
  assert.equal(frame.version, 2)
  assert.equal(frame.type, 'shell.command')
  assert.equal(frame.command.version, 1)
  assert.equal(frame.command.requestId, requestId)
  assert.equal(frame.command.environmentId, ENV)
  assert.equal(frame.command.viewerId, VIEWER)
  assert.equal(frame.command.type, 'OPEN')
  assert.deepEqual(frame.command.payload, { expectedExited: null })
  probe.close()
})

test('encodes INPUT bytes as canonical Base64 with the current mode revision and grant', () => {
  // Test intent: raw bytes never appear as a JSON array; the payload carries Base64 plus the exact digest inputs.
  const { probe, socket } = openProbe()
  socket.deliver(attached())
  socket.deliver(viewEvent(reset(['', '', ''])))
  const grant = { epoch: '66666666-6666-4666-8666-666666666666', token: '77777777-7777-4777-8777-777777777777' }
  socket.deliver({
    version: 2,
    type: 'shell.event',
    event: {
      version: 1,
      requestId: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
      environmentId: ENV,
      viewerId: VIEWER,
      identity: { daemonInstanceId: DAEMON, terminalId: TERMINAL },
      type: 'WRITER_CHANGED',
      payload: { writer: { writerEpoch: grant.epoch }, result: { status: 'GRANTED', grant, recovered: null, reason: null } },
    },
  })
  const bytes = Buffer.from('printf hi\r', 'utf8')
  probe.sendInput(bytes, 1)
  const command = lastCommand(socket, 'INPUT')
  assert.equal(command.payload.bytes, bytes.toString('base64'))
  assert.equal(command.payload.seq, 1)
  assert.equal(command.payload.inputModeRevision, 1)
  assert.deepEqual(command.payload.grant, grant)
  probe.close()
})

test('applies structured RESET/PATCH and ACKs the exact in-flight version', () => {
  // Test intent: VIEW_APPLIED targets the applied structured version and screen text comes from numeric slots.
  const { probe, socket } = openProbe()
  socket.deliver(attached())
  socket.deliver(viewEvent(reset(['KKS_ABCDEF', '', ''])))
  assert.equal(probe.lastAppliedVersion, 1)
  assert.ok(probe.screen.includes('KKS_ABCDEF'))
  const ack = lastCommand(socket, 'VIEW_APPLIED')
  assert.equal(ack.payload.version, 1)
  assert.equal(ack.payload.streamId, STREAM)
  assert.deepEqual(ack.payload.identity, { daemonInstanceId: DAEMON, terminalId: TERMINAL })

  socket.deliver(viewEvent(patch({ 1: 'KKS_GHIJKL' })))
  assert.equal(probe.lastAppliedVersion, 2)
  assert.ok(probe.screen.includes('KKS_GHIJKL'))
  assert.equal(probe.screenRowCount('KKS_GHIJKL'), 1)
  assert.equal(lastCommand(socket, 'VIEW_APPLIED').payload.version, 2)
  probe.close()
})

test('with autoApplied disabled the ack is explicit', () => {
  // Test intent: an unacked RESET must stay unacked so a test can prove VIEW_NOT_APPLIED gating.
  const { probe, socket } = openProbe({ autoApplied: false })
  socket.deliver(attached())
  socket.deliver(viewEvent(reset(['Z', '', ''])))
  assert.equal(lastCommand(socket, 'VIEW_APPLIED'), undefined)
  probe.ack()
  assert.equal(lastCommand(socket, 'VIEW_APPLIED').payload.version, 1)
  assert.throws(() => probe.ack(0), /before a structured view update/)
  probe.close()
})

test('fences foreign identity and a mismatched stream', () => {
  // Test intent: events for another viewer/environment are ignored; a wrong streamId is a fatal fault.
  const { probe, socket } = openProbe()
  socket.deliver(attached())
  socket.deliver(viewEvent(reset(['A', '', '']), null))
  // Foreign viewer and environment events are silently ignored.
  const foreign = attached({ viewerId: '99999999-9999-4999-8999-999999999999' })
  socket.deliver(foreign)
  assert.equal(probe.identity.terminalId, TERMINAL)
  socket.deliver(viewEvent({ ...reset(['A', '', '']), streamId: '99999999-9999-4999-8999-999999999999' }))
  assert.ok(probe.failure)
  assert.throws(() => probe.check(), /streamId/)
  probe.close()
})

test('correlates OP_ACK by epoch/seq/digest and supports the drop hook', async () => {
  // Test intent: OP_ACK has no requestId, so matching uses the writer fence; the drop hook hides exactly one ack.
  const { probe, socket } = openProbe()
  socket.deliver(attached())
  socket.deliver(viewEvent(reset(['', '', ''])))
  const epoch = '66666666-6666-4666-8666-666666666666'
  const digest = inputDigest(Buffer.from('x'), 1)
  socket.deliver({
    version: 2,
    type: 'shell.event',
    event: {
      version: 1,
      requestId: null,
      environmentId: ENV,
      viewerId: VIEWER,
      identity: { daemonInstanceId: DAEMON, terminalId: TERMINAL },
      type: 'OP_ACK',
      payload: { writerEpoch: epoch, result: { kind: 'CONFIRMED', seq: 1, digest, outcome: 'WRITTEN', reason: null }, code: null },
    },
  })
  const ack = await probe.waitOpAck({ epoch, seq: 1, digest }, 1_000)
  assert.equal(ack.kind, 'CONFIRMED')
  probe.dropNextOpAck()
  socket.deliver({
    version: 2,
    type: 'shell.event',
    event: {
      version: 1,
      requestId: null,
      environmentId: ENV,
      viewerId: VIEWER,
      identity: { daemonInstanceId: DAEMON, terminalId: TERMINAL },
      type: 'OP_ACK',
      payload: { writerEpoch: epoch, result: { kind: 'CONFIRMED', seq: 2, digest: resizeDigest(80, 24), outcome: 'WRITTEN', reason: null }, code: null },
    },
  })
  assert.equal(probe.counters.droppedOpAcks, 1)
  assert.equal(probe.opAcks.length, 1)
  probe.close()
})

test('retains at most 64 logical frames and clears the heartbeat on close', async () => {
  // Test intent: terminal output must not accumulate without bound, and close must release the timer/listeners.
  const { probe, socket } = openProbe()
  socket.deliver(attached())
  socket.deliver(viewEvent(reset(['', '', ''])))
  for (let index = 0; index < 200; index++) socket.deliver({ version: 2, type: 'heartbeat' })
  assert.ok(probe.framed.frames.length <= 64, `frames must stay bounded, got ${probe.framed.frames.length}`)
  await probe.close()
  assert.equal(probe.closed, true)
  assert.equal(socket.readyState, 3)
  assert.equal(probe.framed.hooks.size, 0)
  assert.ok(probe.framed.frames.length <= 64)
})

test('heartbeat renews the owned writer lease and stops after detach', async () => {
  // Test intent: a 5s-style heartbeat keeps the observer/writer alive but releases after DETACH.
  const { probe, socket } = openProbe({ heartbeatMs: 25 })
  socket.deliver(attached())
  socket.deliver(viewEvent(reset(['', '', ''])))
  socket.deliver({
    version: 2,
    type: 'shell.event',
    event: {
      version: 1,
      requestId: 'cccccccc-cccc-4ccc-8ccc-cccccccccccc',
      environmentId: ENV,
      viewerId: VIEWER,
      identity: { daemonInstanceId: DAEMON, terminalId: TERMINAL },
      type: 'WRITER_CHANGED',
      payload: {
        writer: { writerEpoch: '66666666-6666-4666-8666-666666666666' },
        result: {
          status: 'GRANTED',
          grant: { epoch: '66666666-6666-4666-8666-666666666666', token: '77777777-7777-4777-8777-777777777777' },
          recovered: null,
          reason: null,
        },
      },
    },
  })
  await new Promise((resolve) => setTimeout(resolve, 60))
  const keepalive = lastCommand(socket, 'KEEPALIVE')
  assert.ok(keepalive, 'heartbeat must emit KEEPALIVE')
  assert.deepEqual(keepalive.payload.grant, probe.grant)
  probe.detach()
  const before = socket.logical.filter((frame) => frame.command?.type === 'KEEPALIVE').length
  await new Promise((resolve) => setTimeout(resolve, 80))
  const after = socket.logical.filter((frame) => frame.command?.type === 'KEEPALIVE').length
  assert.equal(after, before, 'detached probe must stop sending KEEPALIVE')
  await probe.close()
})

test('claimGranted retries VIEW_NOT_APPLIED then accepts the grant; a rejection fails fast', async () => {
  // Test intent: VIEW_APPLIED has no ack, so a claim raced before it is retried; a real reject must surface.
  const { probe, socket } = openProbe()
  socket.deliver(attached())
  socket.deliver(viewEvent(reset(['', '', ''])))
  const grant = { epoch: '66666666-6666-4666-8666-666666666666', token: '77777777-7777-4777-8777-777777777777' }
  let claims = 0
  socket.onSend = (logical) => {
    if (logical.type !== 'shell.command' || logical.command.type !== 'CLAIM') return
    claims += 1
    const requestId = logical.command.requestId
    if (claims === 1) {
      socket.deliver({
        version: 2,
        type: 'shell.event',
        event: {
          version: 1,
          requestId,
          environmentId: ENV,
          viewerId: VIEWER,
          identity: { daemonInstanceId: DAEMON, terminalId: TERMINAL },
          type: 'ERROR',
          payload: { code: 'VIEW_NOT_APPLIED', disposition: 'NOT_EXECUTED' },
        },
      })
      return
    }
    socket.deliver({
      version: 2,
      type: 'shell.event',
      event: {
        version: 1,
        requestId,
        environmentId: ENV,
        viewerId: VIEWER,
        identity: { daemonInstanceId: DAEMON, terminalId: TERMINAL },
        type: 'WRITER_CHANGED',
        payload: { writer: { writerEpoch: grant.epoch }, result: { status: 'GRANTED', grant, recovered: null, reason: null } },
      },
    })
  }
  const result = await probe.claimGranted({ timeoutMs: 2_000 })
  assert.equal(result.status, 'GRANTED')
  assert.equal(claims, 2)
  assert.deepEqual(probe.grant, grant)

  socket.onSend = (logical) => {
    if (logical.type !== 'shell.command' || logical.command.type !== 'CLAIM') return
    socket.deliver({
      version: 2,
      type: 'shell.event',
      event: {
        version: 1,
        requestId: logical.command.requestId,
        environmentId: ENV,
        viewerId: VIEWER,
        identity: { daemonInstanceId: DAEMON, terminalId: TERMINAL },
        type: 'WRITER_CHANGED',
        payload: { writer: { writerEpoch: grant.epoch }, result: { status: 'REJECTED', grant: null, recovered: null, reason: 'NOT_OWNER' } },
      },
    })
  }
  await assert.rejects(() => probe.claimGranted({ timeoutMs: 1_000 }), /NOT_OWNER/)
  probe.close()
})

test('a broadcast WRITER_CHANGED is never mistaken for the request receipt', async () => {
  // Test intent: the daemon emits a broadcast (requestId=null, result=null) before the direct
  // request response (requestId set, result GRANTED); only the direct frame may resolve a control wait.
  const { probe, socket } = openProbe()
  socket.deliver(attached())
  socket.deliver(viewEvent(reset(['', '', ''])))
  const grant = { epoch: '66666666-6666-4666-8666-666666666666', token: '77777777-7777-4777-8777-777777777777' }
  const writerChanged = (requestId, result) => ({
    version: 2,
    type: 'shell.event',
    event: {
      version: 1,
      requestId,
      environmentId: ENV,
      viewerId: VIEWER,
      identity: { daemonInstanceId: DAEMON, terminalId: TERMINAL },
      type: 'WRITER_CHANGED',
      payload: { writer: { writerEpoch: grant.epoch }, result },
    },
  })

  socket.deliver(writerChanged(null, null))
  assert.equal(probe.writer.writerEpoch, grant.epoch, 'the broadcast still updates the observed writer')
  assert.equal(probe.grant, null, 'a broadcast must never grant the writer')
  assert.equal(probe.controlResults.length, 0, 'a broadcast must not be recorded as a request receipt')
  await assert.rejects(
    () => probe.waitControlResult('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', 200),
    /timed out/,
  )

  socket.deliver(writerChanged('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', { status: 'GRANTED', grant, recovered: null, reason: null }))
  const result = await probe.waitControlResult('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', 1_000)
  assert.equal(result.status, 'GRANTED')
  assert.deepEqual(probe.grant, grant)

  // A later broadcast that rotates the epoch away from our grant releases the owned writer.
  socket.deliver({
    version: 2,
    type: 'shell.event',
    event: {
      version: 1,
      requestId: null,
      environmentId: ENV,
      viewerId: VIEWER,
      identity: { daemonInstanceId: DAEMON, terminalId: TERMINAL },
      type: 'WRITER_CHANGED',
      payload: { writer: { writerEpoch: '88888888-8888-4888-8888-888888888888' }, result: null },
    },
  })
  assert.equal(probe.grant, null, 'a rotated broadcast epoch must release the stale grant')
  probe.close()
})

test('RESET must carry a full history append and the current input mode revision', () => {
  // Test intent: keep the mirror strict against the Java TerminalViewUpdate invariants.
  const { probe, socket } = openProbe()
  socket.deliver(attached())
  socket.deliver(viewEvent({ ...reset(['', '', '']), history: 1, historyTrim: 0, historyAppend: [] }))
  assert.ok(probe.failure, 'RESET with a mismatched historyAppend length must fault')
  probe.close()
})

test('digest derivation matches the canonical wire algorithm', () => {
  // Test intent: lock the INPUT/RESIZE SHA-256 derivation to the daemon reducer's byte layout.
  assert.equal(
    inputDigest(Buffer.from("printf '%s\\n' ABKKS_abcd1234efgh\r"), 1),
    '12a8b61bb3670c4bc4727eefac81c3d91f3b421f65f80f20fc00597da585675b',
  )
  assert.equal(resizeDigest(100, 30), '8b37bee03c2dbc97135eb7501ea73ce898cb8d6959f55a81ef83a93bcdc41d9c')
})
