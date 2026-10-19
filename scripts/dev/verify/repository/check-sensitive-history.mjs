#!/usr/bin/env node
/**
 * Audit the whole public Git history (and the working tree) for sensitive data.
 *
 * The current-tree gate in `check-sensitive-data.mjs` only sees files that exist
 * today, so it can never prove anything about what was committed earlier. This
 * entry reuses that gate's rules, adds a few broad credential shapes, and walks
 * every ref that is visible in the local repository:
 *
 * - reachable blobs (content is read once per unique blob through the git
 *   `--batch` protocol instead of once per commit; binary blobs are additionally
 *   reduced to printable ASCII/UTF-16 strings before scanning);
 * - the full content of every annotated tag object, including tag targets that are
 *   blobs or trees (which a commit walk alone would never reach);
 * - commit messages;
 * - file paths and ref names, with the stored location redacted when the path
 *   itself contains a match;
 * - optionally the public pull-request refs (`refs/pull/*`) pulled into an
 *   isolated temporary bare repository so the audited repository's refs stay
 *   untouched.
 *
 * Every read that could not complete is recorded as a degraded-scan item instead
 * of being skipped silently: a short `cat-file` body, a missing object, a
 * malformed header, a non-zero `cat-file` exit, an unreadable worktree file, an
 * unsupported ref target, or a remote-coverage check that cannot be verified.
 * `--fail-on-high` and `scan_incomplete` are never reported as success.
 *
 * Nothing here is a verdict. Every hit is a candidate pending human review: the
 * report recommends credential rotation only after a reviewer confirms a real
 * credential, and never infers key leakage from a personal absolute path. Values
 * never leave the scanner; value-level evidence is stored as a short hash plus
 * redacted locations so a reviewer can grade each group by hand. The remote URL is
 * reduced to the opaque label `origin`, so no query token, userinfo, host or
 * local path can reach a report.
 */

import { spawnSync } from 'node:child_process'
import { createHash } from 'node:crypto'
import {
  closeSync,
  lstatSync,
  mkdtempSync,
  openSync,
  readFileSync,
  readSync,
  rmSync,
  writeFileSync,
} from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { pathToFileURL } from 'node:url'
import { parseArgs } from 'node:util'

import {
  ALLOWED_USER_NAMES,
  PRIVATE_ENVIRONMENT_MARKERS,
  REPOSITORY_ROOT,
  RULES as BASE_RULES,
  gitPaths,
} from './check-sensitive-data.mjs'

export const DEFAULT_MAX_BLOB_BYTES = 2 * 1024 * 1024
export const DEFAULT_STRING_LIMIT = 256 * 1024
const MAX_GROUP_LOCATIONS = 25
const MAX_BINARY_OBJECTS = 50

/** A git subprocess exited non-zero while the caller required success. */
export class CalledProcessError extends Error {
  constructor(args, status) {
    super(`git ${args[0]} exited with ${status}`)
    this.name = 'CalledProcessError'
    this.returncode = status
  }
}

/** A caller supplied an invalid limit. */
export class ValueError extends Error {
  constructor(message) {
    super(message)
    this.name = 'ValueError'
  }
}

// Hosts that are *usually* harmless (reserved example domains and loopback
// aliases). They are only used to label a hit: a password on such a host can
// still be a real credential, so nothing here removes a finding. The label lets
// a reviewer grade the group instead of the tool deciding for them.
const RESERVED_HOST_NAMES = new Set(['localhost', '127.0.0.1', '0.0.0.0', '::1', 'host.docker.internal'])
const RESERVED_HOST_SUFFIXES = [
  'example.com',
  'example.org',
  'example.net',
  'example.test',
  'example',
  'test',
  'invalid',
  'localhost',
]

// Broad, lower-confidence shapes the formatted-token rules do not cover.
// Regexes are assembled from fragments so this file never matches its own rules.
const CREDENTIAL_URL_PATTERN = new RegExp(
  '[A-Za-z][A-Za-z0-9+.-]*' +
    '://' +
    '(?<userinfo>[^/@\\s:]+)' +
    ':' +
    '(?<password>[^/@\\s]+)' +
    '@' +
    '(?<host>[^/\\s:@?#]+)',
  'g',
)
// `key` after the name may be quoted (`"apiKey": "..."`) or camelCased
// (`sensitiveKey`/`clientSecret`/`accessToken`), so the quoted form and the
// bare `key`/`token` tails are both accepted; a missed key is worse than a
// noisy candidate, because every hit stays pending human review anyway. The
// separator only spans blanks, never a newline: otherwise a key name at the end
// of one line would be glued to the next line and reported as a fake "value".
const GENERIC_SECRET_KEYS = [
  'api[_-]?key',
  'apikey',
  'secret',
  'secret[_-]?key',
  'client[_-]?secret',
  'access[_-]?key',
  'access[_-]?token',
  'auth[_-]?token',
  'api[_-]?token',
  'refresh[_-]?token',
  'id[_-]?token',
  'private[_-]?key',
  'passwd',
  'password',
  'bearer',
  'token',
  'key',
]
const GENERIC_SECRET_PATTERN = new RegExp(
  '(?<key>' +
    GENERIC_SECRET_KEYS.join('|') +
    ')' +
    '[ \\t]*["\']?[ \\t]*[:=][ \\t]*' +
    '(?<quote>["\']?)' +
    '(?<value>[A-Za-z0-9_./+=\\-]{16,})' +
    '\\k<quote>',
  'gi',
)

const SUPPLEMENTARY_RULES = [
  ['credential-url', CREDENTIAL_URL_PATTERN],
  ['generic-secret', GENERIC_SECRET_PATTERN],
]

/** Ordered so reports are stable. Mutable on purpose: tests substitute synthetic rules. */
export const ALL_RULES = [...BASE_RULES, ...SUPPLEMENTARY_RULES]

// rule -> [category, confidence]. Confidence grades how much a hit means; it is
// never a verdict and never removes a hit from the report.
const CATEGORY_CONFIDENCE = {
  'private-key': ['private-key', 'high'],
  'aws-access-key': ['token', 'high'],
  'google-api-key': ['token', 'high'],
  'gitlab-token': ['token', 'high'],
  'github-token': ['token', 'high'],
  'slack-token': ['token', 'high'],
  'stripe-live-token': ['token', 'high'],
  jwt: ['token', 'high'],
  'slack-webhook': ['webhook', 'high'],
  'discord-webhook': ['webhook', 'high'],
  'telegram-webhook': ['webhook', 'high'],
  'dingtalk-webhook': ['webhook', 'high'],
  'feishu-webhook': ['webhook', 'high'],
  'wecom-webhook': ['webhook', 'high'],
  'private-environment': ['private-environment', 'high'],
  'personal-path': ['personal-path', 'medium'],
  'credential-url': ['credential-url', 'medium'],
  'generic-secret': ['generic-secret', 'medium'],
}

function categoryConfidence(rule) {
  return CATEGORY_CONFIDENCE[rule] ?? ['other', 'medium']
}

// Value-level grading states. Only a published example can be proven to be a
// non-credential; everything else stays "pending-review" until a human decides,
// and a personal path never implies key leakage.
export const STATUS_PUBLIC_EXAMPLE = 'known-public-example'
export const STATUS_PENDING = 'pending-review'
export const STATUS_PERSONAL_DATA = 'personal-data-exposure'
export const STATUS_ENVIRONMENT = 'environment-identifier'

// Values published in vendor documentation; assembled so this file stays clean.
const KNOWN_PUBLIC_EXAMPLES = new Set(['AKIA' + 'IOSFODNN7EXAMPLE'])

const PLACEHOLDER_HINT =
  /test|fake|example|local|dummy|unused|placeholder|sample|changeme|redact|sensitive|foo|bar|preview|only|candidate|invalid/i

