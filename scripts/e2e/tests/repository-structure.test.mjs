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
  const realPromptDir = path.join(REPOSITORY_ROOT, 'harness/prompt')
  assert.equal(existsSync(realPromptDir), false)
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
    assert.equal(existsSync(realPromptDir), false, 'real repository must not have harness/prompt')
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
    writeFileSync(path.join(mirrorRuntimeDocsDir, 'README.md'), '# Obsolete local doc\n')

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
