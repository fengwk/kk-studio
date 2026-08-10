import { createHash } from 'node:crypto'

export const ACTIVE_TOOLS = Object.freeze(['read', 'write', 'edit', 'bash', 'grep', 'find'])
export const PROVIDER_NAME = 'minimax'
export const VARIANT = 'high'
export const DEFAULT_BASE_URL = 'http://127.0.0.1:18091'
export const DEFAULT_DAEMON_ENV = 'docker-reliability'
export const DEFAULT_MAX_COST_USD = 5
export const CASE_TIMEOUT_MS = 10 * 60 * 1000
export const MAX_HARD_COST_USD = 5
export const WRITE_PROOF_PATH = '.reliability-write-proof.txt'
export const WRITE_PROOF_MARKER = 'RELIABILITY_WRITE_PROOF_V1'

export const MODELS = Object.freeze([
  Object.freeze({
    key: 'm27',
    providerName: PROVIDER_NAME,
    modelName: 'MiniMax-M2.7',
    ref: 'minimax/MiniMax-M2.7',
    variant: VARIANT,
  }),
  Object.freeze({
    key: 'm3',
    providerName: PROVIDER_NAME,
    modelName: 'MiniMax-M3',
    ref: 'minimax/MiniMax-M3',
    variant: VARIANT,
  }),
])

export const WRITE_PROOF = Array.from(
  { length: 192 },
  (_, index) =>
    `${WRITE_PROOF_MARKER}|${String(index).padStart(4, '0')}|`
    + 'abcdefghijklmnopqrstuvwxyz0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ'
    + '|dedicated-write-tool-transport-integrity-check',
).join('\n') + '\n'

export const WRITE_PROOF_META = Object.freeze({
  byteLength: Buffer.byteLength(WRITE_PROOF),
  sha256: sha256(WRITE_PROOF),
})

if (WRITE_PROOF_META.byteLength < 12 * 1024) {
  throw new Error(`write proof payload is too small: ${WRITE_PROOF_META.byteLength} bytes`)
}

const TASKS = Object.freeze([
  Object.freeze({
    anchor: 'pi',
    taskClass: 'investigate',
    title: 'pi compaction-aware context investigation',
    oracle: 'pi-investigate',
  }),
  Object.freeze({
    anchor: 'pi-base',
    taskClass: 'investigate',
    title: 'pi-base find lexical relativization investigation',
    oracle: 'pi-base-investigate',
  }),
  Object.freeze({
    anchor: 'pi',
    taskClass: 'repair',
    title: 'pi latest-compaction duplicate repair',
    oracle: 'pi-repair',
    sourcePath: 'packages/coding-agent/src/core/session-manager.ts',
    correctSnippet: 'path.slice(compactionIdx + 1)',
    defectSnippet: 'path.slice(compactionIdx)',
    targetTestArgs: [
      'test',
      '--workspace',
      '@earendil-works/pi-coding-agent',
      '--',
      'test/session-manager/build-context.test.ts',
    ],
    targetTestCommand:
      'npm test --workspace @earendil-works/pi-coding-agent -- test/session-manager/build-context.test.ts',
  }),
  Object.freeze({
    anchor: 'pi-base',
    taskClass: 'repair',
    title: 'pi-base slash-pattern recursive-prefix repair',
    oracle: 'pi-base-repair',
    sourcePath: 'src/find-tool.ts',
    correctSnippet: 'effectivePattern = `**/${pattern}`',
    defectSnippet: 'effectivePattern = `*/${pattern}`',
    targetTestArgs: ['test', '--', 'tests/find-tool-native.test.ts'],
    targetTestCommand: 'npm test -- tests/find-tool-native.test.ts',
  }),
])

export const CASES = Object.freeze(
  MODELS.flatMap((model) =>
    TASKS.map((task) =>
      Object.freeze({
        ...task,
        model,
        id: `${model.key}-${task.anchor}-${task.taskClass}`,
        casePath: `/workspace/cases/${model.key}-${task.anchor}-${task.taskClass}`,
      }),
    ),
  ),
)

