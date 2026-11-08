import { mkdtempSync, writeFileSync, chmodSync, mkdirSync, readFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join, resolve } from 'node:path'
import { pathToFileURL } from 'node:url'
import { describe, expect, it } from 'vitest'

import {
  DAEMON_VERSION,
  EnvironmentConfigurationError,
  checkConfig,
  invalidConfigurationLine,
  loadDaemonConfig,
  parseDaemonConfiguration,
  printInfoCommand,
  readRegistrationToken,
  resolveDaemonConfiguration,
  resolveDistributionDir,
  resolveExecutable,
  validateDirectArgs,
} from '../src/config.js'
import { currentPlatformKey } from '../src/release.js'
import { createDaemonRoot } from './helpers.js'

const ENVIRONMENT_ID = '11111111-2222-4333-8444-555555555555'

function writeConfig(root: string, config: unknown): string {
  const path = join(root, 'daemon.json')
  writeFileSync(path, JSON.stringify(config), 'utf8')
  chmodSync(path, 0o600)
  return path
}

function document(
  daemonConfig: Record<string, unknown> = { studioUrl: 'https://a.example.com' },
  overrides: Record<string, unknown> = {},
): Record<string, unknown> {
  return {
    environmentId: ENVIRONMENT_ID,
    version: '4',
    daemonConfig,
    ...overrides,
  }
}

function tokenPath(root: string): string {
  return join(root, 'daemon.token')
}

