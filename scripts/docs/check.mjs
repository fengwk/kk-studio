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
  return path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
}

const repositoryRoot = resolveRepositoryRoot()
const docsRoot = path.join(repositoryRoot, 'docs')

const moduleDocuments = [
  'canvas-core.md',
  'canvas-infra.md',
  'frontend.md',
  'harness-builtin.md',
  'harness-common.md',
  'harness-contributor-api.md',
  'harness-daemon.md',
  'harness-environment.md',
  'harness-environment-server.md',
  'harness-infra.md',
  'harness-mcp.md',
  'harness-provider.md',
  'harness-runtime.md',
  'harness-tool.md',
  'platform.md',
  'schema.md',
  'share.md',
  'web.md',
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
  ...documentPaths,
  'deploy/local/README.md',
  'deploy/test/README.md',
]
const checkableTopLevels = new Set([
  'AGENTS.md',
  'README.md',
  'canvas',
  'deploy',
  'frontend',
  'harness',
  'platform',
  'schema',
  'scripts',
  'share',
  'web',
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

function checkRepositoryStructure() {
  const legitimatePromptPackage = path.join(
    repositoryRoot,
    'harness/common/src/main/java/fun/fengwk/kkstudio/harness/common/prompt',
  )
  if (!existsSync(legitimatePromptPackage)) {
    addError(
      `expected shared prompt package missing: ${relativeFromRoot(legitimatePromptPackage)}`,
    )
  }

  const expectedRootModules = [
    'share',
    'schema',
    'canvas',
    'harness',
    'platform',
    'plugins',
    'web',
  ]
  const rootPomPath = path.join(repositoryRoot, 'pom.xml')
  if (existsSync(rootPomPath)) {
    const actualRootModules = extractPomModules('pom.xml')
    if (JSON.stringify(actualRootModules) !== JSON.stringify(expectedRootModules)) {
      addError(
        `pom.xml modules mismatch: expected [${expectedRootModules.join(', ')}], found [${actualRootModules.join(', ')}]`,
      )
    }
    for (const moduleName of expectedRootModules) {
      const moduleDir = path.join(repositoryRoot, moduleName)
      if (!existsSync(moduleDir)) {
        addError(`missing root module directory: ${moduleName}`)
      }
    }
  }

  const expectedHarnessModules = [
    'common',
    'mcp',
    'tool',
    'environment',
    'environment-server',
    'runtime',
    'provider',
    'contributor-api',
    'builtin',
    'infra',
    'daemon',
  ]
  const harnessRoot = path.join(repositoryRoot, 'harness')
  if (existsSync(harnessRoot)) {
    const actualHarnessDirectories = readdirSync(harnessRoot, { withFileTypes: true })
      // Dot-prefixed directories are local IDE/tool metadata, not repository modules.
      .filter(
        (entry) =>
          entry.isDirectory() && entry.name !== 'target' && !entry.name.startsWith('.'),
      )
      .map((entry) => entry.name)
      .sort()
    const expectedHarnessDirectories = [...expectedHarnessModules].sort()
    if (
      JSON.stringify(actualHarnessDirectories) !== JSON.stringify(expectedHarnessDirectories)
    ) {
      addError(
        `harness directories mismatch: expected [${expectedHarnessDirectories.join(', ')}], found [${actualHarnessDirectories.join(', ')}]`,
      )
    }
  }

  const harnessPomPath = path.join(repositoryRoot, 'harness/pom.xml')
  if (existsSync(harnessPomPath)) {
    const actualHarnessModules = extractPomModules('harness/pom.xml')
    if (JSON.stringify(actualHarnessModules) !== JSON.stringify(expectedHarnessModules)) {
      addError(
        `harness/pom.xml modules mismatch: expected [${expectedHarnessModules.join(', ')}], found [${actualHarnessModules.join(', ')}]`,
      )
    }
    for (const moduleName of expectedHarnessModules) {
      const moduleDir = path.join(repositoryRoot, 'harness', moduleName)
      if (!existsSync(moduleDir)) {
        addError(`missing harness module directory: harness/${moduleName}`)
      }
    }
  }

  // plugins 只聚合构建期可选 Plugin；发行物包含哪些 Plugin 由 web 的 runtime dependency 决定。
  const expectedPluginsModules = ['minimax-mavis']
  const pluginsRoot = path.join(repositoryRoot, 'plugins')
  if (existsSync(pluginsRoot)) {
    const actualPluginsDirectories = readdirSync(pluginsRoot, { withFileTypes: true })
      .filter(
        (entry) =>
          entry.isDirectory() && entry.name !== 'target' && !entry.name.startsWith('.'),
      )
      .map((entry) => entry.name)
      .sort()
    const expectedPluginsDirectories = [...expectedPluginsModules].sort()
    if (JSON.stringify(actualPluginsDirectories) !== JSON.stringify(expectedPluginsDirectories)) {
      addError(
        `plugins directories mismatch: expected [${expectedPluginsDirectories.join(', ')}], found [${actualPluginsDirectories.join(', ')}]`,
      )
    }
  }

  const pluginsPomPath = path.join(repositoryRoot, 'plugins/pom.xml')
  if (existsSync(pluginsPomPath)) {
    const actualPluginsModules = extractPomModules('plugins/pom.xml')
    if (JSON.stringify(actualPluginsModules) !== JSON.stringify(expectedPluginsModules)) {
      addError(
        `plugins/pom.xml modules mismatch: expected [${expectedPluginsModules.join(', ')}], found [${actualPluginsModules.join(', ')}]`,
      )
    }
    for (const moduleName of expectedPluginsModules) {
      const moduleDir = path.join(repositoryRoot, 'plugins', moduleName)
      if (!existsSync(moduleDir)) {
        addError(`missing plugins module directory: plugins/${moduleName}`)
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

const harnessProductionModules = [
  'common',
  'mcp',
  'tool',
  'contributor-api',
  'builtin',
  'environment',
  'environment-server',
  'daemon',
  'runtime',
  'provider',
  'infra',
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

function harnessProductionPackages(moduleName) {
  const srcMainJava = path.join(repositoryRoot, 'harness', moduleName, 'src/main/java')
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

function checkHarnessPackageInfo() {
  for (const moduleName of harnessProductionModules) {
    for (const { dir, name: expectedPackageName } of harnessProductionPackages(moduleName)) {
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

function checkHarnessPackageArchitectureDocs() {
  for (const moduleName of harnessProductionModules) {
    const docPath = `docs/modules/harness-${moduleName}.md`
    const section = levelTwoSection(read(docPath), '包架构')
    if (section === null) {
      addError(`${docPath}: missing '## 包架构' section`)
      continue
    }
    if (!/^\|\s*(?:包名|包路径)\s*\|/mu.test(section)) {
      addError(`${docPath}: package architecture section must contain a package table`)
    }
    for (const { name: packageName } of harnessProductionPackages(moduleName)) {
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
checkRepositoryStructure()
checkNoHarnessModuleDocs()
checkHarnessPackageInfo()
checkHarnessPackageArchitectureDocs()
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
