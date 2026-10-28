import { Buffer } from 'node:buffer'

import { describe, expect, it } from 'vitest'

import {
  MUX_ACK_THRESHOLD,
  MUX_CONNECTION_CREDIT,
  MUX_MAX_MANAGEMENT_BYTES,
  MUX_MAX_MANAGEMENT_FRAMES,
  MUX_MAX_STREAMS,
  MUX_STREAM_CREDIT,
  MuxError,
  createMuxConnection,
  type MuxConnection,
  type MuxConnectionOptions,
  type MuxStream,
  type MuxWriteOptions,
} from '../src/transport/mux.js'
import {
  MuxWireError,
  decodeFrame,
  encodeFrame,
  type ClosePayload,
  type DataFrame,
  type HeadersPayload,
  type MuxFrame,
  type OpenRequest,
  type ResetPayload,
} from '../src/transport/mux-wire.js'
import { tick } from './helpers.js'

const VIEW_ID = '01234567-89ab-4def-8123-456789abcdef'
const BINDING_EPOCH = '1'

function openRequest(mode: 'HTTP' | 'WS' = 'HTTP'): OpenRequest {
  return {
    viewId: VIEW_ID,
    bindingEpoch: BINDING_EPOCH,
    mode,
    method: mode === 'WS' ? 'GET' : 'POST',
    path: mode === 'WS' ? '/ws' : '/request',
    headers: [['accept', '*/*']],
  }
}

function frame(value: MuxFrame): Uint8Array {
  return encodeFrame(value)
}

function decodeAll(frames: Uint8Array[]): MuxFrame[] {
  return frames.map((value) => decodeFrame(value))
}

interface Sink {
  raw: Uint8Array[]
  calls: number
  frames(): MuxFrame[]
  send(value: Uint8Array): void
}

function sink(): Sink {
  const raw: Uint8Array[] = []
  const output: Sink = {
    raw,
    calls: 0,
    frames: () => decodeAll(raw),
    send(value: Uint8Array): void {
      output.calls++
      raw.push(value)
    },
  }
  return output
}

function makeConnection(options: Omit<MuxConnectionOptions, 'send'>, output: Sink): MuxConnection {
  return createMuxConnection({
    ...options,
    send(value: Uint8Array) {
      output.send(value)
    },
  } as MuxConnectionOptions)
}

function captureOnOpen(): { streams: MuxStream[]; onOpen(stream: MuxStream): void } {
  const streams: MuxStream[] = []
  return {
    streams,
    onOpen(stream: MuxStream) {
      streams.push(stream)
    },
  }
}

function acceptAndHeaders(stream: MuxStream, status = 200): Promise<unknown> {
  return Promise.all([stream.accept(), stream.sendHeaders({ status, headers: [['content-type', 'text/plain']] })])
}

async function receiveOpen(connection: MuxConnection, streamId: number, mode: 'HTTP' | 'WS' = 'HTTP'): Promise<void> {
  connection.receive(frame({ type: 'OPEN', streamId, request: openRequest(mode) }))
  await tick()
}

async function waitFor(predicate: () => boolean, message = 'condition'): Promise<void> {
  for (let index = 0; index < 200; index++) {
    if (predicate()) {
      return
    }
    await tick()
  }
  throw new Error(`timed out waiting for ${message}`)
}

function deferred(): { promise: Promise<void>; resolve(): void; reject(error: Error): void } {
  let resolve: () => void = () => {}
  let reject: (error: Error) => void = () => {}
  const promise = new Promise<void>((resolveValue, rejectValue) => {
    resolve = resolveValue
    reject = rejectValue
  })
  return { promise, resolve, reject }
}

function dataFrames(frames: MuxFrame[]): DataFrame[] {
  return frames.filter((value): value is DataFrame => value.type === 'DATA')
}

function windows(frames: MuxFrame[]): { streamId: number; credit: number }[] {
  return frames
    .filter((value): value is Extract<MuxFrame, { type: 'WINDOW' }> => value.type === 'WINDOW')
    .map(({ streamId, credit }) => ({ streamId, credit }))
}

describe('single writer and per-stream ordering', () => {
  it('round-robins DATA one frame per ready stream', async () => {
    const output = sink()
    const server = makeConnection({ role: 'server' }, output)
    const first = server.open(openRequest())
    const second = server.open(openRequest())
    await server.drain()
    server.receive(frame({ type: 'READY', streamId: first.id }))
    server.receive(frame({ type: 'READY', streamId: second.id }))
    const writes = [
      first.write(Buffer.from('a1')),
      second.write(Buffer.from('b1')),
      first.write(Buffer.from('a2')),
      second.write(Buffer.from('b2')),
    ]
    await Promise.all(writes)
    await server.drain()
    expect(dataFrames(output.frames()).map((data) => [data.streamId, data.payload.toString()])).toEqual([
      [first.id, 'a1'],
      [second.id, 'b1'],
      [first.id, 'a2'],
      [second.id, 'b2'],
    ])
  })

  it('does not let END overtake earlier DATA from the same stream', async () => {
    const output = sink()
    const server = makeConnection({ role: 'server' }, output)
    const first = server.open(openRequest())
    const second = server.open(openRequest())
    await server.drain()
    server.receive(frame({ type: 'READY', streamId: first.id }))
    server.receive(frame({ type: 'READY', streamId: second.id }))
    const order = [
      first.write(Buffer.from('one')),
      first.write(Buffer.from('two')),
      first.write(Buffer.from('three')),
      first.end(),
      second.write(Buffer.from('other')),
    ]
    await Promise.all(order)
    await server.drain()
    expect(
      output
        .frames()
        .filter((value) => value.type === 'DATA' || value.type === 'END')
        .map((value) => (value.type === 'END' ? `END:${value.streamId}` : `DATA:${value.streamId}:${value.payload.toString()}`)),
    ).toEqual([
      `DATA:${first.id}:one`,
      `DATA:${first.id}:two`,
      `DATA:${second.id}:other`,
      `DATA:${first.id}:three`,
      `END:${first.id}`,
    ])
  })

  it('uses a single physical writer and never retries a failed send', async () => {
    const calls: Uint8Array[] = []
    let fatal: MuxError | null = null
    const server = createMuxConnection({
      role: 'server',
      send: async (value) => {
        calls.push(value)
        throw new Error('socket write failed')
      },
      onFatal: (error) => {
        fatal ??= error
      },
    })
    const first = server.open(openRequest())
    const second = server.open(openRequest())
    await expect(server.drain()).rejects.toThrow(/physical mux send failed|socket write failed/)
    expect(calls).toHaveLength(1)
    expect(fatal?.message).toBe('physical mux send failed')
    expect(() => server.open(openRequest())).toThrow(MuxError)
    await expect(first.accept()).rejects.toThrow(MuxError)
    await expect(second.write(Buffer.from('x'))).rejects.toThrow(MuxError)
    await expect(server.drain()).rejects.toThrow(/physical mux send failed/)
    server.shutdown()
    expect(calls).toHaveLength(1)
  })

  it('treats a synchronous sender throw as fail-stop and ignores later frames', async () => {
    let calls = 0
    let fatal = 0
    const server = createMuxConnection({
      role: 'server',
      send: () => {
        calls++
        throw new Error('boom')
      },
      onFatal: () => {
        fatal++
      },
    })
    server.open(openRequest())
    await tick()
    expect(calls).toBe(1)
    expect(fatal).toBe(1)
    server.receive(frame({ type: 'READY', streamId: 1 }))
    expect(calls).toBe(1)
  })

  it('shutdown rejects pending work without onFatal and stops writes', async () => {
    const pending: (() => void)[] = []
    let calls = 0
    let fatal = 0
    const server = createMuxConnection({
      role: 'server',
      send: () => {
        calls++
        return new Promise<void>((resolve) => {
          pending.push(resolve)
        })
      },
      onFatal: () => {
        fatal++
      },
    })
    const stream = server.open(openRequest())
    await tick()
    expect(calls).toBe(1)
    server.receive(frame({ type: 'READY', streamId: stream.id }))
    const write = stream.write(Buffer.from('queued'))
    server.shutdown()
    await expect(write).rejects.toThrow(/closed/)
    await expect(server.drain()).rejects.toThrow(/closed/)
    await expect(stream.accept()).rejects.toThrow(/closed/)
    pending[0]?.()
    await tick()
    expect(calls).toBe(1)
    expect(fatal).toBe(0)
  })
})

