import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import {
  cpSync,
  existsSync,
  mkdirSync,
  mkdtempSync,
  readdirSync,
  readFileSync,
  rmSync,
  symlinkSync,
  writeFileSync,
} from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'
import test from 'node:test'
import { fileURLToPath } from 'node:url'

const REPOSITORY_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../..')
const CHECK_SCRIPT = path.join(REPOSITORY_ROOT, 'scripts/docs/check.mjs')

function createRepositoryMirror() {
  const mirrorRoot = mkdtempSync(path.join(tmpdir(), 'kk-studio-structure-'))
  for (const entry of readdirSync(REPOSITORY_ROOT, { withFileTypes: true })) {
    if (
      entry.name === '.git' ||
      entry.name === '.workspace' ||
      entry.name === 'harness' ||
      entry.name === 'docs'
    ) {
      continue
    }
    symlinkSync(
      path.join(REPOSITORY_ROOT, entry.name),
      path.join(mirrorRoot, entry.name),
      entry.isDirectory() ? 'dir' : 'file',
    )
  }

  // Mutable negative-test surfaces must be physical copies; build outputs are irrelevant.
  const copyOptions = {
    recursive: true,
    filter: (source) => path.basename(source) !== 'target',
  }
  cpSync(path.join(REPOSITORY_ROOT, 'harness'), path.join(mirrorRoot, 'harness'), copyOptions)
  cpSync(path.join(REPOSITORY_ROOT, 'docs'), path.join(mirrorRoot, 'docs'), copyOptions)

  return mirrorRoot
}

test('repository structure guard verifies presence of shared prompt package', () => {
  // Test intent: ensure the legitimate harness/common/.../prompt package remains intact as shared prompt-template code.
  const legitimatePromptPkg = path.join(
    REPOSITORY_ROOT,
    'harness/common/src/main/java/fun/fengwk/kkstudio/harness/common/prompt',
  )
  assert.equal(
    existsSync(legitimatePromptPkg),
    true,
    'harness/common prompt package must exist',
  )
  assert.equal(
    existsSync(path.join(legitimatePromptPkg, 'PromptTemplate.java')),
    true,
    'PromptTemplate.java must exist under harness/common',
  )
  assert.equal(
    existsSync(path.join(legitimatePromptPkg, 'PromptTemplateLoader.java')),
    true,
    'PromptTemplateLoader.java must exist under harness/common',
  )
})

test('repository structure guard verifies root and harness POM module lists', () => {
  // Test intent: ensure declared POM module declarations exactly match expected topology.
  const rootPom = readFileSync(path.join(REPOSITORY_ROOT, 'pom.xml'), 'utf8')
  const rootModules = [...rootPom.matchAll(/<module>\s*([^<\s]+)\s*<\/module>/gu)].map((m) => m[1])
  assert.deepEqual(rootModules, ['share', 'schema', 'canvas', 'harness', 'platform', 'web'])

  const harnessPom = readFileSync(path.join(REPOSITORY_ROOT, 'harness/pom.xml'), 'utf8')
  const harnessModules = [...harnessPom.matchAll(/<module>\s*([^<\s]+)\s*<\/module>/gu)].map((m) => m[1])
  assert.deepEqual(harnessModules, [
    'common',
    'tool',
    'environment',
    'runtime',
    'contributor-api',
    'builtin',
    'infra',
    'daemon',
  ])
})

test('docs check fails when unexpected harness module directory is introduced', () => {
  // Test intent: ensure normal validation via scripts/docs/check.mjs immediately fails
  // if an unexpected harness module directory is introduced, without mutating the repository.
  const mirrorRoot = createRepositoryMirror()
  const unexpectedModuleDir = path.join(mirrorRoot, 'harness/unexpected-module')
  try {
    mkdirSync(unexpectedModuleDir, { recursive: true })
    assert.throws(
      () => {
        execFileSync('node', [CHECK_SCRIPT, '--root', mirrorRoot], {
          cwd: mirrorRoot,
          encoding: 'utf8',
          stdio: ['ignore', 'pipe', 'pipe'],
        })
      },
      (error) => {
        const stderr = error.stderr || ''
        return stderr.includes('harness directories mismatch')
      },
    )
  } finally {
    rmSync(mirrorRoot, { recursive: true, force: true })
  }
})

