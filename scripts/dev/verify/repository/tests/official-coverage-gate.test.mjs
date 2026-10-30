import assert from 'node:assert/strict'
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'
import test from 'node:test'
import { fileURLToPath } from 'node:url'

/**
 * agent-builtin 用官方 JaCoCo Ant ReportTask 同时分析 Runtime 与 Builtin classfiles。
 * 该 guard 只校验仓库内真实存在的 Ant report/check 合同，不复制产品类，也不接受缺失或降低阈值。
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

/** 原 Runtime 27 项 + 拆分到 Builtin 的 5 项；必须逐项保留，不能静默增加或删除。 */
const CRITICAL_CLASSES = [
  'fun.fengwk.kkstudio.harness.runtime.ManualCompactionControl',
  'fun.fengwk.kkstudio.harness.runtime.ThreadSnapshot',
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
  'fun.fengwk.kkstudio.harness.builtin.model.ModelInvocation',
  'fun.fengwk.kkstudio.harness.builtin.processor.ModelExecution',
  'fun.fengwk.kkstudio.harness.builtin.compaction.AutomaticCompactionPlanner',
  'fun.fengwk.kkstudio.harness.builtin.request.ResolvedRequestValidator',
  'fun.fengwk.kkstudio.harness.builtin.model.ModelRequestSpec',
]

function stripComments(xml) {
  return xml.replace(/<!--[\s\S]*?-->/gu, '')
}

function attributes(text) {
  const result = new Map()
  for (const match of text.matchAll(/([A-Za-z_][\w:.-]*)="([^"]*)"/gu)) {
    result.set(match[1], match[2])
  }
  return result
}

function pluginBlocks(xml) {
  return [...xml.matchAll(/<plugin>([\s\S]*?)<\/plugin>/gu)].map((match) => match[1])
}

function findPlugin(xml, artifactId) {
  return pluginBlocks(xml).find((block) =>
    new RegExp(`<artifactId>${artifactId}</artifactId>`, 'u').test(block),
  )
}

function resolveConfiguredPath(value, moduleDirectory) {
  const expanded = value
    .replaceAll('${project.basedir}', moduleDirectory)
    .replaceAll('${project.build.directory}', path.join(moduleDirectory, 'target'))
  return path.resolve(moduleDirectory, expanded)
}

function filesetDirectories(block, moduleDirectory) {
  return [...block.matchAll(/<fileset\b[^>]*\bdir="([^"]+)"/gu)].map((match) =>
    resolveConfiguredPath(match[1], moduleDirectory),
  )
}

function parseGroups(report, moduleDirectory) {
  const structure = report.match(/<structure\b[^>]*>([\s\S]*?)<\/structure>/u)?.[1] ?? ''
  return [...structure.matchAll(/<group\b([^>]*)>([\s\S]*?)<\/group>/gu)].map((match) => {
    const groupAttributes = attributes(match[1])
    const body = match[2]
    const classfiles = body.match(/<classfiles>([\s\S]*?)<\/classfiles>/u)?.[1] ?? ''
    const sourcefiles = body.match(/<sourcefiles\b[^>]*>([\s\S]*?)<\/sourcefiles>/u)?.[1] ?? ''
    return {
      name: groupAttributes.get('name') ?? '',
      classRoots: filesetDirectories(classfiles, moduleDirectory),
      sourceRoots: filesetDirectories(sourcefiles, moduleDirectory),
    }
  })
}

function escapeRegExp(value) {
  return value.replace(/[.*+?^${}()|[\]\\]/gu, '\\$&')
}

/** 在配置的 sourcefiles 中定位顶层类；嵌套类还必须确认源文件声明了该成员类型。 */
function findClassSource(groups, className) {
  const parts = className.split('.')
  for (const group of groups) {
    for (const sourceRoot of group.sourceRoots) {
      for (let end = parts.length; end > 0; end -= 1) {
        const candidate = path.join(sourceRoot, ...parts.slice(0, end)) + '.java'
        if (!existsSync(candidate)) {
          continue
        }
        if (end === parts.length) {
          return { group, sourceRoot, sourceFile: candidate, sourceClass: className }
        }
        const nested = parts.slice(end)
        const declared = new RegExp(
          `\\b(?:class|interface|enum|record)\\s+${escapeRegExp(nested[0])}\\b`,
          'u',
        ).test(readFileSync(candidate, 'utf8'))
        if (declared) {
          return {
            group,
            sourceRoot,
            sourceFile: candidate,
            sourceClass: parts.slice(0, end).join('.'),
          }
        }
      }
    }
  }
  return null
}