// Necessary (not sufficient) lowercase substrings per rule: a rule whose marker
// is absent from a blob cannot match, so its regex is skipped. Lowercase makes
// each check a superset, so it can never hide a real match.
export const PREFILTERS = {
  'private-key': ['private key'],
  'aws-access-key': ['akia', 'asia'],
  'google-api-key': ['aiza'],
  'gitlab-token': ['glpat-', 'gldt-', 'glrt-', 'glft-', 'gloas-', 'glsoat-'],
  'github-token': ['ghp_', 'gho_', 'ghu_', 'ghs_', 'ghr_', 'github_pat_'],
  'slack-token': ['xox'],
  'stripe-live-token': ['sk_live_', 'rk_live_'],
  jwt: ['eyj'],
  'slack-webhook': ['hooks.slack.com'],
  'discord-webhook': ['discord'],
  'telegram-webhook': ['api.telegram.org'],
  'dingtalk-webhook': ['dingtalk'],
  'feishu-webhook': ['feishu', 'larksuite'],
  'wecom-webhook': ['qyapi'],
  'private-environment': PRIVATE_ENVIRONMENT_MARKERS.map((marker) => marker.toLowerCase()),
  'personal-path': ['/home', '/users', '\\users', '/mnt/', 'wsl.localhost'],
  'credential-url': ['://'],
  'generic-secret': ['api', 'key', 'secret', 'password', 'passwd', 'bearer', 'token'],
}

const ASCII_RUN = /[\x20-\x7e]{6,}/g
const UTF16_RUN = /(?:[\x20-\x7e]\x00){6,}/g

export const BATCH_EOF = Symbol('batch-eof')

function compare(left, right) {
  return left < right ? -1 : left > right ? 1 : 0
}

/**
 * Everything the scan could not read completely, as value-free rows.
 *
 * A degraded item never contains a matched value, a raw blob body or a remote
 * URL: only the surface, a machine-readable detail and (optionally) an object
 * id / redacted locator. An empty collector means "nothing was skipped"; it is
 * not a statement that the content is clean.
 */
export class ScanIssues {
  static MAX_ITEMS = 50

  constructor() {
    this.items = []
    this.omitted = 0
  }

  add(surface, detail, object = '') {
    if (this.items.length >= ScanIssues.MAX_ITEMS) {
      this.omitted += 1
      return
    }
    this.items.push({ surface, detail, object })
  }

  extend(other) {
    for (const item of other.items) {
      this.add(item.surface, item.detail, item.object)
    }
    this.omitted += other.omitted
  }

  asDict() {
    return {
      incomplete: this.items.length > 0 || this.omitted > 0,
      items: this.items,
      omitted: this.omitted,
    }
  }
}

/** True when a rule is graded high confidence (still not a verdict). */
export function isHigh(rule) {
  return categoryConfidence(rule)[1] === 'high'
}

/** True for hosts that are usually fixtures; used as a hint, not a filter. */
export function isReservedHost(host) {
  const colon = host.lastIndexOf(':')
  const bare = (colon >= 0 ? host.slice(0, colon) : host).replace(/^[[\]]+|[[\]]+$/g, '').toLowerCase()
  if (RESERVED_HOST_NAMES.has(bare)) {
    return true
  }
  return RESERVED_HOST_SUFFIXES.some((suffix) => bare === suffix || bare.endsWith('.' + suffix))
}

/**
 * Rule-specific allowlist; only shapes that cannot be real secrets pass.
 *
 * `credential-url` deliberately has no allowlist: a password on a reserved
 * host is still a candidate, so the host only becomes a group hint.
 */
function isAllowlisted(ruleName, match) {
  if (ruleName === 'personal-path') {
    return ALLOWED_USER_NAMES.has(match.groups.user)
  }
  return false
}

/** Grade one matched value without ever exposing it: `[status, hint]`. */
export function classifyValue(rule, value, hint = '') {
  if (rule === 'personal-path') {
    return [STATUS_PERSONAL_DATA, '']
  }
  if (rule === 'private-environment') {
    return [STATUS_ENVIRONMENT, '']
  }
  if (KNOWN_PUBLIC_EXAMPLES.has(value)) {
    return [STATUS_PUBLIC_EXAMPLE, 'published-vendor-example']
  }
  const hints = hint ? [hint] : []
  const placeholder = PLACEHOLDER_HINT.exec(value)
  if (placeholder) {
    hints.push(placeholder[0].toLowerCase())
  }
  return [STATUS_PENDING, hints.join(',')]
}

export const LOCATOR_CACHE = new Map()

/**
 * Return the path with every rule match masked by its rule name.
 *
 * Overlapping or touching spans are merged into one union before masking: with
 * a "skip the overlapping span" rule the tail of the dropped span would stay
 * visible in the report.
 */
export function redactedLocator(filePath) {
  const cached = LOCATOR_CACHE.get(filePath)
  if (cached !== undefined) {
    return cached
  }
  const spans = []
  for (const [ruleName, pattern] of ALL_RULES) {
    for (const match of filePath.matchAll(pattern)) {
      if (!isAllowlisted(ruleName, match)) {
        spans.push([match.index, match.index + match[0].length, ruleName])
      }
    }
  }
  if (spans.length === 0) {
    LOCATOR_CACHE.set(filePath, filePath)
    return filePath
  }
  spans.sort((a, b) => a[0] - b[0] || a[1] - b[1] || compare(a[2], b[2]))
  const merged = []
  for (const [start, end, ruleName] of spans) {
    const last = merged[merged.length - 1]
    if (last && start <= last[1]) {
      last[1] = Math.max(last[1], end)
      last[2].add(ruleName)
    } else {
      merged.push([start, end, new Set([ruleName])])
    }
  }
  const parts = []
  let cursor = 0
  for (const [start, end, rules] of merged) {
    parts.push(filePath.slice(cursor, start))
    parts.push('<redacted:' + [...rules].sort().join('+') + '>')
    cursor = end
  }
  parts.push(filePath.slice(cursor))
  const locator = parts.join('')
  LOCATOR_CACHE.set(filePath, locator)
  return locator
}

/** Value-level evidence: sha256 group + redacted locations, never the value. */
export class ValueGroups {
  constructor() {
    this.groups = new Map()
  }

  add(rule, value, locator, line, hint = '') {
    const digest = createHash('sha256').update(value, 'utf8').digest('hex').slice(0, 12)
    const key = `${rule}\0${digest}`
    let group = this.groups.get(key)
    if (group === undefined) {
      const [status, hintText] = classifyValue(rule, value, hint)
      const [category, confidence] = categoryConfidence(rule)
      group = {
        rule,
        category,
        confidence,
        value_sha256_12: digest,
        status,
        hint: hintText,
        count: 0,
        locations: [],
      }
      this.groups.set(key, group)
    }
    group.count += 1
    if (group.locations.length < MAX_GROUP_LOCATIONS) {
      group.locations.push({ path: locator, line })
    }
  }

  render() {
    return [...this.groups.values()]
      .sort(
        (a, b) =>
          compare(a.status, b.status) ||
          compare(a.rule, b.rule) ||
          compare(a.value_sha256_12, b.value_sha256_12),
      )
      .map((group) => ({ ...group, locations_truncated: group.count > group.locations.length }))
  }
}

/** A redacted location for one sensitive-data match; carries no value. */
export class HistoryFinding {
  constructor(rule, object, commit, filePath, line) {
    this.rule = rule
    this.object = object
    this.commit = commit
    this.path = filePath
    this.line = line
  }

  get category() {
    return categoryConfidence(this.rule)[0]
  }

  get confidence() {
    return categoryConfidence(this.rule)[1]
  }

  get key() {
    return [this.rule, this.object, this.commit, this.path, this.line].join('\0')
  }

  asDict() {
    return {
      rule: this.rule,
      category: this.category,
      object: this.object,
      commit: this.commit,
      path: this.path,
      line: this.line,
    }
  }
}

function git(root, args, { check = true, cwd = root } = {}) {
  const completed = spawnSync('git', args, {
    cwd,
    maxBuffer: 1 << 30,
    stdio: ['ignore', 'pipe', 'pipe'],
  })
  if (completed.error) {
    throw completed.error
  }
  if (check && completed.status !== 0) {
    throw new CalledProcessError(args, completed.status)
  }
  return { returncode: completed.status, stdout: completed.stdout }
}

