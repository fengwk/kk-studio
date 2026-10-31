import assert from 'node:assert/strict'
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'
import test from 'node:test'
import { fileURLToPath } from 'node:url'
import { indexMainSources, isExactClassName } from './support/jacoco-source-index.mjs'

/**
 * 官方 JaCoCo 覆盖门禁守护：runtime 与 agent-builtin 各自用 native jacoco:check 校验自己的关键类，
 * 不再由一个模块的 Ant task 读取另一个模块的 classfiles。每个 include 必须是当前模块真实存在的
 * 精确类名（嵌套类型按 JaCoCo 的 `.` 归一命名），否则空匹配会让门禁静默失效。
 */

function repositoryRoot() {
  if (process.env.KK_STUDIO_REPO_ROOT) {
    return path.resolve(process.env.KK_STUDIO_REPO_ROOT)
  }
  let directory = path.dirname(fileURLToPath(import.meta.url))
  for (;;) {
    if (existsSync(path.join(directory, '.git'))) {
      return directory
    }
    const parent = path.dirname(directory)
    if (parent === directory) {
      throw new Error('cannot locate the kk-studio worktree root; set KK_STUDIO_REPO_ROOT')
    }
    directory = parent
  }
}

const REPOSITORY_ROOT = repositoryRoot()
const RUNTIME_MODULE = 'harness/runtime'
const BUILTIN_MODULE = 'harness/agent-builtin'

/** 34 个关键类按物理源码归属拆分：runtime 29 项，builtin 5 项。 */
const RUNTIME_CRITICAL_CLASSES = [
  'fun.fengwk.kkstudio.harness.runtime.ManualCompactionControl',
  'fun.fengwk.kkstudio.harness.runtime.ThreadSnapshot',
  'fun.fengwk.kkstudio.harness.runtime.history.EntryCurrentState',
  'fun.fengwk.kkstudio.harness.runtime.history.EntryPathMetrics',
  'fun.fengwk.kkstudio.harness.runtime.compaction.CompactionHistory',
  'fun.fengwk.kkstudio.harness.runtime.thread.ThreadContextProbe',
  'fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding',
  'fun.fengwk.kkstudio.harness.runtime.invocation.codec.ToolBindingJsonCodec',
  'fun.fengwk.kkstudio.harness.runtime.AcceptCommandsControl',
  'fun.fengwk.kkstudio.harness.runtime.AgentSpawner',
  'fun.fengwk.kkstudio.harness.runtime.SpawnRequest',
  'fun.fengwk.kkstudio.harness.runtime.SpawnRequest.Continuation',
  'fun.fengwk.kkstudio.harness.runtime.SpawnRequest.Budget',
  'fun.fengwk.kkstudio.harness.runtime.SpawnTickets',
  'fun.fengwk.kkstudio.harness.runtime.compaction.CompactionChildSpawn',
  'fun.fengwk.kkstudio.harness.runtime.join.ThreadJoin',
  'fun.fengwk.kkstudio.harness.runtime.thread.ThreadConstraints',
  'fun.fengwk.kkstudio.harness.runtime.thread.ThreadExecutionControl',
  'fun.fengwk.kkstudio.harness.runtime.thread.ThreadExecutionFacts',
  'fun.fengwk.kkstudio.harness.runtime.thread.ThreadState',
  'fun.fengwk.kkstudio.harness.tool.ToolCall',
  'fun.fengwk.kkstudio.harness.tool.codec.AgentToolDefinitionJsonCodec',
  'fun.fengwk.kkstudio.harness.tool.codec.ToolDescriptorJsonCodec',
  'fun.fengwk.kkstudio.harness.tool.codec.ToolResultJsonCodec',
  'fun.fengwk.kkstudio.harness.contributor.api.HarnessCatalog',
  'fun.fengwk.kkstudio.harness.builtin.BuiltinHarnessContributor',
  'fun.fengwk.kkstudio.harness.builtin.goal.GoalProgressCodec',
  'fun.fengwk.kkstudio.harness.prompt.PromptTemplate',
  'fun.fengwk.kkstudio.harness.prompt.PromptTemplateLoader',
]

const BUILTIN_CRITICAL_CLASSES = [
  'fun.fengwk.kkstudio.harness.builtin.model.ModelInvocation',
  'fun.fengwk.kkstudio.harness.builtin.processor.ModelExecution',
  'fun.fengwk.kkstudio.harness.builtin.compaction.AutomaticCompactionPlanner',
  'fun.fengwk.kkstudio.harness.builtin.request.ResolvedRequestValidator',
  'fun.fengwk.kkstudio.harness.builtin.model.ModelRequestSpec',
]

