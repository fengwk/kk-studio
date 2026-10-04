import { describe, expect, it } from 'vitest'
import { mkdtempSync, writeFileSync, readFileSync, existsSync, rmSync, readdirSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { isAbsolute, join } from 'node:path'
import { spawnSync } from 'node:child_process'
import type { EnvironmentInstallConfigDTO } from '@/shared/api/contracts/ai-environment'
import { detectInstallOS, generateInstallCommand, generateUninstallCommand, validateInstallConfig } from './install-command'

const config = (): EnvironmentInstallConfigDTO => ({
  operatingSystem: 'linux',
  // Backend forbids $ % ~ in javaHome; quotes/backticks still exercise shell escaping.
  javaHome: "/opt/jdk-21/汉字 '`",
  daemon: {
    studioUrl: 'https://studio.example.com/',
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

function expectedDaemon(input: EnvironmentInstallConfigDTO) {
  return JSON.parse(JSON.stringify(validateInstallConfig(input).daemon))
}

// Execute the actual pasted wrapper with a fake download and an installer recorder.
// Verifies credential bytes, sibling staging, permissions, argv/environment and cleanup.
function execute(command: string, exit = 0) {
  const dir = mkdtempSync(join(tmpdir(), 'kk-command-'))
  const result = join(dir, 'record.json')
  const recorder = `#!/bin/bash
python3 - "$@" <<'PY'
import json, os, sys, stat
args = sys.argv[1:]
data = {'args': args, 'env': dict(os.environ)}
if args[0] == 'install':
    c = args[args.index('--config-file') + 1]
    t = args[args.index('--token-file') + 1]
    data.update(config=json.load(open(c)), token=open(t).read(), stage=os.path.dirname(c),
                sibling=os.path.dirname(c) == os.path.dirname(t),
                modes=[stat.S_IMODE(os.stat(p).st_mode) for p in [os.path.dirname(c), c, t]])
json.dump(data, open(os.environ['RECORD'], 'w'))
PY
exit ${exit}
`
  writeFileSync(join(dir, 'recorder'), recorder)
  writeFileSync(
    join(dir, 'curl'),
    '#!/bin/bash\nwhile [[ "$1" != "-o" ]]; do shift; done\ncp "$FIXTURE" "$2"\n',
    { mode: 0o700 },
  )
  try {
    const run = spawnSync('bash', [], {
      input: command, encoding: 'utf8', cwd: dir,
      env: { ...process.env, PATH: `${dir}:${process.env.PATH}`, TMPDIR: '.', RECORD: result,
        FIXTURE: join(dir, 'recorder'),
        // A broken/xtrace-enabled parent must not print or intercept credentials.
        SHELLOPTS: 'xtrace', 'BASH_FUNC_printf%%': '() { echo unsafe >&2; exit 42; }' },
    })
    const record = JSON.parse(readFileSync(result, 'utf8'))
    expect(existsSync(join(dir, 'NEVER'))).toBe(false)
    if (record.stage) expect(existsSync(record.stage)).toBe(false)
    return { run, record }
  } finally {
    rmSync(dir, { recursive: true, force: true })
  }
}

describe('install command', () => {
  it.skipIf(process.platform === 'win32').each(['linux', 'macos'] as const)(
    'executes safe %s installation with exact bytes and no secret child args/env',
    os => {
      const input = { ...config(), operatingSystem: os }
      const token = "private-汉字'\"$`$(touch NEVER)"
      const { run, record } = execute(generateInstallCommand(input, token))
      expect(run.status).toBe(0)
      expect(run.stdout + run.stderr).not.toContain(token)
      expect(record.config).toEqual(expectedDaemon(input))
      expect(record.token).toBe(token)
      expect(record.sibling).toBe(true)
      expect(isAbsolute(record.stage)).toBe(true)
      expect(record.modes).toEqual([0o700, 0o600, 0o600])
      expect(record.args).toEqual([
        'install', '--config-file', `${record.stage}/daemon.json`, '--token-file',
        `${record.stage}/daemon.token`, '--java-home', input.javaHome,
      ])
      expect(JSON.stringify([record.args, record.env])).not.toContain(token)
    },
  )

  it.skipIf(process.platform === 'win32')(
    'cleans staged files after child failure and supports omitted Java home',
    () => {
      const input = config()
      input.javaHome = null
      const { run, record } = execute(generateInstallCommand(input, 'private'), 17)
      expect(run.status).toBe(17)
      expect(record.args).not.toContain('--java-home')
    },
  )

  it.skipIf(process.platform === 'win32')(
    'cleans credential files on download failure without invoking an installer',
    () => {
      const dir = mkdtempSync(join(tmpdir(), 'kk-download-failure-'))
      try {
        writeFileSync(join(dir, 'curl'), '#!/bin/bash\nexit 8\n', { mode: 0o700 })
        const run = spawnSync('bash', [], {
          input: generateInstallCommand(config(), 'private'), encoding: 'utf8', cwd: dir,
          env: { ...process.env, PATH: `${dir}:${process.env.PATH}`, TMPDIR: dir },
        })
        expect(run.status).toBe(8)
        expect(readdirSync(dir)).toEqual(['curl'])
        expect(run.stdout + run.stderr).not.toContain('private')
      } finally {
        rmSync(dir, { recursive: true, force: true })
      }
    },
  )

  it.skipIf(process.platform === 'win32').each(['linux', 'macos'] as const)(
    'executes %s uninstall without config/token parameters',
    os => {
      const { run, record } = execute(generateUninstallCommand(os))
      expect(run.status).toBe(0)
      expect(record.args).toEqual(['uninstall'])
    },
  )

  it('stages Windows credentials only after protected ACLs, without the .NET Framework-only overload', () => {
    const input = config()
    input.operatingSystem = 'windows'
    input.javaHome = "C:\\Program Files\\Java\\it's"
    const command = generateInstallCommand(input, "private-'$`")
    // Directory and file ACLs both disable inheritance and target the current SID.
    expect(command).toContain('function Set-KkPrivateAcl')
    expect(command).toContain('[Security.AccessControl.DirectorySecurity]::new()')
    expect(command).toContain('[Security.AccessControl.FileSecurity]::new()')
    expect(command).toContain('$acl.SetAccessRuleProtection($true, $false)')
    expect(command).not.toContain('[IO.Directory]::CreateDirectory')
    expect(command).toContain('New-Item -ItemType Directory -Path $stage')
    expect(command).toContain('[IO.FileMode]::CreateNew')
    expect(command).toContain('New-Object Text.UTF8Encoding($false)')
    // Every credential file gets its own protected ACL before any bytes are written.
    expect(command.indexOf('Set-KkPrivateAcl -Path $stage -Directory'))
      .toBeLessThan(command.indexOf('WriteAllText($config'))
    expect(command.indexOf('Set-KkPrivateAcl -Path $path'))
      .toBeLessThan(command.indexOf('WriteAllText($config'))
    expect(command.indexOf('Set-KkPrivateAcl -Path $path'))
      .toBeLessThan(command.indexOf('WriteAllText($token'))
    expect(command).toContain('Set-PSDebug -Off')
    expect(command).toContain("throw 'Daemon command failed; review installer output and host prerequisites.'")
    expect(command).toContain('finally')
    expect(command).toContain('Remove-Item -LiteralPath $stage -Recurse -Force')
    const invocation = command.split('\n').find(line => line.includes('& powershell'))!
    expect(invocation).toContain("-File $installer install -ConfigFile $config -TokenFile $token -JavaHome 'C:\\Program Files\\Java\\it''s'")
    expect(invocation).not.toContain('private')
    // Uninstall reuses the private staging but writes no credentials.
    const uninstall = generateUninstallCommand('windows')
    expect(uninstall).not.toContain('WriteAllText')
    expect(uninstall).toContain('Set-KkPrivateAcl -Path $stage -Directory')
    expect(uninstall).toContain('-File $installer uninstall')
    input.javaHome = null
    expect(generateInstallCommand(input, 'private')).not.toContain('-JavaHome')
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
      expect(generateInstallCommand(input, 'private-prototype')).toContain(
        JSON.stringify(normalized.daemon),
      )
    },
  )

  it('bounds and validates LSP map and rejects heredoc/control injection', () => {
    const input = config()
    for (const lsp of [{ servers: {} }, { servers: [] }, { servers: { 'bad id': { command: ['x'], extensions: ['.a'] } } },
      { servers: {}, other: true }]) {
      input.daemon.lsp = lsp as EnvironmentInstallConfigDTO['daemon']['lsp']
      expect(() => validateInstallConfig(input)).toThrow(/^Invalid /)
    }
    expect(() => generateInstallCommand(config(), "x\nKK_STUDIO_INSTALL\n$(touch NEVER)"))
      .toThrow('registrationToken')
    expect(() => generateUninstallCommand('wrong' as 'linux')).toThrow('operatingSystem')
  })
})