test('docs check passes against an isolated physical mirror', () => {
  // Test intent: the repository itself must satisfy every documentation and structure gate.
  const mirrorRoot = createRepositoryMirror()
  try {
    const output = execFileSync('node', [CHECK_SCRIPT, '--root', mirrorRoot], {
      cwd: mirrorRoot,
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'pipe'],
    })
    assert.equal(output.includes('PASS docs'), true, `expected PASS docs, got:\n${output}`)
  } finally {
    rmSync(mirrorRoot, { recursive: true, force: true })
  }
})

test('docs check fails when harness module docs path is reintroduced', () => {
  // Test intent: ensure docs check rejects any docs directory or file under any harness module,
  // verifying that relative paths are reported and real repository is not mutated.
  const mirrorRoot = createRepositoryMirror()
  const realRuntimeDocsDir = path.join(REPOSITORY_ROOT, 'harness/runtime/docs')
  assert.equal(existsSync(realRuntimeDocsDir), false)

  try {
    const mirrorRuntimeDocsDir = path.join(mirrorRoot, 'harness/runtime/docs')
    mkdirSync(mirrorRuntimeDocsDir, { recursive: true })
    writeFileSync(path.join(mirrorRuntimeDocsDir, 'README.md'), '# Fixture local doc\n')

    assert.throws(
      () => {
        execFileSync('node', [CHECK_SCRIPT, '--root', mirrorRoot], {
          cwd: mirrorRoot,
          encoding: 'utf8',
          stdio: ['ignore', 'pipe', 'pipe'],
        })
      },
      (error) => {
        const stderr = error.stderr || ''
        return (
          stderr.includes('forbidden harness module docs path: harness/runtime/docs') &&
          stderr.includes('forbidden harness module docs path: harness/runtime/docs/README.md')
        )
      },
    )
    assert.equal(
      existsSync(realRuntimeDocsDir),
      false,
      'real repository must not contain harness/runtime/docs',
    )
  } finally {
    rmSync(mirrorRoot, { recursive: true, force: true })
  }
})

test('docs check fails when production package lacks package-info.java', () => {
  // Test intent: deleting package metadata from an existing production package must fail closed.
  const mirrorRoot = createRepositoryMirror()
  const packageInfoRelative =
    'harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/admission/package-info.java'
  const realPackageInfo = path.join(REPOSITORY_ROOT, packageInfoRelative)
  const initialPackageInfo = readFileSync(realPackageInfo, 'utf8')
  const mirrorPackageInfo = path.join(mirrorRoot, packageInfoRelative)

  try {
    rmSync(mirrorPackageInfo)

    assert.throws(
      () => {
        execFileSync('node', [CHECK_SCRIPT, '--root', mirrorRoot], {
          cwd: mirrorRoot,
          encoding: 'utf8',
          stdio: ['ignore', 'pipe', 'pipe'],
        })
      },
      (error) => {
        const stderr = error.stderr || ''
        return stderr.includes(`missing package-info.java: ${packageInfoRelative}`)
      },
    )
    assert.equal(
      readFileSync(realPackageInfo, 'utf8'),
      initialPackageInfo,
      'real package-info.java must not be modified',
    )
  } finally {
    rmSync(mirrorRoot, { recursive: true, force: true })
  }
})

