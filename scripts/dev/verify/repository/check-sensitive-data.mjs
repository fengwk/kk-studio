#!/usr/bin/env node
/** Scan tracked and non-ignored untracked files for high-confidence secrets. */

import { execFileSync } from 'node:child_process'
import { lstatSync, readFileSync } from 'node:fs'
import path from 'node:path'
import { pathToFileURL } from 'node:url'
import { parseArgs } from 'node:util'

import { repositoryRoot } from '../../lib/repo-root.mjs'

export const REPOSITORY_ROOT = repositoryRoot()
export const ALLOWED_USER_NAMES = new Set(['dev', 'test', 'user', 'kkdaemon'])
export const PRIVATE_ENVIRONMENT_MARKERS = ['vps-' + 'opencli-hub', 'mvn.' + 'hz.' + 'netease.com']
const PRIVATE_ENVIRONMENT_PATTERN = new RegExp(
  PRIVATE_ENVIRONMENT_MARKERS.map((marker) => marker.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('|'),
  'gi',
)

const UNIX_PERSONAL_PATH_PATTERN =
  /(?<![A-Za-z0-9._-])\/(?:home|Users)\/(?<user>[A-Za-z0-9][A-Za-z0-9._-]*)(?![A-Za-z0-9._-])/g
const WINDOWS_PERSONAL_PATH_PATTERN =
  /(?<![A-Za-z0-9._-])[A-Za-z]:[\\/]+Users[\\/]+(?<user>[A-Za-z0-9][A-Za-z0-9._-]*)(?![A-Za-z0-9._-])/gi
const WSL_MOUNT_PERSONAL_PATH_PATTERN =
  /(?<![A-Za-z0-9._-])\/mnt\/[A-Za-z]:?[\\/]+Users[\\/]+(?<user>[A-Za-z0-9][A-Za-z0-9._-]*)(?![A-Za-z0-9._-])/gi
const WSL_UNC_PERSONAL_PATH_PATTERN =
  /(?<![A-Za-z0-9._-])\\\\wsl\.localhost[\\/]+[^\\/]+[\\/]+home[\\/]+(?<user>[A-Za-z0-9][A-Za-z0-9._-]*)(?![A-Za-z0-9._-])/gi

export const RULES = [
  ['private-key', /-----BEGIN (?:[A-Z0-9]+ )*PRIVATE KEY(?: BLOCK)?-----/g],
  ['aws-access-key', /\b(?:AKIA|ASIA)[0-9A-Z]{16}\b/g],
  ['google-api-key', /(?<![A-Za-z0-9_-])AIza[A-Za-z0-9_-]{35}(?![A-Za-z0-9_-])/g],
  [
    'gitlab-token',
    /(?<![A-Za-z0-9_-])(?:glpat|gldt|glrt|glft|gloas|glsoat)-[A-Za-z0-9_-]{20,}(?![A-Za-z0-9_-])/g,
  ],
  ['github-token', /\b(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,})\b/g],
  ['slack-token', /\bxox[baprs]-[0-9A-Za-z-]{10,}\b/g],
  ['stripe-live-token', /\b(?:sk|rk)_live_[A-Za-z0-9]{16,}\b/g],
  ['jwt', /\beyJ[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]{5,}\b/g],
  ['slack-webhook', /https?:\/\/hooks\.slack\.com\/services\/[A-Za-z0-9/_-]{10,}/g],
  [
    'discord-webhook',
    /https?:\/\/discord(?:app)?\.com\/api\/webhooks\/[0-9]{6,}\/[A-Za-z0-9._-]{10,}/g,
  ],
  [
    'telegram-webhook',
    /https?:\/\/api\.telegram\.org\/bot[0-9]{6,}:[A-Za-z0-9_-]{20,}\/[A-Za-z]+/g,
  ],
  [
    'dingtalk-webhook',
    /https?:\/\/oapi\.dingtalk\.com\/robot\/send\?access_token=[A-Za-z0-9_-]{20,}/g,
  ],
  [
    'feishu-webhook',
    /https?:\/\/(?:open\.feishu\.cn|open\.larksuite\.com)\/open-apis\/bot\/v2\/hook\/[A-Za-z0-9_-]{20,}/g,
  ],
  [
    'wecom-webhook',
    /https?:\/\/qyapi\.weixin\.qq\.com\/cgi-bin\/webhook\/send\?key=[A-Za-z0-9_-]{20,}/g,
  ],
  ['private-environment', PRIVATE_ENVIRONMENT_PATTERN],
  ['personal-path', UNIX_PERSONAL_PATH_PATTERN],
  ['personal-path', WINDOWS_PERSONAL_PATH_PATTERN],
  ['personal-path', WSL_MOUNT_PERSONAL_PATH_PATTERN],
  ['personal-path', WSL_UNC_PERSONAL_PATH_PATTERN],
]