describe('loadDaemonConfig', () => {
  it('loads the canonical environment snapshot and derives token/data/gateway paths', () => {
    const { root, configPath } = createDaemonRoot()
    const config = loadDaemonConfig(configPath, root)
    expect(config.configPath).toBe(configPath)
    expect(config.dataDir).toBe(root)
    expect(config.tokenPath).toBe(tokenPath(root))
    expect(config.environmentId).toBe(ENVIRONMENT_ID)
    expect(config.configVersion).toBe(0n)
    expect(config.studioUrl).toBe('https://studio.example.com')
    expect(config.gatewayUri).toBe('wss://studio.example.com/ws/daemon')
    expect(config.distributionDir).toBe(root)
    expect(config.bashExecutableSetting).toBeNull()
    expect(config.bashExecutable).toBe(resolveExecutable('bash'))
    expect(config.proxyUrl).toBeNull()
    expect(config.lsp).toEqual({})
  })

  it('maps http studio urls to ws and normalizes the origin', () => {
    const { root } = createDaemonRoot()
    const configPath = writeConfig(root, document({ studioUrl: 'http://127.0.0.1:8080/' }))
    const config = loadDaemonConfig(configPath)
    expect(config.studioUrl).toBe('http://127.0.0.1:8080')
    expect(config.gatewayUri).toBe('ws://127.0.0.1:8080/ws/daemon')
  })

  it('rejects non-origin studio urls', () => {
    const { root } = createDaemonRoot()
    for (const studioUrl of [
      'ftp://studio.example.com',
      'https://user:pass@studio.example.com',
      'https://studio.example.com/path',
      'https://studio.example.com?q=1',
      'not-a-url',
      '',
    ]) {
      const configPath = writeConfig(root, document({ studioUrl }))
      expect(() => loadDaemonConfig(configPath), studioUrl).toThrow()
    }
  })

  it('rejects the legacy flat document and unknown daemonConfig fields', () => {
    const { root } = createDaemonRoot()
    expect(() =>
      loadDaemonConfig(writeConfig(root, { studioUrl: 'https://a.example.com' })),
    ).toThrow(/unknown field/)
    expect(() =>
      loadDaemonConfig(writeConfig(root, document({ studioUrl: 'https://a.example.com', note: 'hello' }))),
    ).toThrow(/unknown field/)
    expect(() =>
      loadDaemonConfig(writeConfig(root, { ...document(), extra: 1 })),
    ).toThrow(/unknown field/)
  })

  it('validates the environment id and canonical non-negative version string', () => {
    const { root } = createDaemonRoot()
    expect(() =>
      loadDaemonConfig(writeConfig(root, { ...document(), environmentId: 'not-a-uuid' })),
    ).toThrow(/canonical lowercase UUID/)
    expect(() =>
      loadDaemonConfig(writeConfig(root, { ...document(), environmentId: 'AAAAAAAA-2222-4333-8444-555555555555' })),
    ).toThrow(/canonical lowercase UUID/)
    for (const version of [0, -1, '01', '+1', ' 1', '1.0', '', null]) {
      expect(() => loadDaemonConfig(writeConfig(root, { ...document(), version })), String(version)).toThrow(
        /canonical non-negative decimal string|signed 64-bit/,
      )
    }
    expect(loadDaemonConfig(writeConfig(root, { ...document(), version: '9223372036854775807' })).configVersion).toBe(
      9223372036854775807n,
    )
    expect(() =>
      loadDaemonConfig(writeConfig(root, { ...document(), version: '9223372036854775808' })),
    ).toThrow(/signed 64-bit/)
  })

  it('rejects duplicate keys and non-object documents', () => {
    const { root } = createDaemonRoot()
    const path = join(root, 'daemon.json')
    writeFileSync(
      path,
      `{"environmentId":"${ENVIRONMENT_ID}","version":"1","daemonConfig":{"studioUrl":"https://a.example.com","studioUrl":"https://b.example.com"}}`,
      'utf8',
    )
    chmodSync(path, 0o600)
    expect(() => loadDaemonConfig(path)).toThrow(/duplicate keys/)
    writeFileSync(path, '[1,2]', 'utf8')
    expect(() => loadDaemonConfig(path)).toThrow(/must be an object/)
  })

  it('does not block bootstrap on a missing bash but rejects it when resolving a candidate', () => {
    const { root } = createDaemonRoot()
    const config = loadDaemonConfig(
      writeConfig(root, document({ studioUrl: 'https://a.example.com', bashExecutable: '/definitely/missing/bash' })),
    )
    expect(config.bashExecutable).toBeNull()
    expect(config.bashExecutableSetting).toBe('/definitely/missing/bash')
    expect(() =>
      resolveDaemonConfiguration(
        {
          configPath: config.configPath,
          dataDir: config.dataDir,
          tokenPath: config.tokenPath,
          distributionDir: config.distributionDir,
        },
        ENVIRONMENT_ID,
        4n,
        parseDaemonConfiguration({ studioUrl: 'https://a.example.com', bashExecutable: '/definitely/missing/bash' }),
      ),
    ).toThrow(EnvironmentConfigurationError)
    const ok = loadDaemonConfig(
      writeConfig(root, document({ studioUrl: 'https://a.example.com', bashExecutable: process.execPath })),
    )
    expect(ok.bashExecutable).toBe(resolveExecutable(process.execPath))
  })

  it('validates proxy urls strictly and stores null as explicit direct', () => {
    const { root } = createDaemonRoot()
    const config = loadDaemonConfig(
      writeConfig(root, document({ studioUrl: 'https://a.example.com', proxyUrl: 'http://127.0.0.1:7890' })),
    )
    expect(config.proxyUrl).toBe('http://127.0.0.1:7890')
    for (const proxyUrl of [
      'https://127.0.0.1:7890',
      'http://user:pass@127.0.0.1:7890',
      'http://127.0.0.1:7890/path',
      'socks5://127.0.0.1:1080',
      'http://127.0.0.1',
    ]) {
      expect(() =>
        loadDaemonConfig(writeConfig(root, document({ studioUrl: 'https://a.example.com', proxyUrl }))),
        proxyUrl,
      ).toThrow(/unauthenticated http:\/\/host:port/)
    }
  })

  it('validates the LSP server map', () => {
    const { root } = createDaemonRoot()
    const lsp = {
      servers: {
        java: {
          command: ['java'],
          extensions: [' .JAVA '],
          rootMarkers: ['pom.xml'],
          firstMatchMarkers: ['src'],
        },
      },
    }
    const config = loadDaemonConfig(writeConfig(root, document({ studioUrl: 'https://a.example.com', lsp })))
    expect(config.lsp.java?.extensions).toEqual(['.java'])
    expect(config.lsp.java?.rootMarkers).toEqual(['pom.xml'])
    expect(() =>
      loadDaemonConfig(writeConfig(root, document({ studioUrl: 'https://a.example.com', lsp: { servers: {} } }))),
    ).toThrow(/at least one server/)
    expect(() =>
      loadDaemonConfig(
        writeConfig(
          root,
          document({ studioUrl: 'https://a.example.com', lsp: { servers: { java: { command: ['java'], extensions: ['java'] } } } }),
        ),
      ),
    ).toThrow(/leading dot/)
    expect(() =>
      loadDaemonConfig(
        writeConfig(
          root,
          document({
            studioUrl: 'https://a.example.com',
            lsp: { servers: { java: { command: ['java'], extensions: ['.java'], rootMarkers: ['../x'] } } },
          }),
        ),
      ),
    ).toThrow(/traversal/)
  })

  it('requires an owner-only sibling token file', async () => {
    const { root, configPath } = createDaemonRoot()
    if (process.platform === 'win32') {
      // Windows 配置加载不解释 POSIX mode；外部读取必须由真实 DACL 读回拒绝。
      const { grantEveryoneRead } = await import('./support/fs-permissions.js')
      await grantEveryoneRead(tokenPath(root))
      const loaded = loadDaemonConfig(configPath)
      const { verifyPrivateFile } = await import('../src/native/index.js')
      await expect(verifyPrivateFile(loaded.tokenPath)).rejects.toThrow(/foreign principal/)
    } else {
      chmodSync(tokenPath(root), 0o644)
      expect(() => loadDaemonConfig(configPath)).toThrow(/group\/other permissions/)
      chmodSync(tokenPath(root), 0o600)
    }
    const otherRoot = mkdtempSync(join(tmpdir(), 'kk-daemon-test-'))
    const otherConfig = writeConfig(otherRoot, document())
    expect(() => loadDaemonConfig(otherConfig)).toThrow(/existing regular file/)
  })

  it('requires an absolute config path', () => {
    expect(() => loadDaemonConfig('relative/daemon.json')).toThrow(/absolute/)
    expect(() => loadDaemonConfig('--config')).toThrow(/absolute/)
  })
})

