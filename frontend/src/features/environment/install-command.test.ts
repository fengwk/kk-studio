import { describe, expect, it } from 'vitest'
import { mkdtempSync, writeFileSync, readFileSync, existsSync, rmSync, readdirSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { spawnSync } from 'node:child_process'
import { url } from '@/shared/api/client'
import {
  detectInstallOS,
  generateInstallCommand,
  generateUninstallCommand,
} from './install-command'

const origin = 'https://studio.example.com'
const environmentId = "env/汉字'$(touch NEVER)"
const installCode = "code'$(touch NEVER)"

function installScriptUrl(os: 'linux' | 'macos' | 'windows', id = environmentId) {
  return `${origin}${url('GET /api/platform/environments/{environmentId}/install-script', {
    path: { environmentId: id },
    query: { code: installCode, operatingSystem: os },
  })}`
}

function uninstallScriptUrl(os: 'linux' | 'macos' | 'windows', id = environmentId) {
  return `${origin}${url('GET /api/platform/environments/{environmentId}/uninstall-script', {
    path: { environmentId: id },
    query: { operatingSystem: os },
  })}`
}

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
    'runs a one-line %s install that fetches the encoded environment URL with the target OS and executes the remote script',
    os => {
      const command = generateInstallCommand(origin, environmentId, os, installCode)
      const expected = installScriptUrl(os)
      expect(command).toBe(`(set -o pipefail; curl -fsSL $'${expected.replaceAll("'", "\\'")}' | bash)`)
      expect(command).not.toContain('\n')
      expect(command).not.toContain('registrationToken')
      expect(command).toContain('operatingSystem=')
      const { run, url: fetched } = execute(command, 'echo installed\n')
      expect(run.status).toBe(0)
      expect(run.stdout.trim()).toBe('installed')
      expect(fetched).toBe(expected)
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
    'runs a one-line %s uninstall that locates this card and the chosen OS',
    os => {
      const command = generateUninstallCommand(origin, environmentId, os)
      const expected = uninstallScriptUrl(os)
      expect(command).toBe(`(set -o pipefail; curl -fsSL $'${expected.replaceAll("'", "\\'")}' | bash)`)
      const { run, url: fetched } = execute(command, 'echo uninstalled\n')
      expect(run.status).toBe(0)
      expect(fetched).toBe(expected)
    },
  )

  it('quotes the Windows download and does not stage credentials on the client', () => {
    const command = generateInstallCommand(origin, environmentId, 'windows', installCode)
    const expected = installScriptUrl('windows').replaceAll("'", "''")
    expect(command).not.toContain('\n')
    expect(command.startsWith('& { Set-PSDebug -Off; ')).toBe(true)
    expect(command.endsWith(' }')).toBe(true)
    expect(command).toContain('Invoke-WebRequest -UseBasicParsing')
    expect(command).toContain('-ErrorAction Stop')
    expect(command).toContain('[scriptblock]::Create($script)')
    expect(command).toContain(`'${expected}'`)
    expect(command).not.toContain('registrationToken')
    expect(command).not.toContain('WriteAllText')
    expect(command).not.toContain('Set-Acl')
    expect(command).not.toContain('-JavaHome')
    const uninstall = generateUninstallCommand(origin, environmentId, 'windows')
    expect(uninstall).toContain(`'${uninstallScriptUrl('windows').replaceAll("'", "''")}'`)
    expect(uninstall).not.toContain('registrationToken')
    expect(uninstall).not.toContain('code=')
  })

  it('detects defaults from the browser platform', () => {
    expect(['Win32', 'MacIntel', 'Linux'].map(detectInstallOS)).toEqual(['windows', 'macos', 'linux'])
  })

  it('bounds and validates identifiers and rejects control or origin injection', () => {
    expect(() => generateInstallCommand(origin, "x\n$(touch NEVER)", 'linux', 'code-1'))
      .toThrow('environmentId')
    expect(() => generateInstallCommand(origin, 'env-1', 'linux', "x\n$(touch NEVER)"))
      .toThrow('installCode')
    expect(() => generateInstallCommand('https://evil.example/path', 'env-1', 'linux', 'code-1'))
      .toThrow('downloadOrigin')
    expect(() => generateInstallCommand('https://user:pass@studio.example.com', 'env-1', 'linux', 'code-1'))
      .toThrow('downloadOrigin')
    expect(() => generateInstallCommand(origin, 'env-1', 'wrong' as 'linux', 'code-1')).toThrow('operatingSystem')
    expect(() => generateUninstallCommand(origin, 'env-1', 'wrong' as 'linux')).toThrow('operatingSystem')
    expect(() => generateUninstallCommand(origin, '', 'linux')).toThrow('environmentId')
    const injected = generateInstallCommand(origin, "a'$(touch NEVER)b", 'linux', "c'$(touch NEVER)")
    const injectedUrl = `${origin}${url(
      'GET /api/platform/environments/{environmentId}/install-script',
      {
        path: { environmentId: "a'$(touch NEVER)b" },
        query: { code: "c'$(touch NEVER)", operatingSystem: 'linux' },
      },
    )}`
    expect(injected).toContain(injectedUrl.replaceAll("'", "\\'"))
    expect(injected).not.toContain('$(touch NEVER)')
    expect(generateInstallCommand(origin, 'env-1', 'linux', 'code-1'))
      .toBe(generateInstallCommand(origin, 'env-1', 'linux', 'code-1'))
  })
})