describe('outbound credit and waits', () => {
  it('waits for stream credit and supports cancellation and a single waiter per stream', async () => {
    const output = sink()
    const server = makeConnection({ role: 'server' }, output)
    const stream = server.open(openRequest())
    await server.drain()
    server.receive(frame({ type: 'READY', streamId: stream.id }))
    const chunk = Buffer.alloc(65_536)
    for (let index = 0; index < MUX_STREAM_CREDIT / chunk.byteLength; index++) {
      await stream.write(chunk)
    }
    const fifth = stream.write(Buffer.from('x'))
    await tick()
    expect(dataFrames(output.frames())).toHaveLength(4)
    await expect(stream.write(Buffer.from('y'))).rejects.toThrow(/pending credit wait/)
    server.receive(frame({ type: 'WINDOW', streamId: stream.id, credit: 512 }))
    await fifth
    expect(dataFrames(output.frames())).toHaveLength(5)

    const controller = new AbortController()
    const sixth = stream.write(Buffer.from('z'), { signal: controller.signal })
    await tick()
    controller.abort()
    await expect(sixth).rejects.toMatchObject({ name: 'AbortError' })
    server.receive(frame({ type: 'WINDOW', streamId: stream.id, credit: 512 }))
    await stream.write(Buffer.from('w'))
  })

  it('fails credit waiters once when a later physical send fails', async () => {
    let calls = 0
    let failNext = false
    const fatal: MuxError[] = []
    const server = createMuxConnection({
      role: 'server',
      send: () => {
        calls++
        if (failNext) {
          return Promise.reject(new Error('socket dead'))
        }
      },
      onFatal: (error) => fatal.push(error),
    })
    const blocked = server.open(openRequest())
    const trigger = server.open(openRequest())
    await server.drain()
    server.receive(frame({ type: 'READY', streamId: blocked.id }))
    server.receive(frame({ type: 'READY', streamId: trigger.id }))
    const chunk = Buffer.alloc(65_536)
    for (let index = 0; index < 4; index++) {
      await blocked.write(chunk)
    }
    const waiting = blocked.write(Buffer.from('waiting'))
    await tick()
    failNext = true
    const failing = trigger.write(Buffer.from('fail'))
    await expect(waiting).rejects.toThrow(/physical mux send failed/)
    await expect(failing).rejects.toThrow(/socket dead/)
    expect(fatal).toHaveLength(1)
    expect(fatal[0]!.message).toBe('physical mux send failed')
    expect(calls).toBe(7)
    await expect(blocked.write(Buffer.from('after-failure'))).rejects.toThrow(/physical mux send failed/)
    await expect(server.drain()).rejects.toThrow(/physical mux send failed/)
    server.receive(frame({ type: 'WINDOW', streamId: 0, credit: 512 }))
    server.shutdown()
    expect(calls).toBe(7)
  })

  it('requires both connection and stream credit', async () => {
    const output = sink()
    const server = makeConnection({ role: 'server' }, output)
    const streams: MuxStream[] = []
    for (let index = 0; index < MUX_MAX_STREAMS; index++) {
      streams.push(server.open(openRequest()))
    }
    await server.drain()
    for (const stream of streams) {
      server.receive(frame({ type: 'READY', streamId: stream.id }))
    }
    const chunk = Buffer.alloc(65_536)
    await Promise.all(streams.map((stream) => stream.write(chunk)))
    expect(dataFrames(output.frames())).toHaveLength(MUX_MAX_STREAMS)
    const extra = streams[0]!.write(Buffer.from('connection-wait'))
    await tick()
    expect(dataFrames(output.frames())).toHaveLength(MUX_MAX_STREAMS)
    server.receive(frame({ type: 'WINDOW', streamId: 0, credit: 512 }))
    await extra
    expect(dataFrames(output.frames())).toHaveLength(MUX_MAX_STREAMS + 1)
    expect(MUX_STREAM_CREDIT).toBe(262_144)
    expect(MUX_CONNECTION_CREDIT).toBe(4 * 1024 * 1024)
  })

  it('rejects WINDOW that overstates sent credit and WINDOW for a never allocated stream', async () => {
    const output = sink()
    let fatal: MuxError | null = null
    const server = makeConnection({ role: 'server', onFatal: (error) => (fatal = error) }, output)
    server.open(openRequest())
    await server.drain()
    server.receive(frame({ type: 'WINDOW', streamId: 0, credit: 512 }))
    expect(fatal?.message).toMatch(/connection credit outstanding/)

    const secondOutput = sink()
    let secondFatal: MuxError | null = null
    const second = makeConnection({ role: 'server', onFatal: (error) => (secondFatal = error) }, secondOutput)
    second.open(openRequest())
    await second.drain()
    second.receive(frame({ type: 'WINDOW', streamId: 99, credit: 512 }))
    expect(secondFatal?.message).toMatch(/unallocated stream/)
  })

  it('enforces the concurrent stream limit', async () => {
    const output = sink()
    const server = makeConnection({ role: 'server' }, output)
    for (let index = 0; index < MUX_MAX_STREAMS; index++) {
      server.open(openRequest())
    }
    expect(() => server.open(openRequest())).toThrow(/concurrent stream limit/)
  })
})

describe('HTTP request/response lifecycle', () => {
  it('half-closes each direction, consumes downstream data, and finishes with COMPLETE', async () => {
    const serverOut = sink()
    const daemonOut = sink()
    const open = captureOnOpen()
    const events: string[] = []
    let serverStream: MuxStream | null = null
    let daemonStream: MuxStream | null = null
    let requestBody = ''
    let responseBody = ''
    let serverEnded = false
    let daemonComplete = false
    const server = makeConnection(
      {
        role: 'server',
        onReady: (stream) => {
          events.push('server:ready')
          serverStream = stream
        },
        onHeaders: (_stream, headers) => {
          events.push(`server:headers:${headers.status}`)
        },
        onData: (_stream, data) => {
          responseBody += Buffer.from(data.payload).toString()
        },
        onEnd: () => {
          events.push('server:response-end')
          serverEnded = true
        },
      },
      serverOut,
    )
    const daemon = makeConnection(
      {
        role: 'daemon',
        onOpen: (stream) => {
          events.push('daemon:open')
          daemonStream = stream
        },
        onData: (_stream, data) => {
          requestBody += Buffer.from(data.payload).toString()
        },
        onEnd: () => {
          events.push('daemon:request-end')
        },
        onComplete: () => {
          events.push('daemon:complete')
          daemonComplete = true
        },
      },
      daemonOut,
    )

    async function exchange(): Promise<void> {
      for (let round = 0; round < 40; round++) {
        await server.drain()
        await daemon.drain()
        await tick()
        let moved = false
        while (serverOut.raw.length > 0) {
          daemon.receive(serverOut.raw.shift()!)
          moved = true
        }
        while (daemonOut.raw.length > 0) {
          server.receive(daemonOut.raw.shift()!)
          moved = true
        }
        await tick()
        await server.drain()
        await daemon.drain()
        await tick()
        if (!moved && serverOut.raw.length === 0 && daemonOut.raw.length === 0) {
          return
        }
      }
      throw new Error('exchange did not settle')
    }

    const stream = server.open(openRequest())
    await exchange()
    expect(daemonStream).not.toBeNull()
    await acceptAndHeaders(daemonStream!)
    await exchange()
    expect(serverStream).toBe(stream)
    expect(events).toContain('server:ready')
    expect(events).toContain('server:headers:200')

    await stream.write(Buffer.from('request-body'))
    await exchange()
    expect(requestBody).toBe('request-body')
    await stream.end()
    await exchange()
    expect(events).toContain('daemon:request-end')
    expect(serverEnded).toBe(false)

    await daemonStream!.write(Buffer.from('response-body'))
    await daemonStream!.end()
    await exchange()
    expect(responseBody).toBe('response-body')
    expect(serverEnded).toBe(true)
    expect(daemonComplete).toBe(false)

    await stream.complete()
    await exchange()
    expect(daemonComplete).toBe(true)
    expect(events).toContain('daemon:complete')
    expect(events.indexOf('server:response-end')).toBeLessThan(events.indexOf('daemon:complete'))
  })

  it('keeps DATA before END when downstream consumption is slow', async () => {
    const output = sink()
    const open = captureOnOpen()
    const gate = deferred()
    const received: string[] = []
    let endCalled = false
    const daemon = makeConnection(
      {
        role: 'daemon',
        ...open,
        onData: async (_stream, data) => {
          received.push(Buffer.from(data.payload).toString())
          await gate.promise
        },
        onEnd: () => {
          endCalled = true
        },
      },
      output,
    )
    await receiveOpen(daemon, 1)
    const stream = open.streams[0]!
    await acceptAndHeaders(stream)
    daemon.receive(frame({ type: 'DATA', streamId: 1, payload: Buffer.from('a'), flags: { kind: 'http' } }))
    daemon.receive(frame({ type: 'END', streamId: 1 }))
    await tick()
    expect(received).toEqual(['a'])
    expect(endCalled).toBe(false)
    gate.resolve()
    await waitFor(() => endCalled)
    expect(endCalled).toBe(true)
  })
})

describe('physical delivery before send completion', () => {
  it('accepts WINDOW delivered before DATA send callbacks settle in both directions', async () => {
    let server!: MuxConnection
    let daemon!: MuxConnection
    const open = captureOnOpen()
    const toServer: MuxFrame[] = []
    const toDaemon: MuxFrame[] = []
    const serverFatal: MuxError[] = []
    const daemonFatal: MuxError[] = []
    let holdServerData = false
    let holdDaemonData = false
    let releaseServerData: (() => void) | null = null
    let releaseDaemonData: (() => void) | null = null
    let serverDataSettled = false
    let daemonDataSettled = false

    server = createMuxConnection({
      role: 'server',
      send(raw) {
        const outbound = decodeFrame(raw)
        toDaemon.push(outbound)
        daemon.receive(raw)
        if (holdServerData && outbound.type === 'DATA') {
          return new Promise<void>((resolve) => {
            releaseServerData = resolve
          })
        }
        return Promise.resolve()
      },
      onFatal: (error) => serverFatal.push(error),
    })
    daemon = createMuxConnection({
      role: 'daemon',
      ...open,
      send(raw) {
        const outbound = decodeFrame(raw)
        toServer.push(outbound)
        server.receive(raw)
        if (holdDaemonData && outbound.type === 'DATA') {
          return new Promise<void>((resolve) => {
            releaseDaemonData = resolve
          })
        }
        return Promise.resolve()
      },
      onFatal: (error) => daemonFatal.push(error),
    })

    const stream = server.open(openRequest())
    await tick()
    const remote = open.streams[0]!
    await remote.accept()
    await remote.sendHeaders({ status: 200, headers: [] })
    await tick()

    holdServerData = true
    const upstream = stream.write(Buffer.from('up'))
    void upstream.then(() => (serverDataSettled = true))
    await tick()
    expect(serverFatal).toEqual([])
    expect(daemonFatal).toEqual([])
    expect(serverDataSettled).toBe(false)
    expect(toServer.some((outbound) => outbound.type === 'WINDOW' && outbound.streamId === 1)).toBe(true)
    expect(releaseServerData).not.toBeNull()
    releaseServerData!()
    await upstream

    holdDaemonData = true
    const downstream = remote.write(Buffer.from('down'))
    void downstream.then(() => (daemonDataSettled = true))
    await tick()
    expect(serverFatal).toEqual([])
    expect(daemonFatal).toEqual([])
    expect(daemonDataSettled).toBe(false)
    expect(toDaemon.some((outbound) => outbound.type === 'WINDOW' && outbound.streamId === 1)).toBe(true)
    expect(releaseDaemonData).not.toBeNull()
    releaseDaemonData!()
    await downstream
  })

  it('accepts COMPLETE before the END send callback and finishes only after both sides settle', async () => {
    let server!: MuxConnection
    let daemon!: MuxConnection
    const open = captureOnOpen()
    const toDaemon: MuxFrame[] = []
    const errors: MuxError[] = []
    let holdDaemonEnd = false
    let releaseDaemonEnd: (() => void) | null = null
    let responseEnded = false
    let requestEnded = false
    let completed = 0

    server = createMuxConnection({
      role: 'server',
      send(raw) {
        toDaemon.push(decodeFrame(raw))
        daemon.receive(raw)
        return Promise.resolve()
      },
      onEnd: (stream) => {
        responseEnded = true
        void stream.complete().catch((error: MuxError) => errors.push(error))
      },
      onFatal: (error) => errors.push(error),
    })
    daemon = createMuxConnection({
      role: 'daemon',
      ...open,
      send(raw) {
        const outbound = decodeFrame(raw)
        server.receive(raw)
        if (holdDaemonEnd && outbound.type === 'END') {
          return new Promise<void>((resolve) => {
            releaseDaemonEnd = resolve
          })
        }
        return Promise.resolve()
      },
      onEnd: () => {
        requestEnded = true
      },
      onComplete: () => {
        completed++
      },
      onFatal: (error) => errors.push(error),
    })

    const stream = server.open(openRequest())
    await tick()
    const remote = open.streams[0]!
    await remote.accept()
    await remote.sendHeaders({ status: 200, headers: [] })
    await stream.end()
    await tick()
    expect(requestEnded).toBe(true)

    holdDaemonEnd = true
    const responseEnd = remote.end()
    let endSettled = false
    void responseEnd.then(() => (endSettled = true))
    await tick()
    expect(responseEnded).toBe(true)
    expect(completed).toBe(0)
    expect(endSettled).toBe(false)
    expect(toDaemon.some((outbound) => outbound.type === 'COMPLETE')).toBe(true)
    expect(errors).toEqual([])
    expect(releaseDaemonEnd).not.toBeNull()
    releaseDaemonEnd!()
    await responseEnd
    await tick()
    expect(completed).toBe(1)
    expect(errors).toEqual([])
  })
})

