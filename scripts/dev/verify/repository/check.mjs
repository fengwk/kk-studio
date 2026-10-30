#!/usr/bin/env node

import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

function resolveRepositoryRoot() {
  const args = process.argv.slice(2)
  for (let i = 0; i < args.length; i++) {
    if (args[i] === '--root' && i + 1 < args.length) {
      return path.resolve(args[i + 1])
    }
  }
  if (process.env.KK_STUDIO_REPO_ROOT) {
    return path.resolve(process.env.KK_STUDIO_REPO_ROOT)
  }
  // 向上寻找 worktree 根（.git 文件或目录），不依赖本脚本在仓库中的深度。
  let directory = path.dirname(fileURLToPath(import.meta.url))
  for (;;) {
    if (existsSync(path.join(directory, '.git'))) {
      return directory
    }
    const parent = path.dirname(directory)
    if (parent === directory) {
      throw new Error(
        'cannot locate the kk-studio repository root; pass --root or set KK_STUDIO_REPO_ROOT',
      )
    }
    directory = parent
  }
}

const repositoryRoot = resolveRepositoryRoot()
const docsRoot = path.join(repositoryRoot, 'docs')

const moduleDocuments = [
  'builtin-tools-design.md',
  'canvas.md',
  'common.md',
  'daemon.md',
  'environment-ide-design.md',
  'frontend-design-language.md',
  'frontend.md',
  'harness-agent-builtin.md',
  'harness-environment.md',
  'harness-infra.md',
  'harness-model.md',
  'harness-runtime.md',
  'ide.md',
  'model-pricing.md',
  'platform.md',
  'platform-plugin-api.md',
  'project.md',
  'schema.md',
  'share.md',
  'thread-control-and-presentation.md',
  'web.md',
  'work.md',
]

const documentPaths = [
  'docs/README.md',
  'docs/system-design.md',
  'docs/canvas-project.md',
  ...moduleDocuments.map((name) => `docs/modules/${name}`),
  'docs/operations/deployment.md',
  'docs/operations/development-and-testing.md',
  'docs/operations/environment-daemon.md',
]

const navigationTargets = documentPaths.filter((relativePath) => relativePath !== 'docs/README.md')
const markdownSurfaces = [
  'README.md',
  'scripts/README.md',
  ...documentPaths,
  'deploy/local/README.md',
  'deploy/test/README.md',
]
const checkableTopLevels = new Set([
  'AGENTS.md',
  'README.md',
  'apps',
  'common',
  'deploy',
  'frontend',
  'harness',
  'platform',
  'schema',
  'scripts',
  'share',
  'web',
  'work',
  'checkstyle.xml',
  'lombok.config',
  'pom.xml',
])
const generatedPathMarkers = ['/target/', '/coverage/', '/reports/']
const forbiddenArchitectureDocuments = ['technical', 'solution'].join('-')
const forbiddenDesignDocuments = ['product', 'design'].join('-')
const forbiddenCacheTerm = ['re', 'dis'].join('')
const forbiddenGoalTable = ['agent', 'thread', 'goal'].join('_')
const forbiddenDocumentationTerms = [
  [forbiddenArchitectureDocuments, new RegExp(forbiddenArchitectureDocuments, 'i')],
  [forbiddenDesignDocuments, new RegExp(forbiddenDesignDocuments, 'i')],
  [forbiddenCacheTerm, new RegExp(`\\b${forbiddenCacheTerm}\\b`, 'i')],
  [forbiddenGoalTable, new RegExp(`\\b${forbiddenGoalTable}\\b`, 'i')],
  [
    'Goals/Non-goals heading',
    /^#{2,6}\s+(?:\d+\.\s*)?(?:Goals|Non-goals|Goals\s*\/\s*Non-goals)\s*$/imu,
  ],
  ['template responsibility heading', /^#{2,6}\s+(?:核心职责|协作边界)\s*$/mu],
  ['Workspace NOTES', /workspace\s+notes/i],
  ['本切片', /本切片/],
  ['路线图', /路线图/],
  ['TODO', /\bTODO\b/i],
]

const errors = []

function addError(message) {
  errors.push(message)
}

function read(relativePath) {
  const absolutePath = path.join(repositoryRoot, relativePath)
  try {
    return readFileSync(absolutePath, 'utf8')
  } catch {
    return ''
  }
}

function relativeFromRoot(absolutePath) {
  return path.relative(repositoryRoot, absolutePath).split(path.sep).join('/')
}

function walkFiles(directory) {
  if (!existsSync(directory)) {
    return []
  }
  return readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const absolutePath = path.join(directory, entry.name)
    return entry.isDirectory() ? walkFiles(absolutePath) : [absolutePath]
  })
}