describe('checkConfig and token reading', () => {
  it('validates config and token without side effects', () => {
    const { configPath } = createDaemonRoot()
    expect(() => checkConfig(configPath)).not.toThrow()
  })

  it('reads a trimmed token and rejects an empty one', () => {
    const { root } = createDaemonRoot('  secret-token\n')
    const config = loadDaemonConfig(join(root, 'daemon.json'))
    expect(readRegistrationToken(config)).toBe('secret-token')
    writeFileSync(tokenPath(root), '   ', 'utf8')
    chmodSync(tokenPath(root), 0o600)
    expect(() => readRegistrationToken(config)).toThrow(/must not be empty/)
  })
})

describe('direct CLI arguments', () => {
  it('keeps unicode, empty, quoted and trailing-backslash arguments unchanged', () => {
    const args = ['--config', 'C:\\用户\\环境\\daemon.json', '', 'say "hi"', 'C:\\tail\\', '😀']
    expect(validateDirectArgs(args)).toBe(args)
  })

  it('rejects a non-array, a non-string element, and NUL without echoing the value', () => {
    expect(() => validateDirectArgs('x' as unknown as string[])).toThrow(/^invalid daemon argument transport$/)
    expect(() => validateDirectArgs(['--config', 5 as unknown as string])).toThrow(/^invalid daemon argument transport$/)
    expect(() => validateDirectArgs(['--config', 'a\0b'])).toThrow(/^invalid daemon argument transport$/)
  })

  it('prints info commands only for a single known flag', () => {
    expect(printInfoCommand(['--help']).handled).toBe(true)
    expect(printInfoCommand(['-h']).output).toContain('Usage: kk-studio-daemon')
    expect(printInfoCommand(['--version']).output).toBe(`kk-studio-daemon ${DAEMON_VERSION}\n`)
    expect(printInfoCommand(['--help', 'x']).handled).toBe(false)
    expect(printInfoCommand(['--config', 'x']).handled).toBe(false)
  })

  it('maps errors to a single safe diagnostic line', () => {
    expect(invalidConfigurationLine(new Error('daemon.studioUrl: must be an HTTP(S) origin'))).toBe(
      'Invalid daemon configuration: daemon.studioUrl: must be an HTTP(S) origin',
    )
    expect(invalidConfigurationLine(new Error('secret leaked https://user:pass@host'))).toBe(
      'Invalid daemon configuration: configuration is invalid',
    )
    expect(invalidConfigurationLine('not an error')).toBe('Invalid daemon configuration: configuration is invalid')
  })
})