describe('inbound credit and downstream acknowledgement', () => {
  it('returns exact stream and connection credit only after each downstream promise settles', async () => {
    const output = sink()
    const open = captureOnOpen()
    const gates = [deferred(), deferred()]
    let delivered = 0
    const daemon = makeConnection(
      {
        role: 'daemon',
        ...open,
        onData: async () => {
          const gate = gates[delivered++]
          if (gate !== undefined) {
            await gate.promise
          }
        },
      },
      output,
    )
    await receiveOpen(daemon, 1)
    const stream = open.streams[0]!
    await acceptAndHeaders(stream)
    const chunk = Buffer.alloc(MUX_ACK_THRESHOLD)
    daemon.receive(frame({ type: 'DATA', streamId: 1, payload: chunk, flags: { kind: 'http' } }))
    daemon.receive(frame({ type: 'DATA', streamId: 1, payload: chunk, flags: { kind: 'http' } }))
    await tick()
    expect(windows(output.frames())).toEqual([])
    expect(delivered).toBe(1)

    gates[0]!.resolve()
    await tick()
    expect(windows(output.frames())).toEqual([
      { streamId: 1, credit: MUX_ACK_THRESHOLD },
      { streamId: 0, credit: MUX_ACK_THRESHOLD },
    ])
    expect(delivered).toBe(2)

    gates[1]!.resolve()
    await tick()
    expect(windows(output.frames())).toEqual([
      { streamId: 1, credit: MUX_ACK_THRESHOLD },
      { streamId: 0, credit: MUX_ACK_THRESHOLD },
      { streamId: 1, credit: MUX_ACK_THRESHOLD },
      { streamId: 0, credit: MUX_ACK_THRESHOLD },
    ])
    // Small frames also cost at least 512 and gain their own WINDOW when the stream drains.
    daemon.receive(frame({ type: 'DATA', streamId: 1, payload: Buffer.from('x'), flags: { kind: 'http' } }))
    await tick()
    expect(windows(output.frames()).slice(-2)).toEqual([
      { streamId: 1, credit: 512 },
      { streamId: 0, credit: 512 },
    ])
  })

  it('fails the connection when one stream exceeds its receive credit', async () => {
    const output = sink()
    const open = captureOnOpen()
    let fatal: MuxError | null = null
    const daemon = makeConnection({ role: 'daemon', ...open, onFatal: (error) => (fatal = error) }, output)
    await receiveOpen(daemon, 1)
    await acceptAndHeaders(open.streams[0]!)
    const chunk = Buffer.alloc(65_536)
    for (let index = 0; index < 4; index++) {
      daemon.receive(frame({ type: 'DATA', streamId: 1, payload: chunk, flags: { kind: 'http' } }))
    }
    daemon.receive(frame({ type: 'DATA', streamId: 1, payload: Buffer.from('over'), flags: { kind: 'http' } }))
    expect(fatal?.message).toMatch(/stream receive credit exceeded/)
  })

  it('fails the connection when aggregate receive credit is exhausted even if every stream fits', async () => {
    const output = sink()
    const open = captureOnOpen()
    let fatal: MuxError | null = null
    const daemon = makeConnection({ role: 'daemon', ...open, onFatal: (error) => (fatal = error) }, output)
    for (let streamId = 1; streamId <= MUX_MAX_STREAMS; streamId++) {
      daemon.receive(frame({ type: 'OPEN', streamId, request: openRequest() }))
    }
    for (const stream of open.streams) {
      void stream.accept().catch(() => {})
      void stream.sendHeaders({ status: 200, headers: [] }).catch(() => {})
    }
    await tick()
    const chunk = Buffer.alloc(65_536)
    for (const stream of open.streams) {
      daemon.receive(frame({ type: 'DATA', streamId: stream.id, payload: chunk, flags: { kind: 'http' } }))
    }
    daemon.receive(frame({ type: 'DATA', streamId: 1, payload: chunk, flags: { kind: 'http' } }))
    expect(fatal?.message).toMatch(/connection receive credit exceeded/)
  })

  it('reclaims credit on RESET, discards closed-stream DATA, and allocates the next stream without tombstones', async () => {
    const output = sink()
    const open = captureOnOpen()
    const gate = deferred()
    const resets: { id: number; reset: ResetPayload }[] = []
    let fatal: MuxError | null = null
    const daemon = makeConnection(
      {
        role: 'daemon',
        ...open,
        onData: async () => gate.promise,
        onReset: (streamId, reset) => {
          resets.push({ id: streamId, reset })
        },
        onFatal: (error) => (fatal = error),
      },
      output,
    )
    await receiveOpen(daemon, 1)
    const first = open.streams[0]!
    await acceptAndHeaders(first)
    daemon.receive(frame({ type: 'DATA', streamId: 1, payload: Buffer.alloc(1000, 1), flags: { kind: 'http' } }))
    daemon.receive(frame({ type: 'DATA', streamId: 1, payload: Buffer.alloc(1000, 1), flags: { kind: 'http' } }))
    await tick()
    daemon.receive(frame({ type: 'RESET', streamId: 1, reset: { code: 'UPSTREAM_GONE', message: 'upstream closed' } }))
    await tick()
    expect(resets).toEqual([{ id: 1, reset: { code: 'UPSTREAM_GONE', message: 'upstream closed' } }])
    expect(fatal).toBeNull()
    // Only queued data is released by RESET; the in-flight callback keeps its 1000-byte reservation.
    expect(windows(output.frames())).toEqual([{ streamId: 0, credit: 1000 }])
    gate.resolve()
    await tick()
    expect(windows(output.frames())).toEqual([
      { streamId: 0, credit: 1000 },
      { streamId: 0, credit: 1000 },
    ])

    // A late DATA frame for the closed id costs connection credit, is discarded, and is returned.
    daemon.receive(frame({ type: 'DATA', streamId: 1, payload: Buffer.alloc(600, 1), flags: { kind: 'http' } }))
    await tick()
    expect(windows(output.frames())).toEqual([
      { streamId: 0, credit: 1000 },
      { streamId: 0, credit: 1000 },
      { streamId: 0, credit: 600 },
    ])
    expect(fatal).toBeNull()

    // Late END/WINDOW/duplicate RESET/CLOSE do not revive or fail the closed stream.
    daemon.receive(frame({ type: 'END', streamId: 1 }))
    daemon.receive(frame({ type: 'WINDOW', streamId: 1, credit: 512 }))
    daemon.receive(frame({ type: 'RESET', streamId: 1, reset: { code: 'AGAIN', message: '' } }))
    daemon.receive(frame({ type: 'COMPLETE', streamId: 1 }))
    daemon.receive(frame({ type: 'CLOSE', streamId: 1, close: { code: 1000, reason: '' } }))
    expect(fatal).toBeNull()

    await receiveOpen(daemon, 2)
    expect(open.streams).toHaveLength(2)
    expect(open.streams[1]!.id).toBe(2)
    await acceptAndHeaders(open.streams[1]!)
    await daemon.drain()
    expect(fatal).toBeNull()
  })

  it('releases an in-flight buffer once when its callback rejects after RESET', async () => {
    const output = sink()
    const open = captureOnOpen()
    const gate = deferred()
    let fatal: MuxError | null = null
    const daemon = makeConnection(
      {
        role: 'daemon',
        ...open,
        onData: () => gate.promise,
        onFatal: (error) => (fatal = error),
      },
      output,
    )
    await receiveOpen(daemon, 1)
    await acceptAndHeaders(open.streams[0]!)
    daemon.receive(frame({ type: 'DATA', streamId: 1, payload: Buffer.alloc(1000), flags: { kind: 'http' } }))
    daemon.receive(frame({ type: 'RESET', streamId: 1, reset: { code: 'CANCEL', message: '' } }))
    await tick()
    expect(windows(output.frames())).toEqual([])
    gate.reject(new Error('consumer aborted'))
    await tick()
    expect(windows(output.frames())).toEqual([{ streamId: 0, credit: 1000 }])

    daemon.receive(frame({ type: 'DATA', streamId: 1, payload: Buffer.alloc(600, 1), flags: { kind: 'http' } }))
    await tick()
    expect(windows(output.frames())).toEqual([
      { streamId: 0, credit: 1000 },
      { streamId: 0, credit: 600 },
    ])
    expect(fatal).toBeNull()
  })

  it('keeps an in-flight downstream buffer charged across RESET and new streams', async () => {
    const output = sink()
    const open = captureOnOpen()
    const gate = deferred()
    let fatal: MuxError | null = null
    const daemon = makeConnection(
      {
        role: 'daemon',
        ...open,
        onData: () => gate.promise,
        onFatal: (error) => (fatal = error),
      },
      output,
    )
    await receiveOpen(daemon, 1)
    const first = open.streams[0]!
    await acceptAndHeaders(first)
    daemon.receive(frame({ type: 'DATA', streamId: 1, payload: Buffer.alloc(100), flags: { kind: 'http' } }))
    daemon.receive(frame({ type: 'RESET', streamId: 1, reset: { code: 'CANCEL', message: 'reset while pending' } }))
    await tick()
    expect(fatal).toBeNull()

    const chunk = Buffer.alloc(65_536)
    for (let streamId = 2; streamId <= 16; streamId++) {
      await receiveOpen(daemon, streamId)
      const stream = open.streams.at(-1)!
      await acceptAndHeaders(stream)
      for (let index = 0; index < 4; index++) {
        daemon.receive(frame({ type: 'DATA', streamId, payload: chunk, flags: { kind: 'http' } }))
      }
    }
    await receiveOpen(daemon, 17)
    const last = open.streams.at(-1)!
    await acceptAndHeaders(last)
    for (let index = 0; index < 3; index++) {
      daemon.receive(frame({ type: 'DATA', streamId: 17, payload: chunk, flags: { kind: 'http' } }))
    }
    daemon.receive(frame({ type: 'DATA', streamId: 17, payload: Buffer.alloc(65_024), flags: { kind: 'http' } }))
    expect(fatal).toBeNull()
    daemon.receive(frame({ type: 'DATA', streamId: 17, payload: Buffer.alloc(1), flags: { kind: 'http' } }))
    expect(fatal?.message).toMatch(/connection receive credit exceeded/)
  })

  it('returns connection credit for abandoned and closed-stream buffers but no stream WINDOW', async () => {
    const output = sink()
    const open = captureOnOpen()
    let fatal: MuxError | null = null
    const daemon = makeConnection({ role: 'daemon', ...open, onFatal: (error) => (fatal = error) }, output)
    await receiveOpen(daemon, 1)
    await acceptAndHeaders(open.streams[0]!)
    daemon.receive(frame({ type: 'DATA', streamId: 1, payload: Buffer.alloc(700), flags: { kind: 'http' } }))
    daemon.receive(frame({ type: 'RESET', streamId: 1, reset: { code: 'CANCEL', message: '' } }))
    await tick()
    expect(fatal).toBeNull()
    expect(windows(output.frames())).toEqual([{ streamId: 0, credit: 700 }])
  })
})