function expectedSet(values) {
  return [...values].sort().join('\n')
}

/**
 * 解析并校验一个官方 JaCoCo Ant report/check 合同。返回 violation 列表，便于对真实仓库和隔离 fixture
 * 使用同一套规则；任何缺失 class、额外 pattern 或低于 0.90 的阈值都必须显式失败。
 */
export function analyzeOfficialCoverageGate({
  pom,
  moduleDirectory,
  expectedClasses,
  expectedGroupNames = null,
  expectedClassRoots = null,
  expectedSourceRoots = null,
  expectedJacocoVersion = null,
}) {
  const violations = []
  const xml = stripComments(pom)
  const plugin = findPlugin(xml, 'maven-antrun-plugin')
  if (!plugin) {
    return ['maven-antrun-plugin must own the official JaCoCo report/check']
  }

  const execution = pluginBlocks(`<plugin>${plugin}</plugin>`)
    .flatMap((block) => [...block.matchAll(/<execution>([\s\S]*?)<\/execution>/gu)])
    .map((match) => match[1])
    .find((block) => block.includes('<id>check-critical-class-line-coverage</id>'))
  if (!execution) {
    violations.push('missing verify execution check-critical-class-line-coverage')
  } else {
    if (!/<phase>verify<\/phase>/u.test(execution)) {
      violations.push('coverage execution must bind to verify')
    }
    if (!/<goal>run<\/goal>/u.test(execution)) {
      violations.push('coverage execution must run the Ant target')
    }
  }

  const antDependency = [...plugin.matchAll(/<dependency>([\s\S]*?)<\/dependency>/gu)]
    .map((match) => match[1])
    .find((block) => block.includes('<artifactId>org.jacoco.ant</artifactId>'))
  if (!antDependency) {
    violations.push('maven-antrun-plugin must depend on org.jacoco.ant')
  } else {
    const version = antDependency.match(/<version>([^<]+)<\/version>/u)?.[1]
    if (!version) {
      violations.push('org.jacoco.ant dependency must declare an explicit version')
    } else if (expectedJacocoVersion && version !== expectedJacocoVersion) {
      violations.push(
        `org.jacoco.ant version ${version} must match root JaCoCo ${expectedJacocoVersion}`,
      )
    }
  }

  const target = execution?.match(/<target>([\s\S]*?)<\/target>/u)?.[1] ?? ''
  if (!target) {
    violations.push('coverage execution must declare an Ant target')
    return violations
  }
  const taskdef = target.match(/<taskdef\b([^>]*)\/>/u)?.[1]
  if (!taskdef) {
    violations.push('official JaCoCo taskdef is missing')
  } else {
    const taskdefAttributes = attributes(taskdef)
    if (taskdefAttributes.get('classname') !== 'org.jacoco.ant.ReportTask') {
      violations.push('taskdef must use org.jacoco.ant.ReportTask')
    }
    if (taskdefAttributes.get('classpathref') !== 'maven.plugin.classpath') {
      violations.push('taskdef must use maven.plugin.classpath')
    }
  }

  const report = target.match(/<jacoco-report\b[^>]*>([\s\S]*?)<\/jacoco-report>/u)?.[1] ?? ''
  if (!report) {
    violations.push('official JaCoCo report element is missing')
    return violations
  }

  const executionData = report.match(/<executiondata>([\s\S]*?)<\/executiondata>/u)?.[1] ?? ''
  const dataFile = executionData.match(/<file\b[^>]*\bfile="([^"]+)"/u)?.[1]
  if (!dataFile) {
    violations.push('report must read one integration jacoco.exec')
  } else {
    const resolved = resolveConfiguredPath(dataFile, moduleDirectory)
    const expected = path.join(moduleDirectory, 'target', 'jacoco.exec')
    if (resolved !== expected) {
      violations.push(`report must read ${expected}, got ${resolved}`)
    }
  }

  const groups = parseGroups(report, moduleDirectory)
  if (groups.length < 2) {
    violations.push('report must analyze both Runtime and Builtin classfiles')
  }
  if (expectedGroupNames) {
    const actual = groups.map((group) => group.name).sort()
    if (expectedSet(actual) !== expectedSet(expectedGroupNames)) {
      violations.push(`report groups must be ${expectedSet(expectedGroupNames)}`)
    }
  }
  if (expectedClassRoots) {
    const actual = groups.flatMap((group) => group.classRoots).sort()
    if (expectedSet(actual) !== expectedSet(expectedClassRoots)) {
      violations.push(`report classfiles roots must be ${expectedSet(expectedClassRoots)}`)
    }
  }
  if (expectedSourceRoots) {
    const actual = groups.flatMap((group) => group.sourceRoots).sort()
    if (expectedSet(actual) !== expectedSet(expectedSourceRoots)) {
      violations.push(`report sourcefiles roots must be ${expectedSet(expectedSourceRoots)}`)
    }
  }
  for (const group of groups) {
    if (group.classRoots.length === 0 || group.sourceRoots.length === 0) {
      violations.push(`group ${group.name || '<unnamed>'} must declare classfiles and sourcefiles`)
    }
  }

  const checks = [...report.matchAll(/<check\b([^>]*)>([\s\S]*?)<\/check>/gu)]
  if (checks.length === 0) {
    violations.push('official JaCoCo check element is missing')
  } else if (checks.length !== 1) {
    violations.push('exactly one official JaCoCo check may own the critical classes')
  } else {
    const check = checks[0]
    const checkAttributes = attributes(check[1])
    if (checkAttributes.get('failonviolation') !== 'true') {
      violations.push('coverage check must fail on violations')
    }
    const rules = [...check[2].matchAll(/<rule\b([^>]*)>([\s\S]*?)<\/rule>/gu)]
    const includes = []
    for (const rule of rules) {
      const ruleAttributes = attributes(rule[1])
      if (ruleAttributes.get('element') !== 'CLASS') {
        violations.push('coverage rules must be CLASS rules')
      }
      const includesText = ruleAttributes.get('includes')
      if (!includesText) {
        violations.push('coverage rule must declare includes')
        continue
      }
      for (const pattern of includesText.split(':')) {
        if (!pattern || /[*?]/u.test(pattern)) {
          violations.push(`coverage include must be one exact class, got '${pattern}'`)
          continue
        }
        includes.push(pattern)
      }
      const limits = [...rule[2].matchAll(/<limit\b([^>]*)\/>/gu)].map((match) =>
        attributes(match[1]),
      )
      if (limits.length !== 1) {
        violations.push(`class rule must declare exactly one LINE limit: ${includesText}`)
      } else {
        const limit = limits[0]
        if (limit.get('counter') !== 'LINE' || limit.get('value') !== 'COVEREDRATIO') {
          violations.push(`coverage limit must be LINE COVEREDRATIO: ${includesText}`)
        }
        const minimum = Number(limit.get('minimum'))
        if (!Number.isFinite(minimum) || minimum < 0.9) {
          violations.push(
            `coverage minimum must be at least 0.90 for ${includesText}, got ${limit.get('minimum')}`,
          )
        }
      }
    }

    const actual = expectedSet(includes)
    const expected = expectedSet(expectedClasses)
    if (actual !== expected) {
      for (const className of expectedClasses) {
        if (!includes.includes(className)) {
          violations.push(`missing critical class coverage rule: ${className}`)
        }
      }
      for (const className of includes) {
        if (!expectedClasses.includes(className)) {
          violations.push(`unexpected critical class coverage rule: ${className}`)
        }
      }
      if (new Set(includes).size !== includes.length) {
        violations.push('critical class coverage rules contain duplicate classes')
      }
    }
  }

  for (const className of expectedClasses) {
    const found = findClassSource(groups, className)
    if (!found) {
      violations.push(`critical class has no source in configured scope: ${className}`)
      continue
    }
    const existingRoots = found.group.classRoots.filter((root) => existsSync(root))
    if (existingRoots.length === 0) {
      continue
    }
    const relativeSource = path
      .relative(found.sourceRoot, found.sourceFile)
      .replace(/\.java$/u, '')
    const nested =
      className === found.sourceClass
        ? ''
        : '$' + className.slice(found.sourceClass.length + 1).split('.').join('$')
    const classFile = `${relativeSource}${nested}.class`
    if (!existingRoots.some((root) => existsSync(path.join(root, classFile)))) {
      violations.push(`critical class is missing from configured classfiles: ${className}`)
    }
  }

  return violations
}

