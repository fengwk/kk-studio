#!/usr/bin/env node

import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs'
import path from 'node:path'
import { repositoryRoot as resolveDefaultRoot } from '../../lib/repo-root.mjs'

function resolveRepositoryRoot() {
  const args = process.argv.slice(2)
  for (let i = 0; i < args.length; i++) {
    if (args[i] === '--root' && i + 1 < args.length) {
      return path.resolve(args[i + 1])
    }
  }
  return resolveDefaultRoot()
}

const repositoryRoot = resolveRepositoryRoot()
const docsRoot = path.join(repositoryRoot, 'docs')

const moduleDocuments = [
  'builtin-tools.md',
  'canvas.md',
  'chat.md',
  'common.md',
  'daemon.md',
  'frontend.md',
  'harness-agent-builtin.md',
  'harness-environment.md',
  'harness-model.md',
  'harness-runtime.md',
  'ide.md',
  'platform.md',
  'platform-plugin-api.md',
  'plugins.md',
  'project.md',
  'schema.md',
  'web.md',
  'work.md',
]

const documentPaths = [
  'docs/README.md',
  'docs/system-design.md',
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
  'plugins',
  'schema',
  'scripts',
  'web',
  'work',
  'checkstyle.xml',
  'lombok.config',
  'pom.xml',
])
const generatedPathMarkers = ['/target/', '/coverage/', '/reports/']

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