describe('WS fragments and CLOSE', () => {
  it('sends fragment flags in order and rejects inconsistent or invalid WS writes', async () => {
    const output = sink()
    const server = makeConnection({ role: 'server' }, output)
    const stream = server.open(openRequest('WS'))
    await server.drain()
    server.receive(frame({ type: 'READY', streamId: stream.id }))
    await expect(stream.write(Buffer.from('early'), { text: true, fin: true })).rejects.toThrow(/handshake/)
    server.receive(frame({ type: 'HEADERS', streamId: stream.id, headers: { status: 101, headers: [] } }))
    await stream.write(Buffer.from('a'), { text: true, fin: false })
    await stream.write(Buffer.from('b'), { text: true, fin: true })
    await stream.write(Buffer.from('c'), { text: false, fin: true })
    await expect(stream.write(Buffer.from('missing'))).rejects.toThrow(/text and fin/)
    await expect(stream.write(Buffer.from('x'), { text: 'yes' as never, fin: true })).rejects.toThrow(/text and fin/)
    await stream.write(Buffer.from('d'), { text: true, fin: false })
    await expect(stream.write(Buffer.from('e'), { text: false, fin: true })).rejects.toThrow(/fragment type/)
    await expect(stream.end()).rejects.toThrow(/HTTP/)
    await stream.close(1000, 'done')
    await server.drain()
    expect(dataFrames(output.frames()).map((data) => [data.payload.toString(), data.flags])).toEqual([
      ['a', { kind: 'ws', text: true, fin: false }],
      ['b', { kind: 'ws', text: true, fin: true }],
      ['c', { kind: 'ws', text: false, fin: true }],
      ['d', { kind: 'ws', text: true, fin: false }],
    ])
    const close = output.frames().at(-1)
    expect(close).toEqual({ type: 'CLOSE', streamId: 1, close: { code: 1000, reason: 'done' } })
  })

  it('does not let a later write overtake a credit waiter after WINDOW', async () => {
    const output = sink()
    const server = makeConnection({ role: 'server' }, output)
    const stream = server.open(openRequest('WS'))
    await server.drain()
    server.receive(frame({ type: 'READY', streamId: stream.id }))
    server.receive(frame({ type: 'HEADERS', streamId: stream.id, headers: { status: 101, headers: [] } }))
    const chunk = Buffer.alloc(65_536)
    for (let index = 0; index < 4; index++) {
      await stream.write(chunk, { text: false, fin: true })
    }
    const waiting = stream.write(Buffer.from('first'), { text: true, fin: false })
    await tick()
    server.receive(frame({ type: 'WINDOW', streamId: stream.id, credit: 512 }))
    const overlapping = stream.write(Buffer.from('second'), { text: false, fin: false })
    await expect(overlapping).rejects.toThrow(/pending credit wait/)
    await waiting
    await server.drain()
    expect(dataFrames(output.frames()).map((data) => data.payload.toString())).toEqual([
      chunk.toString(),
      chunk.toString(),
      chunk.toString(),
      chunk.toString(),
      'first',
    ])
  })

  it('rejects a credit waiter and emits no DATA when WS CLOSE follows WINDOW', async () => {
    const output = sink()
    const server = makeConnection({ role: 'server' }, output)
    const stream = server.open(openRequest('WS'))
    await server.drain()
    server.receive(frame({ type: 'READY', streamId: stream.id }))
    server.receive(frame({ type: 'HEADERS', streamId: stream.id, headers: { status: 101, headers: [] } }))
    const chunk = Buffer.alloc(65_536)
    for (let index = 0; index < 4; index++) {
      await stream.write(chunk, { text: false, fin: true })
    }
    const waiting = stream.write(Buffer.from('after-window'), { text: true, fin: true })
    await tick()
    server.receive(frame({ type: 'WINDOW', streamId: stream.id, credit: 512 }))
    server.receive(frame({ type: 'CLOSE', streamId: stream.id, close: { code: 1000, reason: 'done' } }))
    await expect(waiting).rejects.toThrow(/closed by the peer/)
    await stream.close(1000, 'ack')
    await server.drain()
    expect(dataFrames(output.frames()).map((data) => data.payload.toString())).not.toContain('after-window')
    expect(dataFrames(output.frames())).toHaveLength(4)
  })

  it('delivers inbound fragments and CLOSE in order and completes the close handshake', async () => {
    const output = sink()
    const open = captureOnOpen()
    const gates = [deferred(), deferred()]
    const received: { payload: string; flags: unknown }[] = []
    let closeEvent: ClosePayload | null = null
    let closedAfterData = false
    let fatal: MuxError | null = null
    let streamRef: MuxStream | null = null
    const daemon = makeConnection(
      {
        role: 'daemon',
        ...open,
        onData: async (_stream, data) => {
          received.push({ payload: Buffer.from(data.payload).toString(), flags: data.flags })
          await gates[received.length - 1]!.promise
        },
        onClose: (stream, close) => {
          closeEvent = close
          closedAfterData = received.length === 2
          streamRef = stream
          void stream.close(1000, 'ack')
        },
        onFatal: (error) => {
          fatal = error
        },
      },
      output,
    )
    await receiveOpen(daemon, 1, 'WS')
    const stream = open.streams[0]!
    await stream.accept()
    await stream.sendHeaders({ status: 101, headers: [] })
    daemon.receive(frame({ type: 'DATA', streamId: 1, payload: Buffer.from('a'), flags: { kind: 'ws', text: true, fin: false } }))
    daemon.receive(frame({ type: 'DATA', streamId: 1, payload: Buffer.from('b'), flags: { kind: 'ws', text: true, fin: true } }))
    daemon.receive(frame({ type: 'CLOSE', streamId: 1, close: { code: 1001, reason: 'going away' } }))
    await tick()
    expect(closeEvent).toBeNull()
    gates[0]!.resolve()
    await tick()
    expect(received).toHaveLength(2)
    expect(closeEvent).toBeNull()
    gates[1]!.resolve()
    await waitFor(() => closeEvent !== null)
    expect(closedAfterData).toBe(true)
    expect(closeEvent).toEqual({ code: 1001, reason: 'going away' })
    expect(streamRef).toBe(stream)
    await daemon.drain()
    expect(fatal).toBeNull()
    expect(dataFrames(output.frames())).toHaveLength(0)
    expect(output.frames().some((value) => value.type === 'CLOSE' && value.close.code === 1000)).toBe(true)
  })

  it('rejects fragment type changes before FIN and DATA after CLOSE', async () => {
    const output = sink()
    const open = captureOnOpen()
    let fatal: MuxError | null = null
    const daemon = makeConnection(
      { role: 'daemon', ...open, onClose: () => {}, onFatal: (error) => (fatal = error) },
      output,
    )
    await receiveOpen(daemon, 1, 'WS')
    const stream = open.streams[0]!
    await stream.accept()
    await stream.sendHeaders({ status: 101, headers: [] })
    daemon.receive(frame({ type: 'DATA', streamId: 1, payload: Buffer.from('a'), flags: { kind: 'ws', text: true, fin: false } }))
    daemon.receive(frame({ type: 'DATA', streamId: 1, payload: Buffer.from('b'), flags: { kind: 'ws', text: false, fin: true } }))
    expect(fatal?.message).toMatch(/fragment type/)

    const secondOutput = sink()
    const secondOpen = captureOnOpen()
    let secondFatal: MuxError | null = null
    const second = makeConnection(
      { role: 'daemon', ...secondOpen, onClose: () => {}, onFatal: (error) => (secondFatal = error) },
      secondOutput,
    )
    await receiveOpen(second, 1, 'WS')
    const secondStream = secondOpen.streams[0]!
    await secondStream.accept()
    await secondStream.sendHeaders({ status: 101, headers: [] })
    second.receive(frame({ type: 'CLOSE', streamId: 1, close: { code: 1000, reason: '' } }))
    await tick()
    second.receive(frame({ type: 'DATA', streamId: 1, payload: Buffer.from('late'), flags: { kind: 'ws', text: true, fin: true } }))
    expect(secondFatal?.message).toMatch(/after WS CLOSE/)
  })
})

