/**
 * Static and optional native contracts for the Windows daemon installer.
 *
 * The repository's default script tests run on Linux, where the Windows-only ScheduledTasks module
 * does not exist. These tests therefore inspect lifecycle and security ordering directly from the
 * shipped `install.ps1` so per-card isolation, the operation-lock helper, update-without-config
 * and stop/publication ordering stay verifiable everywhere.
 *
 * The companion PowerShell suite (`test_daemon_install_windows.ps1`) exercises the real
 * ScheduledTasks objects and the direct `node.exe` argv round-trip on Windows; it is executed by
 * the Windows CI leg, so no Java toolchain is needed on any leg.
 */

import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { existsSync, readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, it } from 'node:test'

import { REPOSITORY_ROOT } from './support/release-fixture.mjs'
import { assertIncludesAll, assertIncludesNone, assertInOrder } from './support/source-order.mjs'

const INSTALL_SCRIPT = path.join(REPOSITORY_ROOT, 'scripts/daemon/install.ps1')
const NATIVE_TEST = path.join(REPOSITORY_ROOT, 'scripts/daemon/tests/test_daemon_install_windows.ps1')

const scriptBytes = () => readFileSync(INSTALL_SCRIPT)
/** The script text without its UTF-8 BOM. */
const scriptText = () => readFileSync(INSTALL_SCRIPT, 'utf8').replace(/^﻿/, '')

/** Extract one top-level PowerShell function for stable ordering assertions. */
function functionBody(name) {
  const escaped = name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const match = new RegExp(`^function ${escaped} \\{\\n([\\s\\S]*?)(?=^function |$(?![\\s\\S]))`, 'm').exec(scriptText())
  assert.ok(match, `PowerShell function not found: ${name}`)
  return match[1]
}

describe('Windows installer surface: public entry, UUID helper and PowerShell 5.1 compatibility', () => {
  it('declares the strict Windows PowerShell 5.1 contract', () => {
    const text = scriptText()
    assert.ok(existsSync(INSTALL_SCRIPT))
    // PS 5.1 must decode the Chinese messages as UTF-8, not the host ANSI code page.
    assert.deepEqual([...scriptBytes().subarray(0, 3)], [0xef, 0xbb, 0xbf])
    assert.ok(text.startsWith('#Requires -Version 5.1\n'))
    assert.ok(text.includes('Set-StrictMode -Version Latest'))
    assert.ok(text.includes('$ErrorActionPreference = "Stop"'))
    assert.doesNotMatch(text, /^\+/m)
  })

  it('lists the four per-card commands and bounded layout in the help', () => {
    const usage = functionBody('Write-Usage')
    assertIncludesAll(usage, [
      'install',
      'update',
      'status',
      'uninstall',
      'help',
      '-EnvironmentId',
      '-Version',
      '-ConfigFile',
      '-TokenFile',
      '%USERPROFILE%\\.kk-studio\\environments\\<environment-id>',
      'daemon.json, daemon.token, daemon.lock, install.lock, lib, ide, skills, .work',
      'last-operation.json',
      'install.lock',
      'No user Node is required',
      'no mutable "latest" release',
      'no backup',
      'no automatic rollback',
      'Task Scheduler does not capture daemon stdout/stderr',
      'best effort settings',
    ])
    assertIncludesNone(usage, ['backups', 'latest official', 'Export-ScheduledTask'])
  })

  it('requires the canonical UUID and an explicit version', () => {
    assertIncludesAll(scriptText(), [
      '[ValidateSet("install", "update", "status", "uninstall", "help")]',
      '[string] $EnvironmentId',
      '$script:EnvironmentIdPattern = "^[0-9a-f]{8}-',
      '$script:InstallParameterNames = @("EnvironmentId", "Version", "ConfigFile", "TokenFile")',
      '$script:UpdateParameterNames = @("EnvironmentId", "Version")',
    ])
    assertIncludesAll(functionBody('Assert-EnvironmentId'), [
      '$Value -cnotmatch $script:EnvironmentIdPattern',
      'canonical lowercase UUID',
      '$script:TargetEnvironmentId = $Value',
    ])
    assertIncludesAll(functionBody('Get-ReleaseArchive'), [
      'Assert-Version -Value $Version',
      '$tag = "v$($script:TargetVersion)"',
      '$asset = "kk-studio-daemon-$tag-windows-amd64.tar.gz"',
    ])
    // No mutable latest release may be resolved.
    assert.ok(!scriptText().includes('releases/latest'))
  })

  it('needs no user JRE or Node', () => {
    const text = scriptText()
    // Only the archive's own frozen runtime may be executed.
    assert.ok(!text.includes('Get-Command "node.exe"'))
    assert.ok(text.includes('runtime\\code-server\\lib\\node.exe'))
  })

  it('can be dot-sourced without running main', () => {
    // Native tests must load pure helpers without installing or registering anything.
    assert.ok(scriptText().includes('if ($MyInvocation.InvocationName -ne ".")'))
    assert.ok(existsSync(NATIVE_TEST), 'native Windows contract test must be shipped')
  })
})

