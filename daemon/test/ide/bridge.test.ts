import { afterEach, describe, expect, it, vi } from 'vitest'
import http from 'node:http'
import { randomUUID } from 'node:crypto'
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
  folders: string[] = ['/managed'],
  hostId = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
): Promise<http.IncomingMessage> {
  const params = new URLSearchParams({ workspacePath, hostId })
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

/** Read a complete command frame without assuming TCP chunk boundaries. */
function nextCommand(stream: http.IncomingMessage): Promise<{ id: string; op: string }> {
  return new Promise((resolve, reject) => {
    let buffer = ''
    const onData = (chunk: string): void => {
      buffer += chunk
      let end = buffer.indexOf('\n')
      while (end >= 0) {
        const line = buffer.slice(0, end)
        buffer = buffer.slice(end + 1)
        const value = JSON.parse(line) as { type: string; id: string; op: string }
        if (value.type === 'command') {
          stream.off('data', onData)
          stream.off('error', reject)
          resolve(value)
          return
        }
        end = buffer.indexOf('\n')
      }
    }
    stream.on('data', onData)
    stream.once('error', reject)
  })
}

describe('loopback IDE bridge', () => {
  it('retains an accepted host identity even if the registration acknowledgement is lost', async () => {
    const instance = await bridge()
    const viewId = '11111111-1111-4111-8111-111111111111'
    const workspace = '/managed/one.code-workspace'
    const oldHost = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
    const newHost = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'
    instance.assign(viewId, workspace, '1', '/target')
    const ready = instance.waitForConnection(viewId, signal())
    const params = new URLSearchParams({ workspacePath: workspace, folder: '/target', hostId: oldHost })
    const lost = http.get(`${instance.url}/bridge/stream?${params}`, { headers: jsonAuth(instance.secret, false) })
    lost.on('error', () => {})
    await ready
    lost.destroy()
    await openStream(instance.url, instance.secret, workspace, ['/target'], newHost)
    await expect(openStream(instance.url, instance.secret, workspace, ['/target'], oldHost)).rejects.toThrow('status 410')
  })

  it('retires an initiating host that reconnects while its directory transition is unresolved', async () => {
    const instance = await bridge()
    const viewId = '11111111-1111-4111-8111-111111111111'
    const workspace = '/managed/one.code-workspace'
    instance.assign(viewId, workspace, '1', '/old')
    const stream = await openStream(instance.url, instance.secret, workspace, ['/old'])
    const command = nextCommand(stream)
    const changed = instance.request(viewId, { op: 'changeDirectory', directory: '/target' }, signal(), {
      expectedDirectory: '/target',
    })
    await command
    await expect(openStream(instance.url, instance.secret, workspace, ['/old'])).rejects.toThrow('status 410')
    expect(await changed).toMatchObject({ ok: false })
    expect(instance.isConnected(viewId, '1')).toBe(false)
    await expect(openStream(instance.url, instance.secret, workspace, ['/target'])).rejects.toThrow('status 410')
  })

  it('does not accept a changed-directory success echoed by the initiating host', async () => {
    const instance = await bridge()
    const viewId = '11111111-1111-4111-8111-111111111111'
    const workspace = '/managed/one.code-workspace'
    instance.assign(viewId, workspace, '1', '/old')
    const stream = await openStream(instance.url, instance.secret, workspace, ['/old'])
    const command = nextCommand(stream)
    const changed = instance.request(viewId, { op: 'changeDirectory', directory: '/target' }, signal(), {
      expectedDirectory: '/target',
    })
    const id = (await command).id
    await request(`${instance.url}/bridge/result`, {
      method: 'POST',
      headers: jsonAuth(instance.secret),
      body: JSON.stringify({ id, ok: true, directory: '/target' }),
    })
    expect(await changed).toMatchObject({ ok: false, message: 'directory change was not applied' })
    expect(instance.isConnected(viewId)).toBe(false)
    await expect(
      openStream(instance.url, instance.secret, workspace, ['/target'], 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'),
    ).rejects.toThrow('status 410')
  })

  it('does not admit the target host after a directory transition times out', async () => {
    const instance = await bridge()
    const viewId = '11111111-1111-4111-8111-111111111111'
    const workspace = '/managed/one.code-workspace'
    instance.assign(viewId, workspace, '1', '/old')
    await openStream(instance.url, instance.secret, workspace, ['/old'])
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    try {
      const changed = instance.request(viewId, { op: 'changeDirectory', directory: '/target' }, signal(), {
        expectedDirectory: '/target',
      })
      await vi.advanceTimersByTimeAsync(20_000)
      expect(await changed).toMatchObject({ ok: false, message: 'ide window did not respond' })
      await expect(
        openStream(instance.url, instance.secret, workspace, ['/target'], 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'),
      ).rejects.toThrow('status 410')
    } finally {
      vi.useRealTimers()
    }
  })

  it('rejects malformed or missing host identifiers at stream admission', async () => {
    const instance = await bridge()
    const viewId = '11111111-1111-4111-8111-111111111111'
    const workspacePath = '/managed/one.code-workspace'
    instance.assign(viewId, workspacePath, '1', '/managed')
    for (const hostId of ['', 'AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA', 'bad']) {
      await expect(openStream(instance.url, instance.secret, workspacePath, ['/managed'], hostId)).rejects.toThrow('status 400')
    }
    const query = new URLSearchParams({ workspacePath, folder: '/managed' })
    expect((await request(`${instance.url}/bridge/stream?${query}`, { headers: jsonAuth(instance.secret, false) })).status).toBe(400)
  })

  it('keeps bounded host retirement across rebind and permits only the current host to reconnect at capacity', async () => {
    const instance = await bridge()
    const viewId = '11111111-1111-4111-8111-111111111111'
    const workspace = '/managed/one.code-workspace'
    instance.assign(viewId, workspace, '1', '/managed')
    const hostIds = Array.from({ length: 256 }, () => randomUUID())
    for (const hostId of hostIds) {
      const stream = await openStream(instance.url, instance.secret, workspace, ['/managed'], hostId)
      stream.resume()
    }
    const last = hostIds[255]!
    await openStream(instance.url, instance.secret, workspace, ['/managed'], last)
    await expect(openStream(instance.url, instance.secret, workspace, ['/managed'], randomUUID())).rejects.toThrow('status 503')
    await expect(openStream(instance.url, instance.secret, workspace, ['/managed'], hostIds[0]!)).rejects.toThrow('status 410')
    instance.assign(viewId, workspace, '2', '/managed')
    await expect(openStream(instance.url, instance.secret, workspace, ['/managed'], last)).rejects.toThrow('status 410')
    await expect(openStream(instance.url, instance.secret, workspace, ['/managed'], randomUUID())).rejects.toThrow('status 503')
    instance.revoke(viewId, '2')
    instance.assign(viewId, workspace, '3', '/managed')
    await openStream(instance.url, instance.secret, workspace, ['/managed'], randomUUID())
  })

  it('never lets a superseded host retake a matching target folder or answer the new host command', async () => {
    const instance = await bridge()
    const viewId = '11111111-1111-4111-8111-111111111111'
    const workspace = '/managed/one.code-workspace'
    const oldHost = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
    const newHost = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'
    instance.assign(viewId, workspace, '1', '/target')
    const old = await openStream(instance.url, instance.secret, workspace, ['/target'], oldHost)
    const oldCommand = nextCommand(old)
    const oldInspect = instance.request(viewId, { op: 'inspect' }, signal())
    const oldId = (await oldCommand).id
    const openingNew = openStream(instance.url, instance.secret, workspace, ['/target'], newHost)
    expect(await oldInspect).toMatchObject({ ok: false })
    const current = instance.request(viewId, { op: 'closeView' }, signal())
    const fresh = await openingNew
    const currentId = (await nextCommand(fresh)).id
    old.resume()
    await request(`${instance.url}/bridge/result`, {
      method: 'POST', headers: jsonAuth(instance.secret),
      body: JSON.stringify({ id: oldId, ok: true, clean: true }),
    })
    const status = await openStream(instance.url, instance.secret, workspace, ['/target'], oldHost).then(
      (stream) => { stream.destroy(); return 200 },
      (error: Error) => Number(error.message.replace('status ', '')),
    )
    expect(status).toBe(410)
    await request(`${instance.url}/bridge/result`, {
      method: 'POST', headers: jsonAuth(instance.secret),
      body: JSON.stringify({ id: currentId, ok: false, code: 'DIRTY', message: 'unsaved changes' }),
    })
    expect(await current).toMatchObject({ ok: false, code: 'DIRTY' })
  })

  it.each(['aborted', 'rejected'] as const)(
    'does not admit a target registration after a directory change is %s',
    async (outcome) => {
      const instance = await bridge()
      const viewId = '11111111-1111-4111-8111-111111111111'
      const workspace = '/managed/one.code-workspace'
      instance.assign(viewId, workspace, '1', '/old')
      const stream = await openStream(instance.url, instance.secret, workspace, ['/old'])
      const controller = new AbortController()
      const command = nextCommand(stream)
      const changed = instance.request(viewId, { op: 'changeDirectory', directory: '/target' }, controller.signal, {
        expectedDirectory: '/target',
      })
      const id = (await command).id
      await expect(
        instance.request(viewId, { op: 'changeDirectory', directory: '/another' }, signal(), {
          expectedDirectory: '/another',
        }),
      ).rejects.toMatchObject({ code: 'BUSY' })
      if (outcome === 'aborted') {
        controller.abort()
      } else {
        await request(`${instance.url}/bridge/result`, {
          method: 'POST',
          body: JSON.stringify({ id, ok: false, code: 'DIRTY', message: 'unsaved changes' }),
          headers: jsonAuth(instance.secret),
        })
      }
      expect(await changed).toMatchObject({ ok: false })
      // A late success from that command cannot resurrect its transition.
      await request(`${instance.url}/bridge/result`, {
        method: 'POST',
        body: JSON.stringify({ id, ok: true, directory: '/target' }),
        headers: jsonAuth(instance.secret),
      })
      await expect(openStream(instance.url, instance.secret, workspace, ['/target'])).rejects.toThrow('status 410')
      await expect(openStream(instance.url, instance.secret, workspace, ['/old', '/target'])).rejects.toThrow('status 410')
      if (outcome === 'aborted') {
        expect(instance.isConnected(viewId, '1')).toBe(false)
        await expect(openStream(instance.url, instance.secret, workspace, ['/old'])).rejects.toThrow('status 410')
        await openStream(instance.url, instance.secret, workspace, ['/old'], 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb')
      }
      expect(instance.isConnected(viewId, '1')).toBe(true)
    },
  )

  it('retires an old-folder reconnect after the target stream confirms a directory change', async () => {
    const instance = await bridge()
    const viewId = '11111111-1111-4111-8111-111111111111'
    const workspace = '/managed/one.code-workspace'
    instance.assign(viewId, workspace, '1', '/old')
    const oldStream = await openStream(instance.url, instance.secret, workspace, ['/old'])
    const oldEnded = new Promise<void>((resolve) => oldStream.once('end', resolve))
    const oldCommand = nextCommand(oldStream)
    const oldInspect = instance.request(viewId, { op: 'inspect' }, signal())
    const oldId = (await oldCommand).id
    const changeCommand = nextCommand(oldStream)
    const changed = instance.request(viewId, { op: 'changeDirectory', directory: '/new' }, signal(), {
      expectedDirectory: '/new',
    })
    await changeCommand
    const openingTarget = openStream(
      instance.url, instance.secret, workspace, ['/new'], 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
    )
    expect(await changed).toMatchObject({ ok: true, directory: '/new' })
    expect(await oldInspect).toMatchObject({ ok: false })

    // A delayed result from the displaced host cannot answer a command on the target stream.
    const targetInspect = instance.request(viewId, { op: 'inspect' }, signal())
    const targetStream = await openingTarget
    const targetCommand = nextCommand(targetStream)
    oldStream.resume()
    await oldEnded
    const targetId = (await targetCommand).id
    await request(`${instance.url}/bridge/result`, {
      method: 'POST',
      body: JSON.stringify({ id: oldId, ok: true, clean: true }),
      headers: jsonAuth(instance.secret),
    })
    const staleStatus = await openStream(instance.url, instance.secret, workspace, ['/old']).then(
      (stream) => {
        stream.destroy()
        return 200
      },
      (error: Error) => Number(error.message.replace('status ', '')),
    )
    expect(staleStatus).toBe(410)
    await request(`${instance.url}/bridge/result`, {
      method: 'POST',
      body: JSON.stringify({ id: targetId, ok: true, clean: false }),
      headers: jsonAuth(instance.secret),
    })
    expect(await targetInspect).toMatchObject({ ok: true, clean: false })
    // The retired host can report the new folder too; identity still fences it forever.
    for (let attempt = 0; attempt < 2; attempt++) {
      await expect(openStream(instance.url, instance.secret, workspace, ['/new'])).rejects.toThrow('status 410')
    }
  })

  it('enforces loopback token and routing', async () => {
    const instance = await bridge()
    instance.assign('11111111-1111-4111-8111-111111111111', '/managed/one.code-workspace', '1', '/managed')

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
    instance.assign(viewId, workspacePath, '1', '/managed')
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
    instance.assign(viewId, '/managed/one.code-workspace', '1', '/managed')
    await expect(instance.request(viewId, { op: 'focusTerminal' }, signal())).rejects.toThrow(/not connected/)
  })

  it('requires the explicitly expected directory before dispatching a transition', async () => {
    const instance = await bridge()
    const viewId = '11111111-1111-4111-8111-111111111111'
    instance.assign(viewId, '/managed/one.code-workspace', '1', '/managed')
    await openStream(instance.url, instance.secret, '/managed/one.code-workspace')
    await expect(
      instance.request(viewId, { op: 'changeDirectory', directory: '/target' }, signal()),
    ).rejects.toThrow('expected directory is invalid')
    await expect(
      instance.request(viewId, { op: 'changeDirectory', directory: '/target' }, signal(), {
        expectedDirectory: '/different',
      }),
    ).rejects.toThrow('expected directory is invalid')
  })

  it('resolves a command as cancelled when the caller aborts', async () => {
    const instance = await bridge()
    const viewId = '11111111-1111-4111-8111-111111111111'
    instance.assign(viewId, '/managed/one.code-workspace', '1', '/managed')
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
    instance.assign(viewId, '/managed/one.code-workspace', '1', '/managed')
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
    instance.assign(viewId, '/managed/one.code-workspace', '1', '/managed')
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

    const pending = instance.request(viewId, { op: 'changeDirectory', directory: '/managed' }, signal(), {
      expectedDirectory: '/managed',
    })
    await new Promise((resolve) => setTimeout(resolve, 20))
    const command = lines.find((line) => line.type === 'command') as { id: string; op: string }
    expect(command.op).toBe('changeDirectory')

    await request(`${instance.url}/bridge/result`, {
      method: 'POST',
      body: JSON.stringify({ id: command.id, ok: true, directory: '/managed' }),
      headers: jsonAuth(instance.secret),
    })
    expect(await pending).toEqual({ ok: true, code: undefined, message: undefined, directory: '/managed' })

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
    instance.assign(viewId, '/managed/one.code-workspace', '1', '/managed')
    await openStream(instance.url, instance.secret, '/managed/one.code-workspace')

    const pending = instance.request(viewId, { op: 'focusTerminal' }, signal())
    await instance.close()
    expect(await pending).toEqual({ ok: false, message: 'ide bridge closed' })
  })

  it('rejects a directory change whose reply reports a different folder', async () => {
    const instance = await bridge()
    const viewId = '11111111-1111-4111-8111-111111111111'
    instance.assign(viewId, '/managed/one.code-workspace', '1', '/managed')
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
    instance.assign(viewId, '/managed/one.code-workspace', '1', '/managed')
    const stream = await openStream(instance.url, instance.secret, '/managed/one.code-workspace')

    // The extension host restarts after accepting the command and never replies on the old stream.
    const changed = instance.request(viewId, { op: 'changeDirectory', directory: '/other' }, signal(), {
      expectedDirectory: '/other',
    })
    await new Promise((resolve) => setTimeout(resolve, 20))
    stream.destroy()
    await new Promise((resolve) => setTimeout(resolve, 20))

    // A new host reports the new folder, which is the actual confirmation.
    await openStream(
      instance.url, instance.secret, '/managed/one.code-workspace', ['/other'],
      'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
    )
    expect(await changed).toEqual({ ok: true, directory: '/other' })

    // A reconnect that never reports the folder is a failure, not a silent success.
    const rejected = instance.request(viewId, { op: 'changeDirectory', directory: '/third' }, signal(), {
      expectedDirectory: '/third',
    })
    await new Promise((resolve) => setTimeout(resolve, 20))
    await openStream(
      instance.url, instance.secret, '/managed/one.code-workspace', ['/other'],
      'cccccccc-cccc-4ccc-8ccc-cccccccccccc',
    )
    expect(await rejected).toEqual({ ok: false, message: 'directory change was not applied' })

    // A command that needs an answer still fails once the window is gone.
    const stream3 = await openStream(
      instance.url, instance.secret, '/managed/one.code-workspace', ['/other'],
      'cccccccc-cccc-4ccc-8ccc-cccccccccccc',
    )
    const focused = instance.request(viewId, { op: 'focusTerminal' }, signal())
    await new Promise((resolve) => setTimeout(resolve, 20))
    stream3.destroy()
    expect(await focused).toEqual({ ok: false, message: 'ide window closed' })
  })

  it('fails the pty registration when supervision throws', async () => {
    const instance = await bridge()
    const viewId = '11111111-1111-4111-8111-111111111111'
    instance.assign(viewId, '/managed/one.code-workspace', '1', '/managed')
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
    instance.assign(viewId, '/managed/one.code-workspace', '1', '/managed')
    instance.assign(viewId, '/managed/two.code-workspace', '2', '/managed')
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
    instance.assign(viewId, '/managed/one.code-workspace', '1', '/managed')

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
    instance.assign(viewId, '/managed/one.code-workspace', '1', '/managed')

    const waiting = instance.waitForConnection(viewId, signal())
    await instance.close()
    await expect(waiting).rejects.toThrow('ide bridge closed')
  })

  it('fails a pending command when its view is revoked', async () => {
    const instance = await bridge()
    const viewId = '11111111-1111-4111-8111-111111111111'
    instance.assign(viewId, '/managed/one.code-workspace', '1', '/managed')
    await openStream(instance.url, instance.secret, '/managed/one.code-workspace')

    const pending = instance.request(viewId, { op: 'focusTerminal' }, signal())
    await new Promise((resolve) => setTimeout(resolve, 20))
    instance.revoke(viewId)
    expect(await pending).toEqual({ ok: false, code: 'VIEW_GONE', message: 'ide window was closed' })
  })
})