describe('protocol state validation', () => {
  it('rejects invalid roles and stream method ordering', async () => {
    expect(() => createMuxConnection({ role: 'peer' as never, send: () => {} })).toThrow(/role/)
    expect(() => createMuxConnection({ role: 'server', send: null as never })).toThrow(/send callback/)
    const output = sink()
    const server = makeConnection({ role: 'server' }, output)
    const stream = server.open(openRequest())
    await expect(stream.accept()).rejects.toThrow(/daemon/)
    await expect(stream.sendHeaders({ status: 200, headers: [] })).rejects.toThrow(/daemon/)
    await expect(stream.end()).rejects.toThrow(/not ready/)
    await expect(stream.write(Buffer.from('x'))).rejects.toThrow(/not ready/)
    await expect(stream.complete()).rejects.toThrow(/ended/)
    server.receive(frame({ type: 'READY', streamId: stream.id }))
    await expect(stream.end()).resolves.toBeUndefined()
    await expect(stream.write(Buffer.from('after-end'))).rejects.toThrow(/closed/)
    await expect(stream.complete()).rejects.toThrow(/consumed/)
    await expect(stream.close(1000)).rejects.toThrow(/WS/)
    const daemonOutput = sink()
    expect(() =>
      makeConnection({ role: 'daemon', onOpen: () => {} }, daemonOutput).open(openRequest()),
    ).toThrow(/server/)
  })

  it('ignores READY and HEADERS that race with a local reset', async () => {
    const output = sink()
    let fatal: MuxError | null = null
    const server = makeConnection({ role: 'server', onFatal: (error) => (fatal = error) }, output)
    const first = server.open(openRequest())
    await first.reset('CANCELLED', 'client cancelled')
    server.receive(frame({ type: 'READY', streamId: first.id }))
    server.receive(frame({ type: 'HEADERS', streamId: first.id, headers: { status: 200, headers: [] } }))
    expect(fatal).toBeNull()

    const second = server.open(openRequest())
    await server.drain()
    expect(second.id).toBe(first.id + 1)
    expect(output.frames().some((value) => value.type === 'OPEN' && value.streamId === second.id)).toBe(true)
  })

  it('rejects duplicate and out-of-order frames and invalid inbound frames', async () => {
    const output = sink()
    let fatal: MuxError | null = null
    const server = makeConnection({ role: 'server', onFatal: (error) => (fatal = error) }, output)
    const stream = server.open(openRequest())
    await server.drain()
    server.receive(frame({ type: 'READY', streamId: stream.id }))
    server.receive(frame({ type: 'READY', streamId: stream.id }))
    expect(fatal?.message).toMatch(/duplicate READY/)

    const headersOutput = sink()
    let headersFatal: MuxError | null = null
    const headersServer = makeConnection({ role: 'server', onFatal: (error) => (headersFatal = error) }, headersOutput)
    const headersStream = headersServer.open(openRequest())
    await headersServer.drain()
    headersServer.receive(frame({ type: 'HEADERS', streamId: headersStream.id, headers: { status: 200, headers: [] } }))
    expect(headersFatal?.message).toMatch(/before READY/)

    const dataOutput = sink()
    let dataFatal: MuxError | null = null
    const dataServer = makeConnection({ role: 'server', onFatal: (error) => (dataFatal = error) }, dataOutput)
    const dataStream = dataServer.open(openRequest())
    await dataServer.drain()
    dataServer.receive(frame({ type: 'READY', streamId: dataStream.id }))
    dataServer.receive(frame({ type: 'DATA', streamId: dataStream.id, payload: Buffer.from('x'), flags: { kind: 'http' } }))
    expect(dataFatal?.message).toMatch(/before HEADERS/)

    const duplicateEndOutput = sink()
    let duplicateEndFatal: MuxError | null = null
    let endCount = 0
    const duplicateEndServer = makeConnection(
      { role: 'server', onEnd: () => { endCount++ }, onFatal: (error) => (duplicateEndFatal = error) },
      duplicateEndOutput,
    )
    const duplicateEndStream = duplicateEndServer.open(openRequest())
    await duplicateEndServer.drain()
    duplicateEndServer.receive(frame({ type: 'READY', streamId: duplicateEndStream.id }))
    duplicateEndServer.receive(frame({ type: 'HEADERS', streamId: duplicateEndStream.id, headers: { status: 200, headers: [] } }))
    duplicateEndServer.receive(frame({ type: 'END', streamId: duplicateEndStream.id }))
    duplicateEndServer.receive(frame({ type: 'END', streamId: duplicateEndStream.id }))
    expect(endCount).toBe(1)
    expect(duplicateEndFatal?.message).toMatch(/duplicate END/)

    const invalidOutput = sink()
    let invalidFatal: MuxError | null = null
    const invalid = makeConnection({ role: 'server', onFatal: (error) => (invalidFatal = error) }, invalidOutput)
    invalid.receive(Buffer.from([1, 2, 3]))
    expect(invalidFatal?.message).toMatch(/invalid mux frame/)
  })

  it('rejects invalid OPEN sequencing and unallocated DATA', async () => {
    const output = sink()
    const open = captureOnOpen()
    let fatal: MuxError | null = null
    const daemon = makeConnection({ role: 'daemon', ...open, onFatal: (error) => (fatal = error) }, output)
    await receiveOpen(daemon, 2)
    expect(fatal?.message).toMatch(/strictly consecutive/)

    const secondOutput = sink()
    const secondOpen = captureOnOpen()
    let secondFatal: MuxError | null = null
    const second = makeConnection({ role: 'daemon', ...secondOpen, onFatal: (error) => (secondFatal = error) }, secondOutput)
    second.receive(frame({ type: 'DATA', streamId: 1, payload: Buffer.from('x'), flags: { kind: 'http' } }))
    expect(secondFatal?.message).toMatch(/unallocated/)

    const thirdOutput = sink()
    const thirdOpen = captureOnOpen()
    let thirdFatal: MuxError | null = null
    const third = makeConnection({ role: 'daemon', ...thirdOpen, onFatal: (error) => (thirdFatal = error) }, thirdOutput)
    await receiveOpen(third, 1)
    third.receive(frame({ type: 'OPEN', streamId: 1, request: openRequest() }))
    expect(thirdFatal?.message).toMatch(/strictly consecutive/)
  })

  it('rejects daemon DATA and END before READY, and COMPLETE on the server', async () => {
    const output = sink()
    const open = captureOnOpen()
    let fatal: MuxError | null = null
    const daemon = makeConnection({ role: 'daemon', ...open, onFatal: (error) => (fatal = error) }, output)
    await receiveOpen(daemon, 1)
    daemon.receive(frame({ type: 'DATA', streamId: 1, payload: Buffer.from('x'), flags: { kind: 'http' } }))
    expect(fatal?.message).toMatch(/before READY/)

    const endOutput = sink()
    const endOpen = captureOnOpen()
    let endFatal: MuxError | null = null
    const endDaemon = makeConnection({ role: 'daemon', ...endOpen, onFatal: (error) => (endFatal = error) }, endOutput)
    await receiveOpen(endDaemon, 1)
    endDaemon.receive(frame({ type: 'END', streamId: 1 }))
    expect(endFatal?.message).toMatch(/before READY/)

    const serverOutput = sink()
    let serverFatal: MuxError | null = null
    const server = makeConnection({ role: 'server', onFatal: (error) => (serverFatal = error) }, serverOutput)
    const stream = server.open(openRequest())
    await server.drain()
    server.receive(frame({ type: 'COMPLETE', streamId: stream.id }))
    expect(serverFatal?.message).toMatch(/unallocated|only valid/)
  })

  it('requires daemon WS HEADERS 101 before accepting request DATA', async () => {
    const output = sink()
    const open = captureOnOpen()
    let fatal: MuxError | null = null
    const daemon = makeConnection({ role: 'daemon', ...open, onFatal: (error) => (fatal = error) }, output)
    await receiveOpen(daemon, 1, 'WS')
    await open.streams[0]!.accept()
    daemon.receive(frame({ type: 'DATA', streamId: 1, payload: Buffer.from('early'), flags: { kind: 'ws', text: true, fin: true } }))
    expect(fatal?.message).toMatch(/HEADERS 101/)

    // Queued HEADERS are not sent yet; peer request DATA must still be rejected.
    const queuedOpen = captureOnOpen()
    let releaseReady: (() => void) | null = null
    let queuedFatal: MuxError | null = null
    const queued = createMuxConnection({
      role: 'daemon',
      ...queuedOpen,
      send(raw) {
        if (decodeFrame(raw).type === 'READY') {
          return new Promise<void>((resolve) => {
            releaseReady = resolve
          })
        }
        return Promise.resolve()
      },
      onFatal: (error) => (queuedFatal = error),
    })
    await receiveOpen(queued, 1, 'WS')
    const queuedStream = queuedOpen.streams[0]!
    const accepting = queuedStream.accept().catch(() => {})
    await tick()
    const sendingHeaders = queuedStream.sendHeaders({ status: 101, headers: [] }).catch(() => {})
    queued.receive(frame({ type: 'DATA', streamId: 1, payload: Buffer.from('queued'), flags: { kind: 'ws', text: true, fin: true } }))
    expect(queuedFatal?.message).toMatch(/HEADERS 101/)
    expect(releaseReady).not.toBeNull()
    releaseReady!()
    await Promise.all([accepting, sendingHeaders])

    // HEADERS dispatched but its send callback is still pending: peer may already answer with DATA.
    const okOpen = captureOnOpen()
    let okFatal: MuxError | null = null
    let releaseHeaders: (() => void) | null = null
    let received = ''
    const ok = createMuxConnection({
      role: 'daemon',
      ...okOpen,
      send(raw) {
        if (decodeFrame(raw).type === 'HEADERS') {
          return new Promise<void>((resolve) => {
            releaseHeaders = resolve
          })
        }
        return Promise.resolve()
      },
      onData: (_stream, data) => {
        received = Buffer.from(data.payload).toString()
      },
      onFatal: (error) => (okFatal = error),
    })
    await receiveOpen(ok, 1, 'WS')
    const stream = okOpen.streams[0]!
    await stream.accept()
    const headersSent = stream.sendHeaders({ status: 101, headers: [] })
    await tick()
    expect(releaseHeaders).not.toBeNull()
    ok.receive(frame({ type: 'DATA', streamId: 1, payload: Buffer.from('after-101'), flags: { kind: 'ws', text: true, fin: true } }))
    expect(okFatal).toBeNull()
    expect(received).toBe('after-101')
    releaseHeaders!()
    await headersSent
  })

  it('rejects invalid HEADERS status and duplicate COMPLETE/CLOSE where applicable', async () => {
    const output = sink()
    const open = captureOnOpen()
    let fatal: MuxError | null = null
    const daemon = makeConnection({ role: 'daemon', ...open, onFatal: (error) => (fatal = error) }, output)
    await receiveOpen(daemon, 1, 'WS')
    const stream = open.streams[0]!
    await stream.accept()
    await expect(stream.sendHeaders({ status: 200, headers: [] })).rejects.toThrow(/101/)
    stream.sendHeaders({ status: 101, headers: [] })
    await expect(stream.sendHeaders({ status: 101, headers: [] })).rejects.toThrow(/already/)
    daemon.receive(frame({ type: 'HEADERS', streamId: 1, headers: { status: 200, headers: [] } }))
    expect(fatal?.message).toMatch(/only valid/)
  })
})

