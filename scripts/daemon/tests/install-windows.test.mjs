/**
 * Static and optional native contracts for the Windows daemon installer.
 *
 * The repository's default script tests run on Linux, where the Windows-only ScheduledTasks module
 * does not exist. These tests therefore inspect lifecycle and security ordering directly from the
 * shipped `install.ps1` so the pre-publication checks, private-input snapshot, program-directory
 * publication and stop/unregister/report ordering stay verifiable everywhere.
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

describe('Windows installer surface: the public PowerShell entry stays discoverable and PowerShell 5.1 compatible', () => {
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

  it('lists commands, layout and Windows limitations in the help', () => {
    const usage = functionBody('Write-Usage')
    assertIncludesAll(usage, [
      'install',
      'status',
      'uninstall',
      'help',
      '-ConfigFile',
      '-TokenFile',
      '%USERPROFILE%\\.kk-studio',
      'daemon.json, daemon.token, lib, logs, backups',
      'No user Node is required',
      'AtLogOn',
      'Interactive',
      'Limited',
      'does not capture daemon stdout/stderr',
      'best effort settings',
    ])
    assertIncludesNone(scriptText(), [
      '-JavaHome',
      'JDK',
      'GatewayUri',
      'RegistrationToken',
      'FromSource',
      '$Version',
      'LspConfig',
      'Read-Host',
      'Invoke-Upgrade',
      'Resolve-RepositoryRoot',
      'Resolve-Executable',
      'Assert-BashReady',
    ])
  })

  it('needs no user JRE or Node', () => {
    // The frozen release archive is the only runtime source: no JDK/JAR and no PATH Node.
    const text = scriptText()
    assertIncludesNone(text, [
      'JAVA_HOME_21',
      'JAVA_TOOL_OPTIONS',
      '_JAVA_OPTIONS',
      'JDK_JAVA_OPTIONS',
      'java.exe',
      '.jar',
      '--base64-args',
      'Assert-Jdk21',
      'Resolve-JavaHome',
      'Invoke-WithoutJavaOptionEnvironment',
      'ConvertTo-DaemonEncodedArguments',
    ])
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

describe('Windows installer security: credentials, task ownership and direct execution are fail-closed contracts', () => {
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

  it('keeps install inputs private siblings and rejects unknown options', () => {
    const body = functionBody('Resolve-InstallInputs')
    assertIncludesAll(body, [
      'Assert-PrivateFile -Path $ConfigFile',
      'Assert-PrivateFile -Path $TokenFile',
      'must be siblings named daemon.json and daemon.token',
    ])
    // The option whitelist is exactly what the usage text offers.
    assert.ok(scriptText().includes('$script:InstallParameterNames = @("ConfigFile", "TokenFile")'))
  })

  it('surfaces only the bounded trusted config marker from the preflight', () => {
    const text = scriptText()
    assertIncludesAll(text, ['$script:ConfigFailureMarker = "Invalid daemon configuration: "', '$script:ConfigFailureMaxLength'])
    assertIncludesAll(functionBody('Get-TrustedConfigFailureDetail'), [
      'StartsWith($script:ConfigFailureMarker',
      '$candidate.Length -gt $script:ConfigFailureMaxLength',
      '$candidate -match',
    ])
    const staged = functionBody('Prepare-StagedArchive')
    assertIncludesAll(staged, [
      'Get-TrustedConfigFailureDetail -Stdout $result.Stdout -Stderr $result.Stderr',
      '输出无法识别',
    ])
    // Raw streams must never be interpolated into the user-visible failure.
    assertIncludesNone(staged, ['$($result.Stdout', '$($result.Stderr'])
  })

  it('owns the task through an exact per-user marker', () => {
    const text = scriptText()
    assertIncludesAll(text, [
      '"kk-studio-environment-daemon-$($script:CurrentSid)"',
      '"Managed by $($script:ManagedBy); schema=1; ownerSid=$($script:CurrentSid)"',
    ])
    const body = functionBody('Assert-ManagedTask')
    assertIncludesAll(body, ['$Task.Description -ne $script:TaskDescription', '拒绝操作非受管计划任务'])
    // Ownership assertions never touch (or repair) the conflicting task.
    assertIncludesNone(body, ['Export-ScheduledTask', 'Stop-ScheduledTask', 'Unregister-ScheduledTask', 'Remove-Item'])
  })

  it('executes node directly in the scheduled action, without wrapper or redirection', () => {
    // Task Scheduler must own node.exe itself so lifecycle state is not a shell process.
    const body = functionBody('New-DaemonScheduledTaskDefinition')
    assertIncludesAll(body, [
      'New-ScheduledTaskAction -Execute $NodeExecutable',
      '-Argument $serializedArguments',
      '-WorkingDirectory $WorkingDirectory',
    ])
    assertIncludesNone(body, ['cmd.exe', 'powershell.exe', 'pwsh.exe', 'Start-Process', 'RedirectStandard', '2>&1'])
  })

  it('fixes long-running and least-privilege defaults in the task model', () => {
    // The generated task must not stop after 72 hours or request elevated privileges.
    const body = functionBody('New-DaemonScheduledTaskDefinition')
    assertIncludesAll(body, [
      'New-ScheduledTaskTrigger -AtLogOn -User $UserSid',
      '-LogonType Interactive -RunLevel Limited',
      '-ExecutionTimeLimit ([TimeSpan]::Zero)',
      '-AllowStartIfOnBatteries',
      '-DontStopIfGoingOnBatteries',
      '-MultipleInstances IgnoreNew',
      '-RestartCount 3',
      '-RestartInterval (New-TimeSpan -Minutes 1)',
    ])
    assertIncludesNone(body, ['RunOnlyIfNetworkAvailable', 'Highest'])
  })

  it('always quotes task arguments through the dedicated serializer', () => {
    // The task command line must match the independently generated update script exactly.
    const definition = functionBody('New-DaemonScheduledTaskDefinition')
    assert.ok(definition.includes('ConvertTo-WindowsCommandLine -Arguments $DaemonArguments'))
    assert.ok(!definition.includes('$DaemonArguments -join'))
    const serializer = functionBody('ConvertTo-WindowsCommandLineArgument')
    assert.ok(serializer.includes('2 * $backslashes'))
    // Always quote: an unquoted bypass would diverge on spaces or metacharacters.
    assert.ok(serializer.includes("$builder.Append('\"')"))
    assertIncludesNone(serializer, ['notmatch', 'return $Argument'])
  })

  it('runs main.js without encoding', () => {
    // Task Scheduler owns one command line; main.js is an ASCII leaf resolved against cwd.
    const body = functionBody('New-DaemonArgumentList')
    assert.ok(body.includes('return @("main.js", "--config", $script:InstalledConfig)'))
    assertIncludesNone(body, ['ToBase64String', '$script:InstalledNode'])
  })

  it('uses the installed lib as the task working directory', () => {
    // A profile-dependent absolute main.js path must never be stored in the command line.
    assertIncludesAll(functionBody('Invoke-Install'), ['-WorkingDirectory $script:InstalledLib', '-NodeExecutable $script:InstalledNode'])
    assert.ok(functionBody('New-DaemonScheduledTaskDefinition').includes('-WorkingDirectory $WorkingDirectory'))
  })

  it('captures natively without encoding and can set a working directory', () => {
    // Only verbatim argv and native cwd travel into the probe; no pre-encoding is applied.
    const capture = functionBody('Invoke-NativeProcess')
    assertIncludesAll(capture, ['[AllowEmptyString()][string] $WorkingDirectory', '$startInfo.WorkingDirectory = $WorkingDirectory'])
    assertIncludesNone(capture, ['ToBase64String', '--base64-args'])

    const gate = functionBody('Assert-DownloadedArchive')
    assertIncludesAll(gate, ['@("main.js", "--version")', '-WorkingDirectory $script:StagedLib', '"runtime\\code-server\\lib\\node.exe"', 'main.js'])
    assert.ok(!gate.includes('ToBase64String'))
  })

  it('unpacks the archive privately and verifies the layout', () => {
    // A SHA-verified archive still has to carry the expected program and frozen runtime.
    const body = functionBody('Assert-DownloadedArchive')
    assertIncludesAll(body, [
      'Get-Command "tar.exe"',
      'New-PrivateDirectory -Path $script:StagedLib',
      '-Arguments @("-xzf", $script:DownloadedArchive, "-C", $script:StagedLib)',
      'is missing main.js',
      'is missing the bundled node.exe runtime',
    ])
    // The unpack target is a fresh private directory inside the private download directory.
    assert.ok(scriptText().includes('$script:StagedLib = Join-Path $script:DownloadDirectory "lib"'))
  })
})

describe('Windows installer lifecycle: validation and ownership checks precede every destructive transition', () => {
  it('validates owner and artifacts before stopping or forcing', () => {
    // Foreign tasks/layout conflicts precede downloads; all preflight precedes publication.
    assertInOrder(
      functionBody('Invoke-Install'),
      'Initialize-HostContext',
      'Find-DaemonTask',
      'Assert-ManagedTask',
      'Assert-ManagedLayout',
      'Resolve-InstallInputs',
      'Prepare-StagedArchive',
      'New-DaemonScheduledTaskDefinition',
      'Assert-ManagedLayout',
      'Backup-ManagedFiles',
      'Stop-DaemonTaskIfRunning',
      'Assert-ManagedTask',
      'Move-Item -LiteralPath $script:StagedLib -Destination $script:InstalledLib',
      'Publish-PrivateFile',
      'Register-ScheduledTask',
      'Start-AndVerifyDaemonTask',
    )
  })

  it('publishes the program directory atomically with a private ACL', () => {
    // The previous program is moved into the backup before the staged lib replaces it.
    const body = functionBody('Invoke-Install')
    assertInOrder(
      body,
      'Move-Item -LiteralPath $script:InstalledLib',
      '-Destination (Join-Path $script:BackupDirectory "lib")',
      'Move-Item -LiteralPath $script:StagedLib -Destination $script:InstalledLib',
      'Set-Acl -LiteralPath $script:InstalledLib -AclObject (New-PrivateAcl -Directory)',
    )
    assertIncludesAll(functionBody('Backup-ManagedFiles'), [
      '$script:InstalledLib',
      '$script:InstalledConfig',
      '$script:InstalledToken',
      '"backups"',
      'Guid',
      'Assert-PrivateFile',
      'Copy-PrivateFile',
      'Export-ScheduledTask',
    ])
  })

  it('never rolls back or touches external paths on failure recovery', () => {
    // Backups contain only fixed regular owned targets; failures report manual recovery.
    const body = functionBody('Invoke-Install')
    assertIncludesAll(body, ['未自动回滚', '备份：'])
    assert.ok(!body.slice(body.indexOf('    catch {')).includes('Publish-PrivateFile'))
    const conflict = functionBody('Assert-ManagedTask')
    assertIncludesAll(conflict, ['缺少精确所有权标记', 'Get-ScheduledTask', '不要伪造所有权标记'])
    assertIncludesNone(conflict, ['Export-ScheduledTask', 'Stop-ScheduledTask', 'Unregister-ScheduledTask', 'Get-ConflictBackupCommands'])
  })

  it('refuses to overwrite a program directory without a managed task', () => {
    // An unknown program directory must never be replaced or deleted.
    const body = functionBody('Assert-ManagedLayout')
    assertIncludesAll(body, ['Test-Path -LiteralPath $script:InstalledLib', '$script:InstalledLib', '未自动回滚，未知文件未变更'])
    assertIncludesNone(body, ['Remove-Item', 'Move-Item', 'Set-Acl'])
  })

  it('uninstall stops and unregisters before removing the program', () => {
    // Stop or unregister failure must leave the managed program directory in place.
    assertInOrder(
      functionBody('Invoke-Uninstall'),
      'Assert-ManagedTask',
      'Stop-DaemonTaskIfRunning',
      'Get-RequiredManagedTask',
      'Unregister-ScheduledTask',
      'Remove-Item -LiteralPath $script:InstalledLib -Recurse -Force',
      '配置、数据和备份已保留。',
    )
  })

  it('uninstall of an absent task reports success without deleting anything', () => {
    // Absence is reported independently of deregistration; nothing else is touched.
    const body = functionBody('Invoke-Uninstall')
    const absent = body.slice(0, body.indexOf('Assert-ManagedTask -Task $task'))
    assertIncludesAll(absent, ['$task = Find-DaemonTask', 'KK Studio 环境未安装受管服务。配置、数据和备份均已保留。', 'return 0'])
    assertIncludesNone(absent, ['Unregister-ScheduledTask', 'Remove-Item', 'Stop-ScheduledTask'])
  })

  it('status is read-only and distinguishes task state from health', () => {
    // Status may inspect task state but must not start, stop, or mutate it.
    const body = functionBody('Invoke-Status')
    assertIncludesAll(body, ['Get-ScheduledTaskInfo', 'State -eq "Running"', 'task state is not gateway health'])
    assertIncludesNone(body, ['Start-ScheduledTask', 'Stop-ScheduledTask', 'Register-ScheduledTask', 'Unregister-ScheduledTask', 'Remove-Item'])
  })

  it('always cleans the staged archive in the main finally', () => {
    // Only the installer-created staging directory is deleted on failures.
    const body = functionBody('Invoke-Main')
    assertIncludesAll(body, ['finally', 'Remove-Item -LiteralPath $script:DownloadDirectory'])
    assert.ok(!body.includes('SilentlyContinue'))
  })

  it('is latest-only and checks the staged config', () => {
    // Official latest tag selects the windows archive, then it validates sibling inputs.
    assert.ok(scriptText().includes('[string] $Command = "install"'))
    assertInOrder(
      functionBody('Prepare-StagedArchive'),
      'Get-ReleaseArchive',
      'Assert-DownloadedArchive',
      'Copy-PrivateFile -Source $ConfigFile',
      'Copy-PrivateFile -Source $TokenFile',
      '"--check-config"',
      'Invoke-NativeProcess',
    )
    assert.ok(functionBody('Prepare-StagedArchive').includes('"Daemon configuration is valid"'))
    assertIncludesAll(functionBody('Get-ReleaseArchive'), [
      '$asset = "kk-studio-daemon-$tag-windows-amd64.tar.gz"',
      'https://api.github.com/repos/fengwk/kk-studio/releases/latest',
      'https://github.com/fengwk/kk-studio/releases/download/$tag',
      '[Net.SecurityProtocolType]::Tls12',
      'Get-FileHash',
      'New-PrivateDirectory',
      'Assert-ReleaseTag',
    ])
  })
})

describe('Windows installer native contracts: native tests run only where Windows and PowerShell are genuinely available', () => {
  it('needs no Java and asserts a direct argv round trip', () => {
    // The native leg must exercise the shipped serializer against a real node.exe.
    const native = readFileSync(NATIVE_TEST, 'latin1')
    assert.doesNotMatch(readFileSync(NATIVE_TEST, 'utf8'), /[^\x00-\x7f]/u, 'the native suite stays ASCII')
    assertIncludesAll(native, [
      'ConvertTo-WindowsCommandLine -Arguments (@($outputPath) + $arguments)',
      'Assert-DirectArgumentRoundTrip',
      'real node.exe',
    ])
    assertIncludesNone(native, ['java.exe', 'JDK', 'base64', 'Base64', 'DaemonArguments', '-jar'])
  })

  it('also runs the managed-update native suite from the installer suite', () => {
    // The CI-invoked installer suite must chain the update suite for both PS hosts.
    assertIncludesAll(readFileSync(NATIVE_TEST, 'utf8'), ['test_daemon_update_windows.ps1', '& $hostExe', 'if ($LASTEXITCODE -ne 0)'])
  })

  it('keeps native capture streams separate and drains both before waiting', () => {
    // Guard PS 5.1 compatibility and deadlock ordering; real outcomes are tested in PowerShell.
    const body = functionBody('Invoke-NativeProcess')
    assertIncludesAll(body, ['ConvertTo-WindowsCommandLine -Arguments $Arguments', '$startInfo.UseShellExecute = $false'])
    assertInOrder(
      body,
      '$process.StandardOutput.ReadToEndAsync()',
      '$process.StandardError.ReadToEndAsync()',
      '$process.WaitForExit()',
      '$stdout.GetAwaiter().GetResult()',
      '$stderr.GetAwaiter().GetResult()',
      '$process.ExitCode',
      '$process.Dispose()',
    )
    assert.ok(!body.includes('ArgumentList'), 'ProcessStartInfo.ArgumentList requires newer .NET')
    const probe = functionBody('Assert-DownloadedArchive')
    assert.ok(probe.includes('Invoke-NativeProcess'))
    assertIncludesNone(probe, ['2>&1', '$LASTEXITCODE'])
  })

  it('runs the native PowerShell contracts', { skip: process.platform !== 'win32' && 'native ScheduledTasks contracts require Windows' }, () => {
    // The companion suite validates real cmdlet objects and the node argv round-trip.
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