function mavenJacocoVersion(xml) {
  const plugin = findPlugin(stripComments(xml), 'jacoco-maven-plugin')
  return plugin?.match(/<version>([^<]+)<\/version>/u)?.[1] ?? null
}

const PROJECT_BASEDIR = '${project.basedir}'
const PROJECT_BUILD_DIRECTORY = '${project.build.directory}'

function createOfficialFixture({ minimum = '0.90', includes = 'demo.Existing' } = {}) {
  const root = mkdtempSync(path.join(tmpdir(), 'kk-official-coverage-'))
  const sourceFile = path.join(root, 'src/main/java/demo/Existing.java')
  mkdirSync(path.dirname(sourceFile), { recursive: true })
  writeFileSync(sourceFile, 'package demo;\nclass Existing {}\n')
  const pom = `<project>
  <build><plugins><plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-antrun-plugin</artifactId>
    <version>3.1.0</version>
    <dependencies><dependency>
      <groupId>org.jacoco</groupId><artifactId>org.jacoco.ant</artifactId><version>0.8.11</version>
    </dependency></dependencies>
    <executions><execution>
      <id>check-critical-class-line-coverage</id>
      <phase>verify</phase>
      <goals><goal>run</goal></goals>
      <configuration><target>
        <taskdef name="jacoco-report" classname="org.jacoco.ant.ReportTask" classpathref="maven.plugin.classpath"/>
        <jacoco-report>
          <executiondata><file file="${PROJECT_BUILD_DIRECTORY}/jacoco.exec"/></executiondata>
          <structure name="fixture"><group name="fixture">
            <classfiles><fileset dir="${PROJECT_BUILD_DIRECTORY}/classes"/></classfiles>
            <sourcefiles><fileset dir="${PROJECT_BASEDIR}/src/main/java"/></sourcefiles>
          </group></structure>
          <check failonviolation="true">
            <rule element="CLASS" includes="${includes}">
              <limit counter="LINE" value="COVEREDRATIO" minimum="${minimum}"/>
            </rule>
          </check>
        </jacoco-report>
      </target></configuration>
    </execution></executions>
  </plugin></plugins></build>
</project>
`
  writeFileSync(path.join(root, 'pom.xml'), pom)
  return { root, pom }
}