function text(buffer) {
  return buffer.toString('utf8')
}

/** Every visible ref, plus a synthetic HEAD entry when it resolves. */
export function listRefs(root) {
  const out = text(git(root, ['for-each-ref', '--format=%(objectname)%00%(objecttype)%00%(refname)']).stdout)
  const refs = []
  for (const line of out.split('\n')) {
    if (!line.trim()) {
      continue
    }
    const [objectname, objecttype, ...rest] = line.split('\0')
    refs.push({ name: rest.join('\0'), objectname, objecttype })
  }
  const head = git(root, ['rev-parse', '--verify', '--quiet', 'HEAD'], { check: false })
  if (head.returncode === 0) {
    refs.push({ name: 'HEAD', objectname: text(head.stdout).trim(), objecttype: 'commit' })
  }
  return refs
}

function isShallow(root) {
  return text(git(root, ['rev-parse', '--is-shallow-repository']).stdout).trim() === 'true'
}

/** Resolve a ref/object to a commit id, or null for non-commit refs. */
export function peelCommit(root, revision) {
  const completed = git(root, ['rev-parse', '--verify', '--quiet', revision + '^{commit}'], { check: false })
  if (completed.returncode !== 0) {
    return null
  }
  return text(completed.stdout).trim()
}

/** Offsets of every line start, for match-offset to line-number mapping. */
function lineStarts(content) {
  const starts = [0]
  let index = content.indexOf('\n')
  while (index !== -1) {
    starts.push(index + 1)
    index = content.indexOf('\n', index + 1)
  }
  return starts
}

/** Number of line starts at or before `offset` (the 1-based line number). */
function lineNumberAt(starts, offset) {
  let low = 0
  let high = starts.length
  while (low < high) {
    const mid = (low + high) >> 1
    if (starts[mid] <= offset) {
      low = mid + 1
    } else {
      high = mid
    }
  }
  return low
}

/**
 * The identifier token ending right before `index` (used for group hints).
 *
 * Hints never remove a candidate; they only tell a reviewer which key name was
 * matched, so `ariaKey`/`labelKey` style matches can be triaged instead of
 * being filtered away automatically.
 */
export function identifierBefore(content, index, limit = 32) {
  let start = index
  while (start > 0 && /[\p{L}\p{N}_-]/u.test(content[start - 1])) {
    start -= 1
  }
  return content.slice(start, index).slice(-limit)
}

/**
 * Return redacted findings for one text; matched values are discarded.
 *
 * Each rule runs once over the whole text, then a match offset is mapped to a
 * line by binary search. `groups` (when given) receives value-level evidence
 * keyed by a value hash, so grading never needs the raw value in the report.
 */
export function findingsInText(content, filePath, commit = '', object = '', groups = null) {
  const findings = []
  const reported = new Set()
  const starts = lineStarts(content)
  const lowered = content.toLowerCase()
  for (const [ruleName, pattern] of ALL_RULES) {
    const required = PREFILTERS[ruleName]
    if (required !== undefined && !required.some((marker) => lowered.includes(marker))) {
      continue
    }
    for (const match of content.matchAll(pattern)) {
      if (isAllowlisted(ruleName, match)) {
        continue
      }
      const lineNumber = lineNumberAt(starts, match.index)
      if (groups !== null) {
        const hints = []
        if (ruleName === 'credential-url' && isReservedHost(match.groups.host)) {
          hints.push('reserved-host')
        }
        if (ruleName === 'generic-secret') {
          const identifier = identifierBefore(content, match.index) + match.groups.key
          hints.push('key-name:' + identifier.toLowerCase())
        }
        groups.add(
          ruleName,
          match[0],
          filePath,
          lineNumber,
          hints.filter((hint) => !hint.endsWith(':')).join(','),
        )
      }
      const key = `${ruleName}\0${lineNumber}`
      if (reported.has(key)) {
        continue
      }
      reported.add(key)
      findings.push(new HistoryFinding(ruleName, object, commit, filePath, lineNumber))
    }
  }
  return findings
}

/** Reduce a binary blob to printable ASCII/UTF-16 runs for candidate checks. */
export function extractStrings(content, limit) {
  const raw = content.toString('latin1')
  const chunks = [...raw.matchAll(ASCII_RUN)].map((match) => match[0])
  chunks.push(...[...raw.matchAll(UTF16_RUN)].map((match) => Buffer.from(match[0], 'latin1').toString('utf16le')))
  return chunks.join('\n').slice(0, limit)
}

function splitBuffer(buffer, separator) {
  const parts = []
  let start = 0
  for (;;) {
    const index = buffer.indexOf(separator, start)
    if (index === -1) {
      parts.push(buffer.subarray(start))
      return parts
    }
    parts.push(buffer.subarray(start, index))
    start = index + 1
  }
}

/** Ordered set of `[commit, path]` pairs keyed by their joined form. */
function addLocation(locations, oid, commit, filePath) {
  let pairs = locations.get(oid)
  if (pairs === undefined) {
    pairs = new Map()
    locations.set(oid, pairs)
  }
  pairs.set(`${commit}\0${filePath}`, [commit, filePath])
}

function sortedPairs(locations, oid) {
  return [...(locations.get(oid)?.values() ?? [])].sort(
    (a, b) => compare(a[0], b[0]) || compare(a[1], b[1]),
  )
}

/**
 * Map every reachable blob to the introducing (commit, path) pairs.
 *
 * `git log --raw` reports each blob once per commit that actually changed
 * it, so the union over every commit covers all reachable content without
 * re-reading trees per commit. Only commit-reachable refs are passed in: a ref
 * that points at a blob or a tree would make `git log` fail, and those
 * targets are enumerated separately by `refTargetBlobs`.
 */
export function blobLocations(root, revisions) {
  const locations = new Map()
  if (revisions.length === 0) {
    return locations
  }
  const completed = git(root, [
    'log',
    '-m',
    '--raw',
    '--root',
    '--no-renames',
    '--no-abbrev',
    '--format=%x00COMMIT%x00%H',
    '-z',
    ...revisions,
  ])
  const tokens = splitBuffer(completed.stdout, 0)
  let commit = ''
  let index = 0
  while (index < tokens.length) {
    const token = tokens[index]
    if (token.toString('latin1') === 'COMMIT') {
      commit = tokens[index + 1].toString('latin1')
      index += 2
      continue
    }
    const meta = token.toString('latin1').replace(/^[\r\n]+/, '')
    if (meta.startsWith(':') && index + 1 < tokens.length) {
      const fields = meta.split(/\s+/).filter(Boolean)
      const filePath = text(tokens[index + 1])
      index += 2
      if (fields.length >= 5) {
        const [, dstMode, , dstSha, status] = [fields[0], fields[1], fields[2], fields[3], fields[4]]
        if (
          ['100644', '100755', '120000'].includes(dstMode) &&
          status !== 'D' &&
          !/^0+$/.test(dstSha)
        ) {
          addLocation(locations, dstSha, commit, filePath)
        }
      }
      continue
    }
    index += 1
  }
  return locations
}

/** Resolve a revision through tag chains to `[oid, objecttype]`. */
function peeledObject(root, revision) {
  const completed = git(root, ['rev-parse', '--verify', '--quiet', revision + '^{}'], { check: false })
  if (completed.returncode !== 0 || !text(completed.stdout).trim()) {
    return [null, null]
  }
  const oid = text(completed.stdout).trim()
  const typed = git(root, ['cat-file', '-t', oid], { check: false })
  if (typed.returncode !== 0) {
    return [oid, null]
  }
  return [oid, text(typed.stdout).trim()]
}

/** Every blob reachable from a tree-ish, as `[oid, path]` pairs. */
function treeBlobs(root, treeish) {
  const completed = git(root, ['ls-tree', '-r', '-z', treeish])
  const pairs = []
  for (const record of splitBuffer(completed.stdout, 0)) {
    if (!record.toString('latin1').trim()) {
      continue
    }
    const tab = record.indexOf(9)
    const meta = record.subarray(0, tab === -1 ? record.length : tab).toString('latin1')
    const filePath = tab === -1 ? '' : text(record.subarray(tab + 1))
    const fields = meta.split(/\s+/).filter(Boolean)
    if (fields.length >= 3 && fields[1] === 'blob') {
      pairs.push([fields[2], filePath])
    }
  }
  return pairs
}

