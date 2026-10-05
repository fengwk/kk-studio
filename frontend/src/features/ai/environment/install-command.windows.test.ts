import { expect, it } from 'vitest'
import { mkdtempSync, writeFileSync, readFileSync, existsSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { spawnSync } from 'node:child_process'
import { generateInstallCommand, generateUninstallCommand } from './install-command'

const origin = 'https://studio.example.com'

/**
 * Windows 原生入口：真正执行生成的一行下载命令。假 Invoke-WebRequest 不落盘，
 * 分别验证下载失败、远端脚本失败，以及 UTF-8 脚本在内存中可执行。
 * 仅在 Windows 运行；非 Windows 主机明确跳过，不用 Git Bash 代替。
 */
it.skipIf(process.platform !== 'win32').each(['powershell.exe', 'pwsh.exe'])(
  'executes the one-line download with %s: failure, script failure, and a UTF-8 script',
  shell => {
    const dir = mkdtempSync(join(tmpdir(), 'kk-command-'))
    const recordPath = join(dir, 'record.json')
    const scriptPath = join(dir, 'command.ps1')
    const environmentId = "env/汉字'\"$`$(New-Item NEVER)"
    const installCode = "code'汉字"
    const install = generateInstallCommand(origin, environmentId, 'windows', installCode)
    const uninstall = generateUninstallCommand('windows', origin)
    function run(command: string, mode: 'ok' | 'download' | 'script') {
      rmSync(recordPath, { force: true })
      const payload = mode === 'script'
        ? "throw 'remote failed'"
        : "$data = @{ url = $script:downloadUrl; marker = '执行-汉字'; token = 'private-synthetic-token' }; [IO.File]::WriteAllText($env:RECORD, ($data | ConvertTo-Json), (New-Object Text.UTF8Encoding($false)))"
      const download = mode === 'download'
        ? "throw 'download failed'"
        : `$script:downloadUrl = $Uri; $content = @'
${payload}
'@
[pscustomobject]@{ Content = $content }`
      writeFileSync(
        scriptPath,
        `\ufefffunction Invoke-WebRequest { param([switch]$UseBasicParsing, $Uri) ${download} }
$ErrorActionPreference = 'Continue'
$script = 'outer'
Set-PSDebug -Trace 2
${command}
if ($ErrorActionPreference -ne 'Continue' -or $script -ne 'outer') { throw 'caller scope changed' }
`,
      )
      return spawnSync(
        shell,
        ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', scriptPath],
        { cwd: dir, encoding: 'utf8', env: { ...process.env, RECORD: recordPath } },
      )
    }
    try {
      const downloaded = run(install, 'ok')
      expect(downloaded.status).toBe(0)
      const record = JSON.parse(readFileSync(recordPath, 'utf8'))
      expect(record.marker).toBe('执行-汉字')
      expect(record.token).toBe('private-synthetic-token')
      expect(record.url).toBe(
        `https://studio.example.com/api/harness/environments/${encodeURIComponent(environmentId)}/install?code=${encodeURIComponent(installCode)}`,
      )
      expect(downloaded.stdout + downloaded.stderr).not.toContain('private-synthetic-token')
      expect(existsSync(join(dir, 'NEVER'))).toBe(false)

      const scriptFailed = run(install, 'script')
      expect(scriptFailed.status).not.toBe(0)
      expect(scriptFailed.stderr).toContain('remote failed')

      const downloadFailed = run(install, 'download')
      expect(downloadFailed.status).not.toBe(0)
      expect(existsSync(recordPath)).toBe(false)

      const removed = run(uninstall, 'ok')
      expect(removed.status).toBe(0)
      expect(JSON.parse(readFileSync(recordPath, 'utf8')).url).toBe(
        'https://studio.example.com/api/harness/environments/uninstall/windows',
      )
    } finally {
      rmSync(dir, { recursive: true, force: true })
    }
  },
)
