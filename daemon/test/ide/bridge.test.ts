import { afterEach, describe, expect, it } from 'vitest'
import http from 'node:http'
import { createIdeBridge, type IdeBridge } from '../../src/ide/bridge.js'

const cleanups: (() => Promise<void> | void)[] = []
afterEach(async () => {
  for (const cleanup of cleanups.splice(0).reverse()) {
    await cleanup()
  }
})

async function bridge(): Promise<IdeBridge> {
  const instance = await createIdeBridge()
  cleanups.push(() => instance.close())
  return instance
}

function request(
  url: string,
  options: { method?: string; body?: string; headers?: Record<string, string> } = {},
): Promise<{ status: number; body: string }> {
  return new Promise((resolve, reject) => {
    const req = http.request(
      url,
      { method: options.method ?? 'GET', headers: options.headers },
      (res) => {
        let body = ''
        res.setEncoding('utf8')
        res.on('data', (chunk: string) => {
          body += chunk
        })
        res.on('end', () => resolve({ status: res.statusCode ?? 0, body }))
      },
    )
    req.on('error', reject)
    if (options.body !== undefined) {
      req.write(options.body)
    }
    req.end()
  })
}

function openStream(base: string, token: string, workspacePath: string): Promise<http.IncomingMessage> {
  return new Promise((resolve, reject) => {
    const req = http.get(
      `${base}/bridge/stream?token=${encodeURIComponent(token)}&workspacePath=${encodeURIComponent(workspacePath)}`,
      (res) => {
        res.setEncoding('utf8')
        if (res.statusCode !== 200) {
          res.resume()
          reject(new Error(`status ${res.statusCode}`))
          return
        }
        cleanups.push(() => res.destroy())
        resolve(res)
      },
    )
    req.on('error', reject)
  })
}

const signal = (): AbortSignal => new AbortController().signal

