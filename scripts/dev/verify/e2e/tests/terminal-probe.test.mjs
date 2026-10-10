import assert from 'node:assert/strict'
import test from 'node:test'
import { inputDigest, resizeDigest, TerminalProbe, openRunningTerminal } from '../lib/terminal-probe.mjs'
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
const EPOCH = '66666666-6666-4666-8666-666666666666'
const TOKEN = '77777777-7777-4777-8777-777777777777'
const encoder = new TextEncoder()
const decoder = new TextDecoder('utf-8', { fatal: true })
let frameSeq = 0

const delay = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

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

/** Fake native socket that starts CONNECTING and both directions use real shared carriers. */
class MockSocket extends EventTarget {
  readyState = 0
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

  open() {
    if (this.readyState === 1) return
    this.readyState = 1
    this.dispatchEvent(new Event('open'))
  }

  fail() {
    this.readyState = 3
    this.dispatchEvent(new Event('error'))
    this.dispatchEvent(new Event('close'))
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
  const socket = created[0]
  if (!options.deferredOpen) socket.open()
  return { probe, socket }
}

function lastCommand(socket, type) {
  const commands = socket.logical
    .filter((frame) => frame.type === 'shell.command')
    .map((frame) => frame.command)
  return type ? commands.filter((command) => command.type === type).at(-1) : commands.at(-1)
}

function attached(overrides = {}, requestId = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa') {
  return {
    version: 2,
    type: 'shell.event',
    event: {
      version: 1,
      requestId,
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

/** Delivers an ATTACHED that answers the probe's most recent OPEN/ATTACH request. */
function sendAttached(probe, socket, overrides = {}) {
  const frame = probe.open()
  socket.deliver(attached(overrides, frame))
  return frame
}

test('running baseline restarts an ended session once through expectedExited', async () => {
  const { probe, socket } = openProbe()
  let opens = 0
  socket.onSend = (logical) => {
    const command = logical.command
    if (command?.type !== 'OPEN') return
    opens++
    if (opens === 1) {
      assert.equal(command.payload.expectedExited, null)
      const ended = attached({}, command.requestId)
      ended.event.payload.status = 'FAILED'
      socket.deliver(ended)
    } else {
      assert.deepEqual(command.payload.expectedExited, { daemonInstanceId: DAEMON, terminalId: TERMINAL })
      socket.deliver(attached({
        identity: { daemonInstanceId: DAEMON, terminalId: '88888888-8888-4888-8888-888888888888' },
      }, command.requestId))
    }
  }
  try {
    const result = await openRunningTerminal(probe, 1_000)
    assert.equal(result.status, 'RUNNING')
    assert.notEqual(result.terminalId, TERMINAL)
    assert.equal(opens, 2)
  } finally { await probe.close() }
})

test('attach request error fails promptly rather than being hidden as a timeout', async () => {
  const { probe, socket } = openProbe()
  try {
    const requestId = probe.open()
    socket.deliver({
      version: 2, type: 'shell.event', event: {
        version: 1, requestId, environmentId: ENV, viewerId: VIEWER, identity: null,
        type: 'ERROR', payload: { code: 'ROUTE_UNAVAILABLE', disposition: 'NOT_EXECUTED' },
      },
    })
    await assert.rejects(() => probe.waitAttached(requestId, 1_000),
      /attach rejected: ROUTE_UNAVAILABLE\/NOT_EXECUTED/)
  } finally { await probe.close() }
})

function writerChangedEvent(requestId, result, identity = { daemonInstanceId: DAEMON, terminalId: TERMINAL }) {
  return {
    version: 2,
    type: 'shell.event',
    event: {
      version: 1,
      requestId,
      environmentId: ENV,
      viewerId: VIEWER,
      identity,
      type: 'WRITER_CHANGED',
      payload: { writer: { writerEpoch: result?.grant?.epoch ?? EPOCH }, result },
    },
  }
}

function opAckEvent(kind, seq, digest, { epoch = EPOCH, identity = { daemonInstanceId: DAEMON, terminalId: TERMINAL }, code = null } = {}) {
  return {
    version: 2,
    type: 'shell.event',
    event: {
      version: 1,
      requestId: null,
      environmentId: ENV,
      viewerId: VIEWER,
      identity,
      type: 'OP_ACK',
      payload: {
        writerEpoch: epoch,
        result: { kind, seq, digest, outcome: kind === 'CONFIRMED' ? 'WRITTEN' : null, reason: null },
        code,
      },
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

test('waitOpen blocks on CONNECTING, sends nothing before OPEN, and surfaces a native failure', async () => {
  // Test intent: a command queued while the socket is still CONNECTING must never be silently dropped.
  const { probe, socket } = openProbe({ deferredOpen: true })
  let opened = false
  const pending = probe.waitOpen(2_000).then(() => { opened = true })
  await delay(30)
  assert.equal(opened, false, 'waitOpen must not resolve while CONNECTING')
  assert.throws(() => probe.open(), /not open/, 'a command before OPEN must fail loudly')
  assert.equal(socket.logical.length, 0, 'no command may be queued while CONNECTING')
  socket.open()
  await pending
  assert.equal(probe.socket.readyState, 1)

  const { probe: failing, socket: dead } = openProbe({ deferredOpen: true })
  const waiting = failing.waitOpen(2_000)
  dead.fail()
  await assert.rejects(() => waiting, /application event socket error/)
  await probe.close()
  await failing.close()
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
  sendAttached(probe, socket)
  socket.deliver(viewEvent(reset(['', '', ''])))
  socket.deliver(writerChangedEvent('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', { status: 'GRANTED', grant: { epoch: EPOCH, token: TOKEN }, recovered: null, reason: null }))
  const bytes = Buffer.from('printf hi\r', 'utf8')
  probe.sendInput(bytes, 1)
  const command = lastCommand(socket, 'INPUT')
  assert.equal(command.payload.bytes, bytes.toString('base64'))
  assert.equal(command.payload.seq, 1)
  assert.equal(command.payload.inputModeRevision, 1)
  assert.deepEqual(command.payload.grant, { epoch: EPOCH, token: TOKEN })
  assert.equal(probe.counters.inputsSent, 1)
  probe.close()
})

test('applies structured RESET/PATCH and ACKs the exact in-flight version', () => {
  // Test intent: VIEW_APPLIED targets the applied structured version and screen text comes from numeric slots.
  const { probe, socket } = openProbe()
  sendAttached(probe, socket)
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
  sendAttached(probe, socket)
  socket.deliver(viewEvent(reset(['Z', '', ''])))
  assert.equal(lastCommand(socket, 'VIEW_APPLIED'), undefined)
  probe.ack()
  assert.equal(lastCommand(socket, 'VIEW_APPLIED').payload.version, 1)
  assert.throws(() => probe.ack(0), /before a structured view update/)
  probe.close()
})

test('ignores an ATTACHED that does not answer a pending OPEN/ATTACH', () => {
  // Test intent: a stale or foreign ATTACHED must never bind identity or a stream.
  const { probe, socket } = openProbe()
  probe.open()
  socket.deliver(attached({}, 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'))
  assert.equal(probe.identity, null, 'a stale ATTACHED must not bind identity')
  assert.equal(probe.streamId, null)
  assert.equal(probe.attachResults.length, 0)
  probe.close()
})

test('drops identity-carrying events from a foreign daemon/terminal without touching state', () => {
  // Test intent: WRITER_CHANGED/OP_ACK/EXITED for another terminal must never mutate this scope.
  const { probe, socket } = openProbe()
  sendAttached(probe, socket)
  const foreign = { daemonInstanceId: DAEMON, terminalId: '99999999-9999-4999-8999-999999999999' }
  socket.deliver(writerChangedEvent(null, null, foreign))
  socket.deliver(opAckEvent('CONFIRMED', 1, inputDigest(Buffer.from('x'), 1), { identity: foreign }))
  socket.deliver({
    version: 2,
    type: 'shell.event',
    event: {
      version: 1,
      requestId: null,
      environmentId: ENV,
      viewerId: VIEWER,
      identity: foreign,
      type: 'EXITED',
      payload: { status: 'FAILED', exitCode: 1 },
    },
  })
  assert.equal(probe.counters.writerChanged, 0)
  assert.equal(probe.counters.opAcks, 0)
  assert.equal(probe.counters.exited, 0)
  assert.equal(probe.controlResults.length, 0)
  assert.equal(probe.opAcks.length, 0)
  assert.equal(probe.exitEvents.length, 0)
  probe.close()
})

test('ATTACH must answer its own request and match the declared identity', () => {
  // Test intent: an ATTACHED with the wrong terminal identity must not rebind the observer stream.
  const { probe, socket } = openProbe()
  sendAttached(probe, socket)
  const declared = { daemonInstanceId: DAEMON, terminalId: TERMINAL }
  const attachId = probe.attach(declared)
  socket.deliver(attached({ identity: { daemonInstanceId: DAEMON, terminalId: '99999999-9999-4999-8999-999999999999' } }, attachId))
  assert.equal(probe.attachResults.length, 1, 'a mismatched ATTACHED must be dropped')
  socket.deliver(attached({}, attachId))
  assert.equal(probe.attachResults.length, 2)
  probe.close()
})

test('fences foreign identity and a mismatched stream', () => {
  // Test intent: events for another viewer/environment are ignored; a wrong streamId is a fatal fault.
  const { probe, socket } = openProbe()
  sendAttached(probe, socket)
  socket.deliver(viewEvent(reset(['A', '', '']), null))
  // Foreign viewer and environment events are silently ignored.
  const foreign = attached({ viewerId: '99999999-9999-4999-8999-999999999999' })
  socket.deliver(foreign)
  assert.equal(probe.identity.terminalId, TERMINAL)
  // A stale stream is never accepted (nor ACKed): it is a protocol violation on this connection.
  socket.deliver(viewEvent({ ...reset(['A', '', '']), streamId: '99999999-9999-4999-8999-999999999999' }))
  assert.ok(probe.failure)
  assert.throws(() => probe.check(), /streamId/)
  probe.close()
})

test('an identity-free ERROR is accepted only while its request is pending', () => {
  // Test intent: deterministic local NOT_EXECUTED errors correlate by requestId, not by identity.
  const { probe, socket } = openProbe()
  const openId = probe.open()
  socket.deliver({
    version: 2,
    type: 'shell.event',
    event: {
      version: 1,
      requestId: openId,
      environmentId: ENV,
      viewerId: VIEWER,
      identity: null,
      type: 'ERROR',
      payload: { code: 'ROUTE_UNAVAILABLE', disposition: 'NOT_EXECUTED' },
    },
  })
  assert.equal(probe.errorEvents.length, 1)
  socket.deliver({
    version: 2,
    type: 'shell.event',
    event: {
      version: 1,
      requestId: 'cccccccc-cccc-4ccc-8ccc-cccccccccccc',
      environmentId: ENV,
      viewerId: VIEWER,
      identity: null,
      type: 'ERROR',
      payload: { code: 'ROUTE_UNAVAILABLE', disposition: 'NOT_EXECUTED' },
    },
  })
  assert.equal(probe.errorEvents.length, 1, 'an identity-free error with no pending request must be dropped')
  probe.close()
})

test('correlates OP_ACK by epoch/seq/digest and supports the seq-limited drop hook', async () => {
  // Test intent: PENDING acks stay recorded; only the final receipt for the selected seq is dropped.
  const { probe, socket } = openProbe()
  sendAttached(probe, socket)
  socket.deliver(viewEvent(reset(['', '', ''])))
  const digest = inputDigest(Buffer.from('x'), 1)
  probe.dropNextOpAck(1)
  socket.deliver(opAckEvent('PENDING', 1, digest))
  assert.equal(probe.opAcks.length, 1, 'PENDING must be recorded even while a drop is armed')
  socket.deliver(opAckEvent('CONFIRMED', 1, digest))
  assert.equal(probe.opAcks.length, 1, 'the final CONFIRMED for seq 1 must be dropped')
  assert.equal(probe.counters.droppedOpAcks, 1)
  socket.deliver(opAckEvent('CONFIRMED', 2, resizeDigest(80, 24)))
  assert.equal(probe.opAcks.length, 2, 'a different seq must be unaffected by the drop')
  const ack = await probe.waitOpAck({ epoch: EPOCH, seq: 2, digest: resizeDigest(80, 24) }, 1_000)
  assert.equal(ack.kind, 'CONFIRMED')
  probe.close()
})

test('clears raw logical frames and keeps bounded metadata', async () => {
  // Test intent: terminal output must not accumulate without bound, and close must release the hooks.
  const { probe, socket } = openProbe()
  sendAttached(probe, socket)
  socket.deliver(viewEvent(reset(['', '', ''])))
  for (let index = 0; index < 200; index++) socket.deliver({ version: 2, type: 'heartbeat' })
  assert.equal(probe.framed.frames.length, 0, 'raw logical frames must not be retained')
  assert.ok(probe.attachResults.length <= 64 && probe.opAcks.length <= 64)
  await probe.close()
  assert.equal(probe.closed, true)
  assert.equal(socket.readyState, 3)
  assert.equal(probe.framed.hooks.size, 0)
})

test('heartbeat renews the owned writer lease and stops after detach', async () => {
  // Test intent: a 5s-style heartbeat keeps the observer/writer alive but releases after DETACH.
  const { probe, socket } = openProbe({ heartbeatMs: 25 })
  sendAttached(probe, socket)
  socket.deliver(viewEvent(reset(['', '', ''])))
  socket.deliver(writerChangedEvent('cccccccc-cccc-4ccc-8ccc-cccccccccccc', { status: 'GRANTED', grant: { epoch: EPOCH, token: TOKEN }, recovered: null, reason: null }))
  await delay(60)
  const keepalive = lastCommand(socket, 'KEEPALIVE')
  assert.ok(keepalive, 'heartbeat must emit KEEPALIVE')
  assert.deepEqual(keepalive.payload.grant, probe.grant)
  probe.detach()
  const before = socket.logical.filter((frame) => frame.command?.type === 'KEEPALIVE').length
  await delay(80)
  const after = socket.logical.filter((frame) => frame.command?.type === 'KEEPALIVE').length
  assert.equal(after, before, 'detached probe must stop sending KEEPALIVE')
  await probe.close()
})

test('claimGranted retries VIEW_NOT_APPLIED then accepts the grant; a rejection fails fast', async () => {
  // Test intent: VIEW_APPLIED has no ack, so a claim raced before it is retried; a real reject must surface.
  const { probe, socket } = openProbe()
  const openId = probe.open()
  socket.deliver(attached({}, openId))
  socket.deliver(viewEvent(reset(['', '', ''])))
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
    socket.deliver(writerChangedEvent(requestId, { status: 'GRANTED', grant: { epoch: EPOCH, token: TOKEN }, recovered: null, reason: null }))
  }
  const result = await probe.claimGranted({ timeoutMs: 2_000 })
  assert.equal(result.status, 'GRANTED')
  assert.equal(claims, 2)
  assert.deepEqual(probe.grant, { epoch: EPOCH, token: TOKEN })

  socket.onSend = (logical) => {
    if (logical.type !== 'shell.command' || logical.command.type !== 'CLAIM') return
    socket.deliver(writerChangedEvent(logical.command.requestId, { status: 'REJECTED', grant: null, recovered: null, reason: 'NOT_OWNER' }))
  }
  await assert.rejects(() => probe.claimGranted({ timeoutMs: 1_000 }), /NOT_OWNER/)
  probe.close()
})

test('a broadcast WRITER_CHANGED is never mistaken for the request receipt', async () => {
  // Test intent: the daemon emits a broadcast (requestId=null, result=null) before the direct
  // request response (requestId set, result GRANTED); only the direct frame may resolve a control wait.
  const { probe, socket } = openProbe()
  sendAttached(probe, socket)
  socket.deliver(viewEvent(reset(['', '', ''])))

  socket.deliver(writerChangedEvent(null, null))
  assert.equal(probe.writer.writerEpoch, EPOCH, 'the broadcast still updates the observed writer')
  assert.equal(probe.grant, null, 'a broadcast must never grant the writer')
  assert.equal(probe.controlResults.length, 0, 'a broadcast must not be recorded as a request receipt')
  await assert.rejects(
    () => probe.waitControlResult('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', 200),
    /timed out/,
  )

  socket.deliver(writerChangedEvent('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', { status: 'GRANTED', grant: { epoch: EPOCH, token: TOKEN }, recovered: null, reason: null }))
  const result = await probe.waitControlResult('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', 1_000)
  assert.equal(result.status, 'GRANTED')
  assert.deepEqual(probe.grant, { epoch: EPOCH, token: TOKEN })

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
  sendAttached(probe, socket)
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