describe('Windows installer security: card isolation, credentials and task ownership fail closed', () => {
  it('scopes the task and every managed path by the environment UUID', () => {
    assertIncludesAll(scriptText(), [
      '"kk-studio-daemon-$($script:CurrentSid)-$($script:TargetEnvironmentId)"',
      'Managed by $($script:ManagedBy); ownerSid=$($script:CurrentSid); environmentId=$($script:TargetEnvironmentId)',
      '$script:EnvironmentsRoot = Join-Path $script:InstallRoot "environments"',
      '$script:EnvironmentRoot = Join-Path $script:EnvironmentsRoot $script:TargetEnvironmentId',
      '$script:WorkDirectory = Join-Path $script:EnvironmentRoot ".work"',
      '$script:LastOperation = Join-Path $script:EnvironmentRoot "last-operation.json"',
    ])
    const body = functionBody('Assert-ManagedLayout')
    assertIncludesAll(body, [
      'Test-Path -LiteralPath $script:InstalledLib',
      '未自动回滚，未知文件未变更',
      'Assert-PrivateDirectory',
      '$script:LastOperation',
    ])
    assertIncludesNone(body, ['Remove-Item', 'Move-Item', 'Set-Acl'])
  })

  it('validates the token by metadata only and requires a private ACL', () => {
    const body = functionBody('Assert-PrivateFile')
    assertIncludesAll(body, [
      'Get-Item -LiteralPath',
      'ReparsePoint',
      '$item.Length',
      'Get-Acl -LiteralPath',
      'AreAccessRulesProtected',
      'GetAccessRules',
      'AccessControlType]::Allow',
      'ReadData',
      '$ExpectedOwnerSid',
      'must not contain a deny-read ACE',
    ])
    assertIncludesNone(body, ['Get-Content', 'ReadAllText', 'ReadAllBytes', 'OpenRead', 'StreamReader'])
  })

  it('turns an absent task into null without hiding scheduler failures', () => {
    const body = functionBody('Find-DaemonTask')
    assertIncludesAll(body, ['Get-ScheduledTask -ErrorAction Stop', '$_.TaskPath -eq', '$_.TaskName -eq $script:TaskName'])
    assert.ok(!body.includes('Get-ScheduledTask -TaskName'))
  })

  it('keeps install inputs private siblings and rejects unknown or misplaced options', () => {
    const body = functionBody('Resolve-InstallInputs')
    assertIncludesAll(body, [
      'Assert-PrivateFile -Path $ConfigFile',
      'Assert-PrivateFile -Path $TokenFile',
      'must be siblings named daemon.json and daemon.token',
    ])
    assertIncludesAll(functionBody('Assert-OptionSet'), [
      '$script:InstallParameterNames',
      '$Allowed -notcontains $name',
      'does not accept -$name',
    ])
    const install = functionBody('Invoke-Install')
    assertIncludesAll(install, ['Assert-OptionSet -ForCommand "install" -Allowed $script:InstallParameterNames'])
    const update = functionBody('Invoke-Update')
    assertIncludesAll(update, ['Assert-OptionSet -ForCommand "update" -Allowed $script:UpdateParameterNames'])
    for (const name of ['Invoke-Status', 'Invoke-Uninstall']) {
      assertIncludesAll(functionBody(name), [`Assert-OptionSet -ForCommand `, '-Allowed @()'])
    }
  })

  it('rejects the old flat JSON and another card configuration before stopping', () => {
    assertIncludesAll(functionBody('Assert-EarlyEnvelope'), [
      '"environmentId"',
      '"daemonConfig"',
      '"version"',
      '配置格式不是当前版本',
    ])
    const envelope = functionBody('Assert-ConfigEnvelope')
    assertIncludesAll(envelope, [
      '$script:EnvelopeScript',
      '$result.ExitCode -eq 3',
      '配置属于另一张环境卡片',
      '配置格式不是当前版本',
    ])
    assertIncludesAll(scriptText(), [
      'const keys = Object.keys(value).sort().join(",");',
      'if (keys !== "daemonConfig,environmentId,version") process.exit(2);',
      'value.environmentId !== process.argv[2]',
      '/^(0|[1-9][0-9]*)$/',
    ])
  })

  it('surfaces only the bounded trusted config marker from the preflight', () => {
    assertIncludesAll(scriptText(), ['$script:ConfigFailureMarker = "Invalid daemon configuration: "', '$script:ConfigFailureMaxLength'])
    assertIncludesAll(functionBody('Get-TrustedConfigFailureDetail'), [
      'StartsWith($script:ConfigFailureMarker',
      '$candidate.Length -gt $script:ConfigFailureMaxLength',
      '$candidate -match',
    ])
    const check = functionBody('Invoke-CheckConfig')
    assertIncludesAll(check, [
      'Get-TrustedConfigFailureDetail -Stdout $result.Stdout -Stderr $result.Stderr',
      '输出无法识别',
      '"Daemon configuration is valid"',
    ])
    // Raw streams must never be interpolated into the user-visible failure.
    assertIncludesNone(check, ['$($result.Stdout', '$($result.Stderr'])
  })

  it('owns the task through an exact per-user, per-card marker', () => {
    const body = functionBody('Assert-ManagedTask')
    assertIncludesAll(body, [
      '$Task.Description -ne $script:TaskDescription',
      '$script:InstalledNode',
      'main.js',
      '$script:InstalledConfig',
      '拒绝操作非受管计划任务',
      '不要伪造所有权标记',
    ])
    // Ownership assertions never touch (or repair) the conflicting task.
    assertIncludesNone(body, ['Stop-ScheduledTask', 'Unregister-ScheduledTask', 'Remove-Item', 'Register-ScheduledTask'])
  })

  it('executes node directly in the scheduled action, without wrapper or redirection', () => {
    const body = functionBody('New-DaemonScheduledTaskDefinition')
    assertIncludesAll(body, [
      'New-ScheduledTaskAction -Execute $NodeExecutable',
      '-Argument $serializedArguments',
      '-WorkingDirectory $WorkingDirectory',
      'New-ScheduledTaskTrigger -AtLogOn -User $UserSid',
      '-LogonType Interactive -RunLevel Limited',
      '-ExecutionTimeLimit ([TimeSpan]::Zero)',
      '-MultipleInstances IgnoreNew',
      '-RestartCount 3',
      '-RestartInterval (New-TimeSpan -Minutes 1)',
    ])
    assertIncludesNone(body, ['cmd.exe', 'powershell.exe', 'pwsh.exe', 'Start-Process', 'RedirectStandard', '2>&1', 'Highest'])
  })

  it('always quotes task arguments through the dedicated serializer', () => {
    const definition = functionBody('New-DaemonScheduledTaskDefinition')
    assert.ok(definition.includes('ConvertTo-WindowsCommandLine -Arguments $DaemonArguments'))
    assert.ok(!definition.includes('$DaemonArguments -join'))
    const serializer = functionBody('ConvertTo-WindowsCommandLineArgument')
    assert.ok(serializer.includes('2 * $backslashes'))
    assert.ok(serializer.includes("$builder.Append('\"')"))
    assertIncludesNone(serializer, ['notmatch', 'return $Argument'])
  })

  it('runs main.js without encoding and uses the installed lib as working directory', () => {
    assertIncludesAll(functionBody('New-DaemonArgumentList'), ['return @("main.js", "--config", $script:InstalledConfig)'])
    assertIncludesNone(functionBody('New-DaemonArgumentList'), ['ToBase64String', '$script:InstalledNode'])
    assertIncludesAll(functionBody('Invoke-InstallCommon'), ['-WorkingDirectory $script:InstalledLib', '-NodeExecutable $script:InstalledNode'])
  })

  it('captures natively without encoding and can set a working directory', () => {
    const capture = functionBody('Invoke-NativeProcess')
    assertIncludesAll(capture, ['[AllowEmptyString()][string] $WorkingDirectory', '$startInfo.WorkingDirectory = $WorkingDirectory'])
    assertIncludesNone(capture, ['ToBase64String', '--base64-args', 'ArgumentList'])
    assertInOrder(
      capture,
      '$process.StandardOutput.ReadToEndAsync()',
      '$process.StandardError.ReadToEndAsync()',
      '$process.WaitForExit()',
      '$stdout.GetAwaiter().GetResult()',
      '$stderr.GetAwaiter().GetResult()',
      '$process.ExitCode',
      '$process.Dispose()',
    )
  })

  it('unpacks the archive privately and verifies the helper layout', () => {
    const body = functionBody('Assert-DownloadedArchive')
    assertIncludesAll(body, [
      'Get-Command "tar.exe"',
      'New-PrivateDirectory -Path $script:StagedLib',
      '-Arguments @("-xzf", $script:DownloadedArchive, "-C", $script:StagedLib)',
      'is missing main.js',
      'native\\lock-helper.js',
      'node_modules\\koffi',
      'is missing the bundled node.exe runtime',
      '@("main.js", "--version")',
      'daemon --version does not match resolved release tag',
    ])
    assert.ok(scriptText().includes('$script:StagedLib = Join-Path $script:DownloadDirectory "lib"'))
  })
})