describe('bounded management queues', () => {
  it('fails the connection at the management frame bound', async () => {
    const raw: Uint8Array[] = []
    let calls = 0
    let fatal: MuxError | null = null
    const open = captureOnOpen()
    const daemon = createMuxConnection({
      role: 'daemon',
      ...open,
      send: () => {
        calls++
        return new Promise<void>(() => {})
      },
      onFatal: (error) => (fatal = error),
    })
    await receiveOpen(daemon, 1)
    void open.streams[0]!.accept().catch(() => {})
    await tick()
    for (let index = 0; index < MUX_MAX_MANAGEMENT_FRAMES + 2 && fatal === null; index++) {
      daemon.receive(frame({ type: 'DATA', streamId: 1, payload: Buffer.from('x'), flags: { kind: 'http' } }))
      await tick()
    }
    expect(calls).toBe(1)
    expect(fatal?.message).toMatch(/management queue limit exceeded/)
    expect(raw).toHaveLength(0)
  })

  it('fails the connection at the management byte bound', async () => {
    let calls = 0
    let fatal: MuxError | null = null
    const open = captureOnOpen()
    const daemon = createMuxConnection({
      role: 'daemon',
      ...open,
      send: () => {
        calls++
        return new Promise<void>(() => {})
      },
      onFatal: (error) => (fatal = error),
    })
    for (let streamId = 1; streamId <= 20 && fatal === null; streamId++) {
      daemon.receive(frame({ type: 'OPEN', streamId, request: openRequest() }))
      const stream = open.streams.at(-1)!
      void stream.accept().catch(() => {})
      void stream.sendHeaders({ status: 200, headers: [['x', 'y'.repeat(16_320)]] }).catch(() => {})
      await tick()
    }
    expect(calls).toBe(1)
    expect(fatal?.message).toMatch(/management queue limit exceeded/)
  })
})

describe('local reset and cancellation', () => {
  it('drops queued DATA and management frames on local RESET, then sends only RESET', async () => {
    const frames: Uint8Array[] = []
    const pending: (() => void)[] = []
    const server = createMuxConnection({
      role: 'server',
      send: (value) => {
        frames.push(value)
        return new Promise<void>((resolve) => pending.push(resolve))
      },
    })
    const stream = server.open(openRequest())
    await tick()
    server.receive(frame({ type: 'READY', streamId: stream.id }))
    const firstWrite = stream.write(Buffer.from('a'))
    const secondWrite = stream.write(Buffer.from('b'))
    const end = stream.end()
    const reset = stream.reset('CLIENT_CANCEL', 'browser canceled')
    await expect(firstWrite).rejects.toThrow(/closed/)
    await expect(secondWrite).rejects.toThrow(/closed/)
    await expect(end).rejects.toThrow(/closed/)
    await expect(stream.accept()).rejects.toThrow(/closed/)
    expect(frames).toHaveLength(1)
    pending.shift()!()
    await tick()
    expect(frames).toHaveLength(2)
    pending.shift()!()
    await reset
    expect(decodeAll(frames)).toEqual([
      { type: 'OPEN', streamId: 1, request: openRequest() },
      { type: 'RESET', streamId: 1, reset: { code: 'CLIENT_CANCEL', message: 'browser canceled' } },
    ])
  })

  it('cancels credit waits on END and on already-aborted signals', async () => {
    const output = sink()
    const server = makeConnection({ role: 'server' }, output)
    const stream = server.open(openRequest())
    await server.drain()
    server.receive(frame({ type: 'READY', streamId: stream.id }))
    const chunk = Buffer.alloc(65_536)
    for (let index = 0; index < 4; index++) {
      await stream.write(chunk)
    }
    const aborted = new AbortController()
    aborted.abort()
    await expect(stream.write(Buffer.from('x'), { signal: aborted.signal })).rejects.toMatchObject({ name: 'AbortError' })
    const controller = new AbortController()
    const waiting = stream.write(Buffer.from('y'), { signal: controller.signal })
    await tick()
    const end = stream.end()
    await expect(waiting).rejects.toThrow(/ended while waiting/)
    await end
    controller.abort()
  })

  it('rejects a credit waiter and emits no DATA when RESET follows WINDOW', async () => {
    const output = sink()
    const server = makeConnection({ role: 'server' }, output)
    const stream = server.open(openRequest())
    await server.drain()
    server.receive(frame({ type: 'READY', streamId: stream.id }))
    const chunk = Buffer.alloc(65_536)
    for (let index = 0; index < 4; index++) {
      await stream.write(chunk)
    }
    const waiting = stream.write(Buffer.from('after-window'))
    await tick()
    server.receive(frame({ type: 'WINDOW', streamId: stream.id, credit: 512 }))
    server.receive(frame({ type: 'RESET', streamId: stream.id, reset: { code: 'CANCEL', message: 'race' } }))
    await expect(waiting).rejects.toThrow(/closed/)
    await server.drain()
    expect(dataFrames(output.frames()).map((data) => data.payload.toString())).not.toContain('after-window')
    expect(dataFrames(output.frames())).toHaveLength(4)
  })

  it('re-checks a granted credit before enqueueing after END', async () => {
    const output = sink()
    const server = makeConnection({ role: 'server' }, output)
    const stream = server.open(openRequest())
    await server.drain()
    server.receive(frame({ type: 'READY', streamId: stream.id }))
    const chunk = Buffer.alloc(65_536)
    for (let index = 0; index < 4; index++) {
      await stream.write(chunk)
    }
    const waiting = stream.write(Buffer.from('late'))
    await tick()
    server.receive(frame({ type: 'WINDOW', streamId: stream.id, credit: 512 }))
    const ending = stream.end()
    await expect(waiting).rejects.toThrow(/closed/)
    await ending
    await server.drain()
    expect(dataFrames(output.frames()).map((data) => data.payload.toString())).not.toContain('late')
    expect(output.frames().at(-1)).toEqual({ type: 'END', streamId: stream.id })
  })

  it('re-checks cancellation when a WINDOW resolves a waiter', async () => {
    const output = sink()
    const server = makeConnection({ role: 'server' }, output)
    const stream = server.open(openRequest())
    await server.drain()
    server.receive(frame({ type: 'READY', streamId: stream.id }))
    const chunk = Buffer.alloc(65_536)
    for (let index = 0; index < 4; index++) {
      await stream.write(chunk)
    }
    const controller = new AbortController()
    const waiting = stream.write(Buffer.from('z'), { signal: controller.signal })
    await tick()
    server.receive(frame({ type: 'WINDOW', streamId: stream.id, credit: 512 }))
    controller.abort()
    await expect(waiting).rejects.toMatchObject({ name: 'AbortError' })
  })
})

describe('stream method validation', () => {
  it('enforces daemon accept/header state, payload bounds and method modes', async () => {
    const output = sink()
    const open = captureOnOpen()
    const daemon = makeConnection({ role: 'daemon', ...open }, output)
    await receiveOpen(daemon, 1)
    const stream = open.streams[0]!
    await expect(stream.write(Buffer.from('x'))).rejects.toThrow(/not accepted/)
    await expect(stream.sendHeaders({ status: 200, headers: [] })).rejects.toThrow(/accepted/)
    await stream.accept()
    await expect(stream.accept()).rejects.toThrow(/already accepted/)
    await expect(stream.write(Buffer.from('x'))).rejects.toThrow(/headers must be sent/)
    await expect(stream.complete()).rejects.toThrow(/server/)
    await stream.sendHeaders({ status: 200, headers: [] })
    await expect(stream.write(Buffer.from('x'), { text: true, fin: true })).rejects.toThrow(/WS fragment/)
    await expect(stream.close(1000)).rejects.toThrow(/WS/)

    const serverOutput = sink()
    const server = makeConnection({ role: 'server' }, serverOutput)
    const serverStream = server.open(openRequest())
    await server.drain()
    server.receive(frame({ type: 'READY', streamId: serverStream.id }))
    await expect(serverStream.write('not-bytes' as never)).rejects.toThrow(/Uint8Array/)
    await expect(serverStream.write(Buffer.alloc(65_537))).rejects.toThrow(/payload limit/)
    await expect(serverStream.end()).resolves.toBeUndefined()
    await expect(serverStream.end()).rejects.toThrow(/already ended/)
  })

  it('validates COMPLETE and CLOSE roles and duplicates', async () => {
    const serverOutput = sink()
    const server = makeConnection({ role: 'server' }, serverOutput)
    const stream = server.open(openRequest())
    await server.drain()
    server.receive(frame({ type: 'READY', streamId: stream.id }))
    server.receive(frame({ type: 'HEADERS', streamId: stream.id, headers: { status: 200, headers: [] } }))
    server.receive(frame({ type: 'END', streamId: stream.id }))
    await tick()
    const end = stream.end()
    const complete = stream.complete()
    await expect(stream.complete()).rejects.toThrow(/already sent/)
    await end
    await complete

    const wsOutput = sink()
    const wsServer = makeConnection({ role: 'server' }, wsOutput)
    const wsStream = wsServer.open(openRequest('WS'))
    await wsServer.drain()
    wsServer.receive(frame({ type: 'READY', streamId: wsStream.id }))
    wsServer.receive(frame({ type: 'HEADERS', streamId: wsStream.id, headers: { status: 101, headers: [] } }))
    await expect(wsStream.complete()).rejects.toThrow(/HTTP/)
    const firstClose = wsStream.close(1000, 'bye')
    await expect(wsStream.close(1000, 'again')).rejects.toThrow(/already sent/)
    await firstClose
  })
})