function stripComments(xml) {
  return xml.replace(/<!--[\s\S]*?-->/gu, '')
}

function pluginBlocks(xml) {
  return [...xml.matchAll(/<plugin\b[^>]*>([\s\S]*?)<\/plugin>/gu)].map((match) => match[1])
}

function findPlugin(xml, artifactId) {
  return pluginBlocks(xml).find((block) =>
    new RegExp(`<artifactId>\\s*${artifactId}\\s*</artifactId>`, 'u').test(block),
  )
}

function executionBlocks(plugin) {
  return [...plugin.matchAll(/<execution>([\s\S]*?)<\/execution>/gu)].map((match) => match[1])
}

function tagValue(xml, tag) {
  return xml.match(new RegExp(`<${tag}>\\s*([^<\\s]+)\\s*</${tag}>`, 'u'))?.[1] ?? null
}

function includeValues(rule) {
  const includes = rule.match(/<includes>([\s\S]*?)<\/includes>/u)?.[1] ?? ''
  return [...includes.matchAll(/<include>\s*([^<\s]+)\s*<\/include>/gu)].map((match) => match[1])
}

function limitBlocks(rule) {
  return [...rule.matchAll(/<limit>([\s\S]*?)<\/limit>/gu)].map((match) => match[1])
}

/**
 * 校验一个模块的 native JaCoCo 合同。返回 violation 列表，真实仓库与隔离 fixture 共用同一套规则。
 * include 既必须精确命中当前模块的主源码类，也必须与期望集合完全一致。
 */
export function analyzeNativeCoverageGate({ pom, moduleDirectory, expectedClasses }) {
  const violations = []
  const xml = stripComments(pom)
  if (/<artifactId>\s*maven-antrun-plugin\s*<\/artifactId>/u.test(xml)) {
    violations.push('maven-antrun-plugin must not own critical-class coverage')
  }
  if (/<artifactId>\s*org\.jacoco\.ant\s*<\/artifactId>/u.test(xml)) {
    violations.push('critical-class coverage must not depend on org.jacoco.ant')
  }

  const plugin = findPlugin(xml, 'jacoco-maven-plugin')
  if (!plugin) {
    violations.push('jacoco-maven-plugin must own the native critical-class check')
    return violations
  }
  if (/<skip>\s*true\s*<\/skip>/u.test(plugin)) {
    violations.push('jacoco-maven-plugin must not skip the critical-class check')
  }

  const execution = executionBlocks(plugin).find((block) =>
    block.includes('<id>check-critical-class-line-coverage</id>'),
  )
  if (!execution) {
    violations.push('missing verify execution check-critical-class-line-coverage')
    return violations
  }
  if (tagValue(execution, 'phase') !== 'verify') {
    violations.push('coverage execution must bind to verify')
  }
  if (!/<goal>\s*check\s*<\/goal>/u.test(execution)) {
    violations.push('coverage execution must run jacoco:check')
  }

  const rulesBlock = execution.match(/<rules>([\s\S]*?)<\/rules>/u)?.[1] ?? ''
  if (!rulesBlock) {
    violations.push('coverage execution must declare rules')
    return violations
  }
  const rules = [...rulesBlock.matchAll(/<rule>([\s\S]*?)<\/rule>/gu)].map((match) => match[1])
  if (rules.length !== 1) {
    violations.push('exactly one CLASS rule must own the critical classes')
  }

  const includes = []
  for (const rule of rules) {
    if (tagValue(rule, 'element') !== 'CLASS') {
      violations.push('critical-class coverage rules must use element CLASS')
    }
    if (/<excludes>/u.test(rule)) {
      violations.push('critical-class coverage rule must not exclude classes')
    }
    const ruleIncludes = includeValues(rule)
    if (ruleIncludes.length === 0) {
      violations.push('critical-class coverage rule must declare includes')
    }
    includes.push(...ruleIncludes)

    const limits = limitBlocks(rule)
    if (limits.length !== 1) {
      violations.push('critical-class coverage rule must declare exactly one LINE limit')
    }
    for (const limit of limits) {
      if (tagValue(limit, 'counter') !== 'LINE' || tagValue(limit, 'value') !== 'COVEREDRATIO') {
        violations.push('critical-class coverage limit must be LINE COVEREDRATIO')
      }
      const minimum = Number(tagValue(limit, 'minimum'))
      if (!Number.isFinite(minimum) || minimum < 0.9) {
        violations.push(
          `critical-class coverage minimum must be at least 0.90, got ${tagValue(limit, 'minimum')}`,
        )
      }
    }
  }

  const sourceClasses = indexMainSources(moduleDirectory).classes
  for (const include of includes) {
    if (!isExactClassName(include)) {
      violations.push(
        `critical-class include must be an exact qualified name with '.' for nested types: ${include}`,
      )
      continue
    }
    if (!sourceClasses.has(include)) {
      violations.push(`critical-class include matches no main source class: ${include}`)
    }
  }

  const actual = new Set(includes)
  for (const className of expectedClasses) {
    if (!actual.has(className)) {
      violations.push(`missing critical class coverage rule: ${className}`)
    }
  }
  for (const className of actual) {
    if (!expectedClasses.includes(className)) {
      violations.push(`unexpected critical class coverage rule: ${className}`)
    }
  }
  if (actual.size !== includes.length) {
    violations.push('critical class coverage rules contain duplicate classes')
  }
  return violations
}