test('docs check fails when package-info Javadoc is empty or package declaration mismatches', () => {
  // Test intent: existing package metadata must contain documentation and declare its path package.
  const mirrorRoot = createRepositoryMirror()
  const packageInfoRelative =
    'harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/admission/package-info.java'
  const expectedPackage = 'fun.fengwk.kkstudio.harness.runtime.admission'
  const realPackageInfo = path.join(REPOSITORY_ROOT, packageInfoRelative)
  const initialPackageInfo = readFileSync(realPackageInfo, 'utf8')
  const mirrorPackageInfo = path.join(mirrorRoot, packageInfoRelative)

  try {
    writeFileSync(mirrorPackageInfo, `/**\n * \n */\npackage ${expectedPackage};\n`)
    assert.throws(
      () => {
        execFileSync('node', [CHECK_SCRIPT, '--root', mirrorRoot], {
          cwd: mirrorRoot,
          encoding: 'utf8',
          stdio: ['ignore', 'pipe', 'pipe'],
        })
      },
      (error) => {
        const stderr = error.stderr || ''
        return stderr.includes(`empty package Javadoc in ${packageInfoRelative}`)
      },
    )

    const mismatchedPackage = 'fun.fengwk.kkstudio.harness.runtime.otherpkg'
    writeFileSync(
      mirrorPackageInfo,
      `/**\n * Valid javadoc.\n */\npackage ${mismatchedPackage};\n`,
    )
    assert.throws(
      () => {
        execFileSync('node', [CHECK_SCRIPT, '--root', mirrorRoot], {
          cwd: mirrorRoot,
          encoding: 'utf8',
          stdio: ['ignore', 'pipe', 'pipe'],
        })
      },
      (error) => {
        const stderr = error.stderr || ''
        return stderr.includes(
          `package declaration mismatch in ${packageInfoRelative}: expected '${expectedPackage}', found '${mismatchedPackage}'`,
        )
      },
    )

    assert.equal(
      readFileSync(realPackageInfo, 'utf8'),
      initialPackageInfo,
      'real package-info.java must not be modified',
    )
  } finally {
    rmSync(mirrorRoot, { recursive: true, force: true })
  }
})

test('docs check fails when a Harness package architecture table omits a production package', () => {
  // Test intent: central module docs must enumerate every production package discovered from source.
  const mirrorRoot = createRepositoryMirror()
  const realRuntimeDoc = path.join(REPOSITORY_ROOT, 'docs/modules/harness-runtime.md')
  const initialRuntimeDoc = readFileSync(realRuntimeDoc, 'utf8')

  try {
    const mirrorRuntimeDoc = path.join(mirrorRoot, 'docs/modules/harness-runtime.md')
    const source = readFileSync(mirrorRuntimeDoc, 'utf8')
    const modified = source.replace(
      /^\| `(?:fun\.fengwk\.kkstudio\.harness\.)?runtime\.admission` \|.*\n/mu,
      '',
    )
    assert.notEqual(modified, source, 'runtime.admission package row must exist in fixture')
    writeFileSync(mirrorRuntimeDoc, modified)

    assert.throws(
      () => {
        execFileSync('node', [CHECK_SCRIPT, '--root', mirrorRoot], {
          cwd: mirrorRoot,
          encoding: 'utf8',
          stdio: ['ignore', 'pipe', 'pipe'],
        })
      },
      (error) => {
        const stderr = error.stderr || ''
        return stderr.includes(
          "docs/modules/harness-runtime.md: package architecture table missing 'fun.fengwk.kkstudio.harness.runtime.admission'",
        )
      },
    )
    assert.equal(
      readFileSync(realRuntimeDoc, 'utf8'),
      initialRuntimeDoc,
      'real harness-runtime.md must not be modified',
    )
  } finally {
    rmSync(mirrorRoot, { recursive: true, force: true })
  }
})