describe('executable and distribution resolution', () => {
  it('resolves executables on PATH and absolute paths', () => {
    expect(resolveExecutable(process.execPath)).toBe(process.execPath)
    expect(resolveExecutable('definitely-not-on-path-xyz')).toBeNull()
    expect(resolveExecutable('   ')).toBeNull()
  })

  it('treats a dist directory as the compiled program parent', () => {
    // file URL 必须带当前平台的绝对路径；file:///opt/... 在 Windows 上没有盘符，fileURLToPath 会直接拒绝。
    const root = resolve(tmpdir(), 'kk-distribution-root')
    const distModule = pathToFileURL(join(root, 'dist', 'main.js')).href
    const sourceModule = pathToFileURL(join(root, 'src', 'main.ts')).href
    expect(resolveDistributionDir(distModule)).toBe(root)
    expect(resolveDistributionDir(sourceModule)).toBe(dirname(join(root, 'src', 'main.ts')))
  })
})

describe('config file content is not echoed', () => {
  it('does not include raw studio url secrets in diagnostics', () => {
    const { root } = createDaemonRoot()
    const configPath = writeConfig(root, document({ studioUrl: 'https://user:pass@studio.example.com' }))
    try {
      loadDaemonConfig(configPath)
      throw new Error('expected failure')
    } catch (error) {
      const line = invalidConfigurationLine(error)
      expect(line).not.toContain('pass')
      expect(readFileSync(configPath, 'utf8')).toContain('user:pass')
    }
  })
})