describe('Windows installer lifecycle: validation and lock ownership precede every destructive transition', () => {
  it('validates ownership and inputs before downloading, and publishes only after the lock', () => {
    assertInOrder(
      functionBody('Invoke-InstallCommon'),
      'Initialize-HostContext',
      'Assert-Version -Value $Version',
      'Resolve-InstallInputs',
      'Assert-EarlyEnvelope',
      'Assert-ManagedLayout',
      'Get-ReleaseArchive',
      'Assert-DownloadedArchive',
      'Prepare-StagedArchive',
      'New-ManagementCopy',
      'New-DaemonScheduledTaskDefinition',
      'New-PrivateDirectory -Path $directory',
      'Start-InstallLockHelper',
      'Stage-WorkProgram',
      'Stop-DaemonTaskAndWait',
      'Publish-Program',
      'Publish-PrivateFile',
      'Register-ScheduledTask',
      'Start-AndVerifyDaemonTask',
    )
    // The lock helper only protects its final directory; all three roots must be created first.
    assert.match(
      functionBody('Invoke-InstallCommon'),
      /foreach \(\$directory in @\(\$script:InstallRoot, \$script:EnvironmentsRoot, \$script:EnvironmentRoot\)\) \{/,
    )
    // Only the bootstrap install writes config/token; update never does.
    assert.ok(functionBody('Invoke-InstallCommon').includes('if ($Operation -eq "install") {'))
  })

  it('holds install.lock from a separate helper copy and waits for daemon.lock', () => {
    const start = functionBody('Start-InstallLockHelper')
    assertIncludesAll(start, [
      'ConvertTo-WindowsCommandLine -Arguments @(',
      '$helper, "hold", $script:EnvironmentRoot',
      'RedirectStandardInput = $true',
      '$process.StandardOutput.ReadLineAsync()',
      '$code -eq 75',
      '另一个安装操作正在执行',
    ])
    assertIncludesAll(functionBody('Remove-WorkDirectory'), ['Remove-Item -LiteralPath $script:WorkDirectory -Recurse -Force -ErrorAction Stop', '$script:CleanupFailed = $true'])
    assertIncludesAll(functionBody('Stop-InstallLockHelper'), [
      '$process.StandardInput.Close()',
      '$process.WaitForExit',
      '锁由进程退出释放',
    ])
    assertIncludesAll(functionBody('Invoke-LockWait'), ['$helper, "wait", $script:EnvironmentRoot', '76 {'])
    const copy = functionBody('New-ManagementCopy')
    assertIncludesAll(copy, [
      'native\\lock-helper.js',
      'node.exe',
      'package.json',
      'delay.js',
      'node_modules\\koffi',
      '@koromix\\koffi-win32-x64',
      '独立管理副本不完整',
    ])
    // The helper copy is cleaned only by a bounded temporary-directory remover after it exits.
    assertIncludesAll(functionBody('Remove-ManagedTemporaryDirectory'), ['GetTempPath', 'StartsWith($root + "\\"', '拒绝删除非临时目录'])
    assertInOrder(
      functionBody('Invoke-Main'),
      'Stop-InstallLockHelper',
      'Remove-ManagedTemporaryDirectory -Path $script:ManagementDirectory',
      'Remove-ManagedTemporaryDirectory -Path $script:DownloadDirectory',
    )
  })

  it('never keeps backups, rollback or operation history', () => {
    const text = scriptText()
    for (const forbidden of ['Backup-ManagedFiles', '$script:BackupDirectory', 'backups', 'Export-ScheduledTask', 'Move-Item -LiteralPath $script:InstalledLib']) {
      assert.ok(!text.includes(forbidden), forbidden)
    }
    assertIncludesAll(functionBody('Publish-Program'), [
      'Remove-Item -LiteralPath $script:InstalledLib -Recurse -Force',
      'Move-Item -LiteralPath $StagedLib -Destination $script:InstalledLib',
      'Set-Acl -LiteralPath $script:InstalledLib -AclObject (New-PrivateAcl -Directory)',
    ])
  })

  it('keeps the real lock state so a failed operation still cleans its staging', () => {
    const main = functionBody('Invoke-Main')
    assert.ok(!main.includes('$script:HelperHeld = $false'), 'the lock state must survive the catch')
    assertIncludesAll(main, ['if ($script:WorkOwned) { Remove-WorkDirectory }', 'Stop-InstallLockHelper'])
    assertInOrder(main, 'Remove-WorkDirectory', 'Write-LastOperation -Result "succeeded"')
    assertIncludesAll(functionBody('Remove-WorkDirectory'), ['$script:CleanupFailed = $true'])
  })

  it('records one bounded last-operation entry with fixed fields', () => {
    const body = functionBody('Write-LastOperation')
    assertIncludesAll(body, [
      'operationId = $script:OperationId',
      'type = $Command',
      'targetVersion = $targetVersion',
      'phase = $script:PhaseCode',
      'result = $Result',
      'time = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()',
      'errorCode = $ErrorCode',
      '$json.Length -gt 4096',
      '[IO.File]::Replace',
      'return $false',
      'return $true',
    ])
    assertIncludesNone(body, ['token', 'ReadAllText($script:InstalledConfig'])
    assertIncludesAll(functionBody('Invoke-Main'), ['Write-LastOperation -Result "succeeded"', 'Write-LastOperation -Result "failed"'])
  })

  it('reports the current fixed phase in failure diagnostics', () => {
    const body = functionBody('Throw-Failure')
    assertIncludesAll(body, ['$script:PhaseCode', '[阶段 ${phase}]'])
    assertIncludesNone(body, ['$args', '$Command'])
  })

  it('uninstall takes the helper copy, stops and unregisters before removing the program', () => {
    assertInOrder(
      functionBody('Invoke-Uninstall'),
      'New-ManagementCopy -SourceRoot $script:InstalledLib',
      'Start-InstallLockHelper',
      'Stop-DaemonTaskAndWait',
      'Unregister-ScheduledTask',
      'Remove-Item -LiteralPath $script:InstalledLib -Recurse -Force',
      '配置、凭据、技能与用户数据已保留。',
    )
    // An absent task reports success without deleting anything.
    const body = functionBody('Invoke-Uninstall')
    const absent = body.slice(0, body.indexOf('Assert-ManagedTask -Task $task'))
    assertIncludesAll(absent, ['$task = Find-DaemonTask', '未安装受管服务。配置、凭据与用户数据均已保留。', 'return 0'])
    assertIncludesNone(absent, ['Unregister-ScheduledTask', 'Remove-Item', 'Stop-ScheduledTask'])
  })

  it('status is read-only and distinguishes task state from health', () => {
    const body = functionBody('Invoke-Status')
    assertIncludesAll(body, ['Get-ScheduledTaskInfo', 'State -eq "Running"', 'task state is not gateway health', '$script:LastOperation'])
    assertIncludesNone(body, ['Start-ScheduledTask', 'Stop-ScheduledTask', 'Register-ScheduledTask', 'Unregister-ScheduledTask', 'Remove-Item'])
  })

  it('always cleans its temporary directories in the main finally', () => {
    const body = functionBody('Invoke-Main')
    assertIncludesAll(body, ['finally', 'Remove-ManagedTemporaryDirectory', '$script:ManagementDirectory', '$script:DownloadDirectory'])
    assert.ok(!body.includes('SilentlyContinue'))
  })

  it('downloads an explicit official release and checks the staged config', () => {
    assertIncludesAll(functionBody('Get-ReleaseArchive'), [
      '$asset = "kk-studio-daemon-$tag-windows-amd64.tar.gz"',
      'https://github.com/fengwk/kk-studio/releases/download/$tag',
      '[Net.SecurityProtocolType]::Tls12',
      'Get-FileHash',
      'New-PrivateDirectory',
      'Assert-Version',
    ])
    assertInOrder(
      functionBody('Prepare-StagedArchive'),
      'Copy-PrivateFile -Source $ConfigFile',
      'Copy-PrivateFile -Source $TokenFile',
      'Assert-ConfigEnvelope',
      'Invoke-CheckConfig',
    )
  })
})

describe('Windows installer native contracts: native tests run only where Windows and PowerShell are genuinely available', () => {
  it('needs no Java and asserts a direct argv round trip', () => {
    const native = readFileSync(NATIVE_TEST, 'latin1')
    assert.doesNotMatch(readFileSync(NATIVE_TEST, 'utf8'), /[^\x00-\x7f]/u, 'the native suite stays ASCII')
    assertIncludesAll(native, [
      'ConvertTo-WindowsCommandLine -Arguments (@($outputPath) + $arguments)',
      'Assert-DirectArgumentRoundTrip',
      'real node.exe',
    ])
  })

  it('runs the native PowerShell contracts', { skip: process.platform !== 'win32' && 'native ScheduledTasks contracts require Windows' }, () => {
    const probe = spawnSync('powershell', ['-NoLogo', '-Command', 'exit 0'])
    const executable = probe.error ? 'pwsh' : 'powershell'
    const result = spawnSync(
      executable,
      ['-NoLogo', '-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', NATIVE_TEST],
      { cwd: REPOSITORY_ROOT, encoding: 'utf8', timeout: 300_000 },
    )
    assert.equal(result.status, 0, result.stdout + result.stderr)
  })
})
