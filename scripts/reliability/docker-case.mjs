import { spawn } from 'node:child_process'
import path from 'node:path'

const PROJECT_NAME = 'kk-studio-reliability'
const COMPOSE_FILE = 'deploy/reliability/compose.yaml'
const COMMAND_OUTPUT_LIMIT = 2 * 1024 * 1024

export const CASE_NODE_SCRIPT = String.raw`
import { createHash } from 'node:crypto'
import { existsSync, readFileSync, realpathSync, writeFileSync } from 'node:fs'
import path from 'node:path'

const input = JSON.parse(await new Promise((resolve, reject) => {
  const chunks = []
  process.stdin.on('data', (chunk) => chunks.push(chunk))
  process.stdin.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')))
  process.stdin.on('error', reject)
}))
const root = realpathSync(process.cwd())

function safePath(requestedPath) {
  if (typeof requestedPath !== 'string' || !requestedPath) {
    throw new Error('path must be a non-empty path')
  }
  const resolved = path.isAbsolute(requestedPath)
    ? path.resolve(requestedPath)
    : path.resolve(root, requestedPath)
  assertInsideRoot(resolved)

  let existing = resolved
  while (!existsSync(existing)) {
    const parent = path.dirname(existing)
    if (parent === existing) break
    existing = parent
  }
  const canonical = path.resolve(realpathSync(existing), path.relative(existing, resolved))
  assertInsideRoot(canonical)
  return resolved
}

function assertInsideRoot(candidate) {
  const relative = path.relative(root, candidate)
  if (relative === '..' || relative.startsWith('..' + path.sep) || path.isAbsolute(relative)) {
    throw new Error('path escapes case root')
  }
}

function count(source, needle) {
  if (!needle) return 0
  let matches = 0
  let offset = 0
  while ((offset = source.indexOf(needle, offset)) >= 0) {
    matches++
    offset += needle.length
  }
  return matches
}

function summary(spec) {
  const file = safePath(spec.path)
  if (!existsSync(file)) return { path: spec.path, exists: false }
  const bytes = readFileSync(file)
  const text = bytes.toString('utf8')
  const counts = {}
  for (const needle of spec.needles || []) counts[needle] = count(text, needle)
  return {
    path: spec.path,
    exists: true,
    byteLength: bytes.length,
    sha256: createHash('sha256').update(bytes).digest('hex'),
    counts,
  }
}

let output
if (input.op === 'inspect') {
  output = { files: (input.files || []).map(summary) }
} else if (input.op === 'replace') {
  const file = safePath(input.path)
  const source = readFileSync(file, 'utf8')
  const matches = count(source, input.from)
  if (matches !== 1) throw new Error('expected exactly one replacement target, found ' + matches)
  if (count(source, input.to) !== 0) throw new Error('replacement value already exists')
  writeFileSync(file, source.replace(input.from, input.to), 'utf8')
  output = summary({ path: input.path, needles: [input.from, input.to] })
} else {
  throw new Error('unknown operation: ' + input.op)
}
process.stdout.write(JSON.stringify(output))
`

export class DockerCaseHarness {
  constructor({ repoRoot }) {
    this.repoRoot = repoRoot
    this.stackScript = path.join(repoRoot, 'scripts/reliability/stack.sh')
  }