/**
 * Cover refs whose target is not a commit (tags to blobs or trees).
 *
 * A commit walk never reaches these objects, so they would otherwise be
 * counted as covered while nothing was read. Anything that cannot be resolved
 * or enumerated is recorded as a degraded-scan item.
 */
function refTargetBlobs(root, refs, issues) {
  const locations = new Map()
  for (const ref of refs) {
    if (ref.name.startsWith('refs/remotes/') || ref.name === 'HEAD') {
      continue
    }
    if (peelCommit(root, ref.name)) {
      continue
    }
    const [oid, objectType] = peeledObject(root, ref.name)
    if (oid === null) {
      issues.add('ref', 'unresolvable-ref', ref.name)
      continue
    }
    if (objectType === 'blob') {
      addLocation(locations, oid, '', `<ref:${ref.name}>`)
    } else if (objectType === 'tree') {
      for (const [blobOid, filePath] of treeBlobs(root, oid)) {
        addLocation(locations, blobOid, '', `<ref:${ref.name}>/${filePath}`)
      }
    } else if (objectType !== 'commit') {
      issues.add('ref', `unsupported-ref-target:${objectType}`, ref.name)
    }
  }
  return locations
}

/** Sequential byte reader over an in-memory buffer or an open file descriptor. */
export class ByteReader {
  constructor(source) {
    this.fd = typeof source === 'number' ? source : null
    this.buffer = this.fd === null ? source : Buffer.alloc(1 << 16)
    this.start = 0
    this.end = this.fd === null ? source.length : 0
  }

  fill() {
    if (this.fd === null) {
      return false
    }
    if (this.start > 0) {
      this.buffer.copy(this.buffer, 0, this.start, this.end)
      this.end -= this.start
      this.start = 0
    }
    if (this.end === this.buffer.length) {
      const grown = Buffer.alloc(this.buffer.length * 2)
      this.buffer.copy(grown, 0, 0, this.end)
      this.buffer = grown
    }
    const count = readSync(this.fd, this.buffer, this.end, this.buffer.length - this.end, null)
    this.end += count
    return count > 0
  }

  /** One line including its trailing newline; empty at end of stream. */
  readline() {
    for (;;) {
      const index = this.buffer.indexOf(10, this.start)
      if (index !== -1 && index < this.end) {
        const line = Buffer.from(this.buffer.subarray(this.start, index + 1))
        this.start = index + 1
        return line
      }
      if (!this.fill()) {
        const rest = Buffer.from(this.buffer.subarray(this.start, this.end))
        this.start = this.end
        return rest
      }
    }
  }

  /** Up to `size` bytes; shorter only at end of stream. */
  read(size) {
    if (this.start === this.end && !this.fill()) {
      return Buffer.alloc(0)
    }
    const count = Math.min(size, this.end - this.start)
    const piece = Buffer.from(this.buffer.subarray(this.start, this.start + count))
    this.start += count
    return piece
  }
}

/** Read exactly `size` bytes; a short body is recorded, never ignored. */
function readExactly(stream, size, { keep = true, issues = null, objective = '' } = {}) {
  const chunks = []
  let remaining = size
  while (remaining > 0) {
    const piece = stream.read(Math.min(remaining, 1 << 16))
    if (piece.length === 0) {
      break
    }
    if (keep) {
      chunks.push(piece)
    }
    remaining -= piece.length
  }
  if (remaining > 0 && issues !== null) {
    issues.add('blob', `short-read missing=${remaining} of=${size}`, objective)
  }
  return keep ? Buffer.concat(chunks) : Buffer.alloc(0)
}

/**
 * Read one `git cat-file --batch` record, or `BATCH_EOF` at end of stream.
 *
 * Returns an object with `oid`/`type`/`size`/`content`/`tooLarge`. Every anomaly
 * (missing object, malformed header, short body, missing record terminator) is
 * recorded on `issues` and yields `content: null` so the caller cannot mistake
 * it for an empty blob.
 */
export function readBatchEntry(stream, issues, maxBlobBytes) {
  const header = stream.readline()
  if (header.length === 0) {
    return BATCH_EOF
  }
  const parts = header
    .toString('latin1')
    .replace(/\n$/, '')
    .split(/\s+/)
    .filter(Boolean)
  const oid = parts.length > 0 ? parts[0] : ''
  if (parts.length === 2 && parts[1] === 'missing') {
    issues.add('object', 'missing-object', oid)
    return { oid, type: 'missing', size: 0, content: null, tooLarge: false }
  }
  if (parts.length < 3 || !/^[0-9]+$/.test(parts[2])) {
    issues.add('object', 'malformed-header', oid)
    return { oid, type: 'malformed', size: 0, content: null, tooLarge: false }
  }
  const objectType = parts[1]
  const size = Number.parseInt(parts[2], 10)
  const tooLarge = objectType === 'blob' && size > maxBlobBytes
  const content = readExactly(stream, size, { keep: !tooLarge, issues, objective: oid })
  const terminator = stream.read(1)
  if (terminator.length > 0 && terminator[0] !== 10) {
    issues.add('object', 'missing-record-terminator', oid)
  }
  return { oid, type: objectType, size, content, tooLarge }
}

/** Stream every unique blob once; return findings, groups, counters, issues. */
export function scanBlob(root, blobIds, locations, maxBlobBytes, stringLimit) {
  const stats = { tooLarge: 0, binary: 0, binaryStringsScanned: 0, lfs: 0, scanned: 0, binaryBytes: 0 }
  const issues = new ScanIssues()
  const findings = []
  const groups = new ValueGroups()
  const binaryObjects = []
  if (blobIds.length === 0) {
    // No reachable blob at all: nothing to stream, and an empty batch input
    // would otherwise look like a malformed record.
    return { findings, groups, binaryObjects, stats, issues }
  }
  // git writes the whole batch to a scratch file that is then read back through
  // `ByteReader`, so memory stays bounded by one blob regardless of repository size.
  const scratch = mkdtempSync(path.join(tmpdir(), 'kk-studio-batch-'))
  try {
    const listPath = path.join(scratch, 'ids')
    const outputPath = path.join(scratch, 'objects')
    writeFileSync(listPath, blobIds.join('\n') + '\n', 'ascii')
    const stdin = openSync(listPath, 'r')
    const stdout = openSync(outputPath, 'w')
    let completed
    try {
      // stderr is dropped, not captured: git's stderr text could carry paths we must not store.
      completed = spawnSync('git', ['cat-file', '--batch'], {
        cwd: root,
        stdio: [stdin, stdout, 'ignore'],
      })
    } finally {
      closeSync(stdin)
      closeSync(stdout)
    }
    if (completed.error) {
      throw completed.error
    }
    const fd = openSync(outputPath, 'r')
    try {
      const stream = new ByteReader(fd)
      for (;;) {
        const entry = readBatchEntry(stream, issues, maxBlobBytes)
        if (entry === BATCH_EOF) {
          break
        }
        if (entry.content === null) {
          continue
        }
        if (entry.type !== 'blob') {
          issues.add('object', `unexpected-object-type:${entry.type}`, entry.oid)
          continue
        }
        if (entry.tooLarge) {
          stats.tooLarge += 1
          continue
        }
        const { content, size, oid } = entry
        let body
        if (content.includes(0)) {
          stats.binary += 1
          stats.binaryBytes += size
          body = extractStrings(content, stringLimit)
          stats.binaryStringsScanned += 1
          binaryObjects.push({
            object: oid,
            size,
            paths: [...(locations.get(oid)?.values() ?? [])]
              .map(([, filePath]) => redactedLocator(filePath))
              .slice(0, 3),
          })
        } else if (content.subarray(0, 64).toString('latin1').startsWith('version https://git-lfs.github.com/spec/')) {
          stats.lfs += 1
          continue
        } else {
          stats.scanned += 1
          body = text(content)
        }
        if (!body) {
          continue
        }
        const blobPaths = sortedPairs(locations, oid)
        // Group evidence records the first known location; the full
        // per-location rows come from the finding re-attribution below.
        const firstLocator = blobPaths.length > 0 ? redactedLocator(blobPaths[0][1]) : '<blob>'
        const blobFindings = findingsInText(body, firstLocator, '', oid, groups)
        if (blobFindings.length === 0) {
          continue
        }
        const recorded = new Set()
        for (const [commit, filePath] of blobPaths) {
          const locator = redactedLocator(filePath)
          for (const finding of blobFindings) {
            const candidate = new HistoryFinding(finding.rule, oid, commit, locator, finding.line)
            if (!recorded.has(candidate.key)) {
              recorded.add(candidate.key)
              findings.push(candidate)
            }
          }
        }
      }
    } finally {
      closeSync(fd)
    }
    if (completed.status !== 0) {
      issues.add('object', `cat-file-exit ${completed.status}`)
    }
  } finally {
    rmSync(scratch, { recursive: true, force: true })
  }
  return { findings, groups, binaryObjects, stats, issues }
}

