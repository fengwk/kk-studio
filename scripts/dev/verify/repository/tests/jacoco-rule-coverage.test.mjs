import assert from 'node:assert/strict'
import { existsSync, mkdirSync, mkdtempSync, readdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'
import test from 'node:test'
import { fileURLToPath } from 'node:url'

/**
 * JaCoCo check 命名规约守护。
 *
 * `BundleChecker` 在匹配规则前按 `JavaNames` 转换元素名称：CLASS/PACKAGE 把 `/`、`$` 转为 `.`，
 * SOURCEFILE 仍是 `包/文件`，BUNDLE 用 bundle 名。因此 CLASS/PACKAGE 若写 `fun/fengwk/...` 或
 * `Outer$Inner`，元素永不命中、规则被静默跳过（假绿）。本测试沿 Maven `<modules>` 递归发现模块 POM，
 * 校验 CLASS/PACKAGE 使用的是能命中真实主源码的合法限定名；BUNDLE/SOURCEFILE 命名规约不同，保持区分。
 * 注：`project` 的 BUNDLE 规则写成包模式（`...project.domain.*`），而 BUNDLE 只匹配 bundle 名，同样不生效；
 * 该独立问题按当前范围不在本守护内处理。
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

function tagValues(xml, tag) {
  return [...xml.matchAll(new RegExp(`<${tag}>\\s*([^<\\s]+)\\s*</${tag}>`, 'gu'))].map((m) => m[1])
}

function stripComments(xml) {
  return xml.replace(/<!--[\s\S]*?-->/gu, '')
}

/**
 * 沿 Maven `<modules>` 递归发现模块 POM（不做全仓库目录遍历）。声明了但缺失的模块 POM 记为 violation，
 * 因为缺失的模块会让其门禁彻底失效而不被发现。
 */
function discoverModulePoms(root) {
  const poms = []
  const violations = []
  const seen = new Set()
  const queue = [path.join(root, 'pom.xml')]
  while (queue.length > 0) {
    const pomPath = queue.shift()
    if (seen.has(pomPath)) {
      continue
    }
    seen.add(pomPath)
    if (!existsSync(pomPath)) {
      violations.push(`declared module POM is missing: ${path.relative(root, pomPath)}`)
      continue
    }
    poms.push(pomPath)
    const moduleDirectory = path.dirname(pomPath)
    for (const moduleName of tagValues(readFileSync(pomPath, 'utf8'), 'module')) {
      const modulePom = path.join(moduleDirectory, moduleName, 'pom.xml')
      if (existsSync(modulePom)) {
        queue.push(modulePom)
      } else {
        violations.push(
          `declared module is missing: ${path.relative(root, path.join(moduleDirectory, moduleName))}`,
        )
      }
    }
  }
  return { poms, violations }
}

/** 只解析 jacoco-maven-plugin 插件块中的 `<rule>`，并先剥离 XML 注释。 */
function parseJacocoRules(xml) {
  const rules = []
  const plugins = [...stripComments(xml).matchAll(/<plugin>[\s\S]*?<\/plugin>/gu)]
    .map((match) => match[0])
    .filter((block) => block.includes('<artifactId>jacoco-maven-plugin</artifactId>'))
  for (const plugin of plugins) {
    for (const block of plugin.matchAll(/<rule>([\s\S]*?)<\/rule>/gu)) {
      const body = block[1]
      const element = body.match(/<element>\s*([^<\s]+)\s*<\/element>/u)?.[1]
      if (!element) {
        continue
      }
      const section = (tag) => body.match(new RegExp(`<${tag}>([\\s\\S]*?)</${tag}>`, 'u'))?.[1] ?? ''
      rules.push({
        element,
        includes: tagValues(section('includes'), 'include'),
        excludes: tagValues(section('excludes'), 'exclude'),
      })
    }
  }
  return rules
}

/** 复刻 `org.jacoco.core.runtime.WildcardMatcher`：`:` 分隔候选，`*` 任意字符，`?` 单字符，其余字面量，整体匹配。 */
function jacocoPatternToRegExp(pattern) {
  const alternatives = pattern.split(':').map((part) => {
    let regex = ''
    for (const char of part) {
      if (char === '*') {
        regex += '.*'
      } else if (char === '?') {
        regex += '.'
      } else {
        regex += char.replace(/[.*+?^${}()|[\]\\]/gu, '\\$&')
      }
    }
    return `(${regex})`
  })
  return new RegExp(`^${alternatives.join('|')}$`, 'u')
}

/** 主源码类/包索引（不含 package-info / module-info：它们不是 capability 目标类）。 */
function indexMainSources(moduleDirectory) {
  const sourceRoot = path.join(moduleDirectory, 'src/main/java')
  const classes = new Set()
  const packages = new Set()
  const walk = (directory) => {
    for (const entry of readdirSync(directory, { withFileTypes: true })) {
      const absolutePath = path.join(directory, entry.name)
      if (entry.isDirectory()) {
        walk(absolutePath)
        continue
      }
      if (!entry.name.endsWith('.java') || /^(?:package|module)-info\.java$/u.test(entry.name)) {
        continue
      }
      const qualifiedName = path
        .relative(sourceRoot, absolutePath)
        .slice(0, -'.java'.length)
        .split(path.sep)
        .join('.')
      classes.add(qualifiedName)
      packages.add(qualifiedName.split('.').slice(0, -1).join('.'))
    }
  }
  if (existsSync(sourceRoot)) {
    walk(sourceRoot)
  }
  return { classes, packages }
}

/** 合法限定名模式：不许 `/`（不会命中）、不许 `$`（限定名规约要求嵌套类用 `.`）、不许 `:`；每段为非空标识符/通配符。 */
function isQualifiedNamePattern(pattern) {
  if (pattern.length === 0 || /[/$:]/u.test(pattern)) {
    return false
  }
  return pattern.split('.').every((segment) => /^[A-Za-z0-9_*?]+$/u.test(segment))
}

function matchesAny(pattern, names) {
  const matcher = jacocoPatternToRegExp(pattern)
  for (const name of names) {
    if (matcher.test(name)) {
      return true
    }
  }
  return false
}

/** 校验全部模块 POM 的 CLASS/PACKAGE 规则；其他 element 的命名规约不同，不作校验。 */
function analyzeJacocoRules(root) {
  const { poms, violations } = discoverModulePoms(root)
  const stats = { modulePomCount: 0, classOrPackageRuleCount: 0, includePatternCount: 0 }
  for (const pomPath of poms) {
    stats.modulePomCount += 1
    const relativePom = path.relative(root, pomPath).split(path.sep).join('/')
    const moduleDirectory = path.dirname(pomPath)
    for (const rule of parseJacocoRules(readFileSync(pomPath, 'utf8'))) {
      if (rule.element !== 'CLASS' && rule.element !== 'PACKAGE') {
        continue
      }
      stats.classOrPackageRuleCount += 1
      const { classes, packages } = indexMainSources(moduleDirectory)
      const names = rule.element === 'CLASS' ? classes : packages
      for (const pattern of [...rule.includes, ...rule.excludes]) {
        if (!isQualifiedNamePattern(pattern)) {
          violations.push(
            `${relativePom}: ${rule.element} rule pattern '${pattern}' is not a qualified name`,
          )
        }
      }
      for (const pattern of rule.includes) {
        stats.includePatternCount += 1
        if (isQualifiedNamePattern(pattern) && !matchesAny(pattern, names)) {
          violations.push(
            `${relativePom}: ${rule.element} rule include '${pattern}' matches no main source`,
          )
        }
      }
    }
  }
  return { violations, stats }
}

/** 临时仓库 fixture：两层 `<modules>` 聚合 + 一个带 JaCoCo 规则与主源码的模块。 */
function createFixture({ rulesXml = '', sources = { 'demo/Existing.java': 'package demo;\nclass Existing {}\n' } } = {}) {
  const fixtureRoot = mkdtempSync(path.join(tmpdir(), 'kk-studio-jacoco-'))
  const moduleDirectory = path.join(fixtureRoot, 'group/sub/mod')
  const write = (relativePath, content) => {
    const target = path.join(fixtureRoot, relativePath)
    mkdirSync(path.dirname(target), { recursive: true })
    writeFileSync(target, content)
  }
  write('pom.xml', '<?xml version="1.0"?><project><modules><module>group</module></modules></project>\n')
  write('group/pom.xml', '<?xml version="1.0"?><project><modules><module>sub</module></modules></project>\n')
  write('group/sub/pom.xml', '<?xml version="1.0"?><project><modules><module>mod</module></modules></project>\n')
  write(
    'group/sub/mod/pom.xml',
    `<?xml version="1.0"?><project><build><plugins><plugin>
<artifactId>jacoco-maven-plugin</artifactId><configuration><rules>${rulesXml}</rules></configuration>
</plugin></plugins></build></project>\n`,
  )
  for (const [relativePath, content] of Object.entries(sources)) {
    write(path.join('group/sub/mod/src/main/java', relativePath), content)
  }
  return fixtureRoot
}

function withFixture(options, assertions) {
  const fixtureRoot = createFixture(options)
  try {
    assertions(fixtureRoot)
  } finally {
    rmSync(fixtureRoot, { recursive: true, force: true })
  }
}

function ruleOf(element, ...patterns) {
  const includes = patterns.map((pattern) => `<include>${pattern}</include>`).join('')
  return `<rule><element>${element}</element><includes>${includes}</includes>
<limits><limit><counter>LINE</counter><value>COVEREDRATIO</value><minimum>0.90</minimum></limit></limits></rule>`
}

test('every repository CLASS/PACKAGE JaCoCo rule uses matching qualified names', () => {
  // 本仓库门禁自身必须全部通过，且扫描到的规则/模式数量具有代表性（防止校验器静默失配）。
  const { violations, stats } = analyzeJacocoRules(REPOSITORY_ROOT)
  assert.deepEqual(violations, [])
  assert.ok(stats.classOrPackageRuleCount >= 10, `expected >= 10 rules, got ${stats.classOrPackageRuleCount}`)
  assert.ok(stats.includePatternCount >= 50, `expected >= 50 include patterns, got ${stats.includePatternCount}`)
})

test('dotted and wildcard CLASS patterns matching main sources are accepted', () => {
  for (const pattern of ['demo.Existing', 'demo.*', 'demo.Ex*']) {
    withFixture({ rulesXml: ruleOf('CLASS', pattern) }, (root) => {
      assert.deepEqual(analyzeJacocoRules(root).violations, [], `pattern ${pattern}`)
    })
  }
})

test('slash CLASS pattern is rejected because JaCoCo never matches it', () => {
  withFixture({ rulesXml: ruleOf('CLASS', 'demo/Existing') }, (root) => {
    const { violations } = analyzeJacocoRules(root)
    assert.equal(violations.length, 1)
    assert.match(violations[0], /demo\/Existing' is not a qualified name/u)
  })
})

test('CLASS pattern without a real main source is rejected', () => {
  withFixture({ rulesXml: ruleOf('CLASS', 'demo.Missing') }, (root) => {
    const { violations } = analyzeJacocoRules(root)
    assert.equal(violations.length, 1)
    assert.match(violations[0], /demo\.Missing' matches no main source/u)
  })
})

test('nested-class dollar notation is rejected under the qualified-name convention', () => {
  withFixture(
    {
      rulesXml: ruleOf('CLASS', 'demo.Existing$Nested'),
      sources: { 'demo/Existing.java': 'package demo;\nclass Existing { static class Nested {} }\n' },
    },
    (root) => {
      const { violations } = analyzeJacocoRules(root)
      assert.equal(violations.length, 1)
      assert.match(violations[0], /is not a qualified name/u)
    },
  )
})

test('PACKAGE rule matches real main packages and rejects slash notation', () => {
  withFixture({ rulesXml: ruleOf('PACKAGE', 'demo') }, (root) => {
    assert.deepEqual(analyzeJacocoRules(root).violations, [])
  })
  withFixture({ rulesXml: ruleOf('PACKAGE', 'demo/') }, (root) => {
    const { violations } = analyzeJacocoRules(root)
    assert.equal(violations.length, 1)
    assert.match(violations[0], /PACKAGE rule pattern 'demo\/' is not a qualified name/u)
  })
})

test('element distinction keeps BUNDLE and SOURCEFILE rules out of the qualified-name check', () => {
  // SOURCEFILE 用 `包/文件`，BUNDLE 用 bundle 名；二者都不应按 CLASS 规约被判非法。
  const rulesXml =
    ruleOf('CLASS', 'demo.Existing') +
    ruleOf('SOURCEFILE', 'demo/Existing.java') +
    ruleOf('BUNDLE', 'kk-studio-fixture')
  withFixture({ rulesXml }, (root) => {
    assert.deepEqual(analyzeJacocoRules(root).violations, [])
  })
})

test('declared module without a POM is reported instead of silently skipped', () => {
  // 缺失模块 POM 必须报 violation，否则该模块门禁会凭空消失而不被发现。
  const fixtureRoot = createFixture()
  try {
    writeFileSync(
      path.join(fixtureRoot, 'group/sub/pom.xml'),
      '<?xml version="1.0"?><project><modules><module>absent</module></modules></project>\n',
    )
    const { violations } = analyzeJacocoRules(fixtureRoot)
    assert.equal(violations.length, 1)
    assert.match(violations[0], /declared module is missing: group\/sub\/absent/u)
  } finally {
    rmSync(fixtureRoot, { recursive: true, force: true })
  }
})

test('JaCoCo rule mentions inside XML comments are ignored', () => {
  // 注释中的示例规则不应被解析为真实门禁。
  const rulesXml = `<!-- ${ruleOf('CLASS', 'demo/Commented')} -->` + ruleOf('CLASS', 'demo.Existing')
  withFixture({ rulesXml }, (root) => {
    assert.deepEqual(analyzeJacocoRules(root).violations, [])
  })
})
