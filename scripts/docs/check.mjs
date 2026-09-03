#!/usr/bin/env node

import { existsSync, readdirSync, readFileSync } from 'node:fs'
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
  'harness-infra.md',
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
  return [...outsideFencedCode(source).matchAll(/`([^`\n]+)`/g)].map((match) => match[1].trim())
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
  for (const relativePath of documentPaths) {
    const source = read(relativePath)
    for (const value of inlineCodeValues(source)) {
      if (!looksLikeCheckablePath(value)) {
        continue
      }
      const absolutePath = path.join(repositoryRoot, value)
      if (!existsSync(absolutePath)) {
        addError(`${relativePath}: missing inline repository path ${value}`)
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
    const source = read(relativePath)
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
  const obsoleteHarnessPrompt = path.join(repositoryRoot, 'harness/prompt')
  if (existsSync(obsoleteHarnessPrompt)) {
    addError(
      `obsolete top-level harness/prompt module directory must not exist: ${relativeFromRoot(obsoleteHarnessPrompt)}`,
    )
  }

  const legitimatePromptPackage = path.join(
    repositoryRoot,
    'harness/common/src/main/java/fun/fengwk/kkstudio/harness/common/prompt',
  )
  if (!existsSync(legitimatePromptPackage)) {
    addError(
      `expected shared prompt package missing: ${relativeFromRoot(legitimatePromptPackage)}`,
    )
  }

  const expectedRootModules = ['share', 'schema', 'canvas', 'harness', 'platform', 'web']
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
    'tool',
    'environment',
    'runtime',
    'contributor-api',
    'builtin',
    'infra',
    'daemon',
  ]
  const harnessPomPath = path.join(repositoryRoot, 'harness/pom.xml')
  if (existsSync(harnessPomPath)) {
    const actualHarnessModules = extractPomModules('harness/pom.xml')
    if (JSON.stringify(actualHarnessModules) !== JSON.stringify(expectedHarnessModules)) {
      addError(
        `harness/pom.xml modules mismatch: expected [${expectedHarnessModules.join(', ')}], found [${actualHarnessModules.join(', ')}]`,
      )
    }
    if (actualHarnessModules.includes('prompt')) {
      addError('harness/pom.xml must not declare obsolete module: prompt')
    }
    for (const moduleName of expectedHarnessModules) {
      const moduleDir = path.join(repositoryRoot, 'harness', moduleName)
      if (!existsSync(moduleDir)) {
        addError(`missing harness module directory: harness/${moduleName}`)
      }
    }
  }
}

checkFixedLayout()
checkHeadings()
checkLinks()
checkInlineSourcePaths()
checkForbiddenReferencesAndTerms()
checkRepositoryStructure()

if (errors.length > 0) {
  console.error(`FAIL docs (${errors.length} error${errors.length === 1 ? '' : 's'})`)
  for (const error of errors) {
    console.error(`- ${error}`)
  }
  process.exitCode = 1
} else {
  console.log(`PASS docs (${documentPaths.length} fixed documents)`)
}