describe('loopback IDE bridge', () => {
  it('enforces loopback token and routing', async () => {
    const instance = await bridge()
    instance.assign('11111111-1111-4111-8111-111111111111', '/managed/one.code-workspace')

    expect((await request(`${instance.url}/bridge/stream?token=wrong&workspacePath=/x`)).status).toBe(401)
    expect((await request(`${instance.url}/bridge/unknown?token=${instance.secret}`)).status).toBe(404)
    expect((await request(`${instance.url}/bridge/stream?token=${instance.secret}`)).status).toBe(400)
  })

  it('binds a stream to a view and drops it on revoke', async () => {
    const instance = await bridge()
    const viewId = '11111111-1111-4111-8111-111111111111'
    const workspacePath = '/managed/one.code-workspace'
    instance.assign(viewId, workspacePath)
    expect(instance.isConnected(viewId)).toBe(false)

    const stream = await openStream(instance.url, instance.secret, workspacePath)
    expect(instance.isConnected(viewId)).toBe(true)

    const ended = new Promise<void>((resolve) => stream.on('end', () => resolve()))
    stream.resume()
    instance.revoke(viewId)
    expect(instance.isConnected(viewId)).toBe(false)
    await ended
  })

  it('rejects requests when no window is connected', async () => {
    const instance = await bridge()
    const viewId = '11111111-1111-4111-8111-111111111111'
    instance.assign(viewId, '/managed/one.code-workspace')
    await expect(instance.request(viewId, { op: 'focusTerminal' }, signal())).rejects.toThrow(/not connected/)
  })

  it('resolves a command as cancelled when the caller aborts', async () => {
    const instance = await bridge()
    const viewId = '11111111-1111-4111-8111-111111111111'
    instance.assign(viewId, '/managed/one.code-workspace')
    await openStream(instance.url, instance.secret, '/managed/one.code-workspace')

    const controller = new AbortController()
    const pending = instance.request(viewId, { op: 'focusTerminal' }, controller.signal)
    controller.abort()
    expect(await pending).toEqual({ ok: false, message: 'ide request was cancelled' })

    // An already aborted signal never sends a command.
    const aborted = new AbortController()
    aborted.abort()
    expect(await instance.request(viewId, { op: 'focusTerminal' }, aborted.signal)).toEqual({
      ok: false,
      message: 'ide request was cancelled',
    })
  })

  it('validates result and pty payloads', async () => {
    const instance = await bridge()
    const viewId = '11111111-1111-4111-8111-111111111111'
    instance.assign(viewId, '/managed/one.code-workspace')
    await openStream(instance.url, instance.secret, '/managed/one.code-workspace')

    const json = { 'content-type': 'application/json' }
    expect(
      (await request(`${instance.url}/bridge/result?token=${instance.secret}`, { method: 'POST', body: 'not json', headers: json }))
        .status,
    ).toBe(400)
    expect(
      (await request(`${instance.url}/bridge/result?token=${instance.secret}`, { method: 'POST', body: '{}', headers: json })).status,
    ).toBe(400)
    expect(
      (await request(`${instance.url}/bridge/pty?token=${instance.secret}`, { method: 'POST', body: '{"pid":0}', headers: json })).status,
    ).toBe(400)
    expect(
      (
        await request(`${instance.url}/bridge/pty?token=${instance.secret}`, {
          method: 'POST',
          body: JSON.stringify({ workspacePath: '/other', pid: 5 }),
          headers: json,
        })
      ).status,
    ).toBe(403)
  })

  it('delivers pty events and resolves a command result', async () => {
    const instance = await bridge()
    const viewId = '11111111-1111-4111-8111-111111111111'
    instance.assign(viewId, '/managed/one.code-workspace')
    const ptyEvents: { viewId: string; pid: number }[] = []
    instance.onPty((id, pid) => ptyEvents.push({ viewId: id, pid }))

    const stream = await openStream(instance.url, instance.secret, '/managed/one.code-workspace')
    const lines: Record<string, unknown>[] = []
    stream.on('data', (chunk: string) => {
      for (const line of chunk.split('\n')) {
        if (line.trim().length > 0) {
          lines.push(JSON.parse(line) as Record<string, unknown>)
        }
      }
    })
    await new Promise((resolve) => setTimeout(resolve, 20))

    const pending = instance.request(viewId, { op: 'changeDirectory', directory: '/other' }, signal())
    await new Promise((resolve) => setTimeout(resolve, 20))
    const command = lines.find((line) => line.type === 'command') as { id: string; op: string }
    expect(command.op).toBe('changeDirectory')

    const json = { 'content-type': 'application/json' }
    await request(`${instance.url}/bridge/result?token=${instance.secret}`, {
      method: 'POST',
      body: JSON.stringify({ id: command.id, ok: true }),
      headers: json,
    })
    expect(await pending).toEqual({ ok: true, code: undefined, message: undefined })

    await request(`${instance.url}/bridge/pty?token=${instance.secret}`, {
      method: 'POST',
      body: JSON.stringify({ workspacePath: '/managed/one.code-workspace', pid: 99 }),
      headers: json,
    })
    expect(ptyEvents).toEqual([{ viewId, pid: 99 }])
  })

  it('releases pending commands on close', async () => {
    const instance = await bridge()
    const viewId = '11111111-1111-4111-8111-111111111111'
    instance.assign(viewId, '/managed/one.code-workspace')
    await openStream(instance.url, instance.secret, '/managed/one.code-workspace')

    const pending = instance.request(viewId, { op: 'focusTerminal' }, signal())
    await instance.close()
    expect(await pending).toEqual({ ok: false, message: 'ide bridge closed' })
  })

  it('treats a delivered folder change as success when its host restarts', async () => {
    const instance = await bridge()
    const viewId = '11111111-1111-4111-8111-111111111111'
    instance.assign(viewId, '/managed/one.code-workspace')
    const stream = await openStream(instance.url, instance.secret, '/managed/one.code-workspace')

    // The extension host restarts right after accepting the command and never replies.
    const changed = instance.request(viewId, { op: 'changeDirectory', directory: '/other' }, signal())
    await new Promise((resolve) => setTimeout(resolve, 20))
    stream.destroy()
    expect(await changed).toEqual({ ok: true, code: undefined, message: undefined })

    // A command that needs an answer still fails once the window is gone.
    const stream2 = await openStream(instance.url, instance.secret, '/managed/one.code-workspace')
    const focused = instance.request(viewId, { op: 'focusTerminal' }, signal())
    await new Promise((resolve) => setTimeout(resolve, 20))
    stream2.destroy()
    expect(await focused).toEqual({ ok: false, message: 'ide window closed' })
  })

  it('fails the pty registration when supervision throws', async () => {
    const instance = await bridge()
    const viewId = '11111111-1111-4111-8111-111111111111'
    instance.assign(viewId, '/managed/one.code-workspace')
    instance.onPty(async () => {
      throw new Error('no supervision')
    })
    await openStream(instance.url, instance.secret, '/managed/one.code-workspace')

    const response = await request(`${instance.url}/bridge/pty?token=${instance.secret}`, {
      method: 'POST',
      body: JSON.stringify({ workspacePath: '/managed/one.code-workspace', pid: 42 }),
      headers: { 'content-type': 'application/json' },
    })
    expect(response.status).toBe(500)
  })

  it('rebinds a view to a new workspace and rejects malformed payloads', async () => {
    const instance = await bridge()
    const viewId = '11111111-1111-4111-8111-111111111111'
    // Re-assigning the same view to another workspace must drop the stale key binding.
    instance.assign(viewId, '/managed/one.code-workspace')
    instance.assign(viewId, '/managed/two.code-workspace')
    await openStream(instance.url, instance.secret, '/managed/two.code-workspace')
    expect(instance.isConnected(viewId)).toBe(true)
    expect((await request(`${instance.url}/bridge/stream?token=${instance.secret}&workspacePath=${encodeURIComponent('/managed/one.code-workspace')}`)).status).toBe(403)

    const json = { 'content-type': 'application/json' }
    const post = (path: string, body: string) =>
      request(`${instance.url}${path}?token=${instance.secret}`, { method: 'POST', body, headers: json })
    expect((await post('/bridge/result', '5')).status).toBe(400)
    expect((await post('/bridge/pty', 'not json')).status).toBe(400)
    expect((await post('/bridge/pty', '5')).status).toBe(400)
  })

  it('fails a pending command when its view is revoked', async () => {
    const instance = await bridge()
    const viewId = '11111111-1111-4111-8111-111111111111'
    instance.assign(viewId, '/managed/one.code-workspace')
    await openStream(instance.url, instance.secret, '/managed/one.code-workspace')

    const pending = instance.request(viewId, { op: 'focusTerminal' }, signal())
    await new Promise((resolve) => setTimeout(resolve, 20))
    instance.revoke(viewId)
    expect(await pending).toEqual({ ok: false, message: 'ide window was closed' })
  })
})
