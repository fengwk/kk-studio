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

  // Copy harness and docs recursively instead of symlinking to avoid writing through to real worktree
  cpSync(path.join(REPOSITORY_ROOT, 'harness'), path.join(mirrorRoot, 'harness'), {
    recursive: true,
  })
  cpSync(path.join(REPOSITORY_ROOT, 'docs'), path.join(mirrorRoot, 'docs'), {
    recursive: true,
  })

  return mirrorRoot
}

function stageValidHarnessDocumentation(mirrorRoot) {
  // Populate valid package-info.java for any unmerged production packages
  const unmergedPackages = [
    {
      dir: 'harness/daemon/src/main/java/fun/fengwk/kkstudio/harness/daemon/journal',
      pkg: 'fun.fengwk.kkstudio.harness.daemon.journal',
      javadoc: '/**\n * Daemon invocation journal.\n */\n',
    },
    {
      dir: 'harness/daemon/src/main/java/fun/fengwk/kkstudio/harness/daemon/transport',
      pkg: 'fun.fengwk.kkstudio.harness.daemon.transport',
      javadoc: '/**\n * Daemon websocket transport.\n */\n',
    },
    {
      dir: 'harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/admission',
      pkg: 'fun.fengwk.kkstudio.harness.runtime.admission',
      javadoc: '/**\n * Runtime concurrency admission.\n */\n',
    },
    {
      dir: 'harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/model/cache',
      pkg: 'fun.fengwk.kkstudio.harness.runtime.model.cache',
      javadoc: '/**\n * Model prompt cache policies.\n */\n',
    },
  ]
  for (const item of unmergedPackages) {
    const pkgInfoPath = path.join(mirrorRoot, item.dir, 'package-info.java')
    if (!existsSync(pkgInfoPath)) {
      mkdirSync(path.dirname(pkgInfoPath), { recursive: true })
      writeFileSync(pkgInfoPath, `${item.javadoc}package ${item.pkg};\n`)
    }
  }

  // Update docs in mirrorRoot to include the required environment route facts
  const runtimeDocPath = path.join(mirrorRoot, 'docs/modules/harness-runtime.md')
  let runtimeContent = readFileSync(runtimeDocPath, 'utf8')
  if (!runtimeContent.includes('requiredEnvironmentId')) {
    runtimeContent += '\n\nWork 中的 `requiredEnvironmentId` 仅允许 target 为 TOOL 的 Work 携带非空。\n'
    writeFileSync(runtimeDocPath, runtimeContent)
  }

  const infraDocPath = path.join(mirrorRoot, 'docs/modules/harness-infra.md')
  let infraContent = readFileSync(infraDocPath, 'utf8')
  if (!infraContent.includes('required_environment_id')) {
    infraContent +=
      '\n\nharness_work 的 `required_environment_id`、`owner_node_id`、`READY` 与 `lease_until`，当前 Dispatcher node 持有有效 lease 进行 claim 围栏。\n'
    writeFileSync(infraDocPath, infraContent)
  }
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

test('docs check passes when mirror has valid package-info and environment route documentation', () => {
  // Test intent: ensure docs check passes completely (exit code 0) when all production packages
  // have valid package-info.java and environment route documentation facts are present.
  const mirrorRoot = createRepositoryMirror()
  try {
    stageValidHarnessDocumentation(mirrorRoot)
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
    stageValidHarnessDocumentation(mirrorRoot)
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
  // Test intent: ensure every package directly containing production .java classes requires
  // a valid package-info.java, and missing package-info triggers an explicit error.
  const mirrorRoot = createRepositoryMirror()
  const realNewPkg = path.join(
    REPOSITORY_ROOT,
    'harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/negpkg',
  )
  assert.equal(existsSync(realNewPkg), false)

  try {
    stageValidHarnessDocumentation(mirrorRoot)
    const mirrorNewPkg = path.join(
      mirrorRoot,
      'harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/negpkg',
    )
    mkdirSync(mirrorNewPkg, { recursive: true })
    writeFileSync(
      path.join(mirrorNewPkg, 'NewService.java'),
      'package fun.fengwk.kkstudio.harness.runtime.negpkg;\npublic class NewService {}\n',
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
          'missing package-info.java: harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/negpkg/package-info.java',
        )
      },
    )
    assert.equal(existsSync(realNewPkg), false, 'real repository must not contain negpkg')
  } finally {
    rmSync(mirrorRoot, { recursive: true, force: true })
  }
})

test('docs check fails when package-info Javadoc is empty or package declaration mismatches', () => {
  // Test intent: ensure package-info.java cannot be an empty shell and must declare the matching package.
  const mirrorRoot = createRepositoryMirror()
  const realShellPkg = path.join(
    REPOSITORY_ROOT,
    'harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/shellpkg',
  )
  assert.equal(existsSync(realShellPkg), false)

  try {
    stageValidHarnessDocumentation(mirrorRoot)
    const mirrorShellPkg = path.join(
      mirrorRoot,
      'harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/shellpkg',
    )
    mkdirSync(mirrorShellPkg, { recursive: true })
    writeFileSync(
      path.join(mirrorShellPkg, 'ShellService.java'),
      'package fun.fengwk.kkstudio.harness.runtime.shellpkg;\npublic class ShellService {}\n',
    )

    // 1. Empty Javadoc comment
    const pkgInfoFile = path.join(mirrorShellPkg, 'package-info.java')
    writeFileSync(
      pkgInfoFile,
      '/**\n * \n */\npackage fun.fengwk.kkstudio.harness.runtime.shellpkg;\n',
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
          'empty package Javadoc in harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/shellpkg/package-info.java',
        )
      },
    )

    // 2. Mismatched package declaration
    writeFileSync(
      pkgInfoFile,
      '/**\n * Valid javadoc.\n */\npackage fun.fengwk.kkstudio.harness.runtime.otherpkg;\n',
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
          "package declaration mismatch in harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/shellpkg/package-info.java: expected 'fun.fengwk.kkstudio.harness.runtime.shellpkg', found 'fun.fengwk.kkstudio.harness.runtime.otherpkg'",
        )
      },
    )

    assert.equal(existsSync(realShellPkg), false, 'real repository must not contain shellpkg')
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
    stageValidHarnessDocumentation(mirrorRoot)
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
    writeFileSync(
      mirrorRuntimeDoc,
      validRuntimeDoc.replace(
        /仅允许 target 为 TOOL 的 Work 携带非空/g,
        '任意类型的 Work 均可携带非空环境配置',
      ),
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
      validInfraDoc.replace(/claim 围栏/g, '无特殊约束调度'),
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
