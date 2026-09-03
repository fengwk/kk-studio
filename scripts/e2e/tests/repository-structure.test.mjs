import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import {
  existsSync,
  mkdirSync,
  mkdtempSync,
  readdirSync,
  readFileSync,
  rmSync,
  symlinkSync,
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
    if (entry.name === '.git' || entry.name === '.workspace' || entry.name === 'harness') {
      continue
    }
    symlinkSync(
      path.join(REPOSITORY_ROOT, entry.name),
      path.join(mirrorRoot, entry.name),
      entry.isDirectory() ? 'dir' : 'file',
    )
  }

  const mirrorHarness = path.join(mirrorRoot, 'harness')
  mkdirSync(mirrorHarness)
  for (const entry of readdirSync(path.join(REPOSITORY_ROOT, 'harness'), {
    withFileTypes: true,
  })) {
    if (entry.name === 'prompt') {
      continue
    }
    symlinkSync(
      path.join(REPOSITORY_ROOT, 'harness', entry.name),
      path.join(mirrorHarness, entry.name),
      entry.isDirectory() ? 'dir' : 'file',
    )
  }
  return mirrorRoot
}

test('repository structure guard verifies absence of top-level harness/prompt and presence of shared prompt', () => {
  // Test intent: top-level harness/prompt module must not return while the legitimate
  // harness/common/.../prompt package remains intact as shared prompt-template code.
  const obsoleteHarnessPrompt = path.join(REPOSITORY_ROOT, 'harness/prompt')
  assert.equal(
    existsSync(obsoleteHarnessPrompt),
    false,
    'top-level harness/prompt must not exist in repository',
  )

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
  // Test intent: ensure declared POM module declarations exactly match expected topology
  // and do not declare obsolete modules like harness/prompt.
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
  assert.equal(harnessModules.includes('prompt'), false)
})

test('docs check fails when obsolete harness/prompt directory is reintroduced', () => {
  // Test intent: ensure normal validation via scripts/docs/check.mjs immediately fails
  // if an obsolete harness/prompt directory is ever recreated, without mutating the repository.
  const mirrorRoot = createRepositoryMirror()
  const fakePromptDir = path.join(mirrorRoot, 'harness/prompt')
  try {
    mkdirSync(fakePromptDir, { recursive: true })
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
        return stderr.includes('obsolete top-level harness/prompt module directory must not exist')
      },
    )
  } finally {
    rmSync(mirrorRoot, { recursive: true, force: true })
  }
})