describe('inbound protocol edge cases', () => {
  it('rejects READY at the daemon, duplicate HEADERS and bad WS handshake status', async () => {
    const output = sink()
    const open = captureOnOpen()
    let fatal: MuxError | null = null
    const daemon = makeConnection({ role: 'daemon', ...open, onFatal: (error) => (fatal = error) }, output)
    await receiveOpen(daemon, 1)
    daemon.receive(frame({ type: 'READY', streamId: 1 }))
    expect(fatal?.message).toMatch(/only valid for the server/)

    const secondOutput = sink()
    let secondFatal: MuxError | null = null
    const server = makeConnection({ role: 'server', onFatal: (error) => (secondFatal = error) }, secondOutput)
    const stream = server.open(openRequest())
    await server.drain()
    server.receive(frame({ type: 'READY', streamId: stream.id }))
    server.receive(frame({ type: 'HEADERS', streamId: stream.id, headers: { status: 200, headers: [] } }))
    server.receive(frame({ type: 'HEADERS', streamId: stream.id, headers: { status: 200, headers: [] } }))
    expect(secondFatal?.message).toMatch(/duplicate HEADERS/)

    const thirdOutput = sink()
    let thirdFatal: MuxError | null = null
    const wsServer = makeConnection({ role: 'server', onFatal: (error) => (thirdFatal = error) }, thirdOutput)
    const wsStream = wsServer.open(openRequest('WS'))
    await wsServer.drain()
    wsServer.receive(frame({ type: 'READY', streamId: wsStream.id }))
    wsServer.receive(frame({ type: 'HEADERS', streamId: wsStream.id, headers: { status: 200, headers: [] } }))
    expect(thirdFatal?.message).toMatch(/101/)
  })

  it('rejects DATA after END, DATA before READY/HEADERS and wrong DATA flags', async () => {
    const endOutput = sink()
    const endOpen = captureOnOpen()
    let endFatal: MuxError | null = null
    const endDaemon = makeConnection({ role: 'daemon', ...endOpen, onFatal: (error) => (endFatal = error) }, endOutput)
    await receiveOpen(endDaemon, 1)
    const endStream = endOpen.streams[0]!
    await acceptAndHeaders(endStream)
    endDaemon.receive(frame({ type: 'END', streamId: 1 }))
    endDaemon.receive(frame({ type: 'DATA', streamId: 1, payload: Buffer.from('late'), flags: { kind: 'http' } }))
    expect(endFatal?.message).toMatch(/after END/)

    const readyOutput = sink()
    let readyFatal: MuxError | null = null
    const readyServer = makeConnection({ role: 'server', onFatal: (error) => (readyFatal = error) }, readyOutput)
    const readyStream = readyServer.open(openRequest())
    await readyServer.drain()
    readyServer.receive(frame({ type: 'DATA', streamId: readyStream.id, payload: Buffer.from('x'), flags: { kind: 'http' } }))
    expect(readyFatal?.message).toMatch(/before READY/)

    const headerOutput = sink()
    let headerFatal: MuxError | null = null
    const headerServer = makeConnection({ role: 'server', onFatal: (error) => (headerFatal = error) }, headerOutput)
    const headerStream = headerServer.open(openRequest())
    await headerServer.drain()
    headerServer.receive(frame({ type: 'READY', streamId: headerStream.id }))
    headerServer.receive(frame({ type: 'DATA', streamId: headerStream.id, payload: Buffer.from('x'), flags: { kind: 'http' } }))
    expect(headerFatal?.message).toMatch(/before HEADERS/)

    const flagOutput = sink()
    const flagOpen = captureOnOpen()
    let flagFatal: MuxError | null = null
    const flagDaemon = makeConnection({ role: 'daemon', ...flagOpen, onFatal: (error) => (flagFatal = error) }, flagOutput)
    await receiveOpen(flagDaemon, 1)
    const flagStream = flagOpen.streams[0]!
    await acceptAndHeaders(flagStream)
    flagDaemon.receive(frame({ type: 'DATA', streamId: 1, payload: Buffer.from('x'), flags: { kind: 'ws', text: true, fin: true } }))
    expect(flagFatal?.message).toMatch(/zero WS flags/)

    const wsOutput = sink()
    const wsOpen = captureOnOpen()
    let wsFatal: MuxError | null = null
    const wsDaemon = makeConnection({ role: 'daemon', ...wsOpen, onFatal: (error) => (wsFatal = error) }, wsOutput)
    await receiveOpen(wsDaemon, 1, 'WS')
    const wsStream = wsOpen.streams[0]!
    await wsStream.accept()
    await wsStream.sendHeaders({ status: 101, headers: [] })
    wsDaemon.receive(frame({ type: 'DATA', streamId: 1, payload: Buffer.from('x'), flags: { kind: 'http' } }))
    expect(wsFatal?.message).toMatch(/requires TEXT or BINARY/)
  })

  it('rejects END/COMPLETE/RESET/CLOSE for invalid streams and WINDOW beyond sent credit', async () => {
    const serverOutput = sink()
    let windowFatal: MuxError | null = null
    const server = makeConnection({ role: 'server', onFatal: (error) => (windowFatal = error) }, serverOutput)
    const stream = server.open(openRequest())
    await server.drain()
    server.receive(frame({ type: 'READY', streamId: stream.id }))
    await stream.write(Buffer.from('x'))
    server.receive(frame({ type: 'WINDOW', streamId: stream.id, credit: 1024 }))
    expect(windowFatal?.message).toMatch(/stream credit outstanding/)

    const unallocated = async (value: MuxFrame): Promise<string | undefined> => {
      let fatal: MuxError | null = null
      const daemon = makeConnection({ role: 'daemon', onOpen: () => {}, onFatal: (error) => (fatal = error) }, sink())
      daemon.receive(frame(value))
      return fatal?.message
    }
    expect(await unallocated({ type: 'END', streamId: 1 })).toMatch(/unallocated/)
    expect(await unallocated({ type: 'RESET', streamId: 1, reset: { code: 'A', message: '' } })).toMatch(/unallocated/)
    expect(await unallocated({ type: 'CLOSE', streamId: 1, close: { code: 1000, reason: '' } })).toMatch(/unallocated/)
    expect(await unallocated({ type: 'COMPLETE', streamId: 1 })).toMatch(/unallocated/)

    const wsOutput = sink()
    const wsOpen = captureOnOpen()
    let wsFatal: MuxError | null = null
    const wsDaemon = makeConnection({ role: 'daemon', ...wsOpen, onFatal: (error) => (wsFatal = error) }, wsOutput)
    await receiveOpen(wsDaemon, 1, 'WS')
    const wsStream = wsOpen.streams[0]!
    await wsStream.accept()
    await wsStream.sendHeaders({ status: 101, headers: [] })
    wsDaemon.receive(frame({ type: 'END', streamId: 1 }))
    expect(wsFatal?.message).toMatch(/only valid for HTTP/)

    const closeOutput = sink()
    const closeOpen = captureOnOpen()
    let closeFatal: MuxError | null = null
    const closeDaemon = makeConnection({ role: 'daemon', ...closeOpen, onFatal: (error) => (closeFatal = error) }, closeOutput)
    await receiveOpen(closeDaemon, 1, 'WS')
    const closeStream = closeOpen.streams[0]!
    await closeStream.accept()
    await closeStream.sendHeaders({ status: 101, headers: [] })
    closeDaemon.receive(frame({ type: 'CLOSE', streamId: 1, close: { code: 1000, reason: '' } }))
    await tick()
    closeDaemon.receive(frame({ type: 'CLOSE', streamId: 1, close: { code: 1000, reason: '' } }))
    expect(closeFatal?.message).toMatch(/duplicate WS CLOSE/)

    const httpCloseOutput = sink()
    const httpCloseOpen = captureOnOpen()
    let httpCloseFatal: MuxError | null = null
    const httpCloseDaemon = makeConnection(
      { role: 'daemon', ...httpCloseOpen, onFatal: (error) => (httpCloseFatal = error) },
      httpCloseOutput,
    )
    await receiveOpen(httpCloseDaemon, 1)
    const httpCloseStream = httpCloseOpen.streams[0]!
    await acceptAndHeaders(httpCloseStream)
    httpCloseDaemon.receive(frame({ type: 'CLOSE', streamId: 1, close: { code: 1000, reason: '' } }))
    expect(httpCloseFatal?.message).toMatch(/only valid for WS/)
  })

  it('rejects END before headers, COMPLETE before END, and invalid COMPLETE', async () => {
    const endOutput = sink()
    let endFatal: MuxError | null = null
    const endServer = makeConnection({ role: 'server', onFatal: (error) => (endFatal = error) }, endOutput)
    const endStream = endServer.open(openRequest())
    await endServer.drain()
    endServer.receive(frame({ type: 'READY', streamId: endStream.id }))
    endServer.receive(frame({ type: 'END', streamId: endStream.id }))
    expect(endFatal?.message).toMatch(/before HEADERS/)

    const completeOutput = sink()
    const completeOpen = captureOnOpen()
    let completeFatal: MuxError | null = null
    const completeDaemon = makeConnection(
      { role: 'daemon', ...completeOpen, onFatal: (error) => (completeFatal = error) },
      completeOutput,
    )
    await receiveOpen(completeDaemon, 1)
    const completeStream = completeOpen.streams[0]!
    await acceptAndHeaders(completeStream)
    completeDaemon.receive(frame({ type: 'COMPLETE', streamId: 1 }))
    expect(completeFatal?.message).toMatch(/before response END/)

    const wsOutput = sink()
    const wsOpen = captureOnOpen()
    let wsFatal: MuxError | null = null
    const wsDaemon = makeConnection({ role: 'daemon', ...wsOpen, onFatal: (error) => (wsFatal = error) }, wsOutput)
    await receiveOpen(wsDaemon, 1, 'WS')
    const wsStream = wsOpen.streams[0]!
    await wsStream.accept()
    await wsStream.sendHeaders({ status: 101, headers: [] })
    wsDaemon.receive(frame({ type: 'COMPLETE', streamId: 1 }))
    expect(wsFatal?.message).toMatch(/invalid COMPLETE/)
  })

  it('enforces server-only OPEN, active stream lookup, and daemon concurrency limits', async () => {
    const output = sink()
    let openFatal: MuxError | null = null
    const server = makeConnection({ role: 'server', onFatal: (error) => (openFatal = error) }, output)
    server.open(openRequest())
    await server.drain()
    server.receive(frame({ type: 'OPEN', streamId: 2, request: openRequest() }))
    expect(openFatal?.message).toMatch(/only valid from the server/)

    const missingOutput = sink()
    let missingFatal: MuxError | null = null
    const missing = makeConnection({ role: 'server', onFatal: (error) => (missingFatal = error) }, missingOutput)
    const missingStream = missing.open(openRequest())
    await missing.drain()
    missing.receive(frame({ type: 'READY', streamId: missingStream.id + 1 }))
    expect(missingFatal?.message).toMatch(/unallocated/)

    const daemonOutput = sink()
    const daemonOpen = captureOnOpen()
    let daemonFatal: MuxError | null = null
    const daemon = makeConnection({ role: 'daemon', ...daemonOpen, onFatal: (error) => (daemonFatal = error) }, daemonOutput)
    for (let streamId = 1; streamId <= 65; streamId++) {
      daemon.receive(frame({ type: 'OPEN', streamId, request: openRequest() }))
    }
    expect(daemonOpen.streams).toHaveLength(MUX_MAX_STREAMS)
    expect(daemonFatal?.message).toMatch(/concurrent stream limit/)

    const unhandledOutput = sink()
    let unhandledFatal: MuxError | null = null
    const unhandled = makeConnection({ role: 'daemon', onFatal: (error) => (unhandledFatal = error) }, unhandledOutput)
    unhandled.receive(frame({ type: 'OPEN', streamId: 1, request: openRequest() }))
    expect(unhandledFatal?.message).toMatch(/handler is not configured/)
  })

  it('swallows an onFatal callback failure and still rejects the connection', async () => {
    let fatal = 0
    const server = createMuxConnection({
      role: 'server',
      send: () => {},
      onFatal: () => {
        fatal++
        throw new Error('observer failed')
      },
    })
    expect(() => server.receive(Buffer.from([1, 2, 3]))).not.toThrow()
    expect(fatal).toBe(1)
    await expect(server.drain()).rejects.toThrow(/invalid mux frame/)
  })
})

