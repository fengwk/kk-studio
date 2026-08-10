import path from 'node:path'

import { WRITE_PROOF_MARKER, WRITE_PROOF_META, WRITE_PROOF_PATH, sha256 } from './matrix.mjs'

const TOKEN_FIELDS = Object.freeze([
  'inputTokens',
  'outputTokens',
  'cacheReadTokens',
  'cacheWriteTokens',
  'cacheWriteLongTokens',
  'reasoningTokens',
  'providerTotalTokens',
])

export class ReliabilityError extends Error {
  constructor(category, message, details = null) {
    super(message)
    this.name = 'ReliabilityError'
    this.category = category
    this.details = details
  }
}

export function replaceExactlyOnce(source, from, to) {
  if (!from || from === to) throw new Error('replacement must use distinct non-empty strings')
  const count = countOccurrences(source, from)
  if (count !== 1) throw new Error(`expected exactly one replacement target, found ${count}`)
  return source.replace(from, to)
}

export function countOccurrences(source, needle) {
  if (!needle) return 0
  let count = 0
  let offset = 0
  while ((offset = source.indexOf(needle, offset)) >= 0) {
    count++
    offset += needle.length
  }
  return count
}

export function validateToolPolicy(testCase, traceData) {
  const names = traceData.toolCalls.map((call) => call.toolName)
  validateToolIsolation(testCase, traceData.toolCalls)
  if (testCase.taskClass === 'investigate') {
    assertRequiredFirstOrder(names, ['find', 'grep', 'read'])
    const forbidden = names.filter((name) => ['bash', 'edit', 'write'].includes(name))
    if (forbidden.length) {
      throw new ReliabilityError(
        'oracle',
        `read-only task used forbidden tools: ${[...new Set(forbidden)].join(', ')}`,
      )
    }
  } else {
    assertRequiredFirstOrder(names, ['find', 'grep', 'read', 'edit', 'bash', 'write'])
    for (const call of traceData.toolCalls.filter((candidate) => candidate.toolName === 'bash')) {
      const command = call.arguments?.command
      const result = validateBashCommand(command, testCase)
      if (!result.ok) {
        throw new ReliabilityError('tool', `forbidden bash command: ${result.reason}`)
      }
      if (call.arguments?.workdir !== testCase.casePath) {
        throw new ReliabilityError(
          'tool',
          `bash workdir must be the disposable case root: ${testCase.casePath}`,
        )
      }
    }
    validateWriteDiagnostics(traceData.writeDiagnostics, testCase)
  }

  const failedResult = traceData.toolResults.find((result) => result.error)
  if (failedResult) {
    throw new ReliabilityError(
      'tool',
      `tool result reported an error: ${failedResult.toolName} (${failedResult.toolCallId})`,
    )
  }
}

export function validateToolIsolation(testCase, toolCalls) {
  for (const call of toolCalls) {
    const args = call.arguments ?? {}
    const rawWorkdir = typeof args.workdir === 'string' ? args.workdir : null
    const rawFilePath = typeof args.path === 'string' ? args.path : null
    const workdir = rawWorkdir ? normalizeToolPath(rawWorkdir) : null
    if (rawWorkdir && !workdir) {
      throw new ReliabilityError('oracle', `${call.toolName} workdir must be absolute`)
    }
    if (workdir) assertCasePath(testCase, call.toolName, 'workdir', workdir)

    if (rawFilePath) {
      const filePath = normalizeToolPath(rawFilePath, workdir)
      if (!filePath) {
        if (path.posix.normalize(stripToolPathPrefix(rawFilePath)).startsWith('..')) {
          throw new ReliabilityError('oracle', `${call.toolName} path escapes the disposable case`)
        }
        throw new ReliabilityError(
          'oracle',
          `${call.toolName} used a relative path without the disposable case workdir`,
        )
      }
      assertCasePath(testCase, call.toolName, 'path', filePath)
    } else if (!workdir && call.toolName !== 'bash') {
      throw new ReliabilityError(
        'oracle',
        `${call.toolName} did not scope its operation to the disposable case`,
      )
    }
  }
}