/** Full message body of an annotated tag object (headers stripped). */
function tagMessage(root, objectname, issues, refname) {
  const completed = git(root, ['cat-file', 'tag', objectname], { check: false })
  if (completed.returncode !== 0) {
    issues.add('tag', `cat-file-tag-exit ${completed.returncode}`, refname)
    return null
  }
  const raw = completed.stdout
  const separator = raw.indexOf('\n\n')
  return (separator === -1 ? '' : text(raw.subarray(separator + 2))).replace(/\n+$/, '')
}

/** `[objectname, refname]` for every annotated tag object. */
function annotatedTags(root, issues) {
  const out = text(git(root, ['for-each-ref', '--format=%(objecttype)%00%(objectname)%00%(refname)', 'refs/tags']).stdout)
  const tags = []
  for (const line of out.split('\n')) {
    if (!line.trim()) {
      continue
    }
    const [objectType, ...remainder] = line.split('\0')
    const [objectname, ...refParts] = remainder
    const refname = refParts.join('\0')
    if (objectType !== 'tag') {
      continue
    }
    if (!objectname || !refname) {
      issues.add('tag', 'malformed-tag-record', refname)
      continue
    }
    tags.push([objectname, refname])
  }
  return tags
}

function revList(root, ref) {
  const commit = peelCommit(root, ref.name)
  if (!commit) {
    return []
  }
  return text(git(root, ['rev-list', commit]).stdout).split(/\s+/).filter(Boolean)
}

/** Scan all reachable content, messages, tag objects, paths and ref names. */
export function scanHistory(root, refs, maxBlobBytes, stringLimit = DEFAULT_STRING_LIMIT) {
  const issues = new ScanIssues()
  const commitRefs = refs.filter((ref) => peelCommit(root, ref.name)).map((ref) => ref.name)
  const locations = blobLocations(root, commitRefs)
  const realPaths = new Set()
  for (const pairs of locations.values()) {
    for (const [, filePath] of pairs.values()) {
      realPaths.add(filePath)
    }
  }
  // Refs whose target is a blob or a tree are unreachable from a commit walk.
  const extraLocations = refTargetBlobs(root, refs, issues)
  for (const [blobOid, pairs] of extraLocations) {
    for (const [commit, filePath] of pairs.values()) {
      addLocation(locations, blobOid, commit, filePath)
    }
  }

  const scanned = scanBlob(root, [...locations.keys()].sort(), locations, maxBlobBytes, stringLimit)
  const { groups, binaryObjects, stats } = scanned
  issues.extend(scanned.issues)
  const findings = [...scanned.findings]

  // Paths themselves can carry a leaked token or a personal absolute path;
  // the stored locator masks any match so the report never echoes a filename.
  for (const filePath of [...realPaths].sort()) {
    findings.push(...findingsInText(filePath, redactedLocator(filePath), '', '<path>', groups))
  }

  // Commit messages (explicit revisions so a detached HEAD is covered too).
  let commitCount = 0
  if (commitRefs.length > 0) {
    const log = git(root, ['log', '--format=%x01%H%x00%B', ...commitRefs]).stdout
    for (const chunk of splitBuffer(log, 1)) {
      if (!chunk.toString('latin1').trim()) {
        continue
      }
      const nul = chunk.indexOf(0)
      const sha = chunk.subarray(0, nul === -1 ? chunk.length : nul).toString('latin1')
      const body = nul === -1 ? '' : text(chunk.subarray(nul + 1))
      commitCount += 1
      findings.push(...findingsInText(body.replace(/\n+$/, ''), '<commit-message>', sha, sha, groups))
    }
  }

  // Annotated tag objects: read the whole object, not the first output line.
  let tagMessageCount = 0
  for (const [objectname, refname] of annotatedTags(root, issues)) {
    const message = tagMessage(root, objectname, issues, refname)
    if (message === null) {
      continue
    }
    tagMessageCount += 1
    findings.push(...findingsInText(message, '<tag-message>', '', objectname, groups))
  }

  // Ref names are reported through an opaque locator, never verbatim.
  for (const ref of refs) {
    findings.push(...findingsInText(ref.name, '<ref-name>', '', ref.objectname, groups))
  }

  const reachable = new Set()
  for (const ref of refs) {
    for (const sha of revList(root, ref)) {
      reachable.add(sha)
    }
  }
  const outStats = {
    ...stats,
    commits: reachable.size,
    commitMessages: commitCount,
    tagMessages: tagMessageCount,
    paths: realPaths.size,
    uniqueBlobs: locations.size,
    nonCommitRefs: extraLocations.size,
  }
  return { findings, groups, binaryObjects, stats: outStats, issues }
}

function readErrorName(error) {
  if (error.code === 'EACCES' || error.code === 'EPERM') {
    return 'PermissionError'
  }
  if (error.code === 'ENOENT') {
    return 'FileNotFoundError'
  }
  if (error.code === 'EISDIR') {
    return 'IsADirectoryError'
  }
  return 'OSError'
}

/** Reuse the current-tree gate's file discovery with the combined rules. */
export function scanWorktree(root) {
  const findings = []
  const groups = new ValueGroups()
  const issues = new ScanIssues()
  for (const relativePath of gitPaths(root)) {
    const filePath = path.join(root, relativePath)
    const locator = redactedLocator(relativePath)
    findings.push(...findingsInText(relativePath, locator, '', '<path>', groups))
    const stat = lstatSync(filePath, { throwIfNoEntry: false })
    if (!stat || stat.isSymbolicLink() || !stat.isFile()) {
      continue
    }
    let raw
    try {
      raw = readFileSync(filePath)
    } catch (error) {
      // An unreadable file is a coverage gap, never a silent skip.
      issues.add('worktree', `read-failed:${readErrorName(error)}`, locator)
      continue
    }
    const body = raw.includes(0) ? extractStrings(raw, DEFAULT_STRING_LIMIT) : text(raw)
    findings.push(...findingsInText(body, locator, '', '<worktree>', groups))
  }
  return { findings, groups, issues }
}

function dedupe(findings) {
  const seen = new Set()
  const unique = []
  for (const finding of findings) {
    if (seen.has(finding.key)) {
      continue
    }
    seen.add(finding.key)
    unique.push(finding)
  }
  return unique
}

function counts(findings) {
  const result = {}
  for (const finding of findings) {
    result[finding.rule] ??= { category: finding.category, confidence: finding.confidence, count: 0 }
    result[finding.rule].count += 1
  }
  return result
}

/** Aggregate value groups by status/confidence for the report header. */
function groupSummary(groups) {
  const summary = {}
  for (const group of groups) {
    summary[group.status] ??= { groups: 0, occurrences: 0, confidence: group.confidence }
    summary[group.status].groups += 1
    summary[group.status].occurrences += group.count
  }
  return summary
}

