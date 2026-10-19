/**
 * Static contracts for the Windows managed update script.
 *
 * `WINDOWS_UPDATE_SCRIPT` is imported from the shipped `daemon/src/update-scripts.ts` through Node,
 * so these assertions always inspect the real source rather than a test copy. The script runs as an
 * independent one-shot scheduled task on Windows, where the ScheduledTasks module does not exist on
 * the Linux/macOS CI legs; the static tests therefore pin the filesystem layout, permit/ownership
 * ordering and the destructive-action boundaries wherever the suite runs.
 *
 * The native companion (`test_daemon_update_windows.ps1`) drives the same exported text through the
 * ScheduledTasks API with disposable fixtures and is invoked by the Windows gate.
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

const PERMIT_WAIT = 'while (-not (Test-Path -LiteralPath $PermitPath -PathType Leaf))'

describe('Windows update surface: one fail-closed wrapper with an explicit exit contract', () => {
  it('declares the three Node distribution directories', () => {
    const text = scriptText()
    assertIncludesAll(text, [
      '[Parameter(Mandatory = $true)][string]$OperationDir',
      '[Parameter(Mandatory = $true)][string]$StagedDir',
      '[Parameter(Mandatory = $true)][string]$InstalledDir',
      '$OperationId = Split-Path -Leaf $OperationDir',
      "$ResultPath = Join-Path $OperationDir 'update-result.json'",
      "(Join-Path $StagedDir 'main.js')",
      "$ErrorActionPreference = 'Stop'",
      // The native suite shortens these bounds in its temporary copy; pin the production values.
      '$PermitWaitSeconds = 120',
      '$StopWaitSeconds = 30',
    ])
  })

  it('keeps failure reporting and cleanup independent', () => {
    const text = scriptText()
    // One fail-closed entry point that throws, so the finally block always runs.
    assertIncludesAll(text, [
      'function Fail-Closed([string]$Message) {',
      'throw [System.InvalidOperationException]::new($Message)',
      'operationId = $OperationId; phase = $Phase; message = $Message',
      'if ($null -ne $failure) { exit 1 }',
      // Write-Error is script-terminating under $ErrorActionPreference = 'Stop' and would skip the
      // failure receipt and the cleanup; the diagnostic goes to stderr instead.
      '[Console]::Error.WriteLine("update ${OperationId} failed: $Message")',
      // Reporting may itself fail: the failure receipt and the PREPARED receipt are both best
      // effort and never abort the surrounding cleanup.
      'could not write the failure receipt',
      'could not write the PREPARED receipt',
      // The one-shot task removal sits in its own finally and warns instead of swallowing.
      'Unregister-ScheduledTask -TaskName $UpdateTaskName -Confirm:$false -ErrorAction Stop',
      'could not unregister its one-shot task',
    ])
    assert.ok(!withoutComments(text).includes('Write-Error'))
    assertIncludesNone(text, [
      'Unregister-ScheduledTask -TaskName $UpdateTaskName -Confirm:$false -ErrorAction SilentlyContinue',
    ])
  })

  it('writes the failure receipt before the cleanup and outside the diagnostic', () => {
    const text = scriptText()
    const failureReceipt = text.indexOf("Write-Result 'FAILED' $failure")
    const diagnostic = text.indexOf('Write-FailureDiagnostic $failure')
    const unregister = text.indexOf('Unregister-ScheduledTask -TaskName $UpdateTaskName')
    assert.ok(failureReceipt >= 0 && failureReceipt < diagnostic)
    assert.ok(diagnostic < unregister)
  })

  it('reports only the stage and the exception type for unexpected failures', () => {
    const text = scriptText()
    for (const stage of ['preflight', 'handoff', 'permit', 'stop', 'backup', 'replace', 'restart']) {
      assert.ok(text.includes(`$Step = '${stage}'`), `the script must pin the ${stage} stage`)
    }
    assert.ok(text.includes('$failure = "update failed during ${Step} ($($_.Exception.GetType().Name))"'))
  })

  it('writes the receipt BOM-free and best effort', () => {
    const text = scriptText()
    // The daemon parses the receipt with JSON.parse, so a BOM would break it.
    assert.ok(text.includes('[IO.File]::WriteAllText($ResultPath, ($payload | ConvertTo-Json -Compress))'))
    assert.ok(!text.includes('Set-Content'))
  })

  it('has no registration, elevation or arbitrary command execution', () => {
    const text = scriptText()
    assertIncludesNone(text, [
      'Register-ScheduledTask',
      'New-ScheduledTask',
      'RunLevel',
      'Restart-Computer',
      'Set-ExecutionPolicy',
      'Invoke-Expression',
      'Start-Process',
      'Remove-Item -Recurse',
    ])
    // The only scheduler mutation is unregistering this operation's own one-shot task.
    assert.equal((text.match(/Unregister-ScheduledTask/g) ?? []).length, 1)
  })

  it('never reads credentials or the config contents', () => {
    const code = withoutComments(scriptText())
    assertIncludesNone(code, [
      'daemon.token',
      'Get-Content -LiteralPath $configPath',
      'ReadAllText',
      'ReadAllBytes',
      'ConvertFrom-SecureString',
    ])
  })
})

describe('Windows update handoff gate: the third argument is the installed distribution, never a user-supplied path', () => {
  it('requires the handoff identity and checks the state later', () => {
    assertIncludesAll(scriptText(), [
      "if (-not (Test-ManagedFile -Path $HandoffPath)) { Fail-Closed 'handoff metadata is missing' }",
      'ConvertFrom-Json -ErrorAction Stop',
      "Fail-Closed 'handoff metadata is malformed'",
      "if ([string]$handoff.operationId -ne $OperationId) { Fail-Closed 'handoff metadata does not match the operation' }",
      "if ([string]$handoff.state -ne 'ACCEPTED') { Fail-Closed 'handoff metadata is not accepted' }",
    ])
  })

  it('runs the state gate after the permit, not before', () => {
    // The one-shot task starts while the daemon still persists PREPARING, so the state gate must
    // sit after the permit: only the operation identity may be checked up front.
    const text = scriptText()
    const permitWait = text.indexOf(PERMIT_WAIT)
    const state = text.indexOf("if ([string]$handoff.state -ne 'ACCEPTED')")
    assert.ok(permitWait >= 0 && permitWait < state)
    const code = withoutComments(text.slice(0, permitWait))
    assert.ok(code.includes('handoff metadata does not match the operation'))
    assert.ok(!code.includes('state'))
    assert.ok(!code.includes('ACCEPTED'))
  })

  it('requires the permit to be a regular file naming this operation', () => {
    const text = scriptText()
    const permitWait = text.indexOf(PERMIT_WAIT)
    const regular = text.indexOf(
      "if (-not (Test-ManagedFile -Path $PermitPath)) { Fail-Closed 'the update permit is not a regular file' }",
    )
    const content = text.indexOf(
      "if ((Get-Content -LiteralPath $PermitPath -Raw).Trim() -ne $OperationId) { Fail-Closed 'the update permit does not match the operation' }",
    )
    const stop = text.indexOf('Stop-ScheduledTask -TaskName $TaskName')
    assert.ok(permitWait >= 0 && permitWait < regular)
    assert.ok(regular < content)
    assert.ok(content < stop)
  })

  it('checks the staged and installed layout before the permit wait', () => {
    const text = scriptText()
    assertInOrder(
      text.slice(0, text.indexOf(PERMIT_WAIT)),
      "if (-not (Test-Path -LiteralPath (Join-Path $StagedDir 'main.js') -PathType Leaf))",
      'if (-not (Test-Path -LiteralPath $InstalledDir))',
    )
  })

  it('fails closed on a missing permit without touching the task', () => {
    const text = scriptText()
    const permitWait = text.indexOf(PERMIT_WAIT)
    const timeout = text.indexOf("Fail-Closed 'the daemon did not release the update permit in time'")
    const stop = text.indexOf('Stop-ScheduledTask -TaskName $TaskName')
    assert.ok(permitWait >= 0 && permitWait < timeout)
    assert.ok(timeout < stop, 'no service action may precede the permit gate')
    assert.ok(text.includes('$deadline = (Get-Date).AddSeconds($PermitWaitSeconds)'))
  })
})

describe('Windows update task ownership: the managed AtLogOn task is re-verified before anything stops', () => {
  it('uses SID-scoped installer markers for the task name and description', () => {
    assertIncludesAll(scriptText(), [
      '$Sid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value',
      '$TaskName = "kk-studio-environment-daemon-$Sid"',
      '$TaskDescription = "Managed by scripts/daemon/install.ps1; schema=1; ownerSid=$Sid"',
    ])
  })

  it('looks tasks up at the scheduler root by exact identity', () => {
    const text = scriptText()
    assert.ok(text.includes("$TaskPath = '\\'"))
    // Enumerating the root keeps an absent task distinguishable from a real CIM failure: a
    // name-filtered Get-ScheduledTask reports absence as an error.
    assertIncludesAll(text, [
      'Get-ScheduledTask -ErrorAction Stop |',
      'Where-Object { $_.TaskPath -eq $TaskPath -and $_.TaskName -eq $TaskName }',
      'function Find-ManagedTask([string]$TaskName) {',
    ])
    assert.ok(!text.includes('Get-ScheduledTask -TaskName'))
  })

  it('checks the description, exactly one action and the frozen runtime', () => {
    assertIncludesAll(scriptText(), [
      '$task = Find-ManagedTask $TaskName',
      "if ($null -eq $task) { Fail-Closed 'managed scheduled task is missing' }",
      "if ([string]$task.Description -ne $TaskDescription) { Fail-Closed 'managed scheduled task is not owned by the installer' }",
      '$actions = @($task.Actions)',
      "if ($actions.Count -ne 1) { Fail-Closed 'managed scheduled task does not have exactly one action' }",
      "$expectedNode = Join-Path $InstalledDir 'runtime\\code-server\\lib\\node.exe'",
      'if (-not (Test-SamePath -Left ([string]$action.Execute) -Right $expectedNode)) {',
      "Fail-Closed 'managed scheduled task does not run the managed runtime'",
      '$expectedArguments = \'"main.js" "--config" "\' + $configPath + \'"\'',
      'if ([string]$action.Arguments -ne $expectedArguments) {',
      "Fail-Closed 'managed scheduled task arguments do not match the managed configuration'",
      'if (-not (Test-SamePath -Left ([string]$action.WorkingDirectory) -Right $InstalledDir)) {',
      "Fail-Closed 'managed scheduled task working directory does not match the managed installation'",
      "$configPath = Join-Path (Split-Path -Parent $InstalledDir) 'daemon.json'",
    ])
  })

  it('runs the ownership checks before the stop', () => {
    const text = scriptText()
    assertInOrder(
      text.slice(0, text.indexOf('Stop-ScheduledTask -TaskName $TaskName')),
      "if ($null -eq $task) { Fail-Closed 'managed scheduled task is missing' }",
      'if ([string]$task.Description -ne $TaskDescription)',
      'if ($actions.Count -ne 1)',
      'if (-not (Test-SamePath -Left ([string]$action.Execute) -Right $expectedNode))',
      'if ([string]$action.Arguments -ne $expectedArguments)',
      'if (-not (Test-SamePath -Left ([string]$action.WorkingDirectory) -Right $InstalledDir))',
    )
  })

  it('waits for both running and queued to clear after the stop', () => {
    assertIncludesAll(scriptText(), [
      "if ($null -eq $current -or [string]$current.State -notin @('Running', 'Queued')) { break }",
      '$stopDeadline = (Get-Date).AddSeconds($StopWaitSeconds)',
      'Stop-ScheduledTask -TaskName $TaskName -TaskPath $TaskPath',
    ])
  })
})

describe('Windows update backup: every applied update gets its own unique backup and no automatic rollback', () => {
  it('orders the GUID backup and the moves before the restart', () => {
    const text = scriptText()
    assertIncludesAll(text, [
      "'backup-' + [Guid]::NewGuid().ToString('N')",
      'New-Item -ItemType Directory -Force -Path $backupDir | Out-Null',
      "$previous = Join-Path $backupDir 'previous'",
    ])
    const backup = text.indexOf('Move-Item -LiteralPath $InstalledDir -Destination $previous')
    const replace = text.indexOf('Move-Item -LiteralPath $StagedDir -Destination $InstalledDir')
    const restart = text.indexOf('Start-ScheduledTask -TaskName $TaskName')
    assert.ok(backup >= 0 && backup < replace)
    assert.ok(replace < restart)
    assertIncludesNone(text, ['Copy-Item', 'Remove-Item'])
  })
})

describe('Windows update native fixture: the Windows-only companion exercises the same exported contract', () => {
  it('imports the shipped TypeScript module', () => {
    const text = nativeText()
    assertIncludesAll(text, [
      'daemon\\src\\update-scripts.ts',
      'WINDOWS_UPDATE_SCRIPT',
      // PS 5.1 会丢掉内联 -e 源码的引号、按控制台代码页解码 stdout，正文只能走 UTF-8 文件。
      'export-update-script.mjs',
    ])
    assertIncludesNone(text, ['--input-type', 'harness' + '/daemon', '.jar', 'java.exe', 'JAVA_HOME'])
  })

  it('covers the handoff, permit and task contract', () => {
    assertIncludesAll(nativeText(), [
      'handoff.json',
      'permit',
      'ACCEPTED',
      'PREPARING',
      'Running',
      'backup-',
      'previous',
      'handoff metadata is not accepted',
      'the daemon did not release the update permit in time',
      'managed scheduled task is missing',
      'managed scheduled task does not run the managed runtime',
      'managed scheduled task arguments do not match the managed configuration',
      'UnregisterScheduledTask',
      '-OperationDir',
      '-StagedDir',
      '-InstalledDir',
    ])
  })

  it('propagates the update script exit code', () => {
    const text = nativeText()
    assertIncludesAll(text, ['-OperationDir', 'exit $LASTEXITCODE'])
    assert.ok(!text.includes(". '__SCRIPT__'"))
  })

  it('keeps the process handle for the exit code', () => {
    // PS 5.1 丢弃从函数返回的 Process 句柄，ExitCode 变 $null；失败用例会因此假通过。
    const text = nativeText()
    const start = text.indexOf('function Start-Case(')
    const wait = text.indexOf('function Wait-Case(')
    const invoke = text.indexOf('function Invoke-Case(')
    const startBody = text.slice(start, wait)
    const waitBody = text.slice(wait, invoke)
    assert.ok(startBody.includes('[void] $process.Handle'))
    assert.ok(!startBody.includes('return Start-Process'))
    assertIncludesAll(waitBody, [
      '$Process.WaitForExit()',
      '$Process.Refresh()',
      'if ($null -eq $Process.ExitCode)',
      '$Case.Name',
      'return [int] $Process.ExitCode',
    ])
  })

  it('does not register or touch real tasks', () => {
    const text = nativeText()
    assert.ok(!text.includes('Register-ScheduledTask'))
    assert.ok(text.includes('Remove-Case'))
  })
})