function walkDirectories(directory) {
  if (!existsSync(directory)) {
    return []
  }
  return readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    if (!entry.isDirectory()) {
      return []
    }
    const absolutePath = path.join(directory, entry.name)
    return [absolutePath, ...walkDirectories(absolutePath)]
  })
}

function outsideFencedCode(source) {
  let fence = null
  const lines = []
  for (const line of source.split(/\r?\n/u)) {
    const fenceMatch = line.match(/^\s*(`{3,}|~{3,})/)
    if (fenceMatch) {
      const marker = fenceMatch[1][0]
      if (fence === null) {
        fence = marker
      } else if (fence === marker) {
        fence = null
      }
      lines.push('')
    } else {
      lines.push(fence === null ? line : '')
    }
  }
  return lines.join('\n')
}

function markdownLinks(source) {
  const links = []
  const pattern = /!?\[[^\]]*]\(([^)\n]+)\)/g
  for (const match of outsideFencedCode(source).matchAll(pattern)) {
    let destination = match[1].trim()
    if (destination.startsWith('<') && destination.includes('>')) {
      destination = destination.slice(1, destination.indexOf('>'))
    } else {
      destination = destination.split(/\s+/u, 1)[0]
    }
    links.push(destination)
  }
  return links
}

function localLinkPath(documentPath, destination) {
  if (!destination || destination.startsWith('#')) {
    return null
  }
  if (/^(?:[a-z][a-z\d+.-]*:|\/\/)/iu.test(destination)) {
    return null
  }
  const withoutFragment = destination.split(/[?#]/u, 1)[0]
  if (!withoutFragment) {
    return null
  }
  try {
    const resolved = path.resolve(
      repositoryRoot,
      path.dirname(documentPath),
      decodeURIComponent(withoutFragment),
    )
    const relative = path.relative(repositoryRoot, resolved)
    if (relative === '..' || relative.startsWith(`..${path.sep}`) || path.isAbsolute(relative)) {
      return false
    }
    return resolved
  } catch {
    return false
  }
}

function fenceAndHeadingCount(source) {
  return outsideFencedCode(source)
    .split(/\r?\n/u)
    .filter((line) => /^#\s+\S/u.test(line))
    .length
}

function inlineCodeValues(source) {
  const withoutLinks = outsideFencedCode(source).replace(/!?\[[^\]]*]\([^)]+\)/g, '')
  return [...withoutLinks.matchAll(/`([^`\n]+)`/g)].map((match) => match[1].trim())
}

function looksLikeCheckablePath(value) {
  if (!value || /\s/u.test(value)) {
    return false
  }
  if (
    value.startsWith('/') ||
    value.startsWith('$') ||
    value.startsWith('@') ||
    value.startsWith('classpath:') ||
    value.includes('://') ||
    value.includes('*') ||
    value.includes('{') ||
    value.includes('}') ||
    value.includes('<') ||
    value.includes('>') ||
    value.includes('=') ||
    value.includes('|') ||
    value.includes('\\') ||
    generatedPathMarkers.some((marker) => value.includes(marker))
  ) {
    return false
  }
  const firstSegment = value.split('/', 1)[0]
  return checkableTopLevels.has(firstSegment) || checkableTopLevels.has(value)
}

function checkFixedLayout() {
  const expected = new Set(documentPaths)
  const expectedDirectories = new Set(['docs', 'docs/modules', 'docs/operations'])
  const actual = new Set(
    walkFiles(docsRoot).map((absolutePath) => relativeFromRoot(absolutePath)),
  )
  const actualDirectories = new Set(
    [docsRoot, ...walkDirectories(docsRoot)].map(relativeFromRoot),
  )
  for (const relativePath of expected) {
    if (!existsSync(path.join(repositoryRoot, relativePath))) {
      addError(`missing fixed document: ${relativePath}`)
    }
  }
  for (const relativePath of actual) {
    if (!expected.has(relativePath)) {
      addError(`unexpected docs file: ${relativePath}`)
    }
  }
  for (const relativePath of actualDirectories) {
    if (!expectedDirectories.has(relativePath)) {
      addError(`unexpected docs directory: ${relativePath}`)
    }
  }
}

function checkHeadings() {
  for (const relativePath of markdownSurfaces) {
    const absolutePath = path.join(repositoryRoot, relativePath)
    if (!existsSync(absolutePath)) {
      addError(`missing markdown surface: ${relativePath}`)
      continue
    }
    const headings = fenceAndHeadingCount(read(relativePath))
    if (headings !== 1) {
      addError(`${relativePath}: expected exactly one H1 outside fenced code, found ${headings}`)
    }
  }
}

function checkLinks() {
  for (const relativePath of markdownSurfaces.filter((candidate) => candidate.startsWith('docs/'))) {
    const source = read(relativePath)
    for (const destination of markdownLinks(source)) {
      const absoluteTarget = localLinkPath(relativePath, destination)
      if (absoluteTarget === false) {
        addError(`${relativePath}: invalid relative link ${destination}`)
      } else if (absoluteTarget !== null && !existsSync(absoluteTarget)) {
        addError(`${relativePath}: broken relative link ${destination}`)
      }
    }
  }

  const topLevelLinkSurfaces = [
    'README.md',
    'scripts/README.md',
    'docs/README.md',
    'docs/operations/deployment.md',
    'docs/operations/development-and-testing.md',
    'deploy/local/README.md',
    'deploy/test/README.md',
  ]
  for (const relativePath of topLevelLinkSurfaces) {
    const source = read(relativePath)
    for (const destination of markdownLinks(source)) {
      const absoluteTarget = localLinkPath(relativePath, destination)
      if (absoluteTarget === false) {
        addError(`${relativePath}: invalid relative link ${destination}`)
      } else if (absoluteTarget !== null && !existsSync(absoluteTarget)) {
        addError(`${relativePath}: broken relative link ${destination}`)
      }
    }
  }

  const indexLinks = markdownLinks(read('docs/README.md'))
  for (const target of navigationTargets) {
    const covered = indexLinks.some((destination) => {
      const resolved = localLinkPath('docs/README.md', destination)
      return resolved !== null && resolved !== false && relativeFromRoot(resolved) === target
    })
    if (!covered) {
      addError(`docs/README.md does not link to ${target}`)
    }
  }

  for (const relativePath of [
    ...moduleDocuments.map((name) => `docs/modules/${name}`),
    'docs/operations/deployment.md',
    'docs/operations/development-and-testing.md',
  ]) {
    const hasSystemLink = markdownLinks(read(relativePath)).some((destination) => {
      const resolved = localLinkPath(relativePath, destination)
      return resolved !== null && resolved !== false && relativeFromRoot(resolved) === 'docs/system-design.md'
    })
    if (!hasSystemLink) {
      addError(`${relativePath}: missing parent System link`)
    }
  }
}

function checkInlineSourcePaths() {
  for (const relativePath of markdownSurfaces) {
    const source = read(relativePath)
    const linkedTargets = new Set(
      markdownLinks(source)
        .map((destination) => localLinkPath(relativePath, destination))
        .filter((target) => target !== null && target !== false)
        .map((target) => path.resolve(target)),
    )
    const reportedUnlinkedPaths = new Set()
    for (const value of inlineCodeValues(source)) {
      if (!looksLikeCheckablePath(value)) {
        continue
      }
      const absolutePath = path.join(repositoryRoot, value)
      if (!existsSync(absolutePath)) {
        addError(`${relativePath}: missing inline repository path ${value}`)
      } else if (
        (value.includes('/') || value.includes('.')) &&
        ![...linkedTargets].some((target) => {
          const resolvedPath = path.resolve(absolutePath)
          if (target === resolvedPath) {
            return true
          }
          return (
            statSync(resolvedPath).isDirectory() &&
            path.relative(resolvedPath, target) !== '..' &&
            !path.relative(resolvedPath, target).startsWith(`..${path.sep}`)
          )
        }) &&
        !reportedUnlinkedPaths.has(value)
      ) {
        addError(`${relativePath}: inline repository path must also be linked ${value}`)
        reportedUnlinkedPaths.add(value)
      }
    }
  }
}

function checkForbiddenReferencesAndTerms() {
  const scanPaths = [
    'README.md',
    'AGENTS.md',
    ...walkFiles(path.join(repositoryRoot, 'scripts')).map(relativeFromRoot),
    ...walkFiles(docsRoot).map(relativeFromRoot),
  ]
  for (const relativePath of scanPaths) {
    const source = read(relativePath)
    const forbiddenPathPattern = new RegExp(
      `docs/(?:${forbiddenArchitectureDocuments}|${forbiddenDesignDocuments})|${forbiddenArchitectureDocuments}|${forbiddenDesignDocuments}`,
      'iu',
    )
    if (forbiddenPathPattern.test(source)) {
      addError(`${relativePath}: contains a forbidden documentation path`)
    }
  }
  for (const relativePath of markdownSurfaces) {
    const source = outsideFencedCode(read(relativePath))
    for (const [label, pattern] of forbiddenDocumentationTerms) {
      if (pattern.test(source)) {
        addError(`${relativePath}: contains forbidden documentation term ${label}`)
      }
    }
  }
}

function extractPomModules(pomRelativePath) {
  const source = read(pomRelativePath)
  const modulesBlock = source.match(/<modules>([\s\S]*?)<\/modules>/u)
  if (!modulesBlock) {
    return []
  }
  return [...modulesBlock[1].matchAll(/<module>\s*([^<\s]+)\s*<\/module>/gu)].map(
    (match) => match[1],
  )
}

/**
 * Maven 模块与分层：apps -> platform -> harness -> common。表中是每个模块允许声明的内部生产依赖（artifactId 去掉
 * `kk-studio-` 前缀，test scope 不受限）；platform 与 harness 从不依赖 apps，platform-runtime 装配
 * agent-builtin，web 是唯一应用组合根（`null`）。
 */
const moduleDependencies = {
  common: [],
  schema: [],
  share: [],
  work: ['common'],
  'harness/environment': ['common'],
  'harness/model': [],
  'harness/runtime': ['common', 'harness-environment', 'harness-model'],
  'harness/agent-builtin': ['common', 'harness-model', 'harness-runtime'],
  'harness/infra': [
    'common',
    'work',
    'harness-environment',
    'harness-model',
    'harness-runtime',
    'harness-agent-builtin',
  ],
  'platform/plugin-api': ['common', 'harness-runtime'],
  'platform/runtime': [
    'common',
    'share',
    'harness-environment',
    'harness-model',
    'harness-runtime',
    'harness-agent-builtin',
    'platform-plugin-api',
  ],
  'apps/canvas/core': ['common', 'platform-plugin-api'],
  'apps/canvas/infra': ['common', 'work', 'canvas-core', 'platform-plugin-api', 'platform-runtime'],
  'apps/project': ['common', 'work', 'harness-runtime', 'platform-runtime'],
  'apps/ide': ['common', 'share', 'harness-environment', 'platform-runtime'],
  'plugins/comfyui': ['common', 'platform-plugin-api'],
  'plugins/media': ['common', 'platform-plugin-api'],
  'plugins/minimax-mavis': ['common', 'harness-runtime', 'platform-plugin-api'],
  'plugins/opencli': ['common', 'harness-runtime', 'platform-plugin-api'],
  web: null,
}

/** 插件只经平台插件 SPI 接触宿主：Harness contributor/tool 契约、Canvas Function SPI 与共享值类型。 */
const pluginImportPrefixes = [
  'fun.fengwk.kkstudio.platform.plugin.',
  'fun.fengwk.kkstudio.canvas.function.',
  'fun.fengwk.kkstudio.harness.contributor.api.',
  'fun.fengwk.kkstudio.harness.tool.',
  'fun.fengwk.kkstudio.common.',
]

/** 内核里的 SPI 与内置工具包只依赖契约，绝不触碰 runtime 内部实现。 */
const kernelContractPackages = ['tool', 'contributor', 'builtin', 'prompt']

function pomDependencies(pomRelativePath) {
  const source = read(pomRelativePath).replace(/<!--[\s\S]*?-->/gu, '')
  const block = source.match(/<dependencies>([\s\S]*?)<\/dependencies>/u)
  if (!block) {
    return []
  }
  return [...block[1].matchAll(/<dependency>([\s\S]*?)<\/dependency>/gu)].map((match) => {
    const tag = (name) => match[1].match(new RegExp(`<${name}>\\s*([^<\\s]+)\\s*</${name}>`, 'u'))?.[1]
    return {
      groupId: tag('groupId'),
      artifactId: tag('artifactId'),
      scope: tag('scope') ?? 'compile',
    }
  })
}

function javaImports(directory) {
  return walkFiles(directory)
    .filter((filePath) => filePath.endsWith('.java'))
    .flatMap((filePath) =>
      [...read(relativeFromRoot(filePath)).matchAll(/^\s*import\s+(?:static\s+)?([\w.*]+)\s*;/gmu)].map(
        (match) => ({ file: relativeFromRoot(filePath), imported: match[1] }),
      ),
    )
}

function checkModuleBoundaries() {
  const modulePaths = Object.keys(moduleDependencies)
  const aggregators = new Map()
  for (const modulePath of modulePaths) {
    const parent = path.posix.dirname(modulePath)
    const key = parent === '.' ? '' : parent
    aggregators.set(key, [...(aggregators.get(key) ?? []), path.posix.basename(modulePath)])
    for (let ancestor = key; ancestor !== ''; ) {
      const above = path.posix.dirname(ancestor)
      const aboveKey = above === '.' ? '' : above
      const name = path.posix.basename(ancestor)
      if (!(aggregators.get(aboveKey) ?? []).includes(name)) {
        aggregators.set(aboveKey, [...(aggregators.get(aboveKey) ?? []), name])
      }
      ancestor = aboveKey
    }
  }
  for (const [aggregator, expected] of aggregators) {
    const pomPath = aggregator === '' ? 'pom.xml' : `${aggregator}/pom.xml`
    if (!existsSync(path.join(repositoryRoot, pomPath))) {
      addError(`missing aggregator POM: ${pomPath}`)
      continue
    }
    const actual = extractPomModules(pomPath)
    if (JSON.stringify([...actual].sort()) !== JSON.stringify([...expected].sort())) {
      addError(
        `${pomPath} modules mismatch: expected [${[...expected].sort().join(', ')}], found [${[...actual].sort().join(', ')}]`,
      )
    }
    // 聚合目录下不允许遗留未声明的模块目录（根目录另有前端、脚本与部署目录，只校验 modules 声明）。
    if (aggregator !== '') {
      const directories = readdirSync(path.join(repositoryRoot, aggregator), { withFileTypes: true })
        .filter(
          (entry) =>
            entry.isDirectory() &&
            entry.name !== 'target' &&
            entry.name !== 'src' &&
            !entry.name.startsWith('.'),
        )
        .map((entry) => entry.name)
        .sort()
      if (JSON.stringify(directories) !== JSON.stringify([...expected].sort())) {
        addError(
          `${aggregator} directories mismatch: expected [${[...expected].sort().join(', ')}], found [${directories.join(', ')}]`,
        )
      }
    }
  }

  for (const [modulePath, allowed] of Object.entries(moduleDependencies)) {
    const pomPath = `${modulePath}/pom.xml`
    if (!existsSync(path.join(repositoryRoot, pomPath))) {
      addError(`missing module POM: ${pomPath}`)
      continue
    }
    for (const dependency of pomDependencies(pomPath)) {
      if (dependency.groupId !== 'fun.fengwk.kk-studio' || dependency.scope === 'test') {
        continue
      }
      const name = dependency.artifactId.replace(/^kk-studio-/u, '')
      if (allowed === null) {
        if ((name.startsWith('plugin-') || name === 'schema') && dependency.scope !== 'runtime') {
          addError(`${pomPath}: ${dependency.artifactId} must use runtime scope`)
        }
        continue
      }
      if (!allowed.includes(name)) {
        addError(`${pomPath}: ${modulePath} must not depend on ${dependency.artifactId}`)
      }
    }
  }

  for (const modulePath of modulePaths.filter((value) => value.startsWith('plugins/'))) {
    const pluginPackage = `fun.fengwk.kkstudio.plugin.${path.posix.basename(modulePath).replaceAll('-', '')}.`
    for (const { file, imported } of javaImports(path.join(repositoryRoot, modulePath, 'src/main/java'))) {
      if (
        imported.startsWith('fun.fengwk.kkstudio.') &&
        !imported.startsWith(pluginPackage) &&
        !pluginImportPrefixes.some((prefix) => imported.startsWith(prefix))
      ) {
        addError(`${file}: plugins may only import the platform plugin SPI, found ${imported}`)
      }
    }
  }

  for (const contractPackage of kernelContractPackages) {
    const directory = path.join(
      repositoryRoot,
      'harness/runtime/src/main/java/fun/fengwk/kkstudio/harness',
      contractPackage,
    )
    for (const { file, imported } of javaImports(directory)) {
      if (imported.startsWith('fun.fengwk.kkstudio.harness.runtime.')) {
        addError(`${file}: kernel contract packages must not import runtime internals (${imported})`)
      }
    }
  }

  // runtime 只经 HarnessCatalog 使用内置工具，不直接依赖 builtin 实现。
  for (const { file, imported } of javaImports(
    path.join(repositoryRoot, 'harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime'),
  )) {
    if (imported.startsWith('fun.fengwk.kkstudio.harness.builtin.')) {
      addError(`${file}: runtime must reach builtin tools only through the catalog (${imported})`)
    }
  }

  // web 生产源码不得编译期接触 opencli；发行物按 web/pom.xml 的 runtime 依赖装配，可以不带该 Plugin。
  for (const { file, imported } of javaImports(path.join(repositoryRoot, 'web/src/main/java'))) {
    if (imported.startsWith('fun.fengwk.kkstudio.plugin.opencli.')) {
      addError(`${file}: web production must not compile-import opencli plugin classes`)
    }
  }
}

function checkNoHarnessModuleDocs() {
  const harnessRoot = path.join(repositoryRoot, 'harness')
  if (!existsSync(harnessRoot)) {
    return
  }
  const directDocs = path.join(harnessRoot, 'docs')
  if (existsSync(directDocs)) {
    addError(`forbidden harness docs path: ${relativeFromRoot(directDocs)}`)
    if (statSync(directDocs).isDirectory()) {
      for (const file of walkFiles(directDocs)) {
        addError(`forbidden harness docs path: ${relativeFromRoot(file)}`)
      }
    }
  }
  for (const entry of readdirSync(harnessRoot, { withFileTypes: true })) {
    if (!entry.isDirectory()) {
      continue
    }
    const moduleDocs = path.join(harnessRoot, entry.name, 'docs')
    if (existsSync(moduleDocs)) {
      addError(`forbidden harness module docs path: ${relativeFromRoot(moduleDocs)}`)
      if (statSync(moduleDocs).isDirectory()) {
        for (const file of walkFiles(moduleDocs)) {
          addError(`forbidden harness module docs path: ${relativeFromRoot(file)}`)
        }
      }
    }
  }
}

/** 内核与 Common：每个生产包都必须有带 Javadoc 的 package-info，并在模块文档的包架构表中占一行。 */
const packageDocumentedModules = {
  common: 'docs/modules/common.md',
  'harness/environment': 'docs/modules/harness-environment.md',
  'harness/model': 'docs/modules/harness-model.md',
  'harness/runtime': 'docs/modules/harness-runtime.md',
  'harness/agent-builtin': 'docs/modules/harness-agent-builtin.md',
  'harness/infra': 'docs/modules/harness-infra.md',
}

function walkJavaSourceDirectories(directory) {
  if (!existsSync(directory)) {
    return []
  }
  return readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    if (!entry.isDirectory() || entry.name === 'target' || entry.name === 'generated') {
      return []
    }
    const absolutePath = path.join(directory, entry.name)
    return [absolutePath, ...walkJavaSourceDirectories(absolutePath)]
  })
}

function productionPackages(modulePath) {
  const srcMainJava = path.join(repositoryRoot, modulePath, 'src/main/java')
  if (!existsSync(srcMainJava)) {
    return []
  }
  const dirs = [srcMainJava, ...walkJavaSourceDirectories(srcMainJava)]
  return dirs.flatMap((dir) => {
    let entries = []
    try {
      entries = readdirSync(dir, { withFileTypes: true })
    } catch {
      return []
    }
    const hasProductionJava = entries.some((entry) => {
      return (
        entry.isFile() &&
        entry.name.endsWith('.java') &&
        entry.name !== 'package-info.java' &&
        entry.name !== 'module-info.java'
      )
    })
    if (!hasProductionJava) {
      return []
    }
    const relativePackagePath = path.relative(srcMainJava, dir).split(path.sep).join('/')
    return [
      {
        dir,
        name: relativePackagePath.split('/').join('.'),
      },
    ]
  })
}

/**
 * 同名包按源集分别归属：生产包和测试包各自在 Maven 模块之间唯一。
 * 同一模块的 main 与 test 可以同名，因为它们位于不同 classpath，不构成运行时冲突。
 */
function packageOwners(sourceSet) {
  const owners = new Map()
  function visitModule(modulePath) {
    const pomPath = path.join(modulePath, 'pom.xml')
    if (!existsSync(path.join(repositoryRoot, pomPath))) {
      return
    }
    const sourceRoot = path.join(repositoryRoot, modulePath, 'src', sourceSet, 'java')
    for (const file of walkFiles(sourceRoot).filter((file) => file.endsWith('.java'))) {
      const match = readFileSync(file, 'utf8').match(/^\s*package\s+([\w.]+)\s*;/mu)
      if (!match) {
        continue
      }
      const name = match[1]
      if (!owners.has(name)) {
        owners.set(name, new Set())
      }
      owners.get(name).add(modulePath || '.')
    }
    for (const match of read(pomPath).matchAll(/<module>\s*([^<]+?)\s*<\/module>/gu)) {
      visitModule(path.join(modulePath, match[1]))
    }
  }
  visitModule('')
  return owners
}

function checkUniquePackages() {
  for (const sourceSet of ['main', 'test']) {
    for (const [name, modules] of packageOwners(sourceSet)) {
      if (modules.size < 2) {
        continue
      }
      addError(
        `Java ${sourceSet} package shared across Maven modules: ${name} (${[...modules].sort().join(', ')})`,
      )
    }
  }
}

function checkPackageInfo() {
  for (const modulePath of Object.keys(packageDocumentedModules)) {
    for (const { dir, name: expectedPackageName } of productionPackages(modulePath)) {
      const packageInfoPath = path.join(dir, 'package-info.java')
      const relativePackageInfoPath = relativeFromRoot(packageInfoPath)

      if (!existsSync(packageInfoPath)) {
        addError(`missing package-info.java: ${relativePackageInfoPath}`)
        continue
      }

      const content = read(relativePackageInfoPath)
      const packageMatch = content.match(/\bpackage\s+([a-zA-Z0-9_.]+)\s*;/u)
      if (!packageMatch) {
        addError(`missing package declaration in ${relativePackageInfoPath}`)
        continue
      }
      const declaredPackage = packageMatch[1]
      if (declaredPackage !== expectedPackageName) {
        addError(
          `package declaration mismatch in ${relativePackageInfoPath}: expected '${expectedPackageName}', found '${declaredPackage}'`,
        )
        continue
      }

      const beforePackage = content.slice(0, packageMatch.index)
      const javadocMatch = beforePackage.match(/\/\*\*([\s\S]*?)\*\//u)
      if (!javadocMatch) {
        addError(`missing package Javadoc in ${relativePackageInfoPath}`)
        continue
      }
      const javadocText = javadocMatch[1].replace(/^\s*\* ?/gm, '').trim()
      if (javadocText.length === 0) {
        addError(`empty package Javadoc in ${relativePackageInfoPath}`)
      }
    }
  }
}

function levelTwoSection(source, title) {
  const lines = source.split(/\r?\n/u)
  const start = lines.findIndex((line) => line.trim() === `## ${title}`)
  if (start < 0) {
    return null
  }
  const next = lines.findIndex((line, index) => index > start && /^##\s+\S/u.test(line))
  return lines.slice(start + 1, next < 0 ? lines.length : next).join('\n')
}

function checkPackageArchitectureDocs() {
  for (const [modulePath, docPath] of Object.entries(packageDocumentedModules)) {
    const section = levelTwoSection(read(docPath), '包架构')
    if (section === null) {
      addError(`${docPath}: missing '## 包架构' section`)
      continue
    }
    if (!/^\|\s*(?:包名|包路径)\s*\|/mu.test(section)) {
      addError(`${docPath}: package architecture section must contain a package table`)
    }
    for (const { name: packageName } of productionPackages(modulePath)) {
      const displayNames = [
        packageName,
        packageName.replace('fun.fengwk.kkstudio.harness.', ''),
      ]
      if (!displayNames.some((displayName) => section.includes(`| \`${displayName}\` |`))) {
        addError(`${docPath}: package architecture table missing '${packageName}'`)
      }
    }
  }
}

function checkEnvironmentRouteDocs() {
  const runtimeDocPath = 'docs/modules/harness-runtime.md'
  const runtimeDoc = read(runtimeDocPath)
  if (!runtimeDoc.includes('requiredEnvironmentId')) {
    addError(`${runtimeDocPath}: must document literal 'requiredEnvironmentId'`)
  }
  const onlyToolWorkNonEmptyPattern =
    /(?:仅|只|only).*(?:TOOL.*Work|Work.*TOOL|target.*TOOL).*(?:非空|non-null)|(?:TOOL.*Work|Work.*TOOL|target.*TOOL).*(?:仅|只|only).*(?:非空|non-null)|(?:TOOL.*Work|Work.*TOOL|target.*TOOL).*(?:非空|non-null).*(?:THREAD|MODEL).*(?:空|null)/iu
  if (!onlyToolWorkNonEmptyPattern.test(runtimeDoc)) {
    addError(
      `${runtimeDocPath}: must document that requiredEnvironmentId is only permitted/non-null for TOOL Work`,
    )
  }

  const infraDocPath = 'docs/modules/harness-infra.md'
  const infraDoc = read(infraDocPath)
  const requiredInfraTerms = ['required_environment_id', 'owner_node_id', 'READY', 'lease_until']
  for (const term of requiredInfraTerms) {
    if (!infraDoc.includes(term)) {
      addError(`${infraDocPath}: must document required term '${term}'`)
    }
  }
  const infraParagraphs = infraDoc.split(/\n\s*\n/)
  const claimFencePattern =
    /(?=.*(?:environment|环境|route|亲和|required_environment_id))(?=.*(?:dispatcher|node))(?=.*(?:lease|租约|lease_until))(?=.*claim)(?=.*(?:围栏|fence))/isu
  const hasClaimFence = infraParagraphs.some((paragraph) => claimFencePattern.test(paragraph))
  if (!hasClaimFence) {
    addError(
      `${infraDocPath}: must document Dispatcher node and active lease claim fence`,
    )
  }
}

checkFixedLayout()
checkHeadings()
checkLinks()
checkInlineSourcePaths()
checkForbiddenReferencesAndTerms()
checkModuleBoundaries()
checkUniquePackages()
checkNoHarnessModuleDocs()
checkPackageInfo()
checkPackageArchitectureDocs()
checkEnvironmentRouteDocs()

if (errors.length > 0) {
  console.error(`FAIL docs (${errors.length} error${errors.length === 1 ? '' : 's'})`)
  for (const error of errors) {
    console.error(`- ${error}`)
  }
  process.exitCode = 1
} else {
  console.log(`PASS docs (${documentPaths.length} fixed documents)`)
}
