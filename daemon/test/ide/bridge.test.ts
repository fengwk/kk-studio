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

/** The bridge secret travels in an Authorization header, never in the URL query. */
function jsonAuth(secret: string, json = true): Record<string, string> {
  return json
    ? { authorization: `Bearer ${secret}`, 'content-type': 'application/json' }
    : { authorization: `Bearer ${secret}` }
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

function openStream(
  base: string,
  token: string,
  workspacePath: string,
  folders: string[] = [],
): Promise<http.IncomingMessage> {
  const params = new URLSearchParams({ workspacePath })
  for (const folder of folders) {
    params.append('folder', folder)
  }
  return new Promise((resolve, reject) => {
    const req = http.get(
      `${base}/bridge/stream?${params.toString()}`,
      { headers: { authorization: `Bearer ${token}` } },
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

    // No credentials, wrong credentials, unknown route and a missing workspace argument.
    expect((await request(`${instance.url}/bridge/stream?workspacePath=/x`)).status).toBe(401)
    expect(
      (await request(`${instance.url}/bridge/stream?workspacePath=/x`, { headers: jsonAuth('wrong', false) })).status,
    ).toBe(401)
    expect((await request(`${instance.url}/bridge/unknown`, { headers: jsonAuth(instance.secret, false) })).status).toBe(
      404,
    )
    expect((await request(`${instance.url}/bridge/stream`, { headers: jsonAuth(instance.secret, false) })).status).toBe(
      400,
    )
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

    expect(
      (await request(`${instance.url}/bridge/result`, { method: 'POST', body: 'not json', headers: jsonAuth(instance.secret) }))
        .status,
    ).toBe(400)
    expect(
      (await request(`${instance.url}/bridge/result`, { method: 'POST', body: '{}', headers: jsonAuth(instance.secret) }))
        .status,
    ).toBe(400)
    expect(
      (await request(`${instance.url}/bridge/pty`, { method: 'POST', body: '{"pid":0}', headers: jsonAuth(instance.secret) }))
        .status,
    ).toBe(400)
    expect(
      (
        await request(`${instance.url}/bridge/pty`, {
          method: 'POST',
          body: JSON.stringify({ workspacePath: '/other', pid: 5 }),
          headers: jsonAuth(instance.secret),
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

    await request(`${instance.url}/bridge/result`, {
      method: 'POST',
      body: JSON.stringify({ id: command.id, ok: true, directory: '/other' }),
      headers: jsonAuth(instance.secret),
    })
    expect(await pending).toEqual({ ok: true, code: undefined, message: undefined, directory: '/other' })

    await request(`${instance.url}/bridge/pty`, {
      method: 'POST',
      body: JSON.stringify({ workspacePath: '/managed/one.code-workspace', pid: 99 }),
      headers: jsonAuth(instance.secret),
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

  it('rejects a directory change whose reply reports a different folder', async () => {
    const instance = await bridge()
    const viewId = '11111111-1111-4111-8111-111111111111'
    instance.assign(viewId, '/managed/one.code-workspace')
    const stream = await openStream(instance.url, instance.secret, '/managed/one.code-workspace')
    const lines: { id: string }[] = []
    stream.on('data', (chunk: string) => {
      for (const line of chunk.split('\n')) {
        if (line.trim().length > 0) {
          lines.push(JSON.parse(line) as { id: string })
        }
      }
    })

    const changed = instance.request(viewId, { op: 'changeDirectory', directory: '/other' }, signal(), {
      expectedDirectory: '/other',
    })
    await new Promise((resolve) => setTimeout(resolve, 20))
    const command = lines.find((line) => line.id !== undefined) as { id: string }

    // A successful-looking reply that names a different folder is not an applied change.
    await request(`${instance.url}/bridge/result`, {
      method: 'POST',
      body: JSON.stringify({ id: command.id, ok: true, directory: '/managed' }),
      headers: jsonAuth(instance.secret),
    })
    expect(await changed).toEqual({ ok: false, message: 'directory change was not applied' })
  })

  it('confirms a directory change only when the reconnected window reports the folder', async () => {
    const instance = await bridge()
    const viewId = '11111111-1111-4111-8111-111111111111'
    instance.assign(viewId, '/managed/one.code-workspace')
    const stream = await openStream(instance.url, instance.secret, '/managed/one.code-workspace')

    // The extension host restarts after accepting the command and never replies on the old stream.
    const changed = instance.request(viewId, { op: 'changeDirectory', directory: '/other' }, signal(), {
      expectedDirectory: '/other',
    })
    await new Promise((resolve) => setTimeout(resolve, 20))
    stream.destroy()
    await new Promise((resolve) => setTimeout(resolve, 20))

    // The reconnected window reports the new folder, which is the actual confirmation.
    await openStream(instance.url, instance.secret, '/managed/one.code-workspace', ['/other'])
    expect(await changed).toEqual({ ok: true, code: undefined, message: undefined, directory: '/other' })

    // A reconnect that never reports the folder is a failure, not a silent success.
    const rejected = instance.request(viewId, { op: 'changeDirectory', directory: '/third' }, signal(), {
      expectedDirectory: '/third',
    })
    await new Promise((resolve) => setTimeout(resolve, 20))
    await openStream(instance.url, instance.secret, '/managed/one.code-workspace', ['/other'])
    expect(await rejected).toEqual({ ok: false, message: 'directory change was not applied' })

    // A command that needs an answer still fails once the window is gone.
    const stream3 = await openStream(instance.url, instance.secret, '/managed/one.code-workspace')
    const focused = instance.request(viewId, { op: 'focusTerminal' }, signal())
    await new Promise((resolve) => setTimeout(resolve, 20))
    stream3.destroy()
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

    const response = await request(`${instance.url}/bridge/pty`, {
      method: 'POST',
      body: JSON.stringify({ workspacePath: '/managed/one.code-workspace', pid: 42 }),
      headers: jsonAuth(instance.secret),
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
    expect(
      (
        await request(`${instance.url}/bridge/stream?workspacePath=${encodeURIComponent('/managed/one.code-workspace')}`, {
          headers: jsonAuth(instance.secret, false),
        })
      ).status,
    ).toBe(403)

    const post = (path: string, body: string) =>
      request(`${instance.url}${path}`, { method: 'POST', body, headers: jsonAuth(instance.secret) })
    expect((await post('/bridge/result', '5')).status).toBe(400)
    expect((await post('/bridge/pty', 'not json')).status).toBe(400)
    expect((await post('/bridge/pty', '5')).status).toBe(400)
  })

  it('waits for the window stream and rejects a cancelled wait', async () => {
    const instance = await bridge()
    const viewId = '11111111-1111-4111-8111-111111111111'
    instance.assign(viewId, '/managed/one.code-workspace')

    // Cancelling before the window connects surfaces the cancellation.
    const aborted = new AbortController()
    const cancelled = instance.waitForConnection(viewId, aborted.signal)
    aborted.abort()
    await expect(cancelled).rejects.toMatchObject({ name: 'AbortError' })

    // Registering the window resumes a pending wait, and a connected view resolves at once.
    const waiting = instance.waitForConnection(viewId, signal())
    await openStream(instance.url, instance.secret, '/managed/one.code-workspace')
    await expect(waiting).resolves.toBeUndefined()
    await expect(instance.waitForConnection(viewId, signal())).resolves.toBeUndefined()
  })

  it('releases a pending connection wait on close', async () => {
    const instance = await bridge()
    const viewId = '11111111-1111-4111-8111-111111111111'
    instance.assign(viewId, '/managed/one.code-workspace')

    const waiting = instance.waitForConnection(viewId, signal())
    await instance.close()
    await expect(waiting).rejects.toThrow('ide bridge closed')
  })

  it('fails a pending command when its view is revoked', async () => {
    const instance = await bridge()
    const viewId = '11111111-1111-4111-8111-111111111111'
    instance.assign(viewId, '/managed/one.code-workspace')
    await openStream(instance.url, instance.secret, '/managed/one.code-workspace')

    const pending = instance.request(viewId, { op: 'focusTerminal' }, signal())
    await new Promise((resolve) => setTimeout(resolve, 20))
    instance.revoke(viewId)
    expect(await pending).toEqual({ ok: false, code: 'VIEW_GONE', message: 'ide window was closed' })
  })
})