export function buildSystemPrompt() {
  return [
    'You are a reliability-test coding agent.',
    '',
    'Operate only inside the disposable Docker case directory explicitly named by the user message.',
    'Never access /workspace/anchors, any other case directory, a remote, credentials, or the network. Never commit.',
    '',
    'Use the dedicated find tool for file discovery, grep for content search, read for file reading, edit for targeted modification, and write for complete-file creation or replacement.',
    'Do not use bash with shell find, grep, rg, fd, cat, sed, awk, perl, python, node, or any other substitute for those dedicated file tools.',
    'Bash is allowed only for the explicitly named target test and these Git checks: git status --short, git diff --check, and git diff -- <path>.',
    'For a read-only task, do not mutate anything and do not call bash.',
    '',
    'Make the smallest evidence-based change. Do not claim success when a required test fails. Report the exact evidence you found and the validation you actually ran.',
  ].join('\n')
}

export function buildUserPrompt(testCase) {
  if (testCase.taskClass === 'investigate' && testCase.anchor === 'pi') {
    return [
      `Disposable case directory: ${testCase.casePath}`,
      '',
      'This is a read-only investigation. Do not call bash and do not mutate files.',
      'Use find, then grep, then read as the first occurrences of those tools.',
      'Investigate how the active compaction-aware entry list is built.',
      'In the final answer, identify the function and source path, explain that the latest compaction is selected, explain firstKeptEntryId, and state exactly how the path suffix after that compaction is appended.',
    ].join('\n')
  }
  if (testCase.taskClass === 'investigate' && testCase.anchor === 'pi-base') {
    return [
      `Disposable case directory: ${testCase.casePath}`,
      '',
      'This is a read-only investigation. Do not call bash and do not mutate files.',
      'Use find, then grep, then read as the first occurrences of those tools.',
      'Investigate the find tool behavior for a slash-containing pattern and how fd output is lexically relativized.',
      'In the final answer, identify the factory and source path, explain --full-path and the **/ prefix logic, and state the exact path.relative expression used for output lines.',
    ].join('\n')
  }

  const defect =
    testCase.anchor === 'pi'
      ? 'The latest compaction is being retained twice in the active context entry list.'
      : 'A slash-containing find pattern has lost its recursive **/ prefix, so nested full-path matching fails.'
  const header = [
    `Disposable case directory: ${testCase.casePath}`,
    '',
    `Repair this defect with the smallest change: ${defect}`,
    `The required target test is exactly: ${testCase.targetTestCommand}`,
    'Use find, then grep, then read, then edit, then bash, then write as the first occurrences of those six tools.',
    'Use bash only for the exact target test or the permitted Git checks from the system instructions.',
    `After the edit and successful target test, use the dedicated write tool (not bash) with path ${WRITE_PROOF_PATH} and workdir ${testCase.casePath}.`,
    `The file content must be byte-for-byte equal to the payload between BEGIN_WRITE_PROOF and END_WRITE_PROOF. Do not include the marker lines.`,
    `Expected UTF-8 byte length: ${WRITE_PROOF_META.byteLength}.`,
    `Expected SHA-256: ${WRITE_PROOF_META.sha256}.`,
    '',
    'BEGIN_WRITE_PROOF',
  ].join('\n')
  return (
    `${header}\n`
    + WRITE_PROOF
    + 'END_WRITE_PROOF\n\n'
    + 'In the final answer, state the defect fixed and the exact target test result. Do not repeat the proof payload.'
  )
}

export function casePromptSummary(testCase) {
  return testCase.taskClass === 'repair'
    ? {
        task: testCase.title,
        casePath: testCase.casePath,
        targetTest: testCase.targetTestCommand,
        writeProof: { path: WRITE_PROOF_PATH, ...WRITE_PROOF_META },
      }
    : {
        task: testCase.title,
        casePath: testCase.casePath,
        readOnly: true,
        requiredToolOrder: ['find', 'grep', 'read'],
      }
}

export function sha256(value) {
  return createHash('sha256').update(value).digest('hex')
}