test('docs check fails when environment route critical facts are removed or corrupted', () => {
  // Test intent: ensure docs check enforces literal requiredEnvironmentId (TOOL work only)
  // in harness-runtime.md and required_environment_id, owner_node_id, READY, lease_until,
  // and Dispatcher claim fence in harness-infra.md.
  const mirrorRoot = createRepositoryMirror()
  const realRuntimeDoc = path.join(REPOSITORY_ROOT, 'docs/modules/harness-runtime.md')
  const realInfraDoc = path.join(REPOSITORY_ROOT, 'docs/modules/harness-infra.md')
  const initialRuntimeDoc = readFileSync(realRuntimeDoc, 'utf8')
  const initialInfraDoc = readFileSync(realInfraDoc, 'utf8')

  try {
    const mirrorRuntimeDoc = path.join(mirrorRoot, 'docs/modules/harness-runtime.md')
    const mirrorInfraDoc = path.join(mirrorRoot, 'docs/modules/harness-infra.md')
    const validRuntimeDoc = readFileSync(mirrorRuntimeDoc, 'utf8')
    const validInfraDoc = readFileSync(mirrorInfraDoc, 'utf8')

    // 1. Missing literal requiredEnvironmentId in harness-runtime.md
    writeFileSync(
      mirrorRuntimeDoc,
      validRuntimeDoc.replace(/requiredEnvironmentId/g, 'someEnvIdentifier'),
    )
    assert.throws(
      () => {
        execFileSync('node', [CHECK_SCRIPT, '--root', mirrorRoot], {
          cwd: mirrorRoot,
          encoding: 'utf8',
          stdio: ['ignore', 'pipe', 'pipe'],
        })
      },
      (error) => {
        const stderr = error.stderr || ''
        return stderr.includes("docs/modules/harness-runtime.md: must document literal 'requiredEnvironmentId'")
      },
    )

    // 2. Missing TOOL Work only constraint in harness-runtime.md
    const runtimeWithoutToolAffinity = validRuntimeDoc.replace(/\bTOOL\b/g, 'OTHER')
    assert.notEqual(
      runtimeWithoutToolAffinity,
      validRuntimeDoc,
      'runtime TOOL work affinity contract must exist in fixture',
    )
    writeFileSync(mirrorRuntimeDoc, runtimeWithoutToolAffinity)
    assert.throws(
      () => {
        execFileSync('node', [CHECK_SCRIPT, '--root', mirrorRoot], {
          cwd: mirrorRoot,
          encoding: 'utf8',
          stdio: ['ignore', 'pipe', 'pipe'],
        })
      },
      (error) => {
        const stderr = error.stderr || ''
        return stderr.includes(
          'docs/modules/harness-runtime.md: must document that requiredEnvironmentId is only permitted/non-null for TOOL Work',
        )
      },
    )
    // Restore runtime doc to valid
    writeFileSync(mirrorRuntimeDoc, validRuntimeDoc)

    // 3. Missing required_environment_id in harness-infra.md
    writeFileSync(
      mirrorInfraDoc,
      validInfraDoc.replace(/required_environment_id/g, 'arbitrary_environment_field'),
    )
    assert.throws(
      () => {
        execFileSync('node', [CHECK_SCRIPT, '--root', mirrorRoot], {
          cwd: mirrorRoot,
          encoding: 'utf8',
          stdio: ['ignore', 'pipe', 'pipe'],
        })
      },
      (error) => {
        const stderr = error.stderr || ''
        return stderr.includes("docs/modules/harness-infra.md: must document required term 'required_environment_id'")
      },
    )

    // 4. Missing Dispatcher claim fence in harness-infra.md
    writeFileSync(
      mirrorInfraDoc,
      validInfraDoc.replace(/围栏/g, '校验').replace(/fence/giu, 'check'),
    )
    assert.throws(
      () => {
        execFileSync('node', [CHECK_SCRIPT, '--root', mirrorRoot], {
          cwd: mirrorRoot,
          encoding: 'utf8',
          stdio: ['ignore', 'pipe', 'pipe'],
        })
      },
      (error) => {
        const stderr = error.stderr || ''
        return stderr.includes(
          'docs/modules/harness-infra.md: must document Dispatcher node and active lease claim fence',
        )
      },
    )

    // Assert real files in worktree were never touched
    assert.equal(
      readFileSync(realRuntimeDoc, 'utf8'),
      initialRuntimeDoc,
      'real harness-runtime.md must not be modified',
    )
    assert.equal(
      readFileSync(realInfraDoc, 'utf8'),
      initialInfraDoc,
      'real harness-infra.md must not be modified',
    )
  } finally {
    rmSync(mirrorRoot, { recursive: true, force: true })
  }
})