describe('downstream failures reset only the affected stream', () => {
  async function setupDaemon(
    onData: MuxConnectionOptions['onData'],
    options: Partial<MuxConnectionOptions> = {},
    mode: 'HTTP' | 'WS' = 'HTTP',
  ): Promise<{
    daemon: MuxConnection
    stream: MuxStream
    output: Sink
    fatal: () => MuxError | null
    resets: { id: number; reset: ResetPayload }[]
  }> {
    const output = sink()
    const open = captureOnOpen()
    const resets: { id: number; reset: ResetPayload }[] = []
    let fatal: MuxError | null = null
    const daemon = makeConnection(
      {
        role: 'daemon',
        ...open,
        onData,
        onReset: (id, reset) => resets.push({ id, reset }),
        onFatal: (error) => (fatal = error),
        ...options,
      },
      output,
    )
    await receiveOpen(daemon, 1, mode)
    const stream = open.streams[0]!
    await stream.accept()
    await stream.sendHeaders({ status: mode === 'WS' ? 101 : 200, headers: [] })
    return { daemon, stream, output, fatal: () => fatal, resets }
  }

  const downstreamReset = {
    id: 1,
    reset: { code: 'DOWNSTREAM_ERROR', message: 'downstream consumption failed' },
  }

  it('resets on synchronous and asynchronous DATA consumer failures', async () => {
    const sync = await setupDaemon(() => {
      throw new Error('consumer failed')
    })
    sync.daemon.receive(frame({ type: 'DATA', streamId: 1, payload: Buffer.from('x'), flags: { kind: 'http' } }))
    await tick()
    expect(sync.fatal()).toBeNull()
    expect(sync.output.frames().at(-1)).toMatchObject({ type: 'RESET', reset: { code: 'DOWNSTREAM_ERROR' } })
    expect(sync.resets).toEqual([downstreamReset])

    const asyncFailure = await setupDaemon(async () => {
      throw new Error('async consumer failed')
    })
    asyncFailure.daemon.receive(frame({ type: 'DATA', streamId: 1, payload: Buffer.from('x'), flags: { kind: 'http' } }))
    await tick()
    expect(asyncFailure.fatal()).toBeNull()
    expect(asyncFailure.output.frames().at(-1)).toMatchObject({ type: 'RESET', reset: { code: 'DOWNSTREAM_ERROR' } })
    expect(asyncFailure.resets).toEqual([downstreamReset])
  })

  it('resets on synchronous and asynchronous END consumer failures', async () => {
    const sync = await setupDaemon(undefined, {
      onEnd: () => {
        throw new Error('flush failed')
      },
    })
    sync.daemon.receive(frame({ type: 'END', streamId: 1 }))
    await tick()
    expect(sync.output.frames().at(-1)).toMatchObject({ type: 'RESET', reset: { code: 'DOWNSTREAM_ERROR' } })
    expect(sync.resets).toEqual([downstreamReset])

    const asyncFailure = await setupDaemon(undefined, {
      onEnd: async () => {
        throw new Error('flush failed')
      },
    })
    asyncFailure.daemon.receive(frame({ type: 'END', streamId: 1 }))
    await tick()
    expect(asyncFailure.output.frames().at(-1)).toMatchObject({ type: 'RESET', reset: { code: 'DOWNSTREAM_ERROR' } })
    expect(asyncFailure.resets).toEqual([downstreamReset])
  })

  it('resets on synchronous and asynchronous CLOSE consumer failures', async () => {
    const sync = await setupDaemon(undefined, {
      onClose: () => {
        throw new Error('close failed')
      },
    }, 'WS')
    sync.daemon.receive(frame({ type: 'CLOSE', streamId: 1, close: { code: 1000, reason: '' } }))
    await tick()
    expect(sync.output.frames().at(-1)).toMatchObject({ type: 'RESET', reset: { code: 'DOWNSTREAM_ERROR' } })
    expect(sync.resets).toEqual([downstreamReset])

    const asyncFailure = await setupDaemon(undefined, {
      onClose: async () => {
        throw new Error('close failed')
      },
    }, 'WS')
    asyncFailure.daemon.receive(frame({ type: 'CLOSE', streamId: 1, close: { code: 1000, reason: '' } }))
    await tick()
    expect(asyncFailure.output.frames().at(-1)).toMatchObject({ type: 'RESET', reset: { code: 'DOWNSTREAM_ERROR' } })
    expect(asyncFailure.resets).toEqual([downstreamReset])
  })
})

describe('id allocation and WS close credit edges', () => {
  it('preserves a queued OPEN before RESET so server stream ids remain consecutive', async () => {
    const frames: Uint8Array[] = []
    const pending: (() => void)[] = []
    const server = createMuxConnection({
      role: 'server',
      send: (value) => {
        frames.push(value)
        return new Promise<void>((resolve) => pending.push(resolve))
      },
    })
    const first = server.open(openRequest())
    const second = server.open(openRequest())
    const reset = second.reset('CANCEL', 'not needed')
    await tick()
    expect(frames).toHaveLength(1)
    pending.shift()!()
    await tick()
    pending.shift()!()
    await tick()
    expect(frames).toHaveLength(3)
    pending.shift()!()
    await reset
    const decoded = decodeAll(frames)
    expect(decoded.filter((value) => value.type === 'OPEN').map((value) => value.streamId)).toEqual([
      first.id,
      second.id,
    ])
    expect(decoded.at(-1)).toEqual({
      type: 'RESET',
      streamId: second.id,
      reset: { code: 'CANCEL', message: 'not needed' },
    })
  })

  it('keeps in-flight WS DATA credit consistent when CLOSE arrives first', async () => {
    const frames: Uint8Array[] = []
    let parked = false
    const pending: (() => void)[] = []
    let fatal: MuxError | null = null
    const server = createMuxConnection({
      role: 'server',
      send: (value) => {
        frames.push(value)
        if (!parked) {
          return
        }
        return new Promise<void>((resolve) => pending.push(resolve))
      },
      onFatal: (error) => (fatal = error),
    })
    const stream = server.open(openRequest('WS'))
    await server.drain()
    server.receive(frame({ type: 'READY', streamId: stream.id }))
    server.receive(frame({ type: 'HEADERS', streamId: stream.id, headers: { status: 101, headers: [] } }))
    parked = true
    const write = stream.write(Buffer.from('x'), { text: true, fin: true })
    await tick()
    server.receive(frame({ type: 'CLOSE', streamId: stream.id, close: { code: 1000, reason: '' } }))
    pending.shift()!()
    await write
    server.receive(frame({ type: 'WINDOW', streamId: stream.id, credit: 512 }))
    expect(fatal).toBeNull()
    await expect(stream.write(Buffer.from('late'), { text: true, fin: true })).rejects.toThrow(/closed by the peer/)
  })
})
