import { createServer } from 'node:http'
import { createHash } from 'node:crypto'
import { mkdir, mkdtemp, readFile, rm, stat, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { Readable, Writable } from 'node:stream'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { DaemonConfig, ManagedProcess, ProcessExit, ProcessSpec, ProcessSupervisor } from '../src/contracts.js'
import {
  HANDOFF_FILE,
  MANAGED_ENTRY,
  PERMIT_FILE,
  RESULT_FILE,
  RUNTIME_MANIFEST,
  STREAM_LIMIT_BYTES,
  UNIX_UPDATE_SCRIPT_NAME,
  WINDOWS_UPDATE_SCRIPT_NAME,
  artifactFileName,
  artifactUrl,
  checksumUrl,
  createUpdateHandler,
  detachedLaunchCommand,
  productionDependencies,
  releaseArchFor,
  releaseOsFor,
  releasePlatformFor,
  type ReleaseArch,
  type ReleaseOs,
  type UpdateDependencies,
} from '../src/update.js'
import { createNativeSupervisorFixture } from './support/native-supervisor.js'

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
  options: { os?: ReleaseOs; arch?: ReleaseArch; checksum?: string; entry?: boolean; manifestVersion?: string; manifestPlatform?: boolean; launchExit?: number } = {},
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
        await writeFile(destination, `${digest}  ${artifactFileName(VERSION, os, arch)}\n`, { mode: 0o600 })
        return
      }
      await writeFile(destination, ARCHIVE, { mode: 0o600 })
    },
    async extractArchive(_archive, destination) {
      await mkdir(path.join(destination, 'runtime', 'code-server', 'lib'), { recursive: true })
      if (options.entry !== false) {
        await writeFile(path.join(destination, MANAGED_ENTRY), '// staged entry')
      }
      const manifest: Record<string, unknown> = {
        schemaVersion: 1,
        daemonVersion: options.manifestVersion ?? VERSION,
        codeServerVersion: '9.9.9-kk.2',
      }
      if (options.manifestPlatform !== false) {
        manifest.codeServer = {
          [`${os}-${arch}`]: {
            url: `https://github.com/fengwk/code-server/releases/download/v9.9.9-kk.2/code-server-9.9.9-kk.2-${os}-${arch}.tar.gz`,
            sha256: 'a'.repeat(64),
          },
        }
      }
      if (options.manifestPlatform !== false) {
        await mkdir(path.join(destination, 'runtime', 'code-server', 'out', 'node'), { recursive: true })
        await writeFile(path.join(destination, 'runtime', 'code-server', 'out', 'node', 'entry.js'), '')
      }
      await writeFile(path.join(destination, RUNTIME_MANIFEST), JSON.stringify(manifest))
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

/**
 * 受管文件的隐私边界：POSIX 上必须只有属主可读写；Windows 没有 POSIX mode 位，隐私由原生 ACL 校验
 * （verifyPrivateFile）承担，因此这里只断言文件真实存在。
 */
async function expectOwnerOnlyFile(target: string): Promise<void> {
  expect((await stat(target)).isFile()).toBe(true)
  if (process.platform !== 'win32') {
    expect((await stat(target)).mode & 0o777).toBe(0o600)
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

async function readHandoff(dir: string): Promise<Record<string, unknown>> {
  return JSON.parse(await readFile(path.join(dir, 'updates', OPERATION_ID, HANDOFF_FILE), 'utf8')) as Record<string, unknown>
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
    expect(await readHandoff(dataDir)).toMatchObject({
      state: 'ACCEPTED',
      operationId: OPERATION_ID,
      targetVersion: VERSION,
      artifactSha256: ARCHIVE_SHA,
    })
    const operationDir = path.join(dataDir, 'updates', OPERATION_ID)
    const script = path.join(operationDir, UNIX_UPDATE_SCRIPT_NAME)
    await expectOwnerOnlyFile(script)
    expect((await stat(path.join(operationDir, PERMIT_FILE))).isFile()).toBe(true)
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

  it('records a failed launch as FAILED and never replays it as prepared', async () => {
    const h = harness({ launchExit: 1 })
    handler = createUpdateHandler(h.deps)
    const outcome = await handler(command(), config(dataDir), new AbortController().signal)
    expect(outcome.phase).toBe('FAILED')
    expect(await readHandoff(dataDir)).toMatchObject({ state: 'FAILED' })
    const retry = await handler(command(), config(dataDir), new AbortController().signal)
    expect(retry.phase).toBe('FAILED')
    expect(h.fetchCount()).toBe(2)
  })

  it('rejects a staged manifest without this platform runtime', async () => {
    const h = harness({ manifestPlatform: false })
    const outcome = await createUpdateHandler(h.deps)(command(), config(dataDir), new AbortController().signal)
    expect(outcome.phase).toBe('FAILED')
    expect(outcome.message).toContain('platform runtime')
  })

  it('rejects a staged runtime that is not the frozen fork asset', async () => {
    const h = harness({ os: 'macos', arch: 'arm64' })
    const deps = h.deps
    const rewritten = createUpdateHandler({
      ...deps,
      async extractArchive(archive, destination, signal) {
        await deps.extractArchive(archive, destination, signal)
        const manifestPath = path.join(destination, RUNTIME_MANIFEST)
        const manifest = JSON.parse(await readFile(manifestPath, 'utf8')) as {
          codeServer: Record<string, { url: string }>
        }
        manifest.codeServer['macos-arm64'].url =
          'https://github.com/coder/code-server/releases/download/v9.9.9-kk.2/code-server-9.9.9-kk.2-macos-arm64.tar.gz'
        await writeFile(manifestPath, JSON.stringify(manifest))
      },
    })
    const outcome = await rewritten(
      command({ artifactUrl: artifactUrl(VERSION, 'macos', 'arm64') }),
      config(dataDir),
      new AbortController().signal,
    )
    expect(outcome.phase).toBe('FAILED')
    expect(outcome.message).toContain('not the frozen fork release')
  })

  it('accepts a macOS staged runtime with the frozen node and entry', async () => {
    const h = harness({ os: 'macos', arch: 'amd64' })
    const outcome = await createUpdateHandler(h.deps)(
      command({ artifactUrl: artifactUrl(VERSION, 'macos', 'amd64') }),
      config(dataDir),
      new AbortController().signal,
    )
    expect(outcome.phase).toBe('PREPARED')
  })

  it('surfaces a non-ENOENT inspection failure instead of treating it as missing', async () => {
    const root = await mkdtemp(path.join(tmpdir(), 'kk-update-'))
    const file = path.join(root, 'not-a-directory')
    await writeFile(file, 'x')
    try {
      const outcome = await createUpdateHandler(harness().deps)(
        command(),
        config(path.join(file, 'child')),
        new AbortController().signal,
      )
      expect(outcome.phase).toBe('FAILED')
      expect(outcome.message).toContain('could not be inspected')
    } finally {
      await rm(root, { recursive: true, force: true })
    }
  })

  it('rejects a staged manifest without an explicit daemon version', async () => {
    const h = harness({ manifestVersion: undefined })
    const deps = h.deps
    const handlerWithoutFallback = createUpdateHandler({
      ...deps,
      async extractArchive(archive, destination, signal) {
        await deps.extractArchive(archive, destination, signal)
        await writeFile(
          path.join(destination, RUNTIME_MANIFEST),
          JSON.stringify({ schemaVersion: 1, version: VERSION, codeServer: { 'linux-amd64': {} } }),
        )
      },
    })
    const outcome = await handlerWithoutFallback(command(), config(dataDir), new AbortController().signal)
    expect(outcome.phase).toBe('FAILED')
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

  it('rejects a handoff recorded for a different operation', async () => {
    await withLayout(async dir => {
      const updates = path.join(dir, 'updates', OPERATION_ID)
      await mkdir(updates, { recursive: true })
      await writeFile(
        path.join(updates, HANDOFF_FILE),
        JSON.stringify({ state: 'ACCEPTED', operationId: '22222222-2222-4222-8222-222222222222' }),
      )
      const h = harness()
      const outcome = await createUpdateHandler(h.deps)(command(), config(dir), new AbortController().signal)
      expect(outcome.phase).toBe('FAILED')
      expect(h.fetchCount()).toBe(0)
    })
  })
})

describe('detached launch command', () => {
  it('uses a transient systemd user unit on Linux and passes all three directories', () => {
    const line = detachedLaunchCommand('linux', OPERATION_ID, '/u/script.sh', '/u/op/extract', '/u/lib')
    expect(line[0]).toBe('systemd-run')
    expect(line).toContain('--user')
    expect(line).toContain(`--unit=kk-studio-daemon-update-${OPERATION_ID}`)
    expect(line.slice(-4)).toEqual(['/u/script.sh', '/u/op', '/u/op/extract', '/u/lib'])
  })

  it('submits a transient launchd job on macOS and passes all three directories', () => {
    const line = detachedLaunchCommand('macos', OPERATION_ID, '/u/script.sh', '/u/op/extract', '/u/lib')
    expect(line.slice(0, 4)).toEqual(['launchctl', 'submit', '-l', `kk-studio-daemon-update-${OPERATION_ID}`])
    expect(line.slice(-4)).toEqual(['/u/script.sh', '/u/op', '/u/op/extract', '/u/lib'])
  })

  it('registers a one-shot scheduled task on Windows through an encoded command', () => {
    const line = detachedLaunchCommand('windows', OPERATION_ID, 'C:/u/script.ps1', 'C:/u/op/extract', 'C:/u/lib')
    expect(line.slice(0, 4)).toEqual(['powershell', '-NoProfile', '-NonInteractive', '-EncodedCommand'])
    const script = Buffer.from(line[4] ?? '', 'base64').toString('utf16le')
    expect(script).toContain(`kk-studio-daemon-update-${OPERATION_ID}`)
    expect(script).toContain('-InstalledDir')
    expect(script).toContain('Register-ScheduledTask')
    expect(script).toContain('Start-ScheduledTask')
  })

  it('escapes single quotes in Windows paths without breaking the encoded command', () => {
    const line = detachedLaunchCommand('windows', OPERATION_ID, "C:/u/it's/script.ps1", "C:/u/it's/extract", 'C:/u/lib')
    const script = Buffer.from(line[4] ?? '', 'base64').toString('utf16le')
    expect(script).toContain("''")
  })
})

describe('release platform mapping', () => {
  it('maps only covered operating systems and architectures', () => {
    expect(releaseOsFor('linux')).toBe('linux')
    expect(releaseOsFor('darwin')).toBe('macos')
    expect(releaseOsFor('win32')).toBe('windows')
    expect(() => releaseOsFor('freebsd')).toThrow()
    expect(releaseArchFor('x64')).toBe('amd64')
    expect(releaseArchFor('arm64')).toBe('arm64')
    expect(() => releaseArchFor('ia32')).toThrow()
  })

  it('rejects a platform pair that has no published artifact', () => {
    expect(releasePlatformFor('linux', 'x64')).toEqual({ os: 'linux', arch: 'amd64' })
    expect(releasePlatformFor('darwin', 'arm64')).toEqual({ os: 'macos', arch: 'arm64' })
    expect(releasePlatformFor('win32', 'x64')).toEqual({ os: 'windows', arch: 'amd64' })
    // Windows 只发布 amd64：绝不允许由架构推导出一份并不存在的 windows-arm64 制品。
    expect(() => releasePlatformFor('win32', 'arm64')).toThrow('the host platform is not covered')
    expect(() => releasePlatformFor('freebsd', 'x64')).toThrow()
    expect(() => releasePlatformFor('linux', 'ia32')).toThrow()
  })

  it('fails closed when the same operation id is prepared for another platform', async () => {
    await withLayout(async dir => {
      const updates = path.join(dir, 'updates', OPERATION_ID)
      await mkdir(updates, { recursive: true })
      await writeFile(
        path.join(updates, HANDOFF_FILE),
        JSON.stringify({ state: 'ACCEPTED', operationId: OPERATION_ID, targetVersion: VERSION }),
      )
      const h = harness()
      const outcome = await createUpdateHandler(h.deps)(command(), config(dir), new AbortController().signal)
      expect(outcome.phase).toBe('FAILED')
      expect(h.fetchCount()).toBe(0)
    })
  })
})

describe('production dependencies', () => {
  it('runs a child process and captures its streams', async () => {
    const processes = createNativeSupervisorFixture()
    try {
      const deps = productionDependencies(processes)
      const result = await deps.run(process.execPath, ['-e', 'process.stdout.write("hi")'], {})
      expect(result.code).toBe(0)
      expect(result.stdout).toBe('hi')
    } finally {
      await processes.close()
    }
  })

  it('reports a non-zero child exit code', async () => {
    const processes = createNativeSupervisorFixture()
    try {
      const deps = productionDependencies(processes)
      const result = await deps.run(process.execPath, ['-e', 'process.exit(3)'], {})
      expect(result.code).toBe(3)
    } finally {
      await processes.close()
    }
  })

  it('rejects when the command cannot be spawned', async () => {
    const processes = createNativeSupervisorFixture()
    try {
      const deps = productionDependencies(processes)
      await expect(deps.run('kk-studio-nonexistent-binary', [], {})).rejects.toThrow()
    } finally {
      await processes.close()
    }
  })

  it('terminates a child that exceeds its timeout and awaits convergence', async () => {
    const processes = createNativeSupervisorFixture()
    try {
      const deps = productionDependencies(processes)
      const result = await deps.run(process.execPath, ['-e', 'setTimeout(() => {}, 5000)'], { timeoutMillis: 50 })
      expect(result.code).toBeNull()
    } finally {
      await processes.close()
    }
  })

  it('terminates a child when the caller cancels', async () => {
    const processes = createNativeSupervisorFixture()
    try {
      const deps = productionDependencies(processes)
      const controller = new AbortController()
      const running = deps.run(process.execPath, ['-e', 'setTimeout(() => {}, 5000)'], { signal: controller.signal })
      controller.abort()
      const result = await running
      expect(result.code).toBeNull()
    } finally {
      await processes.close()
    }
  })

  it('keeps each stream bounded while still draining it', async () => {
    const processes = createNativeSupervisorFixture()
    try {
      const deps = productionDependencies(processes)
      const result = await deps.run(
        process.execPath,
        ['-e', 'process.stdout.write("x".repeat(200000))'],
        {},
      )
      expect(result.code).toBe(0)
      expect(result.stdout.length).toBe(STREAM_LIMIT_BYTES)
    } finally {
      await processes.close()
    }
  })

  it('extracts a real tar.gz archive into the destination', async () => {
    await withLayout(async dir => {
      const source = path.join(dir, 'source')
      await mkdir(source, { recursive: true })
      await writeFile(path.join(source, MANAGED_ENTRY), '// entry')
      const archive = path.join(dir, 'payload.tar.gz')
      const processes = createNativeSupervisorFixture()
      try {
        const deps = productionDependencies(processes)
        const tar = await deps.run('tar', ['-czf', archive, '-C', source, MANAGED_ENTRY], {})
        expect(tar.code).toBe(0)
        const destination = path.join(dir, 'out')
        await mkdir(destination, { recursive: true })
        await deps.extractArchive(archive, destination, new AbortController().signal)
        expect(await readFile(path.join(destination, MANAGED_ENTRY), 'utf8')).toBe('// entry')
      } finally {
        await processes.close()
      }
    })
  })

  // Windows 上没有 /etc/hosts，且该夹具依赖 POSIX 绝对路径与 tar -P 语义，因此只在 POSIX 平台断言。
  it.runIf(process.platform !== 'win32')('rejects a real archive with an escaping entry', async () => {
    await withLayout(async dir => {
      const source = path.join(dir, 'source')
      await mkdir(source, { recursive: true })
      await writeFile(path.join(source, 'main.js'), '// entry')
      const archive = path.join(dir, 'payload.tar.gz')
      const processes = createNativeSupervisorFixture()
      try {
        const deps = productionDependencies(processes)
        const tar = await deps.run('tar', ['-czf', archive, '-P', '-C', source, '/etc/hosts'], {})
        expect(tar.code).toBe(0)
        const destination = path.join(dir, 'out')
        await mkdir(destination, { recursive: true })
        await expect(
          deps.extractArchive(archive, destination, new AbortController().signal),
        ).rejects.toThrow()
      } finally {
        await processes.close()
      }
    })
  })

  it('downloads a response body to a private file and fails on an error status', async () => {
    await withLayout(async dir => {
      const server = createServer((request, response) => {
        if (request.url === '/ok') {
          response.writeHead(200)
          response.end('payload')
        } else if (request.url === '/big') {
          response.writeHead(200)
          response.end('x'.repeat(4096))
        } else {
          response.writeHead(404)
          response.end()
        }
      })
      await new Promise<void>(resolve => server.listen(0, '127.0.0.1', resolve))
      const address = server.address()
      const port = typeof address === 'object' && address !== null ? address.port : 0
      const processes = createNativeSupervisorFixture()
      try {
        const deps = productionDependencies(processes)
        const destination = path.join(dir, 'download')
        await deps.fetchTo(`http://127.0.0.1:${port}/ok`, destination, new AbortController().signal, 1024)
        expect(await readFile(destination, 'utf8')).toBe('payload')
        await expectOwnerOnlyFile(destination)
        await expect(
          deps.fetchTo(`http://127.0.0.1:${port}/missing`, path.join(dir, 'nope'), new AbortController().signal, 1024),
        ).rejects.toThrow()
        await expect(
          deps.fetchTo(`http://127.0.0.1:${port}/big`, path.join(dir, 'oversize'), new AbortController().signal, 128),
        ).rejects.toThrow()
        await expect(stat(path.join(dir, 'oversize'))).rejects.toThrow()
      } finally {
        await processes.close()
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

function stubProcess(
  options: {
    stdout?: Readable
    stderr?: Readable
    exit?: Promise<ProcessExit>
    terminate?: () => Promise<void>
  } = {},
): ManagedProcess {
  return {
    pid: 4242,
    stdin: new Writable({
      write(_chunk, _encoding, callback) {
        callback()
      },
    }),
    stdout: options.stdout ?? Readable.from([]),
    stderr: options.stderr ?? Readable.from([]),
    exit: options.exit ?? Promise.resolve({ exitCode: 0, signal: null }),
    terminate: options.terminate ?? (async () => undefined),
  }
}

function supervisorOf(start: (spec: ProcessSpec) => Promise<ManagedProcess>): ProcessSupervisor {
  return {
    start,
    async close() {},
  }
}

describe('managed process lifecycle', () => {
  it('never starts a process when the caller already aborted', async () => {
    const start = vi.fn(async () => stubProcess())
    const controller = new AbortController()
    controller.abort()

    const result = await productionDependencies(supervisorOf(start)).run('node', ['--version'], {
      signal: controller.signal,
    })

    expect(result.code).toBeNull()
    expect(start).not.toHaveBeenCalled()
  })

  it('terminates and converges when the caller cancels during startup', async () => {
    let terminateCalls = 0
    let release!: () => void
    const gate = new Promise<void>(resolve => {
      release = resolve
    })
    const managed = stubProcess({
      exit: new Promise<ProcessExit>(() => undefined),
      terminate: async () => {
        terminateCalls += 1
      },
    })
    const controller = new AbortController()

    const running = productionDependencies(
      supervisorOf(async () => {
        await gate
        return managed
      }),
    ).run('node', [], { signal: controller.signal, timeoutMillis: 5000 })
    controller.abort()
    release()
    const result = await running

    expect(result.code).toBeNull()
    expect(terminateCalls).toBe(1)
    expect(managed.stdin.writableEnded).toBe(true)
  })

  it('surfaces a termination failure instead of reporting success', async () => {
    let terminateCalls = 0
    const managed = stubProcess({
      terminate: async () => {
        terminateCalls += 1
        throw new Error('the scope was not proven converged')
      },
    })

    await expect(
      productionDependencies(supervisorOf(async () => managed)).run('node', [], { timeoutMillis: 1000 }),
    ).rejects.toThrow('could not be proven converged')
    expect(terminateCalls).toBe(1)
  })

  it('closes stdin so a non-interactive command can exit', async () => {
    const managed = stubProcess()

    const result = await productionDependencies(supervisorOf(async () => managed)).run('node', [], {
      timeoutMillis: 1000,
    })

    expect(result.code).toBe(0)
    expect(managed.stdin.writableEnded).toBe(true)
  })

  it('counts process startup against the timeout budget', async () => {
    let terminateCalls = 0
    const managed = stubProcess({
      terminate: async () => {
        terminateCalls += 1
      },
    })

    const result = await productionDependencies(
      supervisorOf(async () => {
        await new Promise(resolve => setTimeout(resolve, 200))
        return managed
      }),
    ).run('node', [], { timeoutMillis: 50 })

    // deadline 在启动前生效：冷启动耗尽的预算不允许再进入等待自然退出。
    expect(result.code).toBeNull()
    expect(terminateCalls).toBe(1)
  })

  it('surfaces a genuine output failure instead of reporting success', async () => {
    const ioError = Object.assign(new Error('i/o error'), { code: 'EIO' })
    const stdout = Readable.from(
      (async function* () {
        yield Buffer.from('partial')
        throw ioError
      })(),
    )

    await expect(
      productionDependencies(supervisorOf(async () => stubProcess({ stdout }))).run('node', [], {
        timeoutMillis: 1000,
      }),
    ).rejects.toThrow('output could not be read')
  })

  it('absorbs a premature close caused by termination and still converges', async () => {
    let terminated = false
    let releaseStream: () => void = () => undefined
    let releaseExit!: (exit: ProcessExit) => void
    const stdout = Readable.from(
      (async function* () {
        yield Buffer.from('partial')
        await new Promise<void>(resolve => {
          releaseStream = resolve
        })
        throw Object.assign(new Error('premature close'), { code: 'ERR_STREAM_PREMATURE_CLOSE' })
      })(),
    )
    const managed = stubProcess({
      stdout,
      exit: new Promise<ProcessExit>(resolve => {
        releaseExit = resolve
      }),
      terminate: async () => {
        terminated = true
        releaseExit({ exitCode: null, signal: 'SIGKILL' })
        releaseStream()
      },
    })
    const controller = new AbortController()

    const running = productionDependencies(supervisorOf(async () => managed)).run('node', [], {
      signal: controller.signal,
      timeoutMillis: 5000,
    })
    await new Promise(resolve => setTimeout(resolve, 20))
    controller.abort()
    const result = await running

    expect(terminated).toBe(true)
    expect(result.code).toBeNull()
    expect(result.stdout).toBe('partial')
  })
})

describe('result file contract', () => {
  it('names the update result file used by the detached updater', () => {
    expect(RESULT_FILE).toBe('update-result.json')
  })
})