describe('config field validation branches', () => {
  function rawConfig(root: string, text: string): string {
    const path = join(root, 'daemon.json')
    writeFileSync(path, text, 'utf8')
    chmodSync(path, 0o600)
    return path
  }

  it('rejects an unreadable token file and a directory as the config', () => {
    expect(() => readRegistrationToken({ tokenPath: join(tmpdir(), 'kk-missing-token-xyz') })).toThrow(
      /cannot read registration token file/,
    )
    const { root } = createDaemonRoot()
    const dir = join(root, 'subdir')
    mkdirSync(dir)
    expect(() => loadDaemonConfig(dir)).toThrow(/cannot read valid JSON/)
  })

  it('rejects oversized config before reading it into memory', () => {
    const { root } = createDaemonRoot()
    const path = join(root, 'daemon.json')
    writeFileSync(
      path,
      `{"environmentId":"${ENVIRONMENT_ID}","version":"1","daemonConfig":{"studioUrl":"https://a.example.com","bashExecutable":"${'x'.repeat(1024 * 1024)}"}}`,
      'utf8',
    )
    chmodSync(path, 0o600)
    expect(() => loadDaemonConfig(path)).toThrow(/cannot read valid JSON/)
  })

  it('rejects non-object and wrong scalar fields at their nested paths', () => {
    const { root } = createDaemonRoot()
    expect(() => loadDaemonConfig(writeConfig(root, document({ studioUrl: 123 } as any)))).toThrow(
      /studioUrl.*must be a string/,
    )
    expect(() => loadDaemonConfig(writeConfig(root, document({ studioUrl: '' })))).toThrow(/studioUrl.*nonblank/)
    expect(() =>
      loadDaemonConfig(writeConfig(root, document({ studioUrl: 'https://a.example.com', bashExecutable: 5 } as any))),
    ).toThrow(/bashExecutable.*must be a string/)
    expect(() =>
      loadDaemonConfig(writeConfig(root, document({ studioUrl: 'https://a.example.com', bashExecutable: '  ' }))),
    ).toThrow(/bashExecutable.*nonblank/)
    expect(() =>
      loadDaemonConfig(
        writeConfig(root, document({ studioUrl: 'https://a.example.com', bashExecutable: 'bad\u0000cmd' })),
      ),
    ).toThrow(/control characters/)
  })

  it('rejects malformed lsp structures', () => {
    const { root } = createDaemonRoot()
    const base = 'https://a.example.com'
    const valid = { command: ['java'], extensions: ['.java'], rootMarkers: [], firstMatchMarkers: [] }
    expect(() => loadDaemonConfig(writeConfig(root, document({ studioUrl: base, lsp: [] })))).toThrow(
      /lsp.*must be an object/,
    )
    expect(() =>
      loadDaemonConfig(writeConfig(root, document({ studioUrl: base, lsp: { servers: { java: valid }, extra: 1 } }))),
    ).toThrow(/unknown field/)
    expect(() => loadDaemonConfig(writeConfig(root, document({ studioUrl: base, lsp: { servers: [] } })))).toThrow(
      /servers.*must be an object/,
    )
    expect(() =>
      loadDaemonConfig(writeConfig(root, document({ studioUrl: base, lsp: { servers: { 'bad id': valid } } }))),
    ).toThrow(/server IDs must match/)
    expect(() =>
      loadDaemonConfig(writeConfig(root, document({ studioUrl: base, lsp: { servers: { java: 5 } } }))),
    ).toThrow(/must be an object/)
    expect(() =>
      loadDaemonConfig(
        writeConfig(root, document({ studioUrl: base, lsp: { servers: { java: { ...valid, extra: 1 } } } })),
      ),
    ).toThrow(/unknown field/)
  })

  it('validates list requirements, emptiness and entries', () => {
    const { root } = createDaemonRoot()
    const base = 'https://a.example.com'
    const lsp = (server: Record<string, unknown>): string =>
      writeConfig(root, document({ studioUrl: base, lsp: { servers: { java: server } } }))
    expect(() => loadDaemonConfig(lsp({ extensions: ['.java'] }))).toThrow(/command.*nonempty array/)
    expect(() => loadDaemonConfig(lsp({ command: [], extensions: ['.java'] }))).toThrow(/command.*nonempty array/)
    // 可选列表缺失或为空都归一为空数组。
    const optional = loadDaemonConfig(lsp({ command: ['java'], extensions: ['.java'], rootMarkers: [] }))
    expect(optional.lsp.java?.rootMarkers).toEqual([])
    const missingOptional = loadDaemonConfig(lsp({ command: ['java'], extensions: ['.java'] }))
    expect(missingOptional.lsp.java?.rootMarkers).toEqual([])
    expect(() => loadDaemonConfig(lsp({ command: [''], extensions: ['.java'] }))).toThrow(/nonblank string/)
  })

  it('requires a regular token file readable by its owner', async () => {
    // 用目录替换凭证文件：lstat 成功但不是普通文件。
    const { root: dirRoot } = createDaemonRoot()
    rmSync(tokenPath(dirRoot), { recursive: true, force: true })
    mkdirSync(tokenPath(dirRoot))
    expect(() => loadDaemonConfig(join(dirRoot, 'daemon.json'))).toThrow(/not a link or directory/)

    const { root: modeRoot } = createDaemonRoot()
    if (process.platform === 'win32') {
      // 拒绝当前用户读取是 Windows 上对应的不可读凭证，读取时必须失败。
      const { denyRead } = await import('./support/fs-permissions.js')
      await denyRead(tokenPath(modeRoot))
      const config = loadDaemonConfig(join(modeRoot, 'daemon.json'))
      expect(() => readRegistrationToken(config)).toThrow(/cannot read/)
    } else {
      // 把 token 换成 write-only（owner 不可读）。
      chmodSync(tokenPath(modeRoot), 0o200)
      expect(() => loadDaemonConfig(join(modeRoot, 'daemon.json'))).toThrow(/readable by its owner/)
    }
  })

  it('rejects a directory or non-executable as the resolved executable', () => {
    const { root } = createDaemonRoot()
    expect(resolveExecutable(root)).toBeNull()
    const previous = process.env.PATH
    process.env.PATH = `:${previous ?? ''}`
    try {
      expect(resolveExecutable('definitely-not-on-path-xyz')).toBeNull()
    } finally {
      process.env.PATH = previous
    }
  })

  it('rejects unknown info flags', () => {
    expect(printInfoCommand(['--unknown']).handled).toBe(false)
  })

  it('maps platform and architecture to release keys correctly', () => {
    expect(currentPlatformKey('linux', 'x64')).toBe('linux-amd64')
    expect(currentPlatformKey('linux', 'arm64')).toBe('linux-arm64')
    expect(currentPlatformKey('darwin', 'x64')).toBe('macos-amd64')
    expect(currentPlatformKey('darwin', 'arm64')).toBe('macos-arm64')
    expect(currentPlatformKey('win32', 'x64')).toBe('windows-amd64')
    expect(() => currentPlatformKey('win32', 'arm64')).toThrow(/not covered/)
    expect(() => currentPlatformKey('freebsd' as any, 'x64')).toThrow(/unsupported.*operating system/)
    expect(() => currentPlatformKey('linux', 'ia32')).toThrow(/unsupported.*architecture/)
  })
})
