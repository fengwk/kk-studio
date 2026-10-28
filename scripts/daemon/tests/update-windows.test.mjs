/**
 * Static contracts for the Windows managed update script.
 *
 * `WINDOWS_UPDATE_SCRIPT` is imported from the shipped `daemon/src/update-scripts.ts` through Node,
 * so these assertions always inspect the real source. The script only launches the private updater
 * (`run`) or stops/starts the installer-owned scheduled task; handoff, permit, replacement and
 * result state belong to the Node updater and are covered by daemon/test/update-entry.test.ts.
 *
 * The native companion (`test_daemon_update_windows.ps1`) drives the same exported text through
 * disposable ScheduledTasks stubs and is invoked by the Windows gate. These static checks keep the
 * fixture from drifting back to the removed handoff/permit contract on Linux/macOS.
 */

import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, it } from 'node:test'

import { nodeExport, REPOSITORY_ROOT } from './support/release-fixture.mjs'
import { assertIncludesAll, assertIncludesNone, assertInOrder, withoutComments } from './support/source-order.mjs'

const NATIVE_TEST = path.join(REPOSITORY_ROOT, 'scripts/daemon/tests/test_daemon_update_windows.ps1')
const nativeText = () => readFileSync(NATIVE_TEST, 'utf8')

let cached
const scriptText = () => (cached ??= nodeExport('WINDOWS_UPDATE_SCRIPT'))

describe('Windows update source: run and service actions are separate boundaries', () => {
  it('declares the explicit action contract and fixed paths', () => {
    assertIncludesAll(scriptText(), [
      "[ValidateSet('run', 'stop', 'start')][string]$Action",
      '[Parameter(Mandatory = $true)][string]$OperationDir',
      '[Parameter(Mandatory = $true)][string]$StagedDir',
      '[Parameter(Mandatory = $true)][string]$InstalledDir',
      '[string]$UpdaterNode',
      '[string]$UpdaterEntry',
      "$ErrorActionPreference = 'Stop'",
      '$StopWaitSeconds = 30',
    ])
  })

  it('run requires and launches only the private updater with fixed argv', () => {
    const text = scriptText()
    assertIncludesAll(text, [
      "if ($Action -eq 'run') {",
      'if ([string]::IsNullOrEmpty($UpdaterNode) -or [string]::IsNullOrEmpty($UpdaterEntry)) {',
      "Write-Failure 'run requires updater node and entry'",
      "if (-not (Test-Path -LiteralPath $UpdaterNode -PathType Leaf)) { Write-Failure 'updater runtime is missing' }",
      "if (-not (Test-Path -LiteralPath $UpdaterEntry -PathType Leaf)) { Write-Failure 'updater entry is missing' }",
      "& $UpdaterNode $UpdaterEntry '--operation-dir' $OperationDir '--staged-dir' $StagedDir '--installed-dir' $InstalledDir '--update-script' $PSCommandPath",
      'exit $LASTEXITCODE',
    ])
    assertInOrder(
      withoutComments(text),
      "if ($Action -eq 'run') {",
      'exit $LASTEXITCODE',
      'Stop-ScheduledTask -TaskName $TaskName',
    )
  })

  it('does not read credentials or own replacement state', () => {
    const code = withoutComments(scriptText())
    assertIncludesNone(code, [
      'handoff',
      'permit',
      'update-result',
      'backup',
      'Move-Item',
      'Copy-Item',
      'Remove-Item',
      'Write-Result',
      'Register-ScheduledTask',
      'Unregister-ScheduledTask',
      'Invoke-Expression',
      'Start-Process',
      'daemon.token',
    ])
    // The only scheduler mutations are the one stop call and the one start call.
    assert.equal((code.match(/Stop-ScheduledTask/g) ?? []).length, 1)
    assert.equal((code.match(/Start-ScheduledTask/g) ?? []).length, 1)
  })
})

