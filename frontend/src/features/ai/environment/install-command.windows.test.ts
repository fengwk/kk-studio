import { expect, it } from 'vitest'
import { mkdtempSync, writeFileSync, readFileSync, existsSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { spawnSync } from 'node:child_process'
import { generateInstallCommand, generateUninstallCommand, validateInstallConfig } from './install-command'
import type { EnvironmentInstallConfigDTO } from '@/shared/api/contracts/ai-environment'

/**
 * Windows 原生记录器：按 install.ps1 `Assert-PrivateFile` 的标准验证生成的暂存命令，
 * 断言目录/文件都禁用 ACL 继承、owner 为当前 SID 且无外部 SID ACE，而不仅是“看起来私有”。
 *
 * 仅在 Windows 运行；Linux 上由 `install-command.test.ts` 覆盖 Unix 路径，不使用 Git Bash
 * 文件权限替代 Windows ACL 验证。
 */
it.skipIf(process.platform !== 'win32')(
  'stages PS5.1 credentials with protected ACLs, exact UTF8 bytes and cleanup',
  () => {
    const dir = mkdtempSync(join(tmpdir(), 'kk-command-'))
    const recordPath = join(dir, 'record.json')
    const fixture = join(dir, 'recorder.ps1')
    const scriptPath = join(dir, 'command.ps1')
    const token = "private-汉字'\"$`$(New-Item NEVER)"
    const config: EnvironmentInstallConfigDTO = {
      operatingSystem: 'windows',
      // $ % ~ are rejected by the shared contract; quotes/backtick still exercise escaping.
      javaHome: "C:\\Program Files\\Java\\it's",
      daemon: {
        studioUrl: 'https://studio.example.com',
        note: "汉字 ' \"$`$(New-Item NEVER)",
        lsp: {
          servers: {
            ts: { command: ['~/server', "quote'$`"], extensions: ['.ts'], rootMarkers: ['pom.xml'] },
          },
        },
      },
    }
    writeFileSync(fixture, `param(
  [Parameter(Position = 0)][string]$Action,
  [string]$ConfigFile,
  [string]$TokenFile,
  [string]$JavaHome
)
$ErrorActionPreference = 'Stop'
$sid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
function Get-Facts([string]$Path) {
  $acl = Get-Acl -LiteralPath $Path
  $rules = @($acl.GetAccessRules($true, $true, [Security.Principal.SecurityIdentifier]))
  [pscustomobject]@{
    protected = $acl.AreAccessRulesProtected
    owner = (New-Object Security.Principal.NTAccount($acl.Owner)).Translate([Security.Principal.SecurityIdentifier]).Value
    foreign = @($rules | Where-Object { $_.IdentityReference.Value -ne $sid }).Count
    readable = @($rules | Where-Object { $_.IdentityReference.Value -eq $sid -and ($_.FileSystemRights -band [Security.AccessControl.FileSystemRights]::ReadData) -ne 0 }).Count
  }
}
$data = @{ action = $Action; javaHome = $JavaHome; environment = @{} }
Get-ChildItem Env: | ForEach-Object { $data.environment[$_.Name] = $_.Value }
if ($Action -eq 'install') {
  $data.sid = $sid
  $data.stage = Split-Path $ConfigFile
  $data.sibling = (Split-Path $TokenFile) -eq $data.stage
  $data.configRaw = [IO.File]::ReadAllText($ConfigFile)
  $data.token = [IO.File]::ReadAllText($TokenFile)
  $data.bytes = [Convert]::ToBase64String([IO.File]::ReadAllBytes($TokenFile))
  $data.dir = Get-Facts $data.stage
  $data.configFile = Get-Facts $ConfigFile
  $data.tokenFile = Get-Facts $TokenFile
}
[IO.File]::WriteAllText($env:RECORD, ($data | ConvertTo-Json -Depth 20), (New-Object Text.UTF8Encoding($false)))
exit 17
`)
    function run(command: string) {
      // Override download only; the generated command still launches a real PowerShell child via -File.
      // PS5.1 needs a BOM for the Unicode script source, while staged daemon/token files must not have one.
      writeFileSync(
        scriptPath,
        `\ufefffunction Invoke-WebRequest { param([switch]$UseBasicParsing, $Uri, $OutFile) Copy-Item -LiteralPath $env:FIXTURE -Destination $OutFile }\n${command}`,
      )
      return spawnSync(
        'powershell.exe',
        ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', scriptPath],
        { cwd: dir, encoding: 'utf8', env: { ...process.env, RECORD: recordPath, FIXTURE: fixture } },
      )
    }
    try {
      const result = run(generateInstallCommand(config, token))
      expect(result.status).not.toBe(0)
      expect(result.stdout + result.stderr).not.toContain(token)
      const record = JSON.parse(readFileSync(recordPath, 'utf8'))
      expect(record.sibling).toBe(true)
      for (const facts of [record.dir, record.configFile, record.tokenFile]) {
        expect(facts.protected).toBe(true)
        expect(facts.owner).toBe(record.sid)
        expect(facts.foreign).toBe(0)
        expect(facts.readable).toBeGreaterThanOrEqual(1)
      }
      expect(record.token).toBe(token)
      expect(record.bytes).toBe(Buffer.from(token, 'utf8').toString('base64'))
      expect(JSON.parse(record.configRaw)).toEqual(
        JSON.parse(JSON.stringify(validateInstallConfig(config).daemon)),
      )
      expect(config.javaHome).toBe("C:\\Program Files\\Java\\it's")
      expect(record.javaHome).toBe("C:\\Program Files\\Java\\it's")
      expect(JSON.stringify(record.environment)).not.toContain(token)
      expect(existsSync(record.stage)).toBe(false)
      expect(existsSync(join(dir, 'NEVER'))).toBe(false)

      run(generateUninstallCommand('windows'))
      const uninstall = JSON.parse(readFileSync(recordPath, 'utf8'))
      expect(uninstall.action).toBe('uninstall')
      expect(uninstall.configRaw).toBeUndefined()
      expect(uninstall.token).toBeUndefined()
      expect(uninstall.javaHome).toBe('')
    } finally {
      rmSync(dir, { recursive: true, force: true })
    }
  },
)
