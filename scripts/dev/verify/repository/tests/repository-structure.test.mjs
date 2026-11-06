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
import { repositoryRoot } from '../../../lib/repo-root.mjs'

const REPOSITORY_ROOT = repositoryRoot()
const CHECK_SCRIPT = path.join(REPOSITORY_ROOT, 'scripts/dev/verify/repository/check.mjs')

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

test('docs check fails when unexpected harness module directory is introduced', () => {
  // Test intent: ensure normal validation via the repository check immediately fails
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

test('docs check ignores hidden Harness IDE metadata', () => {
  // Test intent: local dot-prefixed IDE metadata must not weaken the exact visible module inventory.
  const mirrorRoot = createRepositoryMirror()
  try {
    const settingsDir = path.join(mirrorRoot, 'harness/.settings')
    mkdirSync(settingsDir, { recursive: true })
    writeFileSync(path.join(settingsDir, 'org.eclipse.m2e.core.prefs'), 'activeProfiles=\n')

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

test('docs check rejects unrelated SQL files next to the fixed documents', () => {
  // Test intent: the fixed docs inventory admits only the declared documents, so a second
  // design DDL or any other stray SQL file must be reported instead of silently accepted.
  const mirrorRoot = createRepositoryMirror()
  const check = () =>
    execFileSync('node', [CHECK_SCRIPT, '--root', mirrorRoot], {
      cwd: mirrorRoot,
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'pipe'],
    })
  try {
    writeFileSync(path.join(mirrorRoot, 'docs/unrelated.sql'), 'select 1;\n')
    assert.throws(
      check,
      (error) => (error.stderr || '').includes('unexpected docs file: docs/unrelated.sql'),
    )
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
    'harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/api/thread/package-info.java'
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
    'harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/api/thread/package-info.java'
  const expectedPackage = 'fun.fengwk.kkstudio.harness.api.thread'
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

test('web production must not compile-import opencli plugin classes', () => {
  // Test intent: the shipped web artifact must keep opencli a runtime-scoped choice; production sources
  // may never compile against it, while test-only composition roots are not part of the artifact boundary.
  const webMainDir = path.join(REPOSITORY_ROOT, 'web/src/main/java')
  if (!existsSync(webMainDir)) {
    return
  }
  function scan(dir) {
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
      const full = path.join(dir, entry.name)
      if (entry.isDirectory()) {
        scan(full)
      } else if (entry.name.endsWith('.java')) {
        const source = readFileSync(full, 'utf8')
        assert.equal(
          /^\s*import\s+(?:static\s+)?fun\.fengwk\.kkstudio\.plugin\.opencli\./mu.test(source),
          false,
          `forbidden compile import in ${path.relative(REPOSITORY_ROOT, full)}`,
        )
      }
    }
  }
  scan(webMainDir)
})

test('web architecture guard rejects undeclared harness imports and controller processors in isolation', () => {
  // Test intent: the repository check owns the web composition-root boundary, so undeclared
  // builtin/processor imports must fail closed even without a module-local architecture test.
  const mirrorRoot = createRepositoryMirror()
  try {
    const mirrorWeb = path.join(mirrorRoot, 'web')
    rmSync(mirrorWeb, { recursive: true, force: true })
    cpSync(path.join(REPOSITORY_ROOT, 'web'), mirrorWeb, {
      recursive: true,
      filter: (source) => path.basename(source) !== 'target',
    })
    writeFileSync(
      path.join(mirrorWeb, 'src/main/java/fun/fengwk/kkstudio/web/ArchGuardProbe.java'),
      'package fun.fengwk.kkstudio.web;\nimport fun.fengwk.kkstudio.harness.builtin.driver.BuiltinAgentDriver;\npublic class ArchGuardProbe {}\n',
    )
    writeFileSync(
      path.join(mirrorWeb, 'src/main/java/fun/fengwk/kkstudio/web/controller/ArchGuardProbeController.java'),
      'package fun.fengwk.kkstudio.web.controller;\nimport fun.fengwk.kkstudio.harness.runtime.processor.ModelProcessor;\npublic class ArchGuardProbeController {}\n',
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
        return (
          stderr.includes('web must use only declared Harness boundaries') &&
          stderr.includes('controllers must not depend on Harness runtime processors')
        )
      },
    )
  } finally {
    rmSync(mirrorRoot, { recursive: true, force: true })
  }
})

test('module boundary guard rejects a plugin depending on the platform runtime in isolation', () => {
  // Test intent: plugins reach the host only through the platform plugin SPI; a runtime dependency must fail closed.
  const mirrorRoot = createRepositoryMirror()
  try {
    const mirrorPlugins = path.join(mirrorRoot, 'plugins')
    rmSync(mirrorPlugins, { recursive: true, force: true })
    cpSync(path.join(REPOSITORY_ROOT, 'plugins'), mirrorPlugins, {
      recursive: true,
      filter: (source) => path.basename(source) !== 'target',
    })
    const mirrorOpencliPom = path.join(mirrorPlugins, 'opencli/pom.xml')
    const original = readFileSync(mirrorOpencliPom, 'utf8')
    const corrupted = original.replace(
      '</dependencies>',
      '    <dependency><groupId>fun.fengwk.kk-studio</groupId><artifactId>kk-studio-platform-runtime</artifactId></dependency>\n    </dependencies>',
    )
    writeFileSync(mirrorOpencliPom, corrupted)
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
        return stderr.includes('plugins/opencli must not depend on kk-studio-platform-runtime')
      },
    )
  } finally {
    rmSync(mirrorRoot, { recursive: true, force: true })
  }
})

test('web plugin import guard rejects forbidden static import in isolation', () => {
  // Test intent: production code compiling against any plugin implementation must fail closed,
  // keeping plugins runtime-scoped and the artifact boundary strict.
  const mirrorRoot = createRepositoryMirror()
  try {
    const mirrorWeb = path.join(mirrorRoot, 'web')
    rmSync(mirrorWeb, { recursive: true, force: true })
    cpSync(path.join(REPOSITORY_ROOT, 'web'), mirrorWeb, {
      recursive: true,
      filter: (source) => path.basename(source) !== 'target',
    })
    const mirrorWebMainDir = path.join(mirrorWeb, 'src/main/java/fun/fengwk/kkstudio/web/plugin')
    mkdirSync(mirrorWebMainDir, { recursive: true })
    const mainFile = path.join(mirrorWebMainDir, 'StaticImportGuard.java')
    writeFileSync(
      mainFile,
      'package fun.fengwk.kkstudio.web.plugin;\nimport static fun.fengwk.kkstudio.plugin.opencli.OpenCliHubClient.ExecutionStatus.*;\npublic class StaticImportGuard {}\n',
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
        return stderr.includes('web production must not import plugin implementations')
      },
    )
  } finally {
    rmSync(mirrorRoot, { recursive: true, force: true })
  }
})

test('module boundary guard rejects a harness driver depending on the platform runtime in isolation', () => {
  // Test intent: agent-builtin may only reach runtime/model/common; platform composition must not become a
  // reverse dependency for drivers, so the new allowlist entry must still fail closed.
  const mirrorRoot = createRepositoryMirror()
  try {
    const driverPom = path.join(mirrorRoot, 'harness/agent-builtin/pom.xml')
    const original = readFileSync(driverPom, 'utf8')
    writeFileSync(
      driverPom,
      original.replace(
        '</dependencies>',
        '    <dependency><groupId>fun.fengwk.kk-studio</groupId><artifactId>kk-studio-platform-runtime</artifactId></dependency>\n    </dependencies>',
      ),
    )
    assert.throws(
      () =>
        execFileSync('node', [CHECK_SCRIPT, '--root', mirrorRoot], {
          cwd: mirrorRoot,
          encoding: 'utf8',
          stdio: ['ignore', 'pipe', 'pipe'],
        }),
      (error) =>
        (error.stderr || '').includes(
          'harness/agent-builtin must not depend on kk-studio-platform-runtime',
        ),
    )
  } finally {
    rmSync(mirrorRoot, { recursive: true, force: true })
  }
})

test('module boundary guard rejects platform depending on an app in isolation', () => {
  // Test intent: platform never depends on apps; reintroducing platform -> project must fail closed.
  const mirrorRoot = createRepositoryMirror()
  try {
    const mirrorPlatform = path.join(mirrorRoot, 'platform')
    rmSync(mirrorPlatform, { recursive: true, force: true })
    cpSync(path.join(REPOSITORY_ROOT, 'platform'), mirrorPlatform, {
      recursive: true,
      filter: (source) => path.basename(source) !== 'target',
    })
    const pom = path.join(mirrorPlatform, 'runtime/pom.xml')
    writeFileSync(
      pom,
      readFileSync(pom, 'utf8').replace(
        '</dependencies>',
        '    <dependency><groupId>fun.fengwk.kk-studio</groupId><artifactId>kk-studio-project</artifactId></dependency>\n    </dependencies>',
      ),
    )
    assert.throws(
      () =>
        execFileSync('node', [CHECK_SCRIPT, '--root', mirrorRoot], {
          cwd: mirrorRoot,
          encoding: 'utf8',
          stdio: ['ignore', 'pipe', 'pipe'],
        }),
      (error) => (error.stderr || '').includes('platform/runtime must not depend on kk-studio-project'),
    )
  } finally {
    rmSync(mirrorRoot, { recursive: true, force: true })
  }
})

test('module boundary guard rejects an app depending on another app in test scope in isolation', () => {
  // Test intent: products never depend on other products even in test scope; app -> app test dependency must fail closed while keeping foundation test dependencies.
  const mirrorRoot = createRepositoryMirror()
  try {
    const mirrorApps = path.join(mirrorRoot, 'apps')
    rmSync(mirrorApps, { recursive: true, force: true })
    cpSync(path.join(REPOSITORY_ROOT, 'apps'), mirrorApps, {
      recursive: true,
      filter: (source) => path.basename(source) !== 'target',
    })
    const pom = path.join(mirrorApps, 'project/pom.xml')
    writeFileSync(
      pom,
      readFileSync(pom, 'utf8').replace(
        '</dependencies>',
        '    <dependency><groupId>fun.fengwk.kk-studio</groupId><artifactId>kk-studio-chat</artifactId><scope>test</scope></dependency>\n    </dependencies>',
      ),
    )
    assert.throws(
      () =>
        execFileSync('node', [CHECK_SCRIPT, '--root', mirrorRoot], {
          cwd: mirrorRoot,
          encoding: 'utf8',
          stdio: ['ignore', 'pipe', 'pipe'],
        }),
      (error) => (error.stderr || '').includes('apps/project must not depend on kk-studio-chat'),
    )
  } finally {
    rmSync(mirrorRoot, { recursive: true, force: true })
  }
})

test('module boundary guard rejects plugin imports outside the plugin SPI in isolation', () => {
  // Test intent: plugin sources may import only the platform plugin SPI and shared value types.
  const mirrorRoot = createRepositoryMirror()
  try {
    const mirrorPlugins = path.join(mirrorRoot, 'plugins')
    rmSync(mirrorPlugins, { recursive: true, force: true })
    cpSync(path.join(REPOSITORY_ROOT, 'plugins'), mirrorPlugins, {
      recursive: true,
      filter: (source) => path.basename(source) !== 'target',
    })
    const source = path.join(
      mirrorPlugins,
      'media/src/main/java/fun/fengwk/kkstudio/plugin/media/RuntimeLeak.java',
    )
    writeFileSync(
      source,
      'package fun.fengwk.kkstudio.plugin.media;\nimport fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;\nclass RuntimeLeak {}\n',
    )
    assert.throws(
      () =>
        execFileSync('node', [CHECK_SCRIPT, '--root', mirrorRoot], {
          cwd: mirrorRoot,
          encoding: 'utf8',
          stdio: ['ignore', 'pipe', 'pipe'],
        }),
      (error) =>
        (error.stderr || '').includes(
          'plugins may only import the platform plugin SPI, found fun.fengwk.kkstudio.harness.runtime.HarnessRuntime',
        ),
    )
  } finally {
    rmSync(mirrorRoot, { recursive: true, force: true })
  }
})

test('repository check rejects prohibited session artifacts with file and line number', () => {
  const mirrorRoot = createRepositoryMirror()
  try {
    const testFile = path.join(mirrorRoot, 'harness/test-session-artifact.txt')
    const badTermSlice = '本切片'
    const badTermBranch = 'Branch B'
    writeFileSync(
      testFile,
      `line 1\nline 2 with ${badTermSlice}\nline 3\nline 4 with ${badTermBranch}\n`,
    )
    assert.throws(
      () =>
        execFileSync('node', [CHECK_SCRIPT, '--root', mirrorRoot], {
          cwd: mirrorRoot,
          encoding: 'utf8',
          stdio: ['ignore', 'pipe', 'pipe'],
        }),
      (error) => {
        const stderr = error.stderr || ''
        const hasSliceHit = stderr.includes(
          `harness/test-session-artifact.txt:2: contains prohibited session artifact '${badTermSlice}'`,
        )
        const hasBranchHit = stderr.includes(
          `harness/test-session-artifact.txt:4: contains prohibited session artifact '${badTermBranch}'`,
        )
        return hasSliceHit && hasBranchHit
      },
    )
  } finally {
    rmSync(mirrorRoot, { recursive: true, force: true })
  }
})

test('documentation rejects session artifacts but permits historical names and fenced examples', () => {
  // The session artifact rule applies to prose while permitting fenced examples.
  const mirrorRoot = createRepositoryMirror()
  const relativePath = 'docs/modules/common.md'
  const docPath = path.join(mirrorRoot, relativePath)
  const original = readFileSync(docPath, 'utf8')
  const slice = '本切片'
  const branch = 'Branch B'
  const workspace = 'workspace notes'
  const check = () =>
    execFileSync('node', [CHECK_SCRIPT, '--root', mirrorRoot], {
      cwd: mirrorRoot,
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'pipe'],
    })
  try {
    writeFileSync(
      docPath,
      `${original}\nDATA_BIND technical-solution\n\n\`\`\`text\n${slice}\n${branch}\n${workspace}\n\`\`\`\n`,
    )
    assert.match(check(), /PASS docs/u)

    writeFileSync(docPath, `${original}\n${slice}\n${branch}\n${workspace}\n`)
    assert.throws(check, (error) => {
      const stderr = error.stderr || ''
      return (
        stderr.includes(`${relativePath}: contains prohibited session artifact '${slice}'`) &&
        stderr.includes(`${relativePath}: contains prohibited session artifact '${branch}'`) &&
        stderr.includes(`${relativePath}: contains prohibited session artifact 'Workspace NOTES'`)
      )
    })
  } finally {
    rmSync(mirrorRoot, { recursive: true, force: true })
  }
})
