import { expect, it } from 'vitest'
import { mkdtempSync, writeFileSync, readFileSync, existsSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { spawnSync } from 'node:child_process'
import { generateInstallCommand, generateUninstallCommand } from './install-command'
import type { EnvironmentInstallConfigDTO } from '@/shared/api/contracts/ai-environment'

// Run on Windows integration hosts with native PS5.1. No network or managed service operations.
it.skipIf(process.platform !== 'win32')('executes PS5.1 staging with private ACL, exact UTF8 bytes, path-only child args and cleanup', () => {
  const dir = mkdtempSync(join(tmpdir(), 'kk-command-'))
  const recordPath = join(dir, 'record.json')
  const fixture = join(dir, 'recorder.ps1')
  const scriptPath = join(dir, 'command.ps1')
  const token = "private-汉字'\"$`$(New-Item NEVER)"
  const config: EnvironmentInstallConfigDTO = {
    operatingSystem: 'windows', javaHome: "C:\\Java\\汉字 '$`",
    daemon: { studioUrl: 'https://studio.example.com', note: "汉字 ' \"$`$(New-Item NEVER)",
      lsp: { servers: { ts: { command: ['~/server', "quote'$`"], extensions: ['.ts'] } } } },
  }
  writeFileSync(fixture, `param(
  [Parameter(Position=0)][string]$Action,
  [string]$ConfigFile, [string]$TokenFile, [string]$JavaHome
)
$ErrorActionPreference = 'Stop'
$data = @{ action = $Action; configFile = $ConfigFile; tokenFile = $TokenFile; javaHome = $JavaHome; environment = @{}; stage = $PSScriptRoot }
Get-ChildItem Env: | ForEach-Object { $data.environment[$_.Name] = $_.Value }
if ($Action -eq 'install') {
  $data.stage = Split-Path $ConfigFile
  $data.sibling = (Split-Path $TokenFile) -eq $data.stage
  $data.config = [IO.File]::ReadAllText($ConfigFile)
  $data.token = [IO.File]::ReadAllText($TokenFile)
  $data.bytes = [Convert]::ToBase64String([IO.File]::ReadAllBytes($TokenFile))
  $sid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
  $data.private = $true
  foreach ($path in @($data.stage, $ConfigFile, $TokenFile)) {
    $acl = Get-Acl -LiteralPath $path
    foreach ($rule in $acl.Access) {
      if ($rule.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value -ne $sid) { $data.private = $false }
    }
  }
}
[IO.File]::WriteAllText($env:RECORD, ($data | ConvertTo-Json -Depth 20), (New-Object Text.UTF8Encoding($false)))
exit 17
`)
  function run(command: string) {
    // Override download only. The generator launches the genuine PowerShell child via -File.
    // PS5.1 requires a BOM for Unicode script source; generated daemon/token files must have none.
    writeFileSync(scriptPath, `\ufefffunction Invoke-WebRequest { param([switch]$UseBasicParsing, $Uri, $OutFile) Copy-Item -LiteralPath $env:FIXTURE -Destination $OutFile }\n${command}`)
    return spawnSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', scriptPath], {
      cwd: dir, encoding: 'utf8', env: { ...process.env, RECORD: recordPath, FIXTURE: fixture },
    })
  }
  try {
    const result = run(generateInstallCommand(config, token))
    expect(result.status).not.toBe(0)
    expect(result.stdout + result.stderr).not.toContain(token)
    const record = JSON.parse(readFileSync(recordPath, 'utf8'))
    expect(record.private).toBe(true)
    expect(record.sibling).toBe(true)
    expect(JSON.parse(record.config)).toEqual({ ...config.daemon, bashExecutable: null })
    expect(record.token).toBe(token)
    expect(record.bytes).toBe(Buffer.from(token, 'utf8').toString('base64'))
    expect(record.javaHome).toBe(config.javaHome)
    expect(JSON.stringify(record.environment)).not.toContain(token)
    expect(existsSync(record.stage)).toBe(false)
    expect(existsSync(join(dir, 'NEVER'))).toBe(false)
    run(generateUninstallCommand('windows'))
    const uninstall = JSON.parse(readFileSync(recordPath, 'utf8'))
    expect(uninstall.action).toBe('uninstall')
    expect(uninstall.configFile).toBe('')
    expect(uninstall.tokenFile).toBe('')
    expect(existsSync(uninstall.stage)).toBe(false)
  } finally { rmSync(dir, { recursive: true, force: true }) }
})