const CORE_STATE_ENUMS = [
  {
    path: 'harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/processor/ProcessResult.java',
    enumName: 'ProcessResult',
    constants: ['STARTED', 'TERMINATED', 'RESCHEDULED', 'LOST_OWNERSHIP'],
  },
  {
    path: 'harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/processor/ThreadProcessResult.java',
    enumName: 'ThreadProcessResult',
    constants: ['COMPLETED', 'RESCHEDULED', 'LOST_OWNERSHIP'],
  },
  {
    path: 'harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/work/WorkTargetType.java',
    enumName: 'WorkTargetType',
    constants: ['THREAD', 'MODEL', 'TOOL'],
  },
  {
    path: 'harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/HarnessRuntimeConflictException.java',
    enumName: 'Reason',
    constants: [
      'STALE_VERSION',
      'STALE_COMMAND_CURSOR',
      'IDEMPOTENCY_KEY_REUSED',
      'PARTIAL_COMMAND_REPLAY',
      'COMMAND_REPLAY_ORDER_MISMATCH',
      'THREAD_ID_REUSED',
      'TERMINAL_APPLY_PENDING',
      'STOP_REQUEST_ID_REUSED',
      'APPROVAL_NOT_APPLICABLE',
      'APPROVAL_DECISION_MISMATCH',
      'MANUAL_COMPACTION_UNAVAILABLE',
    ],
  },
  {
    path: 'harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/thread/ThreadRuntimeStatus.java',
    enumName: 'ThreadRuntimeStatus',
    constants: [
      'IDLE',
      'CONTINUATION_DUE',
      'MODEL_READY',
      'MODEL_DISPATCHING',
      'MODEL_RUNNING',
      'APPLYING',
      'TOOL_WAITING_APPROVAL',
      'TOOL_RUNNING',
      'TOOL_DISPATCHING',
      'TOOL_READY',
    ],
  },
  {
    path: 'harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/invocation/model/ModelInvocationStatus.java',
    enumName: 'ModelInvocationStatus',
    constants: ['READY', 'DISPATCHING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'UNKNOWN'],
  },
  {
    path: 'harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/invocation/tool/ToolInvocationStatus.java',
    enumName: 'ToolInvocationStatus',
    constants: [
      'WAITING_APPROVAL',
      'READY',
      'DISPATCHING',
      'RUNNING',
      'SUCCEEDED',
      'FAILED',
      'CANCELLED',
      'UNKNOWN',
    ],
  },
  {
    path: 'harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/invocation/tool/ToolApprovalDecision.java',
    enumName: 'ToolApprovalDecision',
    constants: ['ALLOWED', 'DENIED'],
  },
  {
    path: 'harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/thread/command/ThreadCommandState.java',
    enumName: 'ThreadCommandState',
    constants: ['QUEUED', 'APPLIED', 'CANCELLED'],
  },
  {
    path: 'harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/entry/TurnEndOutcome.java',
    enumName: 'TurnEndOutcome',
    constants: ['COMPLETED', 'FAILED', 'STOPPED', 'CANCELLED'],
  },
  {
    path: 'harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/history/TurnEndReason.java',
    enumName: 'TurnEndReason',
    constants: [
      'USER_STOP',
      'HISTORY_CUT',
      'CANCELLED',
      'TURN_FAILED',
      'OUTPUT_TRUNCATED',
      'CONTENT_FILTERED',
    ],
  },
  {
    path: 'harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/entry/TurnStartReason.java',
    enumName: 'TurnStartReason',
    constants: ['INPUT', 'CONTINUATION', 'COMPACTION'],
  },
  {
    path: 'harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/history/ToolResultStatus.java',
    enumName: 'ToolResultStatus',
    constants: ['SUCCEEDED', 'FAILED', 'CANCELLED', 'UNKNOWN'],
  },
  {
    path: 'harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/history/ToolResultReason.java',
    enumName: 'ToolResultReason',
    constants: ['HISTORY_CUT'],
  },
  {
    path: 'harness/builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/goal/GoalStatus.java',
    enumName: 'GoalStatus',
    constants: ['ACTIVE', 'COMPLETE', 'BLOCKED'],
  },
  {
    path: 'harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/compaction/CompactionPhase.java',
    enumName: 'CompactionPhase',
    constants: ['FULL', 'HISTORY', 'TURN_PREFIX'],
  },
  {
    path: 'harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/compaction/CompactionTrigger.java',
    enumName: 'CompactionTrigger',
    constants: ['THRESHOLD', 'OVERFLOW', 'MANUAL'],
  },
  {
    path: 'harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/model/provider/GenerationStopReason.java',
    enumName: 'GenerationStopReason',
    constants: ['COMPLETE', 'LENGTH', 'FILTERED'],
  },
  {
    path: 'harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/model/provider/ProviderErrorKind.java',
    enumName: 'ProviderErrorKind',
    constants: [
      'TRANSIENT',
      'OVERFLOW',
      'AUTHENTICATION',
      'BILLING',
      'INVALID_REQUEST',
      'INVALID_RESPONSE',
      'CANCELLED',
    ],
  },
  {
    path: 'harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/ManualCompactionAvailability.java',
    enumName: 'DisabledReason',
    constants: [
      'THREAD_BUSY',
      'OWNERSHIP_BARRIER',
      'NO_RESOLVED_CONTEXT',
      'MODEL_CHANGED',
      'BELOW_MINIMUM',
      'NOTHING_TO_COMPACT',
    ],
  },
  {
    path: 'harness/daemon/src/main/java/fun/fengwk/kkstudio/harness/daemon/DaemonRuntimeState.java',
    enumName: 'DaemonRuntimeState',
    constants: ['STOPPED', 'CONNECTING', 'READY', 'DISCONNECTED', 'FAILED'],
  },
  {
    path: 'harness/daemon/src/main/java/fun/fengwk/kkstudio/harness/daemon/journal/DaemonInvocationState.java',
    enumName: 'DaemonInvocationState',
    constants: ['RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED'],
  },
  {
    path: 'harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/processor/ModelExecution.java',
    enumName: 'PendingKind',
    constants: ['EVENT', 'SUCCEEDED', 'FAILED', 'UNKNOWN'],
  },
  {
    path: 'harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/processor/ModelExecution.java',
    enumName: 'Applied',
    constants: ['PROGRESSED', 'RETRY', 'TERMINAL', 'LOST'],
  },
  {
    path: 'harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/processor/ModelExecution.java',
    enumName: 'TerminalKind',
    constants: ['FAILED', 'CANCELLED', 'UNKNOWN'],
  },
  {
    path: 'harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/processor/ToolExecution.java',
    enumName: 'PendingKind',
    constants: ['PARTIAL', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'UNKNOWN'],
  },
  {
    path: 'harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/processor/ToolExecution.java',
    enumName: 'Applied',
    constants: ['PROGRESSED', 'RETRY', 'TERMINAL', 'LOST'],
  },
  {
    path: 'harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/processor/ToolExecution.java',
    enumName: 'TerminalKind',
    constants: ['FAILED', 'CANCELLED', 'UNKNOWN'],
  },
  {
    path: 'canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/CanvasFunctionRunStatus.java',
    enumName: 'CanvasFunctionRunStatus',
    constants: ['READY', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED'],
  },
  {
    path: 'canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/CanvasConflictException.java',
    enumName: 'Reason',
    constants: ['VERSION_CONFLICT', 'IDEMPOTENCY_CONFLICT'],
  },
  {
    path: 'canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/function/CanvasFunctionRunException.java',
    enumName: 'Reason',
    constants: ['NOT_FOUND', 'CONFLICT'],
  },
  {
    path: 'platform/src/main/java/fun/fengwk/kkstudio/platform/environment/query/EnvironmentQueryStatus.java',
    enumName: 'EnvironmentQueryStatus',
    constants: ['PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED'],
  },
  {
    path: 'platform/src/main/java/fun/fengwk/kkstudio/platform/environment/registry/LiveEnvironmentStatus.java',
    enumName: 'LiveEnvironmentStatus',
    constants: ['CONNECTING', 'READY'],
  },
  {
    path: 'platform/src/main/java/fun/fengwk/kkstudio/platform/storage/service/model/StorageBlobState.java',
    enumName: 'StorageBlobState',
    constants: ['ACTIVE', 'DELETING'],
  },
  {
    path: 'share/src/main/java/fun/fengwk/kkstudio/share/storage/StorageUploadState.java',
    enumName: 'StorageUploadState',
    constants: ['PENDING', 'READY'],
  },
  {
    path: 'platform/src/main/java/fun/fengwk/kkstudio/platform/canvas/function/h3/H3ComfyHistory.java',
    enumName: 'Status',
    constants: ['PENDING', 'SUCCESS', 'ERROR'],
  },
  {
    path: 'platform/src/main/java/fun/fengwk/kkstudio/platform/canvas/function/opencli/OpenCliHubClient.java',
    enumName: 'ExecutionStatus',
    constants: ['PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'TIMED_OUT', 'CANCELLED'],
  },
  {
    path: 'platform/src/main/java/fun/fengwk/kkstudio/platform/harness/task/DatabaseSubagentRunner.java',
    enumName: 'RunState',
    constants: ['COMPLETED', 'ERROR', 'CANCELLED'],
  },
  {
    path: 'platform/src/main/java/fun/fengwk/kkstudio/platform/harness/tool/gateway/ToolExecutionGateway.java',
    enumName: 'SignalKind',
    constants: ['PARTIAL', 'COMPLETE', 'ERROR'],
  },
  {
    path: 'platform/src/main/java/fun/fengwk/kkstudio/platform/environment/gateway/EnvironmentDaemonGateway.java',
    enumName: 'SendOutcome',
    constants: ['SENT', 'NOT_SENT', 'UNCERTAIN'],
  },
]

function extractEnumConstantSection(source, enumName) {
  const enumRegex = new RegExp(`\\benum\\s+${enumName}\\b[^{]*\\{`, 'u')
  const match = enumRegex.exec(source)
  if (!match) {
    throw new Error(`Cannot find enum ${enumName}`)
  }
  const startIndex = match.index + match[0].length

  let depth = 1
  let parenDepth = 0
  let inLineComment = false
  let inBlockComment = false
  let inString = false
  let inChar = false

  let endIndex = -1
  for (let i = startIndex; i < source.length; i++) {
    const ch = source[i]
    const next = source[i + 1]

    if (inLineComment) {
      if (ch === '\n') {
        inLineComment = false
      }
      continue
    }
    if (inBlockComment) {
      if (ch === '*' && next === '/') {
        inBlockComment = false
        i++
      }
      continue
    }
    if (inString) {
      if (ch === '\\') {
        i++
      } else if (ch === '"') {
        inString = false
      }
      continue
    }
    if (inChar) {
      if (ch === '\\') {
        i++
      } else if (ch === "'") {
        inChar = false
      }
      continue
    }

    if (ch === '/' && next === '/') {
      inLineComment = true
      i++
      continue
    }
    if (ch === '/' && next === '*') {
      inBlockComment = true
      i++
      continue
    }
    if (ch === '"') {
      inString = true
      continue
    }
    if (ch === "'") {
      inChar = true
      continue
    }

    if (ch === '(') {
      parenDepth++
      continue
    }
    if (ch === ')') {
      parenDepth--
      continue
    }
    if (ch === '{') {
      depth++
      continue
    }
    if (ch === '}') {
      depth--
      if (depth === 0) {
        endIndex = i
        break
      }
      continue
    }
    if (ch === ';' && depth === 1 && parenDepth === 0) {
      endIndex = i
      break
    }
  }

  if (endIndex === -1) {
    throw new Error(`Unterminated enum body for ${enumName}`)
  }

  return source.slice(startIndex, endIndex)
}

function parseEnumConstantEntries(constantSection) {
  const constants = []
  let parenDepth = 0
  let braceDepth = 0
  let inLineComment = false
  let inBlockComment = false
  let inString = false
  let inChar = false

  let lastDelimiterEnd = 0

  for (let i = 0; i < constantSection.length; i++) {
    const ch = constantSection[i]
    const next = constantSection[i + 1]

    if (inLineComment) {
      if (ch === '\n') {
        inLineComment = false
      }
      continue
    }
    if (inBlockComment) {
      if (ch === '*' && next === '/') {
        inBlockComment = false
        i++
      }
      continue
    }
    if (inString) {
      if (ch === '\\') {
        i++
      } else if (ch === '"') {
        inString = false
      }
      continue
    }
    if (inChar) {
      if (ch === '\\') {
        i++
      } else if (ch === "'") {
        inChar = false
      }
      continue
    }

    if (ch === '/' && next === '/') {
      inLineComment = true
      i++
      continue
    }
    if (ch === '/' && next === '*') {
      inBlockComment = true
      i++
      continue
    }
    if (ch === '"') {
      inString = true
      continue
    }
    if (ch === "'") {
      inChar = true
      continue
    }

    if (ch === '(') {
      parenDepth++
      continue
    }
    if (ch === ')') {
      parenDepth--
      continue
    }
    if (ch === '{') {
      braceDepth++
      continue
    }
    if (ch === '}') {
      braceDepth--
      continue
    }

    if (parenDepth === 0 && braceDepth === 0) {
      if (ch === ',') {
        lastDelimiterEnd = i + 1
        continue
      }

      if (/[A-Za-z_]/u.test(ch)) {
        const idStart = i
        while (i < constantSection.length && /[A-Za-z0-9_]/u.test(constantSection[i])) {
          i++
        }
        const identifier = constantSection.slice(idStart, i)
        i--

        if (/^[A-Z][A-Z0-9_]*$/u.test(identifier)) {
          const prefix = constantSection.slice(lastDelimiterEnd, idStart)
          const trimmed = prefix.trim()
          const hasSingleJavadoc =
            trimmed.startsWith('/**') &&
            trimmed.endsWith('*/') &&
            !trimmed.slice(3, -2).includes('*/')
          const javadocText = hasSingleJavadoc
            ? trimmed
                .slice(3, -2)
                .replace(/^\s*\*\s?/gmu, '')
                .trim()
            : ''

          constants.push({
            name: identifier,
            hasImmediateNonEmptyJavadoc: javadocText.length > 0,
          })
        }
      }
    }
  }

  return constants
}

function assertManagedCoreStateEnums(repositoryRoot) {
  for (const entry of CORE_STATE_ENUMS) {
    const fullPath = path.join(repositoryRoot, entry.path)
    assert.equal(existsSync(fullPath), true, `file must exist: ${entry.path}`)

    const source = readFileSync(fullPath, 'utf8')
    const constantSection = extractEnumConstantSection(source, entry.enumName)
    const parsedEntries = parseEnumConstantEntries(constantSection)

    const actualConstants = parsedEntries.map((p) => p.name)
    assert.deepEqual(
      actualConstants,
      entry.constants,
      `enum ${entry.enumName} in ${entry.path} constants mismatch`,
    )

    for (const parsed of parsedEntries) {
      assert.equal(
        parsed.hasImmediateNonEmptyJavadoc,
        true,
        `enum ${entry.enumName}.${parsed.name} in ${entry.path} must have immediate non-empty Javadoc`,
      )
    }
  }
}

test('repository structure guard verifies managed core state enums have exact constants and immediate non-empty Javadoc', () => {
  // Test intent: ensure all managed core state enums declare exactly the specified constants and document every constant.
  assertManagedCoreStateEnums(REPOSITORY_ROOT)
})

test('repository structure guard rejects missing or empty core state enum constant Javadoc in isolation', () => {
  // Test intent: prove the enum documentation guard fails closed without mutating the repository.
  const mirrorRoot = createRepositoryMirror()
  const relativePath =
    'harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/processor/ProcessResult.java'
  const mirrorPath = path.join(mirrorRoot, relativePath)
  const source = readFileSync(mirrorPath, 'utf8')
  const startedJavadoc =
    /(\n[ \t]*)\/\*\*(?:(?!\*\/)[\s\S])*\*\/(\s*STARTED,)/u

  try {
    const missing = source.replace(startedJavadoc, '$1$2')
    assert.notEqual(missing, source, 'STARTED Javadoc fixture must be removable')
    writeFileSync(mirrorPath, missing)
    assert.throws(
      () => assertManagedCoreStateEnums(mirrorRoot),
      /enum ProcessResult\.STARTED .* must have immediate non-empty Javadoc/u,
    )

    const empty = source.replace(startedJavadoc, '$1/** */$2')
    assert.notEqual(empty, source, 'STARTED Javadoc fixture must be replaceable with an empty block')
    writeFileSync(mirrorPath, empty)
    assert.throws(
      () => assertManagedCoreStateEnums(mirrorRoot),
      /enum ProcessResult\.STARTED .* must have immediate non-empty Javadoc/u,
    )
  } finally {
    rmSync(mirrorRoot, { recursive: true, force: true })
  }
})