function createFixture({
  minimum = '0.90',
  includes = ['demo.Existing'],
  sources = { 'demo/Existing.java': 'package demo;\nclass Existing {}\n' },
  extraPlugins = '',
} = {}) {
  const root = mkdtempSync(path.join(tmpdir(), 'kk-native-coverage-'))
  for (const [relativePath, source] of Object.entries(sources)) {
    const file = path.join(root, 'src/main/java', relativePath)
    mkdirSync(path.dirname(file), { recursive: true })
    writeFileSync(file, source)
  }
  const includeXml = includes.map((value) => `<include>${value}</include>`).join('')
  const pom = `<project>
  <build><plugins><plugin>
    <groupId>org.jacoco</groupId>
    <artifactId>jacoco-maven-plugin</artifactId>
    <executions><execution>
      <id>check-critical-class-line-coverage</id>
      <phase>verify</phase>
      <goals><goal>check</goal></goals>
      <configuration><rules><rule>
        <element>CLASS</element>
        <includes>${includeXml}</includes>
        <limits><limit>
          <counter>LINE</counter><value>COVEREDRATIO</value><minimum>${minimum}</minimum>
        </limit></limits>
      </rule></rules></configuration>
    </execution></executions>
  </plugin>${extraPlugins}</plugins></build>
</project>
`
  writeFileSync(path.join(root, 'pom.xml'), pom)
  return { root, pom, includes }
}

function analyzeFixture(fixture, expectedClasses = fixture.includes) {
  return analyzeNativeCoverageGate({
    pom: fixture.pom,
    moduleDirectory: fixture.root,
    expectedClasses,
  })
}

function withFixture(options, assertions) {
  const fixture = createFixture(options)
  try {
    assertions(fixture)
  } finally {
    rmSync(fixture.root, { recursive: true, force: true })
  }
}

test('the 34 critical classes are split into runtime 29 and builtin 5 without overlap', () => {
  assert.equal(RUNTIME_CRITICAL_CLASSES.length, 29)
  assert.equal(BUILTIN_CRITICAL_CLASSES.length, 5)
  const all = [...RUNTIME_CRITICAL_CLASSES, ...BUILTIN_CRITICAL_CLASSES]
  assert.equal(new Set(all).size, 34)
  assert.equal(
    RUNTIME_CRITICAL_CLASSES.filter((name) => BUILTIN_CRITICAL_CLASSES.includes(name)).length,
    0,
  )
})

test('runtime native gate owns 29 runtime classes at 0.90', () => {
  const pom = readFileSync(path.join(REPOSITORY_ROOT, RUNTIME_MODULE, 'pom.xml'), 'utf8')
  assert.deepEqual(
    analyzeNativeCoverageGate({
      pom,
      moduleDirectory: path.join(REPOSITORY_ROOT, RUNTIME_MODULE),
      expectedClasses: RUNTIME_CRITICAL_CLASSES,
    }),
    [],
  )
})

test('builtin native gate owns 5 builtin classes at 0.90', () => {
  const pom = readFileSync(path.join(REPOSITORY_ROOT, BUILTIN_MODULE, 'pom.xml'), 'utf8')
  assert.deepEqual(
    analyzeNativeCoverageGate({
      pom,
      moduleDirectory: path.join(REPOSITORY_ROOT, BUILTIN_MODULE),
      expectedClasses: BUILTIN_CRITICAL_CLASSES,
    }),
    [],
  )
})

test('SpawnRequest nested types use the dotted JaCoCo class names', () => {
  // JaCoCo 把 `SpawnRequest$Continuation` 归一为 `SpawnRequest.Continuation` 后才会命中。
  const { classes } = indexMainSources(path.join(REPOSITORY_ROOT, RUNTIME_MODULE))
  assert.ok(classes.has('fun.fengwk.kkstudio.harness.runtime.SpawnRequest.Continuation'))
  assert.ok(classes.has('fun.fengwk.kkstudio.harness.runtime.SpawnRequest.Budget'))
})

