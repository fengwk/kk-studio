import { mkdtempSync, writeFileSync, chmodSync, mkdirSync, readFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

import {
  DAEMON_VERSION,
  checkConfig,
  invalidConfigurationLine,
  loadDaemonConfig,
  printInfoCommand,
  readRegistrationToken,
  validateDirectArgs,
  resolveDistributionDir,
  resolveExecutable,
} from '../src/config.js'
import { createDaemonRoot } from './helpers.js'

function writeConfig(root: string, config: unknown): string {
  const path = join(root, 'daemon.json')
  writeFileSync(path, JSON.stringify(config), 'utf8')
  chmodSync(path, 0o600)
  return path
}

function tokenPath(root: string): string {
  return join(root, 'daemon.token')
}

describe('loadDaemonConfig', () => {
  it('loads a valid config and derives token/data/gateway paths', () => {
    const { root, configPath } = createDaemonRoot()
    const config = loadDaemonConfig(configPath, root)
    expect(config.configPath).toBe(configPath)
    expect(config.dataDir).toBe(root)
    expect(config.tokenPath).toBe(tokenPath(root))
    expect(config.studioUrl).toBe('https://studio.example.com')
    expect(config.gatewayUri).toBe('wss://studio.example.com/api/harness/environment-daemon/v1')
    expect(config.distributionDir).toBe(root)
    expect(config.note).toBeNull()
    expect(config.lsp).toEqual({})
  })

  it('maps http studio urls to ws and normalizes the origin', () => {
    const { root } = createDaemonRoot()
    const configPath = writeConfig(root, { studioUrl: 'http://127.0.0.1:8080/' })
    const config = loadDaemonConfig(configPath)
    expect(config.studioUrl).toBe('http://127.0.0.1:8080')
    expect(config.gatewayUri).toBe('ws://127.0.0.1:8080/api/harness/environment-daemon/v1')
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
      const configPath = writeConfig(root, { studioUrl })
      expect(() => loadDaemonConfig(configPath), studioUrl).toThrow()
    }
  })

  it('validates note structure and rejects controls and surrounding whitespace', () => {
    const { root } = createDaemonRoot()
    expect(loadDaemonConfig(writeConfig(root, { studioUrl: 'https://a.example.com', note: '  hello  ' })).note).toBe('hello')
    expect(() => loadDaemonConfig(writeConfig(root, { studioUrl: 'https://a.example.com', note: 'bad\nnote' }))).toThrow(
      /control characters/,
    )
    expect(() => loadDaemonConfig(writeConfig(root, { studioUrl: 'https://a.example.com', note: 'x'.repeat(600) }))).toThrow(
      /512/,
    )
  })

  it('rejects unknown fields and duplicate keys', () => {
    const { root } = createDaemonRoot()
    expect(() => loadDaemonConfig(writeConfig(root, { studioUrl: 'https://a.example.com', extra: 1 }))).toThrow(/unknown field/)
    const path = join(root, 'daemon.json')
    writeFileSync(path, '{"studioUrl":"https://a.example.com","studioUrl":"https://b.example.com"}', 'utf8')
    chmodSync(path, 0o600)
    expect(() => loadDaemonConfig(path)).toThrow(/duplicate keys/)
  })

  it('resolves an absolute bash executable and fails closed for a missing one', () => {
    const { root } = createDaemonRoot()
    const ok = loadDaemonConfig(writeConfig(root, { studioUrl: 'https://a.example.com', bashExecutable: process.execPath }))
    expect(ok.bashExecutable).toBe(resolveExecutable(process.execPath))
    expect(() =>
      loadDaemonConfig(writeConfig(root, { studioUrl: 'https://a.example.com', bashExecutable: '/definitely/missing/bash' })),
    ).toThrow(/must resolve to an executable/)
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
    const config = loadDaemonConfig(writeConfig(root, { studioUrl: 'https://a.example.com', lsp }))
    expect(config.lsp.java?.extensions).toEqual(['.java'])
    expect(config.lsp.java?.rootMarkers).toEqual(['pom.xml'])
    expect(() =>
      loadDaemonConfig(writeConfig(root, { studioUrl: 'https://a.example.com', lsp: { servers: {} } })),
    ).toThrow(/at least one server/)
    expect(() =>
      loadDaemonConfig(
        writeConfig(root, {
          studioUrl: 'https://a.example.com',
          lsp: { servers: { java: { command: ['java'], extensions: ['java'] } } },
        }),
      ),
    ).toThrow(/leading dot/)
    expect(() =>
      loadDaemonConfig(
        writeConfig(root, {
          studioUrl: 'https://a.example.com',
          lsp: { servers: { java: { command: ['java'], extensions: ['.java'], rootMarkers: ['../x'] } } },
        }),
      ),
    ).toThrow(/traversal/)
  })

  it('requires an owner-only sibling token file', () => {
    const { root, configPath } = createDaemonRoot()
    chmodSync(tokenPath(root), 0o644)
    expect(() => loadDaemonConfig(configPath)).toThrow(/group\/other permissions/)
    chmodSync(tokenPath(root), 0o600)
    const otherRoot = mkdtempSync(join(tmpdir(), 'kk-daemon-test-'))
    const otherConfig = writeConfig(otherRoot, { studioUrl: 'https://a.example.com' })
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
    expect(resolveDistributionDir('file:///opt/kk-studio/dist/main.js')).toBe('/opt/kk-studio')
    expect(resolveDistributionDir('file:///opt/kk-studio/src/main.ts')).toBe('/opt/kk-studio/src')
  })
})