function assertCasePath(testCase, toolName, field, value) {
  if (value === '/workspace/anchors' || value.startsWith('/workspace/anchors/')) {
    throw new ReliabilityError('oracle', `${toolName} accessed the forbidden anchor area`)
  }
  const otherCase = value.match(/^\/workspace\/cases\/([^/]+)/)?.[1]
  if (otherCase && otherCase !== testCase.id) {
    throw new ReliabilityError('oracle', `${toolName} accessed another disposable case`)
  }
  if (value !== testCase.casePath && !value.startsWith(`${testCase.casePath}/`)) {
    throw new ReliabilityError(
      'oracle',
      `${toolName} ${field} is outside the disposable case: ${value}`,
    )
  }
}

function normalizeToolPath(value, base = null) {
  const stripped = stripToolPathPrefix(value)
  if (path.posix.isAbsolute(stripped)) return path.posix.normalize(stripped)
  return base ? path.posix.resolve(base, stripped) : null
}

function stripToolPathPrefix(value) {
  return value.startsWith('@') ? value.slice(1) : value
}

export function assertRequiredFirstOrder(actualNames, requiredNames) {
  let previous = -1
  for (const required of requiredNames) {
    const index = actualNames.indexOf(required)
    if (index < 0) {
      throw new ReliabilityError('oracle', `required tool was not called: ${required}`)
    }
    if (index <= previous) {
      throw new ReliabilityError(
        'oracle',
        `required first tool order violated: ${requiredNames.join(' -> ')}; actual=${actualNames.join(' -> ')}`,
      )
    }
    previous = index
  }
}

