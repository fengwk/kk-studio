import { describe, expect, it } from 'vitest'
import { mkdtempSync, writeFileSync, readFileSync, existsSync, rmSync, readdirSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { spawnSync } from 'node:child_process'
import type { EnvironmentInstallConfigDTO } from '@/shared/api/contracts/ai-environment'
import { detectInstallOS, generateInstallCommand, generateUninstallCommand, validateInstallConfig } from './install-command'

const origin = 'https://studio.example.com'
const environmentId = "env/汉字'$(touch NEVER)"
const installCode = "code'$(touch NEVER)"

const config = (): EnvironmentInstallConfigDTO => ({
  operatingSystem: 'linux',
  // Backend forbids $ % ~ in javaHome; quotes/backticks still exercise shell escaping.
  javaHome: "/opt/jdk-21/汉字 '`",
  daemon: {
    studioUrl: 'https://daemon.example.com/',
    note: "汉字 ' \" $ ` $(touch NEVER) KK_STUDIO_INSTALL",
    bashExecutable: "/bin/汉字 '$`",
    lsp: {
      servers: {
        'server-1': {
          command: ['~/server', "quote' \"$` $(touch NEVER)"],
          extensions: [' .java'],
          rootMarkers: ['pom.xml'],
          firstMatchMarkers: ['.git'],
        },
      },
    },
  },
})

/** 用假 curl 执行实际粘贴的一行命令，记录请求 URL 并回放远端脚本。 */
function execute(command: string, script: string, exit = 0) {
  const dir = mkdtempSync(join(tmpdir(), 'kk-command-'))
  const requested = join(dir, 'requested.txt')
  const body = join(dir, 'body.sh')
  writeFileSync(body, script)
  writeFileSync(
    join(dir, 'curl'),
    `#!/bin/bash
set -euo pipefail
url=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    -o|-f|-s|-S|-L) shift ;;
    -fsSL) shift ;;
    *) url="$1"; shift ;;
  esac
done
printf '%s' "$url" > "$REQUESTED"
cat "$FIXTURE"
exit ${exit}
`,
    { mode: 0o700 },
  )
  try {
    const run = spawnSync('bash', [], { input: command,
      encoding: 'utf8', cwd: dir,
      env: {
        ...process.env, PATH: `${dir}:${process.env.PATH}`, REQUESTED: requested, FIXTURE: body,
      },
    })
    const url = existsSync(requested) ? readFileSync(requested, 'utf8') : ''
    expect(existsSync(join(dir, 'NEVER'))).toBe(false)
    return { run, url, dir }
  } finally {
    rmSync(dir, { recursive: true, force: true })
  }
}

describe('install command', () => {
  it.skipIf(process.platform === 'win32').each(['linux', 'macos'] as const)(
    'runs a one-line %s install that fetches the encoded environment URL and executes the remote script',
    os => {
      const input = { ...config(), operatingSystem: os }
      const command = generateInstallCommand(origin, environmentId, os, installCode)
      const expected = `https://studio.example.com/api/harness/environments/${encodeURIComponent(environmentId)}/install?code=${encodeURIComponent(installCode)}`
      expect(command).toBe(`(set -o pipefail; curl -fsSL $'${expected.replaceAll("'", "\\'")}' | bash)`)
      expect(command).not.toContain('\n')
      expect(command).not.toContain(input.daemon.studioUrl)
      expect(command).not.toContain('registrationToken')
      const { run, url } = execute(command, 'echo installed\n')
      expect(run.status).toBe(0)
      expect(run.stdout.trim()).toBe('installed')
      expect(url).toBe(expected)
      expect(existsSync(join(process.cwd(), 'NEVER'))).toBe(false)
    },
  )

  it.skipIf(process.platform === 'win32')(
    'returns the remote script status and does not treat a curl failure as success',
    () => {
      const command = generateInstallCommand(origin, 'env-1', 'linux', 'code-1')
      const failed = execute(command, 'echo ran; exit 17\n')
      expect(failed.run.status).toBe(17)
      expect(failed.run.stdout).toContain('ran')

      const dir = mkdtempSync(join(tmpdir(), 'kk-download-failure-'))
      try {
        writeFileSync(join(dir, 'curl'), '#!/bin/bash\nexit 8\n', { mode: 0o700 })
        const run = spawnSync('bash', [], { input: command,
          encoding: 'utf8', cwd: dir,
          env: { ...process.env, PATH: `${dir}:${process.env.PATH}` },
        })
        expect(run.status).toBe(8)
        expect(readdirSync(dir)).toEqual(['curl'])
        expect(run.stdout + run.stderr).not.toContain('token')
      } finally {
        rmSync(dir, { recursive: true, force: true })
      }
    },
  )

  it.skipIf(process.platform === 'win32').each(['linux', 'macos'] as const)(
    'runs a one-line %s uninstall against the saved operating system',
    os => {
      const command = generateUninstallCommand(os, origin)
      expect(command).toBe(
        `(set -o pipefail; curl -fsSL $'https://studio.example.com/api/harness/environments/uninstall/${os}' | bash)`,
      )
      const { run, url } = execute(command, 'echo uninstalled\n')
      expect(run.status).toBe(0)
      expect(url).toBe(`https://studio.example.com/api/harness/environments/uninstall/${os}`)
    },
  )

  it('quotes the Windows download and does not stage credentials on the client', () => {
    const input = config()
    input.operatingSystem = 'windows'
    input.javaHome = "C:\\Program Files\\Java\\it's"
    const command = generateInstallCommand(origin, environmentId, 'windows', installCode)
    const encoded = encodeURIComponent(environmentId).replaceAll("'", "''")
    const encodedCode = encodeURIComponent(installCode).replaceAll("'", "''")
    expect(command).not.toContain('\n')
    expect(command).toContain('Invoke-WebRequest -UseBasicParsing')
    expect(command).toContain('-ErrorAction Stop')
    expect(command).toContain('[scriptblock]::Create($script)')
    expect(command).toContain(`'https://studio.example.com/api/harness/environments/${encoded}/install?code=${encodedCode}'`)
    expect(command).not.toContain('registrationToken')
    expect(command).not.toContain('WriteAllText')
    expect(command).not.toContain('Set-Acl')
    expect(command).not.toContain('-JavaHome')
    expect(command).not.toContain(input.javaHome!)
    const uninstall = generateUninstallCommand('windows', origin)
    expect(uninstall).toContain(
      "'https://studio.example.com/api/harness/environments/uninstall/windows'",
    )
    expect(uninstall).not.toContain('registrationToken')
  })

  it('normalizes like the shared codec: origin, note, extension case and marker trim', () => {
    expect(validateInstallConfig({
      operatingSystem: 'linux',
      javaHome: '/opt/jdk',
      daemon: {
        studioUrl: 'HTTPS://Host:8443/',
        note: '  hi  ',
        bashExecutable: ' bash ',
        lsp: {
          servers: {
            Jdtls: {
              command: [' jdtls '],
              extensions: [' .JAVA '],
              rootMarkers: [' pom.xml '],
              firstMatchMarkers: ['.git'],
            },
          },
        },
      },
    })).toEqual({
      operatingSystem: 'linux',
      javaHome: '/opt/jdk',
      daemon: {
        studioUrl: 'https://host:8443',
        note: 'hi',
        bashExecutable: ' bash ',
        lsp: {
          servers: {
            Jdtls: {
              command: [' jdtls '],
              extensions: ['.java'],
              rootMarkers: ['pom.xml'],
              firstMatchMarkers: ['.git'],
            },
          },
        },
      },
    })
  })

  it('detects defaults without overriding saved settings', () => {
    expect(['Win32', 'MacIntel', 'Linux'].map(detectInstallOS)).toEqual(['windows', 'macos', 'linux'])
    expect(validateInstallConfig({ operatingSystem: 'linux', javaHome: null, daemon: { studioUrl: 'http://[::1]:8080/', note: null, bashExecutable: null } }))
      .toEqual({ operatingSystem: 'linux', javaHome: null,
        daemon: { studioUrl: 'http://[::1]:8080', note: null, bashExecutable: null, lsp: null } })
  })

  it.each(['ftp://host', 'https://user:pass@host', 'https://@host', 'https://host:', 'https://host:0',
    'https://host /', 'https://host/path', 'https://host?x', 'https://host#x', 'https://host:99999',
    'https://', 'https://host\\path', 'https://host\n', 'https://host//', 'https://[::1', 'https://host:abc',
    'https://例え.jp'])('rejects unsafe origin %s', studioUrl => {
    expect(() => validateInstallConfig({ ...config(), daemon: { studioUrl } }))
      .toThrow(/daemon\.studioUrl/)
  })

  it.each([
    { javaHome: 'relative' }, { javaHome: '/${JAVA_HOME}' }, { javaHome: '/opt/jdk~' },
    { javaHome: '/opt/jdk%20' }, { javaHome: '/opt/jdk ' }, { javaHome: ' /opt/jdk' },
    { javaHome: ' ' }, { javaHome: 12 },
    { operatingSystem: 'unknown' }, { daemon: { studioUrl: '' } },
    { daemon: { studioUrl: 'https://host', note: 'x'.repeat(513) } },
    { daemon: { studioUrl: 'https://host', note: '' } },
    { daemon: { studioUrl: 'https://host', note: '\u0085' } },
    { daemon: { studioUrl: 'https://host', note: 'x\u2028y' } },
    { daemon: { studioUrl: 'https://host', bashExecutable: 'a\nb' } },
    { daemon: { studioUrl: 'https://host', other: 1 } }, { other: 1 },
  ])('rejects invalid fields without echoing values %#', patch => {
    expect(() => validateInstallConfig({ ...config(), ...patch } as EnvironmentInstallConfigDTO))
      .toThrow(/^Invalid /)
  })

  it('rejects Windows javaHome with invalid characters or UNC gaps', () => {
    const input = config()
    input.operatingSystem = 'windows'
    for (const javaHome of ['java', 'C:java', 'C:\\Java|bad', '/opt/jdk', 'C:\\Java\\jdk~']) {
      expect(() => validateInstallConfig({ ...input, javaHome })).toThrow('installConfig.javaHome')
    }
    expect(validateInstallConfig({ ...input, javaHome: 'C:\\Java\\jdk-21' }).javaHome)
      .toBe('C:\\Java\\jdk-21')
    expect(validateInstallConfig({ ...input, javaHome: '\\\\server\\share\\jdk' }).javaHome)
      .toBe('\\\\server\\share\\jdk')
  })

  it.each([
    null, [], {}, { command: [] }, { command: [''] }, { command: [1] }, { command: ['x'] },
    { command: ['x'] , extensions: [] }, { command: ['x'], extensions: ['.'] },
    { command: ['x'], extensions: ['java'] }, { command: ['x'], extensions: ['.a/b'] },
    { command: ['x'], extensions: ['.a', ''] }, { command: ['x'], extensions: ['.a'], unknown: [] },
    { command: ['x'], extensions: ['.a'], rootMarkers: ['..'] },
    { command: ['x'], extensions: ['.a'], rootMarkers: ['.'] },
    { command: ['x'], extensions: ['.a'], rootMarkers: ['a//b'] },
    { command: ['x'], extensions: ['.a'], rootMarkers: ['a:'] },
    { command: ['x'], extensions: ['.a'], rootMarkers: ['/pom'] },
    { command: ['x'], extensions: ['.a'], firstMatchMarkers: ['C:\\pom'] },
    { command: ['x'], extensions: ['.a'], firstMatchMarkers: 'bad' },
  ])('rejects malformed LSP server %#', server => {
    const input = config()
    input.daemon.lsp = { servers: { server } } as EnvironmentInstallConfigDTO['daemon']['lsp']
    expect(() => validateInstallConfig(input)).toThrow(/^Invalid /)
  })

  it('requires extension and command arrays but keeps optional markers optional', () => {
    const input = config()
    input.daemon.lsp = { servers: { server: { command: ['x'], extensions: ['.ts'] } } }
    expect(validateInstallConfig(input).daemon.lsp!.servers.server)
      .toEqual({ command: ['x'], extensions: ['.ts'], rootMarkers: [], firstMatchMarkers: [] })
  })

  it.each(['__proto__', 'constructor', 'toString'])(
    'preserves the valid LSP server id %s as an own JSON field',
    id => {
      // server id 是外部键，不应改变对象原型或在写入 daemon.json 时丢失。
      const input: EnvironmentInstallConfigDTO = {
        operatingSystem: 'linux',
        daemon: {
          studioUrl: 'https://studio.example.com',
          lsp: { servers: Object.fromEntries([[id, { command: ['server'], extensions: ['.ts'] }]]) },
        },
      }
      const normalized = validateInstallConfig(input)
      const servers = normalized.daemon.lsp!.servers
      expect(Object.getPrototypeOf(servers)).toBeNull()
      expect(Object.hasOwn(servers, id)).toBe(true)
      expect(Object.keys(JSON.parse(JSON.stringify(servers)))).toEqual([id])
      expect(servers[id]).toEqual({
        command: ['server'], extensions: ['.ts'], rootMarkers: [], firstMatchMarkers: [],
      })
      const command = generateInstallCommand(origin, id, 'linux', 'code-1')
      expect(command).toContain(encodeURIComponent(id).replaceAll("'", "\\'"))
      expect(command).not.toContain(JSON.stringify(normalized.daemon))
    },
  )

  it('bounds and validates LSP map and rejects control or origin injection', () => {
    const input = config()
    for (const lsp of [{ servers: {} }, { servers: [] }, { servers: { 'bad id': { command: ['x'], extensions: ['.a'] } } },
      { servers: {}, other: true }]) {
      input.daemon.lsp = lsp as EnvironmentInstallConfigDTO['daemon']['lsp']
      expect(() => validateInstallConfig(input)).toThrow(/^Invalid /)
    }
    expect(() => generateInstallCommand(origin, "x\n$(touch NEVER)", 'linux', 'code-1'))
      .toThrow('environmentId')
    expect(() => generateInstallCommand(origin, 'env-1', 'linux', "x\n$(touch NEVER)"))
      .toThrow('installCode')
    expect(() => generateInstallCommand('https://evil.example/path', 'env-1', 'linux', 'code-1'))
      .toThrow('downloadOrigin')
    expect(() => generateInstallCommand('https://user:pass@studio.example.com', 'env-1', 'linux', 'code-1'))
      .toThrow('downloadOrigin')
    expect(() => generateInstallCommand(origin, 'env-1', 'wrong' as 'linux', 'code-1')).toThrow('operatingSystem')
    expect(() => generateUninstallCommand('wrong' as 'linux', origin)).toThrow('operatingSystem')
    const injected = generateInstallCommand(origin, "a'$(touch NEVER)b", 'linux', "c'$(touch NEVER)")
    expect(injected).toContain(encodeURIComponent("a'$(touch NEVER)b").replaceAll("'", "\\'"))
    expect(injected).toContain(`code=${encodeURIComponent("c'$(touch NEVER)").replaceAll("'", "\\'")}`)
    expect(injected).not.toContain('$(touch NEVER)')
    expect(generateInstallCommand(origin, 'env-1', 'linux', 'code-1'))
      .toBe(generateInstallCommand(origin, 'env-1', 'linux', 'code-1'))
  })
})
