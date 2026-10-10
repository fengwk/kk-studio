import { createServer } from 'node:http'
import { createHash } from 'node:crypto'
import { mkdir, mkdtemp, readFile, rm, stat, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import type { DaemonConfig } from '../src/contracts.js'
import {
  HANDOFF_FILE,
  MANAGED_ENTRY,
  RUNTIME_MANIFEST,
  UNIX_UPDATE_SCRIPT_NAME,
  WINDOWS_UPDATE_SCRIPT_NAME,
  artifactFileName,
  artifactUrl,
  checksumUrl,
  createUpdateHandler,
  detachedLaunchCommand,
  productionDependencies,
  type ReleaseArch,
  type ReleaseOs,
  type UpdateDependencies,
} from '../src/update.js'

const VERSION = '1.0.12'
const OPERATION_ID = '11111111-1111-4111-8111-111111111111'
const ARCHIVE = Buffer.from('verified-distribution-archive-bytes')
const ARCHIVE_SHA = createHash('sha256').update(ARCHIVE).digest('hex')

interface Harness {
  deps: UpdateDependencies
  fetchCount(): number
  downloaded(): string[]
  runCalls(): { command: string; args: string[] }[]
  setPreflight(ok: boolean): void
}

function harness(
  options: { os?: ReleaseOs; arch?: ReleaseArch; checksum?: string; entry?: boolean; manifestVersion?: string; launchExit?: number } = {},
): Harness {
  const os = options.os ?? 'linux'
  const arch = options.arch ?? 'amd64'
  let fetchCount = 0
  const downloaded: string[] = []
  const runCalls: { command: string; args: string[] }[] = []
  let preflight = true
  const deps: UpdateDependencies = {
    releaseOs: os,
    releaseArch: arch,
    id: () => OPERATION_ID,
    async fetchTo(url, destination) {
      fetchCount += 1
      downloaded.push(url)
      if (url.endsWith('.sha256')) {
        const digest = options.checksum ?? ARCHIVE_SHA
        await writeFile(destination, `${digest}  ${artifactFileName(VERSION, os, arch)}\n`)
        return
      }
      await writeFile(destination, ARCHIVE)
    },
    async extractArchive(_archive, destination) {
      await mkdir(path.join(destination, 'runtime', 'code-server', 'lib'), { recursive: true })
      if (options.entry !== false) {
        await writeFile(path.join(destination, MANAGED_ENTRY), '// staged entry')
      }
      await writeFile(
        path.join(destination, RUNTIME_MANIFEST),
        JSON.stringify({ schemaVersion: 1, daemonVersion: options.manifestVersion ?? VERSION }),
      )
      await writeFile(
        path.join(destination, 'runtime', 'code-server', 'lib', os === 'windows' ? 'node.exe' : 'node'),
        '',
      )
    },
    async run(command, args) {
      runCalls.push({ command, args })
      if (args.includes('--check-config')) {
        return { code: preflight ? 0 : 1, stdout: preflight ? 'Daemon configuration is valid\n' : '', stderr: '' }
      }
      return { code: options.launchExit ?? 0, stdout: '', stderr: '' }
    },
  }
  return {
    deps,
    fetchCount: () => fetchCount,
    downloaded: () => downloaded,
    runCalls: () => runCalls,
    setPreflight: ok => {
      preflight = ok
    },
  }
}

function config(dataDir: string): DaemonConfig {
  return {
    configPath: path.join(dataDir, 'daemon.json'),
    dataDir,
    tokenPath: path.join(dataDir, 'daemon.token'),
    studioUrl: 'https://studio.example.com',
    gatewayUri: 'wss://studio.example.com/api/harness/environment-daemon/v1',
    bashExecutable: 'bash',
    note: null,
    lsp: {},
    distributionDir: path.join(dataDir, 'lib'),
  }
}

async function managedInstallation(dataDir: string): Promise<void> {
  await mkdir(path.join(dataDir, 'lib'), { recursive: true })
  await writeFile(path.join(dataDir, 'lib', MANAGED_ENTRY), '// installed entry')
  await writeFile(path.join(dataDir, 'daemon.json'), '{}')
}

function command(
  overrides: Partial<Record<'operationId' | 'targetVersion' | 'artifactUrl' | 'artifactSha256', string>> = {},
): Record<string, unknown> {
  return {
    operationId: OPERATION_ID,
    targetVersion: VERSION,
    artifactUrl: artifactUrl(VERSION, 'linux', 'amd64'),
    artifactSha256: ARCHIVE_SHA,
    ...overrides,
  }
}

describe('update handler', () => {
  let dataDir: string
  let handler: ReturnType<typeof createUpdateHandler>

  beforeEach(async () => {
    dataDir = await mkdtemp(path.join(tmpdir(), 'kk-update-'))
    await managedInstallation(dataDir)
    handler = createUpdateHandler(harness().deps)
  })

  afterEach(async () => {
    await rm(dataDir, { recursive: true, force: true })
  })

  it('prepares a verified official distribution and launches the detached updater', async () => {
    const h = harness()
    handler = createUpdateHandler(h.deps)
    const outcome = await handler(command(), config(dataDir), new AbortController().signal)
    expect(outcome).toMatchObject({ operationId: OPERATION_ID, phase: 'PREPARED', targetVersion: VERSION })
    expect(h.fetchCount()).toBe(2)
    expect(h.downloaded()[0]).toBe(artifactUrl(VERSION, 'linux', 'amd64'))
    expect(h.downloaded()[1]).toBe(`${artifactUrl(VERSION, 'linux', 'amd64')}.sha256`)
    const handoff = path.join(dataDir, 'updates', OPERATION_ID, HANDOFF_FILE)
    expect(JSON.parse(await readFile(handoff, 'utf8'))).toMatchObject({
      operationId: OPERATION_ID,
      targetVersion: VERSION,
      artifactSha256: ARCHIVE_SHA,
    })
    const script = path.join(dataDir, 'updates', OPERATION_ID, UNIX_UPDATE_SCRIPT_NAME)
    expect((await stat(script)).mode & 0o777).toBe(0o700)
    expect(h.runCalls().some(call => call.command === 'systemd-run')).toBe(true)
  })

  it('reuses a completed handoff for the same operation without a second download', async () => {
    const h = harness()
    handler = createUpdateHandler(h.deps)
    await handler(command(), config(dataDir), new AbortController().signal)
    const outcome = await handler(command(), config(dataDir), new AbortController().signal)
    expect(outcome.phase).toBe('PREPARED')
    expect(h.fetchCount()).toBe(2)
  })

  it('rejects an artifact URL that is not the official target for this platform', async () => {
    const outcome = await handler(
      command({ artifactUrl: artifactUrl(VERSION, 'macos', 'arm64') }),
      config(dataDir),
      new AbortController().signal,
    )
    expect(outcome.phase).toBe('FAILED')
    await expect(stat(path.join(dataDir, 'updates', OPERATION_ID, HANDOFF_FILE))).rejects.toThrow()
  })

  it('rejects a downloaded artifact whose SHA256 does not match the command', async () => {
    const outcome = await handler(
      command({ artifactSha256: '0'.repeat(64) }),
      config(dataDir),
      new AbortController().signal,
    )
    expect(outcome.phase).toBe('FAILED')
  })

  it('rejects a non-canonical operation id and echoes the safe value', async () => {
    const outcome = await handler(
      command({ operationId: 'NOT-A-UUID' }),
      config(dataDir),
      new AbortController().signal,
    )
    expect(outcome).toMatchObject({ phase: 'FAILED' })
    expect(outcome.message).not.toContain('NOT-A-UUID')
  })

  it('rejects when the managed installation layout is not present', async () => {
    await rm(path.join(dataDir, 'lib', MANAGED_ENTRY), { force: true })
    const outcome = await handler(command(), config(dataDir), new AbortController().signal)
    expect(outcome.phase).toBe('FAILED')
  })

  it('rejects when the staged distribution refuses the existing configuration', async () => {
    const h = harness()
    h.setPreflight(false)
    handler = createUpdateHandler(h.deps)
    const outcome = await handler(command(), config(dataDir), new AbortController().signal)
    expect(outcome.phase).toBe('FAILED')
    await expect(stat(path.join(dataDir, 'updates', OPERATION_ID, HANDOFF_FILE))).rejects.toThrow()
  })

  it('uses the Windows script and scheduled-task launcher on Windows', async () => {
    const h = harness({ os: 'windows' })
    handler = createUpdateHandler(h.deps)
    const outcome = await handler(
      command({ artifactUrl: artifactUrl(VERSION, 'windows', 'amd64') }),
      config(dataDir),
      new AbortController().signal,
    )
    expect(outcome.phase).toBe('PREPARED')
    expect((await stat(path.join(dataDir, 'updates', OPERATION_ID, WINDOWS_UPDATE_SCRIPT_NAME))).isFile()).toBe(true)
    expect(h.runCalls().some(call => call.command === 'powershell')).toBe(true)
  })
})

async function withLayout<T>(run: (dir: string) => Promise<T>): Promise<T> {
  const dir = await mkdtemp(path.join(tmpdir(), 'kk-update-'))
  await managedInstallation(dir)
  try {
    return await run(dir)
  } finally {
    await rm(dir, { recursive: true, force: true })
  }
}

describe('update command validation', () => {
  it('rejects unknown fields without echoing values', async () => {
    await withLayout(async dir => {
      const handler = createUpdateHandler(harness().deps)
      const outcome = await handler(
        { ...command(), extra: 'leak' },
        config(dir),
        new AbortController().signal,
      )
      expect(outcome.phase).toBe('FAILED')
      expect(outcome.message).toContain('unexpected field')
      expect(outcome.message).not.toContain('leak')
    })
  })

  it('rejects a non-text or blank field', async () => {
    await withLayout(async dir => {
      const handler = createUpdateHandler(harness().deps)
      const outcome = await handler(
        { ...command(), targetVersion: 42 },
        config(dir),
        new AbortController().signal,
      )
      expect(outcome.phase).toBe('FAILED')
    })
  })

  it('rejects a malformed artifact sha256', async () => {
    await withLayout(async dir => {
      const handler = createUpdateHandler(harness().deps)
      const outcome = await handler(
        command({ artifactSha256: 'xyz' }),
        config(dir),
        new AbortController().signal,
      )
      expect(outcome.phase).toBe('FAILED')
    })
  })
})

describe('staged distribution failures', () => {
  it('rejects a malformed official checksum file', async () => {
    await withLayout(async dir => {
      const h = harness({ checksum: 'not-a-digest' })
      const outcome = await createUpdateHandler(h.deps)(command(), config(dir), new AbortController().signal)
      expect(outcome.phase).toBe('FAILED')
    })
  })

  it('rejects when the official checksum file disagrees with the artifact', async () => {
    await withLayout(async dir => {
      const h = harness({ checksum: 'a'.repeat(64) })
      const outcome = await createUpdateHandler(h.deps)(command(), config(dir), new AbortController().signal)
      expect(outcome.phase).toBe('FAILED')
    })
  })

  it('rejects a staged distribution whose manifest version differs from the target', async () => {
    await withLayout(async dir => {
      const h = harness({ manifestVersion: '9.9.9' })
      const outcome = await createUpdateHandler(h.deps)(command(), config(dir), new AbortController().signal)
      expect(outcome.phase).toBe('FAILED')
    })
  })

  it('rejects a staged distribution missing its entry point', async () => {
    await withLayout(async dir => {
      const h = harness({ entry: false })
      const outcome = await createUpdateHandler(h.deps)(command(), config(dir), new AbortController().signal)
      expect(outcome.phase).toBe('FAILED')
    })
  })

  it('does not leave a reusable handoff when the detached launcher fails', async () => {
    await withLayout(async dir => {
      const h = harness({ launchExit: 1 })
      const outcome = await createUpdateHandler(h.deps)(command(), config(dir), new AbortController().signal)
      expect(outcome.phase).toBe('FAILED')
      await expect(stat(path.join(dir, 'updates', OPERATION_ID, HANDOFF_FILE))).rejects.toThrow()
    })
  })

  it('ignores a corrupt handoff and re-downloads', async () => {
    await withLayout(async dir => {
      const updates = path.join(dir, 'updates', OPERATION_ID)
      await mkdir(updates, { recursive: true })
      await writeFile(path.join(updates, HANDOFF_FILE), 'not json')
      const h = harness()
      const outcome = await createUpdateHandler(h.deps)(command(), config(dir), new AbortController().signal)
      expect(outcome.phase).toBe('PREPARED')
      expect(h.fetchCount()).toBe(2)
    })
  })
})

describe('detached launch command', () => {
  it('uses a transient systemd user unit on Linux', () => {
    const line = detachedLaunchCommand('linux', OPERATION_ID, '/u/script.sh', '/u/extract', '/u/lib')
    expect(line[0]).toBe('systemd-run')
    expect(line).toContain('--user')
    expect(line).toContain(`--unit=kk-studio-daemon-update-${OPERATION_ID}`)
    expect(line).toContain('/u/script.sh')
  })

  it('submits a transient launchd job on macOS', () => {
    const line = detachedLaunchCommand('macos', OPERATION_ID, '/u/script.sh', '/u/extract', '/u/lib')
    expect(line.slice(0, 4)).toEqual(['launchctl', 'submit', '-l', `kk-studio-daemon-update-${OPERATION_ID}`])
  })

  it('registers a one-shot scheduled task on Windows', () => {
    const line = detachedLaunchCommand('windows', OPERATION_ID, 'C:/u/script.ps1', 'C:/u/extract', 'C:/u/lib')
    expect(line[0]).toBe('powershell')
    expect(line[line.length - 1]).toContain(OPERATION_ID)
    expect(line[line.length - 1]).toContain('-InstalledDir')
  })
})

describe('production dependencies', () => {
  it('runs a child process and captures its streams', async () => {
    const deps = productionDependencies()
    const result = await deps.run(process.execPath, ['-e', 'process.stdout.write("hi")'], {})
    expect(result.code).toBe(0)
    expect(result.stdout).toBe('hi')
  })

  it('reports a non-zero child exit code', async () => {
    const deps = productionDependencies()
    const result = await deps.run(process.execPath, ['-e', 'process.exit(3)'], {})
    expect(result.code).toBe(3)
  })

  it('rejects when the command cannot be spawned', async () => {
    const deps = productionDependencies()
    await expect(deps.run('kk-studio-nonexistent-binary', [], {})).rejects.toThrow()
  })

  it('kills a child that exceeds its timeout', async () => {
    const deps = productionDependencies()
    const result = await deps.run(process.execPath, ['-e', 'setTimeout(() => {}, 5000)'], { timeoutMillis: 50 })
    expect(result.code).toBeNull()
  })

  it('extracts a real tar.gz archive into the destination', async () => {
    await withLayout(async dir => {
      const source = path.join(dir, 'source')
      await mkdir(source, { recursive: true })
      await writeFile(path.join(source, MANAGED_ENTRY), '// entry')
      const archive = path.join(dir, 'payload.tar.gz')
      const deps = productionDependencies()
      const tar = await deps.run('tar', ['-czf', archive, '-C', source, MANAGED_ENTRY], {})
      expect(tar.code).toBe(0)
      const destination = path.join(dir, 'out')
      await mkdir(destination, { recursive: true })
      await deps.extractArchive(archive, destination, new AbortController().signal)
      expect(await readFile(path.join(destination, MANAGED_ENTRY), 'utf8')).toBe('// entry')
    })
  })

  it('downloads a response body to a private file and fails on an error status', async () => {
    await withLayout(async dir => {
      const server = createServer((request, response) => {
        if (request.url === '/ok') {
          response.writeHead(200)
          response.end('payload')
        } else {
          response.writeHead(404)
          response.end()
        }
      })
      await new Promise<void>(resolve => server.listen(0, '127.0.0.1', resolve))
      const address = server.address()
      const port = typeof address === 'object' && address !== null ? address.port : 0
      const deps = productionDependencies()
      try {
        const destination = path.join(dir, 'download')
        await deps.fetchTo(`http://127.0.0.1:${port}/ok`, destination, new AbortController().signal)
        expect(await readFile(destination, 'utf8')).toBe('payload')
        expect((await stat(destination)).mode & 0o777).toBe(0o600)
        await expect(
          deps.fetchTo(`http://127.0.0.1:${port}/missing`, path.join(dir, 'nope'), new AbortController().signal),
        ).rejects.toThrow()
      } finally {
        await new Promise<void>(resolve => server.close(() => resolve()))
      }
    })
  })

  it('derives artifact and checksum URLs from the version and platform', () => {
    const url = artifactUrl(VERSION, 'macos', 'arm64')
    expect(url).toBe(`${'https://github.com/fengwk/kk-studio/releases/download'}/v${VERSION}/${artifactFileName(VERSION, 'macos', 'arm64')}`)
    expect(checksumUrl(VERSION, 'macos', 'arm64')).toBe(`${url}.sha256`)
  })
})