function section(findings, groups) {
  const unique = dedupe(findings)
  return {
    counts: counts(unique),
    groups: groupSummary(groups),
    value_groups: groups,
    findings: unique
      .sort(
        (a, b) =>
          compare(a.rule, b.rule) ||
          compare(a.path, b.path) ||
          a.line - b.line ||
          compare(a.commit, b.commit),
      )
      .map((finding) => finding.asDict()),
  }
}

/** Redacted markdown table over report dicts (rule/object/commit/path/line). */
function table(findings) {
  const rows = findings.map(
    (finding) =>
      `| ${finding.rule} | ${finding.category} | ${finding.object || '-'} | ${
        finding.commit ? finding.commit.slice(0, 12) : '-'
      } | ${finding.path} | ${finding.line} |`,
  )
  if (rows.length === 0) {
    return '_no findings_'
  }
  return [
    '| rule | category | object | commit | path | line |',
    '| --- | --- | --- | --- | --- | --- |',
    ...rows,
  ].join('\n')
}

/** Compact (rule, status) aggregation used for the remote PR section. */
function statusRollup(groups) {
  const buckets = new Map()
  for (const group of groups) {
    const key = `${group.rule}\0${group.status}`
    const entry = buckets.get(key) ?? { rule: group.rule, status: group.status, groups: 0, occurrences: 0 }
    entry.groups += 1
    entry.occurrences += group.count
    buckets.set(key, entry)
  }
  return [...buckets.values()].sort((a, b) => compare(a.rule, b.rule) || compare(a.status, b.status))
}

function groupTable(groups) {
  const rows = groups.map((group) => {
    const location = group.locations.length > 0 ? group.locations[0] : { path: '-', line: '-' }
    return `| ${group.rule} | ${group.status} | ${group.value_sha256_12} | ${group.count} | ${
      group.hint || '-'
    } | ${location.path}:${location.line} |`
  })
  if (rows.length === 0) {
    return '_no value groups_'
  }
  return [
    '| rule | status | value-sha256(12) | count | hint | first location |',
    '| --- | --- | --- | --- | --- | --- |',
    ...rows,
  ].join('\n')
}

/** Number of distinct value groups in a (confidence, status) bucket. */
function countGroups(sectionData, confidence, status) {
  return sectionData.value_groups.filter(
    (group) => group.confidence === confidence && group.status === status,
  ).length
}

export function renderMarkdown(report) {
  const { coverage, limits, history, worktree, degraded } = report
  const remoteCheck = coverage.remote
  const pending = history.value_groups.filter((group) => group.status === STATUS_PENDING)
  const lines = [
    '# 仓库敏感信息扫描（当前树 + 全历史）',
    '',
    '本报告由 `scripts/dev/verify/repository/check-sensitive-history.mjs` 生成，只记录',
    '`rule/category/object/commit/path/line`、值哈希与统计；匹配到的值绝不写入报告，',
    '文件名或引用名自身含匹配值时改存脱敏 Locator。报告写在私有 workspace，不进公开代码。',
    '',
    '## 结论（自动分级，非裁决）',
    '',
    '- 所有自动命中都是**待人工确认的候选**，不是结论。',
    '- 仅当人工确认存在**真实凭据**时，才建议轮换该凭据，并在授权后清理历史；本工具不轮换、',
    '  不改写、不推送。',
    '- 个人绝对路径与内部环境标识属于信息暴露，**不能据此推断密钥泄漏**。',
    `- 历史高置信度候选待核查值组：${countGroups(history, 'high', STATUS_PENDING)} 组；`,
    `  已证明为公开示例：${countGroups(history, 'high', STATUS_PUBLIC_EXAMPLE)} 组。`,
    `- 历史中置信度（通用/凭据 URL）待核查值组：${pending.length} 组，累计 ` +
      `${pending.reduce((sum, group) => sum + group.count, 0)} 处（按唯一 blob/消息去重）；未证明为夹具的一律列在“值分级”中。`,
    `- 扫描完整性：scan_incomplete=${titleBool(degraded.incomplete)}` +
      `（未读全项 ${degraded.items.length}，省略 ${degraded.omitted}）；` +
      'incomplete=true 时不得视为“已覆盖全部内容”。',
    '',
    '## 覆盖范围（coverage）',
    '',
    `- shallow 仓库：${titleBool(coverage.shallow)}`,
    `- refs 总数：${coverage.refs_total}` +
      `（heads=${coverage.refs_heads}, remotes=${coverage.refs_remotes},` +
      ` tags=${coverage.refs_tags}, other=${coverage.refs_other}）`,
    `- 本地 origin 跟踪 refs：${remoteCheck.tracking_refs}` +
      `；远端覆盖核对：${remoteCheck.status}` +
      `（heads=${remoteCheck.heads}, tags=${remoteCheck.tags},` +
      ` 本地缺失=${remoteCheck.absent_locally}, OID 不一致=${remoteCheck.oid_mismatch}）`,
    `- HEAD commit：\`${coverage.head_commit}\``,
    `- 可达 commit 数：${coverage.commits}`,
    `- 唯一可达 blob 数：${coverage.unique_blobs}` +
      `（文本 ${coverage.blobs_scanned}，二进制 ${coverage.blobs_binary}` +
      `（${coverage.binary_bytes} bytes，已做 strings 候选扫描 ${coverage.binary_strings_scanned}），` +
      `超大 ${coverage.blobs_too_large}，LFS 指针 ${coverage.blobs_lfs}）`,
    `- 扫描 commit message 数：${coverage.commit_messages}；annotated tag message 数：${coverage.tag_messages}`,
    `- 非 commit 目标的 ref 数：${coverage.non_commit_refs}（tag 指向 blob/tree 时按可达对象扫描）`,
    `- 扫描路径数：${coverage.paths}；扫描 ref 名数：${coverage.refnames}`,
    `- 扫描耗时：${coverage.duration_seconds.toFixed(2)}s`,
  ]
  const remote = report.remote_pr_refs
  if (remote) {
    lines.push(
      `- 公开 PR refs：status=${remote.status}，refs=${remote.refs ?? 0}，` +
        `命中=${remote.findings ?? '-'}`,
    )
  }
  lines.push(
    '',
    '## 扫描完整性（degraded）',
    '',
    '以下每一项都表示“这块内容没有被完整读取”，因此本次扫描不能宣称覆盖它。',
    '',
  )
  if (degraded.items.length > 0) {
    lines.push('| surface | detail | object |', '| --- | --- | --- |')
    for (const item of degraded.items) {
      lines.push(`| ${item.surface} | ${item.detail} | ${item.object || '-'} |`)
    }
    if (degraded.omitted) {
      lines.push(`\n_另有 ${degraded.omitted} 项未列出（上限 ${ScanIssues.MAX_ITEMS}）。_`)
    }
  } else {
    lines.push('_本次没有未读全项（不代表内容干净）。_')
  }
  lines.push(
    '',
    '## 限制（limits）',
    '',
    `- 单一 blob 上限：${limits.max_blob_bytes} bytes（超出只计数、不扫描内容）`,
    '- 二进制判定：内容含 NUL 字节；仅对提取出的 ASCII/UTF-16 字符串做候选扫描，',
    '  **不宣称覆盖全部二进制内容**',
    '- LFS 指针不追内容；Git LFS 对象不在本地对象库里，本次未下载',
    '- 值分级只保存 sha256 前 12 位与脱敏位置，不保存原值',
    '',
    '## 边界（out of scope）',
    '',
    ...report.boundaries.map((item) => `- ${item}`),
    '',
    '## 值分级（待核查证据，按值去重，不含原值）',
    '',
    groupTable(history.value_groups),
    '',
    '## 二进制对象（本次）',
    '',
  )
  if (report.binary_objects.length > 0) {
    lines.push('| object | size | first path | strings 已扫描 |', '| --- | --- | --- | --- |')
    for (const item of report.binary_objects) {
      const first = item.paths.length > 0 ? item.paths[0] : '-'
      lines.push(`| ${item.object.slice(0, 12)} | ${item.size} | ${first} | yes |`)
    }
  } else {
    lines.push('_no binary blobs_')
  }
  if (remote && remote.status === 'scanned') {
    lines.push(
      '',
      '## 公开 PR refs 值分级（汇总）',
      '',
      '| rule | status | value groups | occurrences |',
      '| --- | --- | --- | --- |',
    )
    for (const entry of statusRollup(remote.value_groups ?? [])) {
      lines.push(`| ${entry.rule} | ${entry.status} | ${entry.groups} | ${entry.occurrences} |`)
    }
    lines.push('', '## 公开 PR refs findings（明细）', '', table(remote.findings_detail ?? []))
  }
  lines.push(
    '',
    '## 当前树 findings',
    '',
    table(worktree.findings),
    '',
    '## 历史 findings',
    '',
    table(history.findings),
    '',
    '## 需要的用户动作',
    '',
    '- 逐项核查“值分级”中 status=pending-review 的组；确认是真实凭据才轮换，并在授权后清理历史；',
    '  确认是夹具则记录理由，不能因为文件在测试目录就默认安全。',
    '- personal-data-exposure / environment-identifier 组不是凭据，但属信息暴露，是否清理历史需授权。',
    '- 若扫描完整性为 incomplete，先处理 degraded 明细再采信覆盖范围。',
    '- 本工具只读且不上传：不推送代码、不联网校验密钥。',
  )
  return lines.join('\n') + '\n'
}