describe('Windows task ownership: the installer marker is re-verified before any service action', () => {
  it('uses SID-scoped installer markers at the scheduler root', () => {
    assertIncludesAll(scriptText(), [
      '$Sid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value',
      '$TaskName = "kk-studio-environment-daemon-$Sid"',
      '$TaskDescription = "Managed by scripts/daemon/install.ps1; schema=1; ownerSid=$Sid"',
      "$TaskPath = '\\'",
      'function Find-ManagedTask([string]$Name) {',
      'Get-ScheduledTask -ErrorAction Stop |',
      'Where-Object { $_.TaskPath -eq $TaskPath -and $_.TaskName -eq $Name }',
    ])
    assert.ok(!scriptText().includes('Get-ScheduledTask -TaskName'))
  })

  it('checks description, exactly one action and the frozen runtime before acting', () => {
    const text = scriptText()
    assertIncludesAll(text, [
      '$task = Find-ManagedTask $TaskName',
      "if ($null -eq $task) { Write-Failure 'managed scheduled task is missing' }",
      "if ([string]$task.Description -ne $TaskDescription) { Write-Failure 'managed scheduled task is not owned by the installer' }",
      '$actions = @($task.Actions)',
      "if ($actions.Count -ne 1) { Write-Failure 'managed scheduled task does not have exactly one action' }",
      '$taskAction = $actions[0]',
      "if (-not (Test-SamePath -Left ([string]$taskAction.Execute) -Right $expectedNode)) {",
      "Write-Failure 'managed scheduled task does not run the managed runtime'",
      "if ([string]$taskAction.Arguments -ne $expectedArguments) {",
      "Write-Failure 'managed scheduled task arguments do not match the managed configuration'",
      "if (-not (Test-SamePath -Left ([string]$taskAction.WorkingDirectory) -Right $InstalledDir)) {",
      "Write-Failure 'managed scheduled task working directory does not match the managed installation'",
    ])
    assert.doesNotMatch(text, /\$action\s*=\s*\$actions\[0\]/iu)
    assertInOrder(
      text,
      "if ([string]$task.Description -ne $TaskDescription)",
      'if ($actions.Count -ne 1)',
      '$taskAction = $actions[0]',
      'Stop-ScheduledTask -TaskName $TaskName',
    )
  })

  it('keeps the -Action parameter separate from the scheduled task action', () => {
    const text = scriptText()
    assert.ok(text.includes("if ($Action -eq 'run')"))
    assert.ok(text.includes('$taskAction = $actions[0]'))
    for (const property of ['Execute', 'Arguments', 'WorkingDirectory']) {
      assert.ok(text.includes(`$taskAction.${property}`), property)
    }
    assert.ok(text.includes("if ($Action -eq 'stop')"))
    assert.ok(!/\$action\s*=\s*\$actions\[0\]/iu.test(text))
  })
})

describe('Windows stop/start boundaries', () => {
  it('stop stops only the installer-owned task, then waits for Running and Queued', () => {
    assertIncludesAll(scriptText(), [
      "if ($Action -eq 'stop') {",
      'Stop-ScheduledTask -TaskName $TaskName -TaskPath $TaskPath',
      '$deadline = (Get-Date).AddSeconds($StopWaitSeconds)',
      "if ($null -eq $current -or [string]$current.State -notin @('Running', 'Queued')) { break }",
      "Write-Failure 'managed scheduled task did not stop'",
    ])
    assertInOrder(
      scriptText(),
      'Stop-ScheduledTask -TaskName $TaskName -TaskPath $TaskPath',
      'Find-ManagedTask $TaskName',
      'Start-ScheduledTask -TaskName $TaskName -TaskPath $TaskPath',
    )
  })

  it('start starts only the installer-owned task after the same ownership checks', () => {
    assertIncludesAll(scriptText(), [
      "if ($Action -eq 'stop') {",
      'Start-ScheduledTask -TaskName $TaskName -TaskPath $TaskPath',
      'exit 0',
    ])
    assertInOrder(
      scriptText(),
      '$taskAction = $actions[0]',
      "if ($Action -eq 'stop') {",
      'Start-ScheduledTask -TaskName $TaskName -TaskPath $TaskPath',
    )
  })
})

describe('Windows native fixture: the Windows-only companion exercises the same exported contract', () => {
  it('imports the shipped TypeScript module through the UTF-8 file exporter', () => {
    const text = nativeText()
    assertIncludesAll(text, [
      'daemon\\src\\update-scripts.ts',
      'WINDOWS_UPDATE_SCRIPT',
      'export-update-script.mjs',
    ])
    assertIncludesNone(text, ['--input-type', 'harness' + '/daemon', '.jar', 'java.exe', 'JAVA_HOME'])
  })

  it('covers run, stop and start without recreating handoff state', () => {
    const text = nativeText()
    assertIncludesAll(text, [
      'Get-ScheduledTask',
      'Stop-ScheduledTask',
      'Start-ScheduledTask',
      '-Action',
      'run',
      'stop',
      'start',
      'managed scheduled task is missing',
      'managed scheduled task is not owned by the installer',
      'managed scheduled task does not run the managed runtime',
      'managed scheduled task arguments do not match the managed configuration',
      'updater|',
      '-UpdaterNode',
      '-UpdaterEntry',
      'exit $LASTEXITCODE',
    ])
    assertIncludesNone(text, ['handoff', 'permit', 'ACCEPTED', 'PREPARING', 'UnregisterScheduledTask', 'backup'])
  })

  it('keeps the child exit code and does not register a real task', () => {
    const text = nativeText()
    assertIncludesAll(text, [
      '[void] $process.Handle',
      '$process.WaitForExit()',
      '$process.Refresh()',
      'if ($null -eq $process.ExitCode)',
    ])
    assert.ok(!text.includes('Register-ScheduledTask'))
    assert.ok(!text.includes('Unregister-ScheduledTask'))
    assert.ok(text.includes('Remove-Item -LiteralPath $sandbox -Recurse -Force'))
  })

  it('covers every fail-closed case with balanced here-strings', () => {
    const text = nativeText()
    assertIncludesAll(text, [
      'foreign-description',
      'foreign-runtime',
      'foreign-arguments',
      'foreign-working-directory',
      'multi-action',
      'scheduler-failure',
      'stop-failure',
      'start-failure',
      'run-missing-arguments',
      'run-missing-runtime',
      'run-missing-entry',
    ])
    // The updater entry and the in-process ScheduledTasks runner are the only here-strings.
    assert.equal((text.match(/@'/gu) ?? []).length, 2)
    assert.equal((text.match(/^'@$/gmu) ?? []).length, 2)
  })
})