describe('config file content is not echoed', () => {
  it('does not include raw studio url secrets in diagnostics', () => {
    const { root } = createDaemonRoot()
    const configPath = writeConfig(root, { studioUrl: 'https://user:pass@studio.example.com' })
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

  it('rejects non-object documents, non-string and blank fields', () => {
    const { root } = createDaemonRoot()
    expect(() => loadDaemonConfig(rawConfig(root, '[1,2]'))).toThrow(/must be an object/)
    expect(() => loadDaemonConfig(writeConfig(root, {}))).toThrow(/studioUrl.*nonblank/)
    expect(() => loadDaemonConfig(writeConfig(root, { studioUrl: 123 }))).toThrow(/studioUrl.*must be a string/)
    expect(() => loadDaemonConfig(writeConfig(root, { studioUrl: 'https://a.example.com', note: 5 }))).toThrow(
      /note.*must be a string/,
    )
    expect(() =>
      loadDaemonConfig(writeConfig(root, { studioUrl: 'https://a.example.com', bashExecutable: 5 })),
    ).toThrow(/bashExecutable.*must be a string/)
    expect(() =>
      loadDaemonConfig(writeConfig(root, { studioUrl: 'https://a.example.com', bashExecutable: '  ' })),
    ).toThrow(/bashExecutable.*nonblank/)
    expect(() =>
      loadDaemonConfig(writeConfig(root, { studioUrl: 'https://a.example.com', bashExecutable: 'bad\u0000cmd' })),
    ).toThrow(/control characters/)
  })

  it('rejects malformed lsp structures', () => {
    const { root } = createDaemonRoot()
    const base = 'https://a.example.com'
    const valid = { command: ['java'], extensions: ['.java'], rootMarkers: [], firstMatchMarkers: [] }
    expect(() => loadDaemonConfig(writeConfig(root, { studioUrl: base, lsp: [] }))).toThrow(/lsp.*must be an object/)
    expect(() =>
      loadDaemonConfig(writeConfig(root, { studioUrl: base, lsp: { servers: { java: valid }, extra: 1 } })),
    ).toThrow(/unknown field/)
    expect(() => loadDaemonConfig(writeConfig(root, { studioUrl: base, lsp: { servers: [] } }))).toThrow(
      /servers.*must be an object/,
    )
    expect(() =>
      loadDaemonConfig(writeConfig(root, { studioUrl: base, lsp: { servers: { 'bad id': valid } } })),
    ).toThrow(/server IDs must match/)
    expect(() =>
      loadDaemonConfig(writeConfig(root, { studioUrl: base, lsp: { servers: { java: 5 } } })),
    ).toThrow(/must be an object/)
    expect(() =>
      loadDaemonConfig(
        writeConfig(root, { studioUrl: base, lsp: { servers: { java: { ...valid, extra: 1 } } } }),
      ),
    ).toThrow(/unknown field/)
  })

  it('validates list requirements, emptiness and entries', () => {
    const { root } = createDaemonRoot()
    const base = 'https://a.example.com'
    const lsp = (server: Record<string, unknown>): string => writeConfig(root, { studioUrl: base, lsp: { servers: { java: server } } })
    expect(() => loadDaemonConfig(lsp({ extensions: ['.java'] }))).toThrow(/command.*nonempty array/)
    expect(() => loadDaemonConfig(lsp({ command: [], extensions: ['.java'] }))).toThrow(/command.*nonempty array/)
    // 可选列表缺失或为空都归一为空数组。
    const optional = loadDaemonConfig(lsp({ command: ['java'], extensions: ['.java'], rootMarkers: [] }))
    expect(optional.lsp.java?.rootMarkers).toEqual([])
    const missingOptional = loadDaemonConfig(lsp({ command: ['java'], extensions: ['.java'] }))
    expect(missingOptional.lsp.java?.rootMarkers).toEqual([])
    expect(() => loadDaemonConfig(lsp({ command: [''], extensions: ['.java'] }))).toThrow(/nonblank string/)
  })

  it('requires a regular token file readable by its owner', () => {
    // 用目录替换凭证文件：lstat 成功但不是普通文件。
    const { root: dirRoot } = createDaemonRoot()
    rmSync(tokenPath(dirRoot), { recursive: true, force: true })
    mkdirSync(tokenPath(dirRoot))
    expect(() => loadDaemonConfig(join(dirRoot, 'daemon.json'))).toThrow(/not a link or directory/)

    // 把 token 换成 write-only（owner 不可读）。
    const { root: modeRoot } = createDaemonRoot()
    chmodSync(tokenPath(modeRoot), 0o200)
    expect(() => loadDaemonConfig(join(modeRoot, 'daemon.json'))).toThrow(/readable by its owner/)
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
})