/** The report text keeps its historical capitalized `True`/`False` spelling. */
function titleBool(value) {
  return value ? 'True' : 'False'
}

function coverageStats(root, refs, stats, duration, remote) {
  const named = (prefix) => refs.filter((ref) => ref.name.startsWith(prefix))
  const heads = named('refs/heads/')
  const remotes = named('refs/remotes/')
  const tags = named('refs/tags/')
  const other = refs.filter(
    (ref) => !['refs/heads/', 'refs/remotes/', 'refs/tags/'].some((prefix) => ref.name.startsWith(prefix)),
  )
  return {
    shallow: isShallow(root),
    refs_total: refs.length,
    refs_heads: heads.length,
    refs_remotes: remotes.length,
    refs_tags: tags.length,
    refs_other: other.length,
    remote,
    tags_present: tags.length > 0,
    head_commit: peelCommit(root, 'HEAD') || '',
    commits: stats.commits,
    unique_blobs: stats.uniqueBlobs,
    blobs_scanned: stats.scanned,
    blobs_binary: stats.binary,
    binary_bytes: stats.binaryBytes,
    binary_strings_scanned: stats.binaryStringsScanned,
    blobs_too_large: stats.tooLarge,
    blobs_lfs: stats.lfs,
    commit_messages: stats.commitMessages,
    tag_messages: stats.tagMessages,
    non_commit_refs: stats.nonCommitRefs,
    paths: stats.paths,
    refnames: refs.length,
    duration_seconds: duration,
  }
}

/**
 * Compare local tracking refs with the remote's advertised OIDs.
 *
 * "some remote-tracking ref exists" is not a coverage claim. The remote is
 * asked for its exact heads/tags and every advertised ref is compared with the
 * local object id; anything the local repository cannot see, or any failure to
 * ask, is reported instead of being presented as full coverage. The URL itself
 * never enters the report: the remote is only ever labelled `origin`.
 */
export function remoteCoverage(root, refs) {
  const result = {
    label: 'origin',
    tracking_refs: refs.filter((ref) => ref.name.startsWith('refs/remotes/origin/')).length,
    status: 'no-remote',
    heads: 0,
    tags: 0,
    absent_locally: 0,
    oid_mismatch: 0,
  }
  const listed = git(root, ['remote', 'get-url', 'origin'], { check: false })
  if (listed.returncode !== 0 || !text(listed.stdout).trim()) {
    return result
  }
  const advertised = git(root, ['ls-remote', '--refs', 'origin', 'refs/heads/*', 'refs/tags/*'], {
    check: false,
  })
  if (advertised.returncode !== 0) {
    result.status = 'ls-remote-failed'
    return result
  }
  const local = new Map()
  for (const ref of refs) {
    if (ref.name.startsWith('refs/remotes/origin/')) {
      local.set('refs/heads/' + ref.name.slice('refs/remotes/origin/'.length), ref.objectname)
    } else if (ref.name.startsWith('refs/tags/')) {
      local.set(ref.name, ref.objectname)
    }
  }
  for (const line of text(advertised.stdout).split('\n')) {
    const fields = line.split('\t')
    if (fields.length !== 2) {
      continue
    }
    const oid = fields[0].trim()
    const name = fields[1].trim()
    if (!oid || !name) {
      continue
    }
    result[name.startsWith('refs/heads/') ? 'heads' : 'tags'] += 1
    const known = local.get(name)
    if (known === undefined) {
      result.absent_locally += 1
    } else if (known !== oid) {
      result.oid_mismatch += 1
    }
  }
  result.status = result.absent_locally || result.oid_mismatch ? 'mismatch' : 'verified'
  return result
}

export const BOUNDARIES = [
  'reflog 条目（`git reflog`）与未写入 refs 的悬空对象不在可达 refs 内，本次不扫描。',
  '远端 Pull Request refs 需显式开启 `--remote-pr`；未开启时 `refs/pull/*` 不在本报告的本地 refs 内。',
  'Git submodule 的内容不在父仓对象库里，本次只扫描 gitlink 指针本身。',
  '已 fetch 的 refs 之外的远端分支不可见；远端覆盖由 `ls-remote` OID 核对给出，核对失败一律记为 incomplete。',
  '二进制 blob 只做 ASCII/UTF-16 字符串候选扫描，不等价于覆盖其全部内容。',
  '报告只以 `origin` 标签指代远端，不写入远端 URL（避免 query token 与本机路径进入报告）。',
]

/**
 * Audit public pull-request refs without touching the audited repository.
 *
 * Only OIDs are read from the remote; when PR refs exist they are fetched into
 * an isolated temporary bare repository under its own `refs/pull/*`
 * namespace, so the audited repository's refs are never modified. The report
 * carries the per-finding rows, not just group counts, so the caller can gate
 * on the same evidence it would get from the local history.
 */
export function scanRemotePrRefs(root, maxBlobBytes, stringLimit = DEFAULT_STRING_LIMIT) {
  const listed = git(root, ['remote', 'get-url', 'origin'], { check: false })
  if (listed.returncode !== 0 || !text(listed.stdout).trim()) {
    return { status: 'no-origin', remote: 'origin' }
  }
  // The URL is only used to talk to git here; it is never stored or printed.
  const url = text(listed.stdout).trim()
  const remote = git(root, ['ls-remote', '--refs', 'origin', 'refs/pull/*/head', 'refs/pull/*/merge'], {
    check: false,
  })
  if (remote.returncode !== 0) {
    return { status: 'ls-remote-failed', remote: 'origin' }
  }
  const refs = text(remote.stdout)
    .split('\n')
    .filter((line) => line.trim())
    .map((line) => line.split('\t').slice(1).join('\t'))
  if (refs.length === 0) {
    return { status: 'none', remote: 'origin', refs: 0 }
  }
  const workspace = mkdtempSync(path.join(tmpdir(), 'kk-studio-pr-refs-'))
  try {
    const bare = path.join(workspace, 'pr.git')
    git(root, ['init', '--bare', '--quiet', bare])
    const fetched = git(
      root,
      ['--git-dir', bare, 'fetch', '--no-tags', '--quiet', url, '+refs/pull/*:refs/pull/*'],
      { check: false },
    )
    if (fetched.returncode !== 0) {
      return { status: 'fetch-failed', remote: 'origin', refs: refs.length }
    }
    const subReport = run(bare, maxBlobBytes, { includeWorktree: false, stringLimit })
    return {
      status: 'scanned',
      remote: 'origin',
      refs: refs.length,
      findings: subReport.history.findings.length,
      findings_detail: subReport.history.findings,
      coverage: subReport.coverage,
      value_groups: subReport.history.value_groups,
      degraded: subReport.degraded,
    }
  } finally {
    rmSync(workspace, { recursive: true, force: true })
  }
}

