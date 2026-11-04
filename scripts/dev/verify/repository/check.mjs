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
  'share.md',
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
  'share',
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
  'platform/plugin-api': ['common', 'harness-runtime'],
  'platform/daemon-server': ['common', 'harness-environment'],
  'platform/runtime': [
    'common',
    'share',
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
    'share',
    'work',
    'canvas-spi',
    'platform-plugin-api',
    'platform-runtime',
  ],
  'apps/project': ['common', 'share', 'work', 'harness-runtime', 'platform-runtime'],
  'apps/ide': ['common', 'share', 'harness-environment', 'platform-daemon-server', 'platform-runtime'],
  'plugins/comfyui': ['common', 'canvas-spi', 'harness-runtime', 'platform-plugin-api'],
  'plugins/media': ['common', 'canvas-spi', 'harness-runtime', 'platform-plugin-api'],
  'plugins/minimax-mavis': ['common', 'harness-runtime', 'platform-plugin-api'],
  'plugins/opencli': ['common', 'canvas-spi', 'harness-runtime', 'platform-plugin-api'],
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
    if (imported.startsWith('fun.fengwk.kkstudio.harness.')) {
      const relativeFile = path.relative(webMainRoot, path.join(repositoryRoot, file)).split(path.sep).join('/')
      const allowed =
        webHarnessAllowedPrefixes.some((prefix) => imported.startsWith(prefix)) ||
        webHarnessValueImports.has(imported) ||
        (webBuiltinImportAllowlist.get(relativeFile) ?? []).includes(imported)
      if (!allowed) {
        addError(`${file}: web must use only declared Harness boundaries (${imported})`)
      }
    }
    for (const prefix of webForbiddenImportPrefixes) {
      if (imported.startsWith(prefix)) {
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
const sessionArtifactTerms = [['本', '切片'].join(''), ['Branch', 'B'].join(' ')]
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