test('runtime and builtin publish test-jars for distinct test support', () => {
  const runtimePom = readFileSync(path.join(REPOSITORY_ROOT, RUNTIME_MODULE, 'pom.xml'), 'utf8')
  const builtinPom = readFileSync(path.join(REPOSITORY_ROOT, BUILTIN_MODULE, 'pom.xml'), 'utf8')
  assert.match(runtimePom, /<goal>\s*test-jar\s*<\/goal>/u)
  assert.match(builtinPom, /<id>\s*builtin-test-support\s*<\/id>/u)
  assert.match(builtinPom, /<goal>\s*test-jar\s*<\/goal>/u)
})

test('dotted nested class names matching real member types are accepted', () => {
  withFixture(
    {
      includes: ['demo.Existing', 'demo.Existing.Nested'],
      sources: { 'demo/Existing.java': 'package demo;\nclass Existing { static final class Nested {} }\n' },
    },
    (fixture) => {
      assert.deepEqual(analyzeFixture(fixture), [])
    },
  )
})

test('a missing class is rejected instead of passing as an empty include match', () => {
  withFixture({}, (fixture) => {
    const violations = analyzeFixture(fixture, ['demo.Existing', 'demo.Missing'])
    assert.ok(violations.some((violation) => violation.includes('demo.Missing')))
  })
})

test('a missing nested class is rejected instead of passing as an empty include match', () => {
  withFixture(
    {
      includes: ['demo.Existing', 'demo.Existing.Missing'],
      sources: { 'demo/Existing.java': 'package demo;\nclass Existing { static final class Nested {} }\n' },
    },
    (fixture) => {
      const violations = analyzeFixture(fixture)
      assert.ok(violations.some((violation) => violation.includes('demo.Existing.Missing')))
    },
  )
})

test('method-local classes do not enter the member-class namespace', () => {
  withFixture(
    {
      includes: ['demo.Existing', 'demo.Existing.Local'],
      sources: {
        'demo/Existing.java': 'package demo;\nclass Existing { void run() { class Local {} } }\n',
      },
    },
    (fixture) => {
      const violations = analyzeFixture(fixture)
      assert.ok(violations.some((violation) => violation.includes('demo.Existing.Local')))
    },
  )
})

test('nested dollar notation is rejected', () => {
  withFixture({ includes: ['demo.Existing$Nested'] }, (fixture) => {
    const violations = analyzeFixture(fixture)
    assert.ok(violations.some((violation) => /exact qualified name/u.test(violation)))
  })
})

test('wildcard includes are rejected', () => {
  withFixture({ includes: ['demo.*'] }, (fixture) => {
    const violations = analyzeFixture(fixture)
    assert.ok(violations.some((violation) => /exact qualified name/u.test(violation)))
  })
})

test('lowering a class threshold below 0.90 is rejected', () => {
  withFixture({ minimum: '0.80' }, (fixture) => {
    const violations = analyzeFixture(fixture)
    assert.ok(violations.some((violation) => /at least 0\.90/u.test(violation)))
  })
})

test('an unexpected extra critical class is rejected', () => {
  withFixture(
    {
      includes: ['demo.Existing', 'demo.Other'],
      sources: {
        'demo/Existing.java': 'package demo;\nclass Existing {}\n',
        'demo/Other.java': 'package demo;\nclass Other {}\n',
      },
    },
    (fixture) => {
      const violations = analyzeFixture(fixture, ['demo.Existing'])
      assert.ok(violations.some((violation) => violation.includes('unexpected critical class')))
    },
  )
})

test('duplicate critical class includes are rejected', () => {
  withFixture({ includes: ['demo.Existing', 'demo.Existing'] }, (fixture) => {
    const violations = analyzeFixture(fixture, ['demo.Existing'])
    assert.ok(violations.some((violation) => /duplicate classes/u.test(violation)))
  })
})

test('cross-module Ant report is rejected', () => {
  withFixture(
    {
      extraPlugins: `<plugin>
        <artifactId>maven-antrun-plugin</artifactId>
        <dependencies><dependency><artifactId>org.jacoco.ant</artifactId></dependency></dependencies>
      </plugin>`,
    },
    (fixture) => {
      const violations = analyzeFixture(fixture)
      assert.ok(violations.some((violation) => /maven-antrun-plugin/u.test(violation)))
      assert.ok(violations.some((violation) => /org\.jacoco\.ant/u.test(violation)))
    },
  )
})