function walkRepositoryFiles(directory) {
  if (!existsSync(directory)) {
    return []
  }
  return readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    if (['.git', '.workspace', 'node_modules', 'target', 'dist', 'coverage', 'reports'].includes(entry.name)) {
      return []
    }
    const absolutePath = path.join(directory, entry.name)
    return entry.isDirectory() ? walkRepositoryFiles(absolutePath) : [absolutePath]
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
 * `kk-studio-` 前缀；产品之间在任何 scope 下均互不依赖，其余 test scope 依赖不受此表限制）；platform 与 harness 不在生产中依赖 apps，platform-runtime 装配
 * agent-builtin，web 是唯一应用组合根（`null`）。
 */
const moduleDependencies = {
  common: [],
  schema: [],
  work: ['common'],
  'harness/environment': ['common'],
  'harness/model': [],
  'harness/runtime': ['common', 'harness-environment', 'harness-model'],
  'harness/agent-builtin': ['common', 'harness-model', 'harness-runtime'],
  'platform/plugin-api': ['common', 'harness-runtime'],
  'platform/daemon-server': ['common', 'harness-environment'],
  'platform/runtime': [
    'common',
    'work',
    'harness-environment',
    'harness-model',
    'harness-runtime',
    'harness-agent-builtin',
    'platform-plugin-api',
    'platform-daemon-server',
  ],
  'apps/canvas/spi': ['common'],
  'apps/canvas/runtime': [
    'common',
    'work',
    'canvas-spi',
    'harness-model',
    'platform-plugin-api',
    'platform-runtime',
  ],
  'apps/project': ['common', 'work', 'harness-runtime', 'platform-runtime'],
  'apps/chat': ['common', 'harness-runtime', 'platform-runtime'],
  'apps/ide': ['common', 'harness-environment', 'platform-daemon-server', 'platform-runtime'],
  'plugins/comfyui': ['common', 'canvas-spi', 'harness-runtime', 'platform-plugin-api'],
  'plugins/media': ['common', 'canvas-spi', 'harness-runtime', 'platform-plugin-api'],
  'plugins/minimax-mavis': ['common', 'harness-runtime', 'platform-plugin-api'],
  'plugins/opencli': ['common', 'canvas-spi', 'harness-runtime', 'platform-plugin-api'],
  web: null,
}

const appArtifactProducts = {
  'canvas-spi': 'canvas',
  'canvas-runtime': 'canvas',
  project: 'project',
  chat: 'chat',
  ide: 'ide',
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
const kernelContractPackages = ['tool', 'contributor', 'prompt']

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

function lineNumberAt(source, index) {
  return source.slice(0, index).split('\n').length
}

function javaImportsForFile(relativePath) {
  const source = read(relativePath)
  return [...source.matchAll(/^\s*import\s+(?:static\s+)?([\w.*]+)\s*;/gmu)].map((match) => ({
    file: relativePath,
    imported: match[1],
    line: lineNumberAt(source, match.index),
  }))
}

function javaImports(directory) {
  return walkFiles(directory)
    .filter((filePath) => filePath.endsWith('.java'))
    .flatMap((filePath) => javaImportsForFile(relativeFromRoot(filePath)))
}

function fileLines(relativePath) {
  return read(relativePath)
    .split(/\r?\n/u)
    .map((line, index) => ({ line: index + 1, text: line }))
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
      if (dependency.groupId !== 'fun.fengwk.kk-studio') {
        continue
      }
      const name = dependency.artifactId.replace(/^kk-studio-/u, '')
      if (allowed === null) {
        if ((name.startsWith('plugin-') || name === 'schema') && dependency.scope !== 'runtime') {
          addError(`${pomPath}: ${dependency.artifactId} must use runtime scope`)
        }
        continue
      }
      if (dependency.scope === 'test') {
        const targetApp = appArtifactProducts[name]
        const currentApp = modulePath.startsWith('apps/') ? modulePath.split('/')[1] : null
        if (currentApp && targetApp && targetApp !== currentApp) {
          addError(`${pomPath}: ${modulePath} must not depend on ${dependency.artifactId}`)
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

  // runtime 只经 HarnessCatalog 使用内置工具，不直接依赖 contributor.builtin 实现。
  for (const { file, imported } of javaImports(
    path.join(repositoryRoot, 'harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime'),
  )) {
    if (imported.startsWith('fun.fengwk.kkstudio.harness.contributor.builtin.')) {
      addError(`${file}: runtime must reach builtin tools only through the catalog (${imported})`)
    }
  }
}

/**
 * web 是唯一应用组合根：生产源码只可接触公开的 Harness 契约包与声明过的组合根类型；Plugin 实现必须保持 runtime 可选，
 * Controller 不得接触 Harness runtime processor。约束在仓库级检查中维护，模块内不再写重复的架构测试。
 */
const webMainJavaRoot = 'web/src/main/java'
const webHarnessAllowedPrefixes = [
  'fun.fengwk.kkstudio.harness.runtime.',
  'fun.fengwk.kkstudio.harness.api.',
  'fun.fengwk.kkstudio.harness.spi.',
  'fun.fengwk.kkstudio.harness.codec.',
  'fun.fengwk.kkstudio.harness.contributor.api.',
  'fun.fengwk.kkstudio.harness.environment.',
]
const webHarnessValueImports = new Set([
  'fun.fengwk.kkstudio.harness.tool.codec.ToolResultJsonCodec',
  'fun.fengwk.kkstudio.harness.model.ImageInputTier',
  'fun.fengwk.kkstudio.harness.model.codec.JsonCodecSupport',
])

/** 精确按文件放行真实装配；不为 controller、普通 mapper 或任意 Driver 私有包开通配。 */
const webBuiltinImportAllowlist = new Map([
  [
    'fun/fengwk/kkstudio/web/runtime/HarnessRuntimeConfiguration.java',
    [
      'fun.fengwk.kkstudio.harness.builtin.driver.BuiltinAgentDriver',
      'fun.fengwk.kkstudio.harness.builtin.port.TurnResolver',
      'fun.fengwk.kkstudio.harness.builtin.processor.ModelProcessor',
      'fun.fengwk.kkstudio.harness.builtin.query.BuiltinRequestQuery',
    ],
  ],
  [
    'fun/fengwk/kkstudio/web/runtime/HarnessProcessorConfiguration.java',
    [
      'fun.fengwk.kkstudio.harness.builtin.port.ModelGateway',
      'fun.fengwk.kkstudio.harness.builtin.processor.ModelProcessor',
      'fun.fengwk.kkstudio.harness.builtin.processor.ModelProcessorConfig',
      'fun.fengwk.kkstudio.harness.builtin.processor.ModelStreamConfig',
    ],
  ],
  [
    'fun/fengwk/kkstudio/web/runtime/HarnessRealtimeConfiguration.java',
    ['fun.fengwk.kkstudio.harness.builtin.port.ModelCheckpointRequester'],
  ],
])

const webForbiddenImportPrefixes = [
  'fun.fengwk.kkstudio.project.repo.',
  'fun.fengwk.kkstudio.platform.environment.gateway.EnvironmentDaemonGateway',
  'fun.fengwk.kkstudio.platform.environment.registry.EnvironmentConnection',
  'fun.fengwk.kkstudio.platform.environment.registry.EnvironmentRegistry',
]

/** 精确按文件放行 base4 已有的技能目录读模型；不为其它 controller 开通配。 */
const webForbiddenImportAllowlist = new Map([
  [
    'fun/fengwk/kkstudio/web/controller/StudioSkillCatalogController.java',
    ['fun.fengwk.kkstudio.platform.environment.registry.EnvironmentRegistry'],
  ],
])
const webPluginImportPrefix = 'fun.fengwk.kkstudio.plugin.'
const webControllerSourcePrefix = 'web/src/main/java/fun/fengwk/kkstudio/web/controller/'
const webHarnessProcessorPrefix = 'fun.fengwk.kkstudio.harness.runtime.processor.'
const webForbiddenSourceTokens = [
  'TrustedJarContributorLoader',
  'URLClassLoader',
  'ServiceLoader',
  'kk-studio.harness.contributors.directory',
  'KK_STUDIO_TRUSTED_CONTRIBUTOR_DIRECTORY',
]
const webForbiddenPomArtifacts = ['kk-studio-common']
const webRequiredPomArtifacts = [
  'kk-studio-platform-plugin-api',
  'kk-studio-platform-runtime',
  'kk-studio-canvas-runtime',
  'kk-studio-harness-environment',
  'kk-studio-harness-runtime',
  'kk-studio-harness-agent-builtin',
  'kk-studio-harness-model',
]
const webRequiredPluginConfigurationLines = [
  'poll-delay: ${KK_STUDIO_PLUGINS_REFRESH_POLL_DELAY:1h}',
  'lease-duration: ${KK_STUDIO_PLUGINS_REFRESH_LEASE_DURATION:2m}',
]
const webBusinessSqlPattern =
  /["'`](?:select\s+.+?\s+from|insert\s+into|update\s+.+?\s+set|delete\s+from)\s+/isu

function checkWebArchitecture() {
  const webMainRoot = path.join(repositoryRoot, webMainJavaRoot)
  if (!existsSync(webMainRoot)) {
    addError(`missing web main sources: ${webMainJavaRoot}`)
    return
  }

  for (const { file, imported } of javaImports(webMainRoot)) {
    const relativeFile = path.relative(webMainRoot, path.join(repositoryRoot, file)).split(path.sep).join('/')
    if (imported.startsWith('fun.fengwk.kkstudio.harness.')) {
      const allowed =
        webHarnessAllowedPrefixes.some((prefix) => imported.startsWith(prefix)) ||
        webHarnessValueImports.has(imported) ||
        (webBuiltinImportAllowlist.get(relativeFile) ?? []).includes(imported)
      if (!allowed) {
        addError(`${file}: web must use only declared Harness boundaries (${imported})`)
      }
    }
    for (const prefix of webForbiddenImportPrefixes) {
      if (
        imported.startsWith(prefix) &&
        !(webForbiddenImportAllowlist.get(relativeFile) ?? []).includes(imported)
      ) {
        addError(`${file}: web must not reference internal implementation (${imported})`)
      }
    }
    if (imported.startsWith(webPluginImportPrefix)) {
      addError(`${file}: web production must not import plugin implementations (${imported})`)
    }
  }

  const pomPath = 'web/pom.xml'
  if (!existsSync(path.join(repositoryRoot, pomPath))) {
    addError(`missing web pom: ${pomPath}`)
  } else {
    const artifacts = new Set(pomDependencies(pomPath).map((dependency) => dependency.artifactId))
    for (const artifact of webForbiddenPomArtifacts) {
      if (artifacts.has(artifact)) {
        addError(`${pomPath}: direct dependency ${artifact} is forbidden in any scope`)
      }
    }
    for (const artifact of webRequiredPomArtifacts) {
      if (!artifacts.has(artifact)) {
        addError(`${pomPath}: composition root must declare direct dependency ${artifact}`)
      }
    }
  }

  const applicationYml = 'web/src/main/resources/application.yml'
  if (!existsSync(path.join(repositoryRoot, applicationYml))) {
    addError(`missing web configuration: ${applicationYml}`)
  } else {
    const yml = read(applicationYml)
    for (const line of webRequiredPluginConfigurationLines) {
      if (!yml.includes(line)) {
        addError(`${applicationYml}: must declare exactly '${line}'`)
      }
    }
  }

  for (const file of walkFiles(webMainRoot).filter((file) => file.endsWith('.java'))) {
    const relativePath = relativeFromRoot(file)
    const source = read(relativePath)
    for (const token of webForbiddenSourceTokens) {
      if (source.includes(token)) {
        addError(`${relativePath}: contains forbidden trusted-JAR remnant '${token}'`)
      }
    }
    if (relativePath.startsWith(webControllerSourcePrefix)) {
      for (const [index, line] of source.split('\n').entries()) {
        if (line.includes(webHarnessProcessorPrefix)) {
          addError(
            `${relativePath}:${index + 1}: controllers must not depend on Harness runtime processors`,
          )
        }
      }
    }
    const sql = source.match(webBusinessSqlPattern)
    if (sql) {
      addError(`${relativePath}: contains direct business SQL query: ${sql[0]}`)
    }
  }
}


/**
 * Harness 的内部实现不是上层契约：平台业务包、apps 与 plugins 只可使用 api/spi 与文档声明的公开包；
 * 存储端口只供平台引擎适配包与 web 组合根使用，Builtin 存储端口只供 platform.harness.builtin 使用。
 */
const mainJavaRoots = ['apps', 'plugins', 'platform', 'web']
const harnessPublicPrefixes = [
  'fun.fengwk.kkstudio.harness.api.',
  'fun.fengwk.kkstudio.harness.spi.',
  'fun.fengwk.kkstudio.harness.codec.',
  'fun.fengwk.kkstudio.harness.contributor.api.',
  'fun.fengwk.kkstudio.harness.contributor.builtin.',
  'fun.fengwk.kkstudio.harness.tool.',
  'fun.fengwk.kkstudio.harness.prompt.',
  'fun.fengwk.kkstudio.harness.environment.',
  'fun.fengwk.kkstudio.harness.model.',
  'fun.fengwk.kkstudio.harness.runtime.HarnessRuntime',
]
const harnessPublicValueImports = new Set([
  'fun.fengwk.kkstudio.harness.runtime.driver.DriverRegistry',
  'fun.fengwk.kkstudio.harness.spi.store.UuidOrder',
])
const harnessBuiltinPublicPrefixes = [
  'fun.fengwk.kkstudio.harness.builtin.driver.',
  'fun.fengwk.kkstudio.harness.builtin.port.',
]
const platformHarnessRoot =
  'platform/runtime/src/main/java/fun/fengwk/kkstudio/platform/harness/'
const platformHarnessBuiltinRoot = `${platformHarnessRoot}builtin/`
const harnessStoreRoots = [
  'platform/runtime/src/main/java/fun/fengwk/kkstudio/platform/harness/store/',
  'platform/runtime/src/main/java/fun/fengwk/kkstudio/platform/harness/builtin/store/',
  'platform/runtime/src/main/java/fun/fengwk/kkstudio/platform/harness/dispatch/',
  'platform/runtime/src/main/java/fun/fengwk/kkstudio/platform/harness/model/',
]
const harnessStoreFiles = new Set([
  'platform/runtime/src/main/java/fun/fengwk/kkstudio/platform/harness/package-info.java',
  'platform/runtime/src/main/java/fun/fengwk/kkstudio/platform/catalog/model/GroupCooldownRepository.java',
])
const webHarnessStoreCompositionFiles = new Set([
  'web/src/main/java/fun/fengwk/kkstudio/web/runtime/HarnessEnvironmentConfiguration.java',
  'web/src/main/java/fun/fengwk/kkstudio/web/runtime/HarnessProcessorConfiguration.java',
  'web/src/main/java/fun/fengwk/kkstudio/web/runtime/HarnessRuntimeConfiguration.java',
])

function isWithin(relativePath, root) {
  const normalized = root.endsWith('/') ? root.slice(0, -1) : root
  return relativePath === normalized || relativePath.startsWith(`${normalized}/`)
}

function mainJavaFiles() {
  return mainJavaRoots.flatMap((root) =>
    walkFiles(path.join(repositoryRoot, root))
      .filter((filePath) => filePath.endsWith('.java'))
      .map((filePath) => relativeFromRoot(filePath))
      .filter((relativePath) => relativePath.includes('/src/main/java/')),
  )
}

function harnessStoreAllowed(relativePath) {
  return (
    harnessStoreRoots.some((root) => isWithin(relativePath, root)) ||
    harnessStoreFiles.has(relativePath) ||
    webHarnessStoreCompositionFiles.has(relativePath)
  )
}

function checkHarnessPackageAccess() {
  for (const relativePath of mainJavaFiles()) {
    if (relativePath.startsWith('web/')) {
      continue
    }
    const isPlatform = relativePath.startsWith('platform/')
    const isPlatformHarness = isWithin(relativePath, platformHarnessRoot)
    const isBuiltinAdapter = isWithin(relativePath, platformHarnessBuiltinRoot)
    for (const { imported, line } of javaImportsForFile(relativePath)) {
      if (!imported.startsWith('fun.fengwk.kkstudio.harness.')) {
        continue
      }
      if (imported.startsWith('fun.fengwk.kkstudio.harness.spi.store.')) {
        if (
          imported === 'fun.fengwk.kkstudio.harness.spi.store.UuidOrder' ||
          harnessStoreAllowed(relativePath)
        ) {
          continue
        }
        addError(
          `${relativePath}:${line}: only platform engine adapters may use harness.spi.store (${imported})`,
        )
        continue
      }
      if (imported.startsWith('fun.fengwk.kkstudio.harness.builtin.port.store.')) {
        if (!isPlatformHarness) {
          addError(
            `${relativePath}:${line}: Builtin storage contracts are limited to platform.harness.* (${imported})`,
          )
        }
        if (
          imported === 'fun.fengwk.kkstudio.harness.builtin.port.store.BuiltinStore' &&
          !isBuiltinAdapter
        ) {
          addError(
            `${relativePath}:${line}: Builtin storage port is limited to platform.harness.builtin (${imported})`,
          )
        }
        continue
      }
      if (imported.startsWith('fun.fengwk.kkstudio.harness.builtin.')) {
        if (
          isBuiltinAdapter ||
          (isPlatform &&
            harnessBuiltinPublicPrefixes.some((prefix) => imported.startsWith(prefix)))
        ) {
          continue
        }
        addError(
          `${relativePath}:${line}: Builtin Driver internals are limited to platform.harness.builtin (${imported})`,
        )
        continue
      }
      if (imported.startsWith('fun.fengwk.kkstudio.harness.runtime.')) {
        if (
          harnessPublicValueImports.has(imported) ||
          harnessPublicPrefixes.some((prefix) => imported.startsWith(prefix)) ||
          isPlatformHarness
        ) {
          continue
        }
        addError(
          `${relativePath}:${line}: runtime internals are limited to HarnessRuntime and platform engine adapters (${imported})`,
        )
        continue
      }
      if (harnessPublicPrefixes.some((prefix) => imported.startsWith(prefix))) {
        continue
      }
      addError(`${relativePath}:${line}: outside the public Harness boundary (${imported})`)
    }
  }
}

/**
 * 表只由归属模块或职责包的 SQL 访问；唯一例外是依赖 work 的模块只读 join durable_work。
 * 前缀映射只有这一份，schema 中的每张表都必须能命中。
 */
const tableOwners = [
  ['agent', 'platform/runtime/src/main/java/fun/fengwk/kkstudio/platform/catalog'],
  ['builtin_model_', 'platform/runtime/src/main/java/fun/fengwk/kkstudio/platform/harness/builtin'],
  ['canvas_', 'apps/canvas'],
  ['chat', 'apps/chat'],
  ['cluster_', 'platform/runtime/src/main/java/fun/fengwk/kkstudio/platform/cluster'],
  ['durable_work', 'platform/runtime/src/main/java/fun/fengwk/kkstudio/platform/harness'],
  ['environment', 'platform/runtime'],
  ['harness_', 'platform/runtime/src/main/java/fun/fengwk/kkstudio/platform/harness'],
  ['mcp_', 'platform/runtime/src/main/java/fun/fengwk/kkstudio/platform/catalog'],
  ['model', 'platform/runtime/src/main/java/fun/fengwk/kkstudio/platform/catalog'],
  ['plugin_', 'platform/runtime/src/main/java/fun/fengwk/kkstudio/platform/pluginhost'],
  ['provider', 'platform/runtime/src/main/java/fun/fengwk/kkstudio/platform/catalog'],
  ['project', 'apps/project'],
  ['realtime_', 'platform/runtime/src/main/java/fun/fengwk/kkstudio/platform/cluster'],
  ['session_', 'platform/runtime/src/main/java/fun/fengwk/kkstudio/platform/storage'],
  ['skill_', 'platform/runtime/src/main/java/fun/fengwk/kkstudio/platform/catalog'],
  ['storage_', 'platform/runtime/src/main/java/fun/fengwk/kkstudio/platform/storage'],
  ['system_setting', 'platform/runtime/src/main/java/fun/fengwk/kkstudio/platform/settings'],
]
const sqlTableReference =
  /\b(from|join|update|insert\s+into|delete\s+from)\s+([a-z_][a-z0-9_]*)\b/gi

function tableOwner(table) {
  return tableOwners.find(([prefix]) => table === prefix || table.startsWith(prefix))?.[1]
}

function javaStringLiterals(source) {
  const literals = []
  let index = 0
  while (index < source.length) {
    if (source.startsWith('"""', index)) {
      const end = source.indexOf('"""', index + 3)
      if (end < 0) {
        break
      }
      let contentStart = index + 3
      if (source[contentStart] === '\r') {
        contentStart++
      }
      if (source[contentStart] === '\n') {
        contentStart++
      }
      literals.push({
        text: source.slice(contentStart, end),
        line: lineNumberAt(source, contentStart),
      })
      index = end + 3
    } else if (source[index] === '"') {
      let cursor = index + 1
      while (cursor < source.length && source[cursor] !== '"') {
        if (source[cursor] === '\\') {
          cursor++
        }
        cursor++
      }
      literals.push({ text: source.slice(index + 1, cursor), line: lineNumberAt(source, index) })
      index = cursor + 1
    } else {
      index++
    }
  }
  return literals
}

function modulePathFor(relativePath) {
  let match = null
  for (const modulePath of Object.keys(moduleDependencies)) {
    if (isWithin(relativePath, modulePath) && (!match || modulePath.length > match.length)) {
      match = modulePath
    }
  }
  return match
}

function checkTableOwnership() {
  const schemaPath = 'schema/src/main/resources/db/migration/V1__schema.sql'
  const schema = read(schemaPath)
  if (!schema) {
    addError(`missing schema: ${schemaPath}`)
  }
  for (const match of schema.matchAll(
    /create\s+table\s+(?:if\s+not\s+exists\s+)?([a-z_][a-z0-9_]*)/giu,
  )) {
    const table = match[1].toLowerCase()
    if (!tableOwner(table)) {
      addError(`schema table has no owner in tableOwners: ${table}`)
    }
  }

  for (const relativePath of mainJavaFiles()) {
    const source = read(relativePath)
    const modulePath = modulePathFor(relativePath)
    const dependsOnWork =
      Array.isArray(moduleDependencies[modulePath]) && moduleDependencies[modulePath].includes('work')
    for (const literal of javaStringLiterals(source)) {
      for (const match of literal.text.matchAll(sqlTableReference)) {
        const operation = match[1].toLowerCase().replace(/\s+/gu, ' ')
        const table = match[2].toLowerCase()
        const owner = tableOwner(table)
        if (!owner || isWithin(relativePath, owner)) {
          continue
        }
        const readOnly = operation === 'from' || operation === 'join'
        if (table === 'durable_work' && readOnly && dependsOnWork) {
          continue
        }
        const line = literal.line + literal.text.slice(0, match.index).split('\n').length - 1
        addError(
          `${relativePath}:${line}: ${table} is owned by ${owner} and cannot be used here (${operation})`,
        )
      }
    }
  }
}


/** 内核只保留纯引擎：不引用 JGit，手动压缩控制不穿透到 processor 实现。 */
function checkKernelBoundary() {
  const manualCompaction =
    'harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/ManualCompactionControl.java'
  if (!existsSync(path.join(repositoryRoot, manualCompaction))) {
    addError(`missing manual compaction control: ${manualCompaction}`)
  }
  for (const { line, text } of fileLines(manualCompaction)) {
    if (text.includes('fun.fengwk.kkstudio.harness.runtime.processor.')) {
      addError(
        `${manualCompaction}:${line}: ManualCompactionControl must not depend on processor internals`,
      )
    }
  }
  for (const { file, imported, line } of javaImports(
    path.join(repositoryRoot, 'harness/runtime/src/main/java'),
  )) {
    if (imported.startsWith('org.eclipse.jgit.')) {
      addError(`${file}:${line}: Harness runtime must not import JGit (${imported})`)
    }
  }
}

const platformDomainRoots = [
  'agenttask',
  'catalog',
  'cluster',
  'configsync',
  'environment',
  'error',
  'events',
  'harness',
  'http',
  'i18n',
  'interaction',
  'notification',
  'orchestration',
  'persistence',
  'pluginhost',
  'secret',
  'settings',
  'storage',
  'vfs',
]
/** 平台契约的子域根保持精确，新增子域必须显式更新这里。 */
function checkPackageRoots() {
  for (const [relativePath, expected] of [
    ['platform/runtime/src/main/java/fun/fengwk/kkstudio/platform', platformDomainRoots],
  ]) {
    const absolutePath = path.join(repositoryRoot, relativePath)
    if (!existsSync(absolutePath)) {
      addError(`missing package root: ${relativePath}`)
      continue
    }
    const actual = readdirSync(absolutePath, { withFileTypes: true })
      .filter((entry) => entry.isDirectory())
      .map((entry) => entry.name)
      .sort()
    if (JSON.stringify(actual) !== JSON.stringify([...expected].sort())) {
      addError(
        `${relativePath}: unexpected domain roots; expected [${[...expected].sort().join(', ')}], found [${actual.join(', ')}]`,
      )
    }
  }
}

/** ProviderType 的事实源位于 harness/model。 */
function checkProviderTypeSingleSource() {
  if (
    !existsSync(
      path.join(
        repositoryRoot,
        'harness/model/src/main/java/fun/fengwk/kkstudio/harness/model/provider/ProviderType.java',
      ),
    )
  ) {
    addError('missing harness/model ProviderType')
  }
}

/** MCP client 只依赖 JDK、官方 SDK、Jackson 3、common 与日志注解。 */
function checkMcpClientBoundary() {
  const allowedPrefixes = [
    'java.',
    'javax.',
    'io.modelcontextprotocol.',
    'com.fasterxml.jackson.annotation.',
    'tools.jackson.',
    'fun.fengwk.kkstudio.common.',
    'fun.fengwk.kkstudio.platform.catalog.mcp.client.',
  ]
  const clientRoot =
    'platform/runtime/src/main/java/fun/fengwk/kkstudio/platform/catalog/mcp/client'
  if (!existsSync(path.join(repositoryRoot, clientRoot))) {
    addError(`missing MCP client package: ${clientRoot}`)
    return
  }
  for (const { file, imported, line } of javaImports(path.join(repositoryRoot, clientRoot))) {
    if (imported !== 'lombok.extern.slf4j.Slf4j' &&
        !allowedPrefixes.some((prefix) => imported.startsWith(prefix))) {
      addError(
        `${file}:${line}: MCP client imports must stay on JDK, official SDK, Jackson 3, common and the logging annotation (${imported})`,
      )
    }
  }
}

const harnessCatalogImport = 'fun.fengwk.kkstudio.harness.contributor.api.HarnessCatalog'
const harnessCatalogImporters = new Set([
  'platform/runtime/src/main/java/fun/fengwk/kkstudio/platform/catalog/tool/PluginRuntimeToolCatalogSource.java',
  'platform/runtime/src/main/java/fun/fengwk/kkstudio/platform/catalog/tool/RuntimeToolCatalogSource.java',
  'platform/runtime/src/main/java/fun/fengwk/kkstudio/platform/harness/tool/gateway/GatewayRun.java',
  'platform/runtime/src/main/java/fun/fengwk/kkstudio/platform/pluginhost/catalog/PluginCatalogSnapshot.java',
])
const harnessCatalogConsumers = [
  'platform/runtime/src/main/java/fun/fengwk/kkstudio/platform/harness/thread/command/DatabaseAgentTurnContextResolver.java',
  'platform/runtime/src/main/java/fun/fengwk/kkstudio/platform/harness/tool/gateway/ToolExecutionGateway.java',
  'platform/runtime/src/main/java/fun/fengwk/kkstudio/platform/catalog/definition/AgentConfigValidator.java',
  'platform/runtime/src/main/java/fun/fengwk/kkstudio/platform/catalog/tool/ToolCatalogQueryService.java',
]
const harnessCatalogGatewayRun =
  'platform/runtime/src/main/java/fun/fengwk/kkstudio/platform/harness/tool/gateway/GatewayRun.java'

/** HarnessCatalog 只用于目录构建、装配与元数据校验，工具查找统一走 RuntimeToolCatalog。 */
function checkHarnessCatalogBoundary() {
  const platformMain = 'platform/runtime/src/main/java'
  for (const { file, imported, line } of javaImports(path.join(repositoryRoot, platformMain))) {
    if (imported === harnessCatalogImport && !harnessCatalogImporters.has(file)) {
      addError(`${file}:${line}: HarnessCatalog imports are limited to catalog builders and wiring`)
    }
  }
  for (const relativePath of harnessCatalogConsumers) {
    if (!existsSync(path.join(repositoryRoot, relativePath))) {
      addError(`missing HarnessCatalog consumer: ${relativePath}`)
      continue
    }
    const source = read(relativePath)
    if (!source.includes('RuntimeToolCatalog')) {
      addError(`${relativePath}: HarnessCatalog consumer must use RuntimeToolCatalog`)
    }
    if (
      relativePath.endsWith('DatabaseAgentTurnContextResolver.java') ||
      relativePath.endsWith('ToolExecutionGateway.java') ||
      relativePath.endsWith('AgentConfigValidator.java')
    ) {
      if (!source.includes('import fun.fengwk.kkstudio.platform.catalog.tool.RuntimeToolCatalogSource;')) {
        addError(`${relativePath}: must import RuntimeToolCatalogSource`)
      }
    }
    if (
      !relativePath.endsWith('ToolExecutionGateway.java') &&
      source.includes('HarnessCatalog')
    ) {
      addError(`${relativePath}: must not reference HarnessCatalog`)
    }
  }
  const gatewayRun = read(harnessCatalogGatewayRun)
  if (!gatewayRun.includes(`import ${harnessCatalogImport};`)) {
    addError(`${harnessCatalogGatewayRun}: must import HarnessCatalog for metadata`)
  }
  if (!/harnessCatalog\s*\.\s*findCustomEntryType/u.test(gatewayRun)) {
    addError(`${harnessCatalogGatewayRun}: must validate custom entry types via HarnessCatalog`)
  }
  for (const token of [
    'harnessCatalog.findTool',
    'harnessCatalog.selectableTools',
    'harnessCatalog.tools()',
  ]) {
    if (gatewayRun.includes(token)) {
      addError(`${harnessCatalogGatewayRun}: must not look up tools via ${token}`)
    }
  }
  const gateway = read(harnessCatalogConsumers[1])
  if (!gateway.includes('toolCatalogSource.acquire')) {
    addError(`${harnessCatalogConsumers[1]}: must acquire the catalog epoch before tool access`)
  }
}

/** 阻塞执行必须经过显式准入；无界 per-task executor 只属于 Model 网关。 */
function checkExecutionAdmissionBoundary() {
  const platformMain = path.join(repositoryRoot, 'platform/runtime/src/main/java')
  for (const filePath of walkFiles(platformMain).filter((file) => file.endsWith('.java'))) {
    const relativePath = relativeFromRoot(filePath)
    for (const { line, text } of fileLines(relativePath)) {
      if (
        (text.includes('newVirtualThreadPerTaskExecutor') ||
          text.includes('newThreadPerTaskExecutor')) &&
        path.basename(relativePath) !== 'ModelExecutionConfiguration.java'
      ) {
        addError(`${relativePath}:${line}: unbounded per-task executor bypasses admission`)
      }
    }
  }
  for (const [relativePath, tokens] of [
    [
      'platform/runtime/src/main/java/fun/fengwk/kkstudio/platform/harness/model/ModelExecutionConfiguration.java',
      ['ConcurrencyAdmission', 'modelExecutionAdmission'],
    ],
    [
      'platform/runtime/src/main/java/fun/fengwk/kkstudio/platform/harness/tool/gateway/HarnessToolGatewayConfiguration.java',
      ['ConcurrencyAdmission', 'toolExecutionAdmission'],
    ],
  ]) {
    const source = read(relativePath)
    for (const token of tokens) {
      if (!source.includes(token)) {
        addError(`${relativePath}: missing explicit admission token '${token}'`)
      }
    }
  }
}

const platformCompositionForbiddenTokens = [
  'TrustedJarContributorLoader',
  'URLClassLoader',
  'ServiceLoader',
  'kk-studio.harness.contributors.directory',
  'KK_STUDIO_TRUSTED_CONTRIBUTOR_DIRECTORY',
]

/** platform 不是组合根，且全仓不接受 LangChain4j；环境网关只实现能力传输。 */
function checkPlatformCompositionBoundary() {
  const platformMain = path.join(repositoryRoot, 'platform/runtime/src/main/java')
  for (const filePath of walkFiles(platformMain).filter((file) => file.endsWith('.java'))) {
    const relativePath = relativeFromRoot(filePath)
    for (const { line, text } of fileLines(relativePath)) {
      const trimmed = text.trim()
      if (trimmed.startsWith('@SpringBootApplication') || trimmed.startsWith('SpringApplication.run(')) {
        addError(`${relativePath}:${line}: platform must not be the Spring Boot entry point`)
      }
      for (const token of platformCompositionForbiddenTokens) {
        if (text.includes(token)) {
          addError(`${relativePath}:${line}: platform must not load trusted contributor JARs (${token})`)
        }
      }
    }
  }

  const environmentGateway =
    'platform/runtime/src/main/java/fun/fengwk/kkstudio/platform/environment/gateway/EnvironmentDaemonGateway.java'
  if (!existsSync(path.join(repositoryRoot, environmentGateway))) {
    addError(`missing environment gateway: ${environmentGateway}`)
  } else {
    const source = read(environmentGateway)
    for (const token of ['fun.fengwk.kkstudio.harness.tool.', 'RemoteTool']) {
      if (source.includes(token)) {
        addError(`${environmentGateway}: environment gateway must use capability terminology, found ${token}`)
      }
    }
  }

  for (const filePath of walkRepositoryFiles(repositoryRoot)) {
    const relativePath = relativeFromRoot(filePath)
    if (path.basename(relativePath) === 'pom.xml') {
      for (const { line, text } of fileLines(relativePath)) {
        if (text.toLowerCase().includes('langchain4j')) {
          addError(`${relativePath}:${line}: LangChain4j dependency must be absent`)
        }
      }
    }
  }
  for (const relativePath of mainJavaFiles()) {
    for (const { line, text } of fileLines(relativePath)) {
      if (
        text.trim().startsWith('import dev.langchain4j.') ||
        text.trim().startsWith('import io.github.langchain4j.')
      ) {
        addError(`${relativePath}:${line}: production sources must not import LangChain4j`)
      }
    }
  }
}

const redisJavaPackages = [
  'io.lettuce.',
  'org.redisson.',
  'org.springframework.boot.autoconfigure.data.redis.',
  'org.springframework.data.redis.',
  'redis.clients.',
]
const redisJavaType = /\b(?:Jedis|Lettuce|Redis|Redisson)[A-Za-z0-9_]*\b/u
const redisPomGroup = /<groupId>\s*(?:io\.lettuce|org\.redisson|redis\.clients)\s*<\/groupId>/iu
const redisPomArtifact =
  /<artifactId>\s*[^<]*(?:jedis|lettuce|redis|redisson)[^<]*<\/artifactId>/iu
const redisConfiguration = /(?:jedis|lettuce|redis|redisson)/iu

function stripJavaComments(source) {
  const lines = source.split(/\r?\n/u)
  let inBlockComment = false
  return lines.map((line) => {
    let code = ''
    for (let cursor = 0; cursor < line.length; ) {
      if (inBlockComment) {
        const end = line.indexOf('*/', cursor)
        if (end < 0) {
          cursor = line.length
        } else {
          inBlockComment = false
          cursor = end + 2
        }
      } else if (line.startsWith('//', cursor)) {
        cursor = line.length
      } else if (line.startsWith('/*', cursor)) {
        inBlockComment = true
        cursor += 2
      } else {
        code += line[cursor]
        cursor++
      }
    }
    return code
  })
}

function isRedisConfiguration(relativePath) {
  const lower = relativePath.toLowerCase()
  const filename = path.basename(relativePath).toLowerCase()
  return (
    lower.endsWith('.yml') ||
    lower.endsWith('.yaml') ||
    lower.endsWith('.properties') ||
    filename.startsWith('.env') ||
    filename.endsWith('.env') ||
    filename.includes('dockerfile')
  )
}

/** Git 管理的后端生产代码、依赖与部署配置不得恢复 Redis 栈。 */
function checkNoRedis() {
  for (const filePath of walkRepositoryFiles(repositoryRoot)) {
    const relativePath = relativeFromRoot(filePath)
    if (
      relativePath.startsWith('frontend/') ||
      relativePath.startsWith('docs/') ||
      relativePath.startsWith('scripts/')
    ) {
      continue
    }
    if (relativePath.endsWith('.java') && relativePath.includes('/src/main/java/')) {
      const codeLines = stripJavaComments(read(relativePath))
      codeLines.forEach((text, index) => {
        if (
          redisJavaPackages.some((value) => text.includes(value)) ||
          redisJavaType.test(text)
        ) {
          addError(`${relativePath}:${index + 1}: Redis stack must be absent (${text.trim()})`)
        }
      })
    } else if (path.basename(relativePath) === 'pom.xml') {
      for (const { line, text } of fileLines(relativePath)) {
        if (redisPomGroup.test(text) || redisPomArtifact.test(text)) {
          addError(`${relativePath}:${line}: Redis dependency must be absent (${text.trim()})`)
        }
      }
    } else if (isRedisConfiguration(relativePath)) {
      for (const { line, text } of fileLines(relativePath)) {
        if (redisConfiguration.test(text)) {
          addError(`${relativePath}:${line}: Redis configuration must be absent (${text.trim()})`)
        }
      }
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

/** 内核与 Common 的生产包必须包含 package-info 和 Javadoc。 */
const packageDocumentedModules = [
  'common',
  'harness/environment',
  'harness/model',
  'harness/runtime',
  'harness/agent-builtin',
]

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
 * Java 包在 Maven 模块之间全局唯一（无论出现在 main 还是 test）。
 * 同一模块内部的 main 与 test 可以同名。
 */
function packageOwners() {
  const owners = new Map()
  function visitModule(modulePath) {
    const pomPath = path.join(modulePath, 'pom.xml')
    if (!existsSync(path.join(repositoryRoot, pomPath))) {
      return
    }
    for (const sourceSet of ['main', 'test']) {
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
    }
    for (const match of read(pomPath).matchAll(/<module>\s*([^<]+?)\s*<\/module>/gu)) {
      visitModule(path.join(modulePath, match[1]))
    }
  }
  visitModule('')
  return owners
}

function checkUniquePackages() {
  for (const [name, modules] of packageOwners()) {
    if (modules.size < 2) {
      continue
    }
    addError(
      `Java package shared across Maven modules: ${name} (${[...modules].sort().join(', ')})`,
    )
  }
}

function checkPackageInfo() {
  for (const modulePath of packageDocumentedModules) {
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

/** 源码、配置与文档中不得出现会话过程用语；AGENTS.md 是 Agent 工作规则，不在此列。 */
const sessionArtifactTerms = ['本切片', 'Branch B']
const sessionArtifactExcludedPaths = new Set(['scripts/dev/verify/repository/check.mjs'])
const sessionArtifactExcludedPrefixes = ['scripts/dev/verify/repository/tests/']
const sessionArtifactExtensions = new Set([
  '.java', '.ts', '.tsx', '.js', '.mjs', '.cjs', '.yml', '.yaml', '.properties', '.sql', '.sh', '.ps1', '.txt',
])
const generatedDirectories = new Set(['.git', '.workspace', 'node_modules', 'target', 'dist', 'coverage', 'reports'])

function sourceFiles(directory) {
  return readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    if (generatedDirectories.has(entry.name)) {
      return []
    }
    const absolutePath = path.join(directory, entry.name)
    if (entry.isDirectory()) {
      return sourceFiles(absolutePath)
    }
    return sessionArtifactExtensions.has(path.extname(entry.name)) ? [absolutePath] : []
  })
}

function checkNoSessionArtifacts() {
  for (const relativePath of markdownSurfaces) {
    const source = outsideFencedCode(read(relativePath))
    for (const term of sessionArtifactTerms) {
      if (source.includes(term)) {
        addError(`${relativePath}: contains prohibited session artifact '${term}'`)
      }
    }
    if (/workspace\s+notes/iu.test(source)) {
      addError(`${relativePath}: contains prohibited session artifact 'Workspace NOTES'`)
    }
  }
  for (const file of sourceFiles(repositoryRoot)) {
    const relativePath = relativeFromRoot(file)
    if (
      sessionArtifactExcludedPaths.has(relativePath) ||
      sessionArtifactExcludedPrefixes.some((prefix) => relativePath.startsWith(prefix))
    ) {
      continue
    }
    read(relativePath)
      .split('\n')
      .forEach((line, index) => {
        for (const term of sessionArtifactTerms) {
          if (line.includes(term)) {
            addError(`${relativePath}:${index + 1}: contains prohibited session artifact '${term}'`)
          }
        }
      })
  }
}

checkFixedLayout()
checkHeadings()
checkLinks()
checkInlineSourcePaths()
checkModuleBoundaries()
checkWebArchitecture()
checkHarnessPackageAccess()
checkTableOwnership()
checkKernelBoundary()
checkPackageRoots()
checkProviderTypeSingleSource()
checkMcpClientBoundary()
checkHarnessCatalogBoundary()
checkExecutionAdmissionBoundary()
checkPlatformCompositionBoundary()
checkNoRedis()
checkUniquePackages()
checkNoHarnessModuleDocs()
checkPackageInfo()
checkNoSessionArtifacts()

if (errors.length > 0) {
  console.error(`FAIL docs (${errors.length} error${errors.length === 1 ? '' : 's'})`)
  for (const error of errors) {
    console.error(`- ${error}`)
  }
  process.exitCode = 1
} else {
  console.log(`PASS docs (${documentPaths.length} fixed documents)`)
}