function isAllowlisted(ruleName, match) {
  return ruleName === 'personal-path' && ALLOWED_USER_NAMES.has(match.groups.user)
}

/** Return only rule and location data; matched values never leave this function. */
export function findingsInLine(line, filePath, lineNumber) {
  const findings = []
  const reportedRules = new Set()
  for (const [ruleName, pattern] of RULES) {
    if (reportedRules.has(ruleName)) {
      continue
    }
    if ([...line.matchAll(pattern)].some((match) => !isAllowlisted(ruleName, match))) {
      findings.push({ rule: ruleName, path: filePath, line: lineNumber })
      reportedRules.add(ruleName)
    }
  }
  return findings
}

function scanLines(lines, filePath) {
  if (lines[lines.length - 1] === '') {
    lines.pop()
  }
  return lines.flatMap((line, index) => findingsInLine(line, filePath, index + 1))
}

/** Scan text supplied by a caller, retaining only redacted finding locations. */
export function scanText(text, filePath = '<memory>') {
  return scanLines(text.split(/\r\n|[\n\r\v\f\x1c-\x1e\x85\u2028\u2029]/), filePath)
}

export function gitPaths(root) {
  const output = execFileSync(
    'git',
    ['ls-files', '--cached', '--others', '--exclude-standard', '-z'],
    { cwd: root, maxBuffer: 1 << 28 },
  )
  return output
    .toString('utf8')
    .split('\0')
    .filter((rawPath) => rawPath)
}

/** Scan Git-tracked files and non-ignored untracked files below root. */
export function scanRepository(root = REPOSITORY_ROOT) {
  const resolvedRoot = path.resolve(root)
  const findings = []
  for (const relativePath of gitPaths(resolvedRoot)) {
    const filePath = path.join(resolvedRoot, relativePath)
    const stat = lstatSync(filePath, { throwIfNoEntry: false })
    if (!stat || stat.isSymbolicLink() || !stat.isFile()) {
      continue
    }
    findings.push(...scanLines(readFileSync(filePath).toString('utf8').split('\n'), relativePath))
  }
  const compare = (left, right) => (left < right ? -1 : left > right ? 1 : 0)
  return findings.sort((a, b) => compare(a.path, b.path) || a.line - b.line || compare(a.rule, b.rule))
}

/** Format only the rule and redacted path:line location for CI output. */
export function formatFinding(finding) {
  return `${finding.rule} ${finding.path}:${finding.line}`
}

export function main(argv = process.argv.slice(2)) {
  let values
  try {
    ;({ values } = parseArgs({
      args: argv,
      options: { root: { type: 'string' }, help: { type: 'boolean', short: 'h' } },
    }))
  } catch (error) {
    process.stderr.write(`check-sensitive-data: error: ${error.message}\n`)
    return 2
  }
  if (values.help) {
    process.stdout.write(
      'usage: check-sensitive-data.mjs [-h] [--root ROOT]\n\nScan tracked and non-ignored untracked files for sensitive data.\n',
    )
    return 0
  }
  let findings
  try {
    findings = scanRepository(values.root ?? REPOSITORY_ROOT)
  } catch {
    process.stderr.write('scan-error repository\n')
    return 2
  }
  for (const finding of findings) {
    process.stdout.write(`${formatFinding(finding)}\n`)
  }
  return findings.length > 0 ? 1 : 0
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  process.exitCode = main()
}