/** `%Y-%m-%dT%H:%M:%S%z` in local time, matching the report's historical format. */
function localTimestamp(date = new Date()) {
  const pad = (value) => String(value).padStart(2, '0')
  const offset = -date.getTimezoneOffset()
  const sign = offset < 0 ? '-' : '+'
  const absolute = Math.abs(offset)
  return (
    `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}` +
    `T${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}` +
    `${sign}${pad(Math.floor(absolute / 60))}${pad(absolute % 60)}`
  )
}

export function run(
  root,
  maxBlobBytes = DEFAULT_MAX_BLOB_BYTES,
  { includeWorktree = true, fetch = false, stringLimit = DEFAULT_STRING_LIMIT } = {},
) {
  if (maxBlobBytes < 0 || stringLimit < 0) {
    throw new ValueError('blob byte and string limits must not be negative')
  }
  const resolvedRoot = path.resolve(root)
  let fetchResult = null
  if (fetch) {
    const fetched = git(resolvedRoot, ['fetch', '--tags', 'origin'], { check: false })
    fetchResult = fetched.returncode === 0 ? 'ok' : 'failed'
    if (fetchResult === 'failed') {
      process.stderr.write('git-fetch-failed\n')
    }
  }
  const start = Date.now()
  const refs = listRefs(resolvedRoot)
  const history = scanHistory(resolvedRoot, refs, maxBlobBytes, stringLimit)
  let worktree = { findings: [], groups: [] }
  let workIssues = new ScanIssues()
  if (includeWorktree) {
    const scanned = scanWorktree(resolvedRoot)
    worktree = { findings: scanned.findings, groups: scanned.groups.render() }
    workIssues = scanned.issues
  }
  const remote = remoteCoverage(resolvedRoot, refs)
  const duration = (Date.now() - start) / 1000
  const issues = new ScanIssues()
  issues.extend(history.issues)
  issues.extend(workIssues)
  if (['ls-remote-failed', 'mismatch'].includes(remote.status)) {
    issues.add('remote', 'remote-coverage:' + remote.status)
  }
  const coverage = coverageStats(resolvedRoot, refs, history.stats, duration, remote)
  if (fetchResult !== null) {
    coverage.fetch = fetchResult
  }
  return {
    repository: 'kk-studio',
    generated_at: localTimestamp(),
    coverage,
    limits: { max_blob_bytes: maxBlobBytes, binary_string_limit: stringLimit },
    boundaries: [...BOUNDARIES],
    degraded: issues.asDict(),
    worktree: section(worktree.findings, worktree.groups),
    history: section(history.findings, history.groups.render()),
    binary_objects: history.binaryObjects.slice(0, MAX_BINARY_OBJECTS),
    binary_objects_truncated: history.binaryObjects.length > MAX_BINARY_OBJECTS,
  }
}

/** Every high-confidence finding row the report holds, across all surfaces. */
export function highConfidenceFindings(report) {
  const remote = report.remote_pr_refs ?? {}
  const rows = [...report.history.findings, ...report.worktree.findings, ...(remote.findings_detail ?? [])]
  return rows.filter((row) => isHigh(row.rule))
}

/** Record a failed PR audit as a degraded item instead of a silent success. */
export function markRemotePrDegraded(report) {
  const remote = report.remote_pr_refs ?? {}
  if (['ls-remote-failed', 'fetch-failed'].includes(remote.status)) {
    report.degraded.items.push({ surface: 'remote-pr', detail: 'status:' + remote.status, object: 'origin' })
    report.degraded.incomplete = true
  }
  const nested = remote.degraded
  if (nested && nested.incomplete) {
    report.degraded.items.push({ surface: 'remote-pr', detail: 'nested-scan-incomplete', object: 'origin' })
    report.degraded.incomplete = true
  }
}

const USAGE =
  'usage: check-sensitive-history.mjs [-h] [--root ROOT] [--json JSON] [--markdown MARKDOWN]\n' +
  '                                   [--max-blob-bytes N] [--string-limit N] [--no-worktree]\n' +
  '                                   [--fetch] [--remote-pr] [--fail-on-high]\n'

/** Join `--option -1` into `--option=-1`: parseArgs rejects a dash-led value otherwise. */
function joinNegativeValues(argv) {
  return argv.reduce((result, arg, index) => {
    if (/^-\d+$/.test(arg) && /^--[a-z-]+$/.test(argv[index - 1] ?? '')) {
      result[result.length - 1] += `=${arg}`
    } else {
      result.push(arg)
    }
    return result
  }, [])
}

function parseInteger(name, value, fallback) {
  if (value === undefined) {
    return fallback
  }
  if (!/^[-+]?\d+$/.test(value)) {
    throw new TypeError(`argument ${name}: invalid int value: '${value}'`)
  }
  return Number.parseInt(value, 10)
}

export function main(argv = process.argv.slice(2)) {
  let values
  let maxBlobBytes
  let stringLimit
  try {
    ;({ values } = parseArgs({
      args: joinNegativeValues(argv),
      options: {
        root: { type: 'string' },
        json: { type: 'string' },
        markdown: { type: 'string' },
        'max-blob-bytes': { type: 'string' },
        'string-limit': { type: 'string' },
        'no-worktree': { type: 'boolean' },
        fetch: { type: 'boolean' },
        'remote-pr': { type: 'boolean' },
        'fail-on-high': { type: 'boolean' },
        help: { type: 'boolean', short: 'h' },
      },
    }))
    maxBlobBytes = parseInteger('--max-blob-bytes', values['max-blob-bytes'], DEFAULT_MAX_BLOB_BYTES)
    stringLimit = parseInteger('--string-limit', values['string-limit'], DEFAULT_STRING_LIMIT)
  } catch (error) {
    process.stderr.write(`${USAGE}check-sensitive-history: error: ${error.message}\n`)
    return 2
  }
  if (values.help) {
    process.stdout.write(
      `${USAGE}\nAudit the full Git history and working tree for sensitive data (redacted).\n`,
    )
    return 0
  }
  if (maxBlobBytes < 0 || stringLimit < 0) {
    process.stderr.write('invalid-arguments: limits must not be negative\n')
    return 2
  }

  const root = values.root ?? REPOSITORY_ROOT
  let report
  try {
    report = run(root, maxBlobBytes, {
      includeWorktree: !values['no-worktree'],
      fetch: values.fetch,
      stringLimit,
    })
    if (values['remote-pr']) {
      report.remote_pr_refs = scanRemotePrRefs(root, maxBlobBytes, stringLimit)
      markRemotePrDegraded(report)
    }
  } catch (error) {
    if (error instanceof CalledProcessError || error instanceof ValueError || typeof error.code === 'string') {
      const name = error instanceof Error && error.name !== 'Error' ? error.name : readErrorName(error)
      process.stderr.write(`scan-error ${name}\n`)
      return 2
    }
    throw error
  }

  if (values.json) {
    writeFileSync(values.json, JSON.stringify(report, null, 2) + '\n', 'utf8')
  }
  if (values.markdown) {
    writeFileSync(values.markdown, renderMarkdown(report), 'utf8')
  }

  const { coverage } = report
  process.stdout.write(
    `refs=${coverage.refs_total} commits=${coverage.commits} blobs=${coverage.unique_blobs} ` +
      `worktree_findings=${report.worktree.findings.length} history_findings=${report.history.findings.length} ` +
      `duration=${coverage.duration_seconds.toFixed(2)}s\n`,
  )
  if (report.remote_pr_refs) {
    process.stdout.write(`remote_pr_refs=${JSON.stringify(report.remote_pr_refs.status ?? null)}\n`)
  }
  const high = highConfidenceFindings(report)
  process.stdout.write(`high_confidence_findings=${high.length}\n`)
  const incomplete = report.degraded.incomplete
  if (incomplete) {
    process.stderr.write(
      `scan-incomplete items=${report.degraded.items.length} omitted=${report.degraded.omitted}\n`,
    )
  }
  if (values['fail-on-high'] && high.length > 0) {
    return 1
  }
  return incomplete ? 3 : 0
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  process.exitCode = main()
}
