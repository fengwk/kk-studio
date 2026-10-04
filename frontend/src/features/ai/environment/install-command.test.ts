import { describe, expect, it } from 'vitest'
import { mkdtempSync, writeFileSync, readFileSync, existsSync, rmSync, readdirSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { isAbsolute, join } from 'node:path'
import { spawnSync } from 'node:child_process'
import type { EnvironmentInstallConfigDTO } from '@/shared/api/contracts/ai-environment'
import { detectInstallOS, generateInstallCommand, generateUninstallCommand, validateInstallConfig } from './install-command'

const config = (): EnvironmentInstallConfigDTO => ({
  operatingSystem: 'linux', javaHome: "/java/汉字 '$ `ticks` $(touch NEVER)",
  daemon: {
    studioUrl: 'https://studio.example.com/',
    note: "汉字 ' \" $ ` $(touch NEVER) KK_STUDIO_INSTALL",
    bashExecutable: "/bin/汉字 '$`",
    lsp: { servers: { 'server-1': {
      command: ['~/server', "quote' \"$` $(touch NEVER)"],
      extensions: ['.java'], rootMarkers: ['pom.xml'], firstMatchMarkers: ['.git'],
    } } },
  },
})

// Execute the actual pasted wrapper with a fake download and an installer recorder.
// It verifies values, sibling staging, permissions, argv/environment and cleanup.
function execute(command: string, exit = 0) {
  const dir = mkdtempSync(join(tmpdir(), 'kk-command-'))
  const result = join(dir, 'record.json')
  const recorder = `#!/bin/bash
python3 - "$@" <<'PY'
import json, os, sys, stat
args=sys.argv[1:]
data={'args':args,'env':dict(os.environ)}
if args[0]=='install':
 c=args[args.index('--config-file')+1]; t=args[args.index('--token-file')+1]
 data.update(config=json.load(open(c)),token=open(t).read(),stage=os.path.dirname(c),sibling=os.path.dirname(c)==os.path.dirname(t),modes=[stat.S_IMODE(os.stat(p).st_mode) for p in [os.path.dirname(c),c,t]])
json.dump(data,open(os.environ['RECORD'],'w'))
PY
exit ${exit}
`
  writeFileSync(join(dir, 'recorder'), recorder)
  writeFileSync(join(dir, 'curl'), '#!/bin/bash\nwhile [[ "$1" != "-o" ]]; do shift; done\ncp "$FIXTURE" "$2"\n', { mode: 0o700 })
  try {
    const run = spawnSync('bash', [], {
      input: command, encoding: 'utf8', cwd: dir,
      env: { ...process.env, PATH: `${dir}:${process.env.PATH}`, TMPDIR: '.', RECORD: result, FIXTURE: join(dir, 'recorder'),
        'BASH_FUNC_printf%%': '() { echo unsafe-child-printf >&2; exit 42; }', SHELLOPTS: 'xtrace' },
    })
    const record = JSON.parse(readFileSync(result, 'utf8'))
    expect(existsSync(join(dir, 'NEVER'))).toBe(false)
    if (record.stage) expect(existsSync(record.stage)).toBe(false)
    return { run, record }
  } finally { rmSync(dir, { recursive: true, force: true }) }
}

describe('install command', () => {
  it.skipIf(process.platform === 'win32').each(['linux', 'macos'] as const)('executes safe %s installation with exact bytes and no secret child args/env', os => {
    const input = { ...config(), operatingSystem: os }
    const token = "private-汉字'\"$`$(touch NEVER)"
    const { run, record } = execute(generateInstallCommand(input, token))
    expect(run.status).toBe(0)
    expect(run.stdout + run.stderr).not.toContain(token)
    expect(record.config).toEqual(validateInstallConfig(input).daemon)
    expect(record.token).toBe(token)
    expect(record.sibling).toBe(true)
    expect(isAbsolute(record.stage)).toBe(true)
    expect(record.modes).toEqual([0o700, 0o600, 0o600])
    expect(record.args).toEqual(['install', '--config-file', `${record.stage}/daemon.json`, '--token-file', `${record.stage}/daemon.token`, '--java-home', input.javaHome])
    expect(JSON.stringify([record.args, record.env])).not.toContain(token)
  })

  it.skipIf(process.platform === 'win32')('cleans staged files even after child failure; supports omitted Java home', () => {
    const input = config(); input.javaHome = null
    const { run, record } = execute(generateInstallCommand(input, 'private'), 17)
    expect(run.status).toBe(17)
    expect(record.args).not.toContain('--java-home')
  })

  it.skipIf(process.platform === 'win32')('cleans credential files on download failure without invoking an installer', () => {
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
    } finally { rmSync(dir, { recursive: true, force: true }) }
  })

  it.skipIf(process.platform === 'win32').each(['linux', 'macos'] as const)('executes %s uninstall without config/token parameters', os => {
    const { run, record } = execute(generateUninstallCommand(os))
    expect(run.status).toBe(0)
    expect(record.args).toEqual(['uninstall'])
  })

  it('builds PS5.1 literals, owner-only directory creation before writes, BOM-free UTF8 and finally cleanup', () => {
    const input = config(); input.operatingSystem = 'windows'; input.javaHome = "C:\\Java\\汉字 '$`"
    const command = generateInstallCommand(input, "private-'$`")
    expect(command).toContain("'private-''$`'")
    expect(command.indexOf('CreateDirectory($stage, $acl)')).toBeLessThan(command.indexOf('WriteAllText'))
    expect(command).toContain('SetAccessRuleProtection($true, $false)')
    expect(command).toContain('Text.UTF8Encoding($false)')
    expect(command).toContain('Set-PSDebug -Off')
    expect(command).toContain("throw 'Daemon command failed; review installer output and host prerequisites.'")
    const invocation = command.split('\n').find(line => line.includes('& powershell'))!
    expect(invocation).toContain("-File $installer install -ConfigFile (Join-Path $stage 'daemon.json') -TokenFile (Join-Path $stage 'daemon.token') -JavaHome 'C:\\Java\\汉字 ''$`'")
    expect(invocation).not.toContain('private')
    expect(command).toContain('finally')
    expect(generateUninstallCommand('windows')).not.toContain('WriteAllText')
    expect(generateUninstallCommand('windows')).toContain('-File $installer uninstall')
    input.javaHome = null
    expect(generateInstallCommand(input, 'private')).not.toContain('-JavaHome')
  })

  it('detects defaults without overriding saved settings; canonicalizes blank optional values', () => {
    expect(['Win32', 'MacIntel', 'Linux'].map(detectInstallOS)).toEqual(['windows', 'macos', 'linux'])
    expect(validateInstallConfig({ operatingSystem: 'linux', javaHome: ' ', daemon: { studioUrl: 'http://[::1]:8080/', note: ' ', bashExecutable: null } }))
      .toEqual({ operatingSystem: 'linux', javaHome: null, daemon: { studioUrl: 'http://[::1]:8080', note: null, bashExecutable: null } })
  })

  it.each(['ftp://host', 'https://user:pass@host', 'https://@host', 'https://host:', 'https://host:0', 'https://host /', 'https://host/path', 'https://host?x', 'https://host#x', 'https://host:99999', 'https://', 'https://host\\path', 'https://host\n', 'https://host//', 'https://[::1', 'https://host:abc'])('rejects unsafe origin %s', studioUrl => {
    expect(() => validateInstallConfig({ ...config(), daemon: { studioUrl } })).toThrow('daemon.studioUrl')
  })

  it.each([
    { javaHome: 'relative' }, { javaHome: '/${JAVA_HOME}' }, { javaHome: 12 },
    { operatingSystem: 'unknown' }, { daemon: { studioUrl: '' } },
    { daemon: { studioUrl: 'https://host', note: 'x'.repeat(513) } },
    { daemon: { studioUrl: 'https://host', bashExecutable: 'a\nb' } },
    { daemon: { studioUrl: 'https://host', other: 1 } }, { other: 1 },
  ])('rejects invalid fields without echoing values', patch => {
    expect(() => validateInstallConfig({ ...config(), ...patch } as EnvironmentInstallConfigDTO)).toThrow('Invalid')
  })

  it.each([
    null, [], {}, { command: [] }, { command: [''] }, { command: [1] },
    { command: ['x'], unknown: [] }, { command: ['x'], extensions: ['java'] },
    { command: ['x'], extensions: ['.a/b'] }, { command: ['x'], rootMarkers: ['../pom'] },
    { command: ['x'], rootMarkers: ['/pom'] }, { command: ['x'], firstMatchMarkers: ['C:\\pom'] },
    { command: ['x'], extensions: 'bad' }, { command: ['x'.repeat(4097)] },
    { command: Array(257).fill('x') },
  ])('rejects malformed LSP server %#', server => {
    const input = config()
    input.daemon.lsp = { servers: { server } } as EnvironmentInstallConfigDTO['daemon']['lsp']
    expect(() => validateInstallConfig(input)).toThrow('Invalid')
  })

  it('bounds and validates LSP map and rejects heredoc/control injection', () => {
    const input = config()
    for (const lsp of [{ servers: {} }, { servers: [] }, { servers: { 'bad id': { command: ['x'] } } }, { servers: Object.fromEntries(Array.from({ length: 65 }, (_, i) => [i, { command: ['x'] }])) }, { servers: {}, other: true }, { servers: { server: { command: ['x'.repeat(65536)] } } }]) {
      input.daemon.lsp = lsp as EnvironmentInstallConfigDTO['daemon']['lsp']
      expect(() => validateInstallConfig(input)).toThrow('Invalid')
    }
    expect(() => generateInstallCommand(config(), "x\nKK_STUDIO_INSTALL\n$(touch NEVER)")).toThrow('registrationToken')
    expect(() => generateUninstallCommand('wrong' as 'linux')).toThrow('operatingSystem')
  })

  it('measures LSP size in UTF8 bytes rather than Unicode character count', () => {
    const input = config()
    input.daemon.lsp = { servers: { server: { command: Array(6).fill('汉'.repeat(4000)) } } }
    expect(JSON.stringify(input.daemon.lsp).length).toBeLessThan(65536)
    expect(() => validateInstallConfig(input)).toThrow('daemon.lsp size')
  })
})