  async reset(testCase) {
    return this.#checked(this.stackScript, ['case-reset', testCase.id, testCase.anchor], {
      timeoutMs: 2 * 60 * 1000,
    })
  }

  async installDependencies(testCase) {
    return this.#checked(this.stackScript, ['case-deps', testCase.id], {
      timeoutMs: 15 * 60 * 1000,
    })
  }

  async inspectFiles(testCase, files) {
    const result = await this.#compose(
      testCase,
      ['node', '--input-type=module', '-e', CASE_NODE_SCRIPT],
      {
        input: JSON.stringify({ op: 'inspect', files }),
        timeoutMs: 2 * 60 * 1000,
      },
    )
    if (result.code !== 0) throw commandError('case Node inspect', result)
    try {
      return JSON.parse(result.stdout)
    } catch {
      throw new Error(`case Node inspect returned invalid JSON: ${summarizeCommand(result)}`)
    }
  }

  async seedDefect(testCase) {
    const result = await this.#compose(
      testCase,
      ['node', '--input-type=module', '-e', CASE_NODE_SCRIPT],
      {
        input: JSON.stringify({
          op: 'replace',
          path: testCase.sourcePath,
          from: testCase.correctSnippet,
          to: testCase.defectSnippet,
        }),
        timeoutMs: 2 * 60 * 1000,
      },
    )
    if (result.code !== 0) throw commandError('case Node defect seed', result)
    try {
      return JSON.parse(result.stdout)
    } catch {
      throw new Error(`case Node defect seed returned invalid JSON: ${summarizeCommand(result)}`)
    }
  }

  async runTargetTest(testCase, { timeoutMs = 5 * 60 * 1000 } = {}) {
    return this.#compose(testCase, ['npm', ...testCase.targetTestArgs], { timeoutMs })
  }

  async gitStatus(testCase) {
    return this.#git(testCase, ['status', '--porcelain'])
  }

  async gitDiffCheck(testCase) {
    return this.#git(testCase, ['diff', '--check'])
  }

  async gitDiff(testCase, filePath) {
    return this.#git(testCase, ['diff', '--', filePath])
  }

  async #git(testCase, args) {
    return this.#compose(testCase, ['git', ...args], { timeoutMs: 2 * 60 * 1000 })
  }

  async #compose(testCase, command, options = {}) {
    return runCommand(
      'docker',
      [
        'compose',
        '--project-name',
        PROJECT_NAME,
        '--file',
        path.join(this.repoRoot, COMPOSE_FILE),
        'exec',
        '--no-TTY',
        '--user',
        '10001:10001',
        '--workdir',
        testCase.casePath,
        'daemon',
        ...command,
      ],
      { cwd: this.repoRoot, ...options },
    )
  }

  async #checked(command, args, options) {
    const result = await runCommand(command, args, { cwd: this.repoRoot, ...options })
    if (result.code !== 0) throw commandError(`${path.basename(command)} ${args[0]}`, result)
    return result
  }
}

export async function runCommand(
  command,
  args,
  { cwd, input, timeoutMs = 120_000, outputLimit = COMMAND_OUTPUT_LIMIT } = {},
) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, {
      cwd,
      stdio: ['pipe', 'pipe', 'pipe'],
      env: process.env,
    })
    const stdout = []
    const stderr = []
    let stdoutBytes = 0
    let stderrBytes = 0
    let timedOut = false
    const timer = setTimeout(() => {
      timedOut = true
      child.kill('SIGTERM')
      setTimeout(() => child.kill('SIGKILL'), 2000).unref()
    }, timeoutMs)

    child.on('error', (error) => {
      clearTimeout(timer)
      reject(error)
    })
    child.stdout.on('data', (chunk) => {
      if (stdoutBytes < outputLimit) stdout.push(chunk.subarray(0, outputLimit - stdoutBytes))
      stdoutBytes += chunk.length
    })
    child.stderr.on('data', (chunk) => {
      if (stderrBytes < outputLimit) stderr.push(chunk.subarray(0, outputLimit - stderrBytes))
      stderrBytes += chunk.length
    })
    child.on('close', (code, signal) => {
      clearTimeout(timer)
      resolve({
        code: code ?? (timedOut ? 124 : 1),
        signal,
        timedOut,
        stdout: Buffer.concat(stdout).toString('utf8'),
        stderr: Buffer.concat(stderr).toString('utf8'),
        stdoutBytes,
        stderrBytes,
        outputTruncated: stdoutBytes > outputLimit || stderrBytes > outputLimit,
      })
    })
    if (input == null) child.stdin.end()
    else child.stdin.end(input)
  })
}

export function summarizeCommand(result, limit = 2000) {
  const output = [result.stdout, result.stderr].filter(Boolean).join('\n').trim()
  return {
    exitCode: result.code,
    signal: result.signal ?? null,
    timedOut: result.timedOut,
    stdoutBytes: result.stdoutBytes,
    stderrBytes: result.stderrBytes,
    outputTruncated: result.outputTruncated,
    output: output.length <= limit ? output : `${output.slice(0, limit)}…`,
  }
}

function commandError(label, result) {
  return new Error(`${label} failed: ${JSON.stringify(summarizeCommand(result))}`)
}