function analyzeFixture(fixture, expectedClasses = ['demo.Existing']) {
  return analyzeOfficialCoverageGate({
    pom: fixture.pom,
    moduleDirectory: fixture.root,
    expectedClasses,
  })
}

test('official Ant gate owns every critical Runtime and Builtin class at 0.90', () => {
  const builtinPom = readFileSync(path.join(REPOSITORY_ROOT, 'harness/agent-builtin/pom.xml'), 'utf8')
  const rootPom = readFileSync(path.join(REPOSITORY_ROOT, 'pom.xml'), 'utf8')
  const violations = analyzeOfficialCoverageGate({
    pom: builtinPom,
    moduleDirectory: path.join(REPOSITORY_ROOT, 'harness/agent-builtin'),
    expectedClasses: CRITICAL_CLASSES,
    expectedGroupNames: ['harness-runtime', 'harness-agent-builtin'],
    expectedClassRoots: [
      path.join(REPOSITORY_ROOT, 'harness/runtime/target/classes'),
      path.join(REPOSITORY_ROOT, 'harness/agent-builtin/target/classes'),
    ],
    expectedSourceRoots: [
      path.join(REPOSITORY_ROOT, 'harness/runtime/src/main/java'),
      path.join(REPOSITORY_ROOT, 'harness/agent-builtin/src/main/java'),
    ],
    expectedJacocoVersion: mavenJacocoVersion(rootPom),
  })
  assert.deepEqual(violations, [])
})

test('runtime no longer owns a local coverage check', () => {
  const runtimePom = readFileSync(path.join(REPOSITORY_ROOT, 'harness/runtime/pom.xml'), 'utf8')
  assert.doesNotMatch(runtimePom, /<rule\b/u)
  assert.doesNotMatch(runtimePom, /check-critical-class-line-coverage/u)
})

test('official gate rejects a missing critical class instead of silently dropping it', () => {
  const fixture = createOfficialFixture()
  try {
    const violations = analyzeFixture(fixture, ['demo.Existing', 'demo.Missing'])
    assert.ok(violations.some((violation) => violation.includes('demo.Missing')))
  } finally {
    rmSync(fixture.root, { recursive: true, force: true })
  }
})

test('official gate rejects lowering a class threshold below 0.90', () => {
  const fixture = createOfficialFixture({ minimum: '0.80' })
  try {
    const violations = analyzeFixture(fixture)
    assert.ok(violations.some((violation) => /at least 0\.90/u.test(violation)))
  } finally {
    rmSync(fixture.root, { recursive: true, force: true })
  }
})

test('official gate rejects wildcard include expansion', () => {
  const fixture = createOfficialFixture({ includes: 'demo.*' })
  try {
    const violations = analyzeFixture(fixture)
    assert.ok(violations.some((violation) => /one exact class/u.test(violation)))
  } finally {
    rmSync(fixture.root, { recursive: true, force: true })
  }
})