export function validateBashCommand(command, testCase) {
  if (typeof command !== 'string' || !command.trim()) {
    return { ok: false, reason: 'missing command string' }
  }
  const normalized = normalizeShellWhitespace(command)
  const forbiddenToken =
    /(?:^|[\s;&|()])(?:find|grep|rg|fd|cat|sed|awk|perl|python(?:3)?|node)(?=$|[\s;&|()])/
  if (forbiddenToken.test(normalized)) {
    return { ok: false, reason: 'shell file discovery/search/read/write substitute detected' }
  }
  if (/[;|`]|\$\(|\|\||[<>]/.test(normalized)) {
    return { ok: false, reason: 'shell composition or redirection is not allowed' }
  }

  const commands = normalized.split(/\s*&&\s*/).filter(Boolean)
  if (!commands.length) return { ok: false, reason: 'empty command' }
  for (const part of commands) {
    if (part === normalizeShellWhitespace(testCase.targetTestCommand)) continue
    if (part === 'git status --short' || part === 'git diff --check') continue
    const expectedDiff = `git diff -- ${testCase.sourcePath}`
    const singleQuotedDiff = `git diff -- '${testCase.sourcePath}'`
    const doubleQuotedDiff = `git diff -- "${testCase.sourcePath}"`
    if ([expectedDiff, singleQuotedDiff, doubleQuotedDiff].includes(part)) continue
    return { ok: false, reason: `command is outside the allowlist: ${part}` }
  }
  return { ok: true, reason: null }
}

export function extractDurableTrace(entries) {
  const trace = []
  const toolCalls = []
  const toolResults = []
  const assistantTexts = []
  const usage = emptyUsage()
  const costAccounting = { assistantRows: 0, costRows: 0 }
  const writeCalls = []
  const writeResults = new Map()

  for (const entry of entries ?? []) {
    if (String(entry?.entryType || '').toUpperCase() !== 'MESSAGE') continue
    let payload
    try {
      payload = JSON.parse(entry.payloadJson)
    } catch {
      throw new ReliabilityError('model', `invalid MESSAGE payloadJson at entry ${entry?.entryId}`)
    }
    const message = payload?.message
    if (!message || !Array.isArray(message.contents)) continue
    if (message.role === 'ASSISTANT') {
      costAccounting.assistantRows++
      addAssistantMetadata(usage, payload.assistantMetadata, costAccounting)
      const text = message.contents
        .filter((content) => content?.type === 'text')
        .map((content) => String(content.text ?? ''))
        .join('')
      if (text) {
        assistantTexts.push(text)
        trace.push({
          entryId: String(entry.entryId),
          kind: 'assistant_text',
          text: summarizeString(text, 1000),
        })
      }
      for (const content of message.contents) {
        if (content?.type !== 'tool_call') continue
        const parsed = parseArguments(content.argumentsJson)
        const call = {
          entryId: String(entry.entryId),
          toolCallId: String(content.toolCallId || ''),
          toolName: String(content.toolName || ''),
          arguments: summarizeToolArguments(String(content.toolName || ''), parsed),
          argumentsValid: parsed.valid,
        }
        toolCalls.push(call)
        trace.push({ kind: 'tool_call', ...call })
        if (call.toolName === 'write') {
          writeCalls.push({
            toolCallId: call.toolCallId,
            valid: parsed.valid,
            path:
              typeof parsed.value?.path === 'string'
                ? summarizeString(parsed.value.path)
                : parsed.value?.path,
            workdir:
              typeof parsed.value?.workdir === 'string'
                ? summarizeString(parsed.value.workdir)
                : parsed.value?.workdir,
            content:
              typeof parsed.value?.content === 'string'
                ? {
                    byteLength: Buffer.byteLength(parsed.value.content),
                    sha256: sha256(parsed.value.content),
                  }
                : null,
          })
        }
      }
    } else if (message.role === 'TOOL') {
      for (const content of message.contents) {
        if (content?.type !== 'tool_result') continue
        const result = {
          entryId: String(entry.entryId),
          toolCallId: String(content.toolCallId || ''),
          toolName: String(content.toolName || ''),
          error: content.error === true,
          text: summarizeToolResultContents(content.contents),
          details: summarizeJsonString(content.detailsJson),
        }
        toolResults.push(result)
        trace.push({ kind: 'tool_result', ...result })
        if (result.toolName === 'write') writeResults.set(result.toolCallId, result)
      }
    }
  }

  const writeDiagnostics = writeCalls.map((call) => ({
    ...call,
    result: writeResults.get(call.toolCallId) ?? null,
  }))
  return {
    trace,
    toolCalls,
    toolResults,
    writeDiagnostics,
    finalText: assistantTexts.at(-1) ?? '',
    finalTextSummary: summarizeString(assistantTexts.at(-1) ?? '', 1200),
    usage,
    costKnown:
      costAccounting.assistantRows > 0
      && costAccounting.costRows === costAccounting.assistantRows,
  }
}

export function aggregateUsage(items) {
  const total = emptyUsage()
  for (const item of items) {
    const usage = item?.usage ?? item
    for (const field of TOKEN_FIELDS) total[field] += numberOrZero(usage?.[field])
    total.costTotal += numberOrZero(usage?.costTotal)
    total.assistantMessages += numberOrZero(usage?.assistantMessages)
  }
  return total
}

export function validateAnswerOracle(testCase, finalText) {
  if (!finalText.trim()) throw new ReliabilityError('model', 'assistant final text is empty')
  const requirements =
    testCase.oracle === 'pi-investigate'
      ? [
          ['buildContextEntries', /buildContextEntries/],
          [
            'packages/coding-agent/src/core/session-manager.ts',
            /packages\/coding-agent\/src\/core\/session-manager\.ts/,
          ],
          ['latest compaction', /latest compaction|last compaction|最新.*compaction/i],
          ['firstKeptEntryId', /firstKeptEntryId/],
          ['post-compaction suffix', /path\.slice\(\s*compactionIdx\s*\+\s*1\s*\)/],
        ]
      : testCase.oracle === 'pi-base-investigate'
        ? [
            ['createFindToolDefinition', /createFindToolDefinition/],
            ['src/find-tool.ts', /src\/find-tool\.ts/],
            ['--full-path', /--full-path/],
            ['**/ prefix', /\*\*\//],
            [
              'path.relative(searchPath, line)',
              /path\.relative\(\s*searchPath\s*,\s*line\s*\)/,
            ],
          ]
        : [
            [
              'target test result',
              testCase.anchor === 'pi'
                ? /test\/session-manager\/build-context\.test\.ts/
                : /tests\/find-tool-native\.test\.ts/,
            ],
            ['successful validation', /\bpass(?:ed)?\b|\bsuccess(?:ful(?:ly)?)?\b/i],
          ]
  const missing = requirements.filter(([, pattern]) => !pattern.test(finalText)).map(([name]) => name)
  if (missing.length) {
    throw new ReliabilityError('oracle', `final answer is missing required facts: ${missing.join(', ')}`)
  }
}

export function parsePorcelainStatus(output) {
  if (!output.trim()) return []
  return output
    .split(/\r?\n/)
    .filter(Boolean)
    .map((line) => {
      if (line.length < 4 || line[2] !== ' ') {
        throw new Error(`unsupported git status --porcelain line: ${line}`)
      }
      return { status: line.slice(0, 2), path: unquoteGitPath(line.slice(3)) }
    })
}

export function assertStatusAllowlist(output, allowlist, { required = [] } = {}) {
  const rows = parsePorcelainStatus(output)
  for (const row of rows) {
    const allowedStatuses = allowlist[row.path]
    if (!allowedStatuses || !allowedStatuses.includes(row.status)) {
      throw new ReliabilityError(
        'postcheck',
        `unexpected git status entry: ${row.status} ${row.path}`,
      )
    }
  }
  for (const path of required) {
    if (!rows.some((row) => row.path === path)) {
      throw new ReliabilityError('postcheck', `required git status entry is missing: ${path}`)
    }
  }
  return rows
}

export function summarizeString(value, limit = 500) {
  const text = String(value ?? '')
  if (text.includes(WRITE_PROOF_MARKER)) {
    return `[REDACTED_WRITE_PROOF bytes=${Buffer.byteLength(text)} sha256=${sha256(text)}]`
  }
  const normalized = text.replace(/\s+/g, ' ').trim()
  return normalized.length <= limit ? normalized : `${normalized.slice(0, limit)}…`
}

function validateWriteDiagnostics(diagnostics, testCase) {
  if (diagnostics.length === 0) {
    throw new ReliabilityError('model', 'required write tool call is missing')
  }
  const expectedPath = path.posix.join(testCase.casePath, WRITE_PROOF_PATH)
  const matching = diagnostics.find(
    (diagnostic) =>
      diagnostic.valid
      && resolveToolPath(diagnostic.path, diagnostic.workdir) === expectedPath
      && diagnostic.content?.byteLength === WRITE_PROOF_META.byteLength
      && diagnostic.content?.sha256 === WRITE_PROOF_META.sha256,
  )
  if (!matching) {
    throw new ReliabilityError(
      'model',
      'write tool arguments were damaged or did not contain the exact proof payload',
      { writeDiagnostics: diagnostics },
    )
  }
  if (!matching.result) {
    throw new ReliabilityError('tool', 'write tool call has no durable tool result')
  }
  if (matching.result.error) {
    throw new ReliabilityError('tool', 'write tool returned an error for intact arguments')
  }
}

function resolveToolPath(filePath, workdir) {
  if (typeof filePath !== 'string' || !filePath) return null
  const normalizedWorkdir = typeof workdir === 'string' ? normalizeToolPath(workdir) : null
  return normalizeToolPath(filePath, normalizedWorkdir)
}

function parseArguments(argumentsJson) {
  if (typeof argumentsJson !== 'string') {
    return { valid: false, value: null, byteLength: 0, sha256: sha256('') }
  }
  try {
    const value = JSON.parse(argumentsJson)
    if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('not object')
    return {
      valid: true,
      value,
      byteLength: Buffer.byteLength(argumentsJson),
      sha256: sha256(argumentsJson),
    }
  } catch {
    return {
      valid: false,
      value: null,
      byteLength: Buffer.byteLength(argumentsJson),
      sha256: sha256(argumentsJson),
    }
  }
}

function summarizeToolArguments(toolName, parsed) {
  if (!parsed.valid) {
    return {
      valid: false,
      jsonByteLength: parsed.byteLength,
      jsonSha256: parsed.sha256,
    }
  }
  const output = {}
  for (const [key, value] of Object.entries(parsed.value)) {
    if (toolName === 'write' && key === 'content' && typeof value === 'string') {
      output.content = { byteLength: Buffer.byteLength(value), sha256: sha256(value) }
    } else {
      output[key] = summarizeValue(value)
    }
  }
  return output
}

function summarizeValue(value) {
  if (typeof value === 'string') return summarizeString(value)
  if (Array.isArray(value)) return value.slice(0, 20).map(summarizeValue)
  if (value && typeof value === 'object') {
    return Object.fromEntries(
      Object.entries(value)
        .slice(0, 30)
        .map(([key, child]) => [key, summarizeValue(child)]),
    )
  }
  return value
}

function summarizeToolResultContents(contents) {
  if (!Array.isArray(contents)) return ''
  return summarizeString(
    contents
      .map((content) => {
        if (content?.type === 'text') return String(content.text ?? '')
        if (content?.type === 'resource') return summarizeResourceContent(content)
        return ''
      })
      .filter(Boolean)
      .join('\n'),
    800,
  )
}

function summarizeResourceContent(content) {
  if (typeof content.preview === 'string' && content.preview.length > 0) {
    return content.preview
  }
  const label =
    typeof content.name === 'string' && content.name.trim()
      ? summarizeString(content.name, 120)
      : typeof content.mediaType === 'string' && content.mediaType.trim()
        ? summarizeString(content.mediaType, 120)
        : 'resource'
  const size =
    Number.isSafeInteger(content.size) && content.size >= 0 ? ` bytes=${content.size}` : ''
  return `[Resource ${label}${size}]`
}

function summarizeJsonString(value) {
  if (typeof value !== 'string') return ''
  try {
    return summarizeValue(JSON.parse(value))
  } catch {
    return summarizeString(value)
  }
}

function addAssistantMetadata(total, metadata, costAccounting) {
  if (!metadata) return
  total.assistantMessages++
  for (const field of TOKEN_FIELDS) total[field] += numberOrZero(metadata.usage?.[field])
  const currency = metadata.cost?.currency
  if (currency != null && currency !== 'USD') {
    throw new ReliabilityError('model', `unexpected cost currency: ${currency}`)
  }
  if (currency === 'USD' && metadata.cost?.total != null) {
    total.costTotal += numberOrZero(metadata.cost.total)
    costAccounting.costRows++
  }
}

function emptyUsage() {
  return {
    inputTokens: 0,
    outputTokens: 0,
    cacheReadTokens: 0,
    cacheWriteTokens: 0,
    cacheWriteLongTokens: 0,
    reasoningTokens: 0,
    providerTotalTokens: 0,
    costTotal: 0,
    assistantMessages: 0,
  }
}

function numberOrZero(value) {
  const number = Number(value ?? 0)
  if (!Number.isFinite(number) || number < 0) {
    throw new ReliabilityError('model', `invalid non-negative usage/cost value: ${value}`)
  }
  return number
}

function normalizeShellWhitespace(value) {
  return value.trim().replace(/\s+/g, ' ')
}

function unquoteGitPath(value) {
  if (value.startsWith('"') && value.endsWith('"')) {
    try {
      return JSON.parse(value)
    } catch {
      return value
    }
  }
  return value
}
