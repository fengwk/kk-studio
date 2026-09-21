import path from 'node:path'

import {
  CASES,
  DEFAULT_BASE_URL,
  DEFAULT_DAEMON_ENV,
  DEFAULT_MAX_COST_USD,
  MAX_HARD_COST_USD,
} from './matrix.mjs'

export function parseArgs(argv, { cwd = process.cwd() } = {}) {
  const args = {
    list: false,
    help: false,
    only: [],
    baseUrl: DEFAULT_BASE_URL,
    daemonEnv: DEFAULT_DAEMON_ENV,
    reportRoot: path.resolve(cwd, 'reports/reliability'),
    maxCostUsd: DEFAULT_MAX_COST_USD,
  }
  for (let index = 0; index < argv.length; index++) {
    const token = argv[index]
    if (token === '--list') {
      args.list = true
    } else if (token === '--help' || token === '-h') {
      args.help = true
    } else if (token === '--only') {
      args.only.push(requireValue(argv, ++index, token))
    } else if (token === '--base-url') {
      args.baseUrl = parseBaseUrl(requireValue(argv, ++index, token))
    } else if (token === '--daemon-env') {
      args.daemonEnv = parseDaemonEnv(requireValue(argv, ++index, token))
    } else if (token === '--report-root') {
      args.reportRoot = path.resolve(cwd, requireValue(argv, ++index, token))
    } else if (token === '--max-cost-usd') {
      args.maxCostUsd = parseMaxCost(requireValue(argv, ++index, token))
    } else {
      throw new Error(`unknown argument: ${token}`)
    }
  }

  const knownIds = new Set(CASES.map((testCase) => testCase.id))
  for (const id of args.only) {
    if (!knownIds.has(id)) {
      throw new Error(`unknown case id for --only: ${id}`)
    }
  }
  args.only = [...new Set(args.only)]
  return args
}

export function selectCases(args) {
  if (!args.only.length) return [...CASES]
  const selected = new Set(args.only)
  return CASES.filter((testCase) => selected.has(testCase.id))
}

export function usage() {
  return [
    'Usage: node scripts/dev/verify/reliability/run-agent-matrix.mjs [options]',
    '',
    'Options:',
    '  --list                     List the frozen eight-case matrix without HTTP or model calls.',
    '  --only <case-id>           Select one case; may be repeated.',
    `  --base-url <url>           Backend URL (default: ${DEFAULT_BASE_URL}).`,
    `  --daemon-env <name>        Environment name (default: ${DEFAULT_DAEMON_ENV}).`,
    '  --report-root <path>       Report root (default: reports/reliability).',
    `  --max-cost-usd <amount>    Stop before later cases after this cap is reached (0-${MAX_HARD_COST_USD}).`,
    '  --help, -h                 Show this help.',
  ].join('\n')
}

export function formatCaseList(cases = CASES) {
  const lines = [
    'ID                          MODEL                       VARIANT  ANCHOR   TASK',
  ]
  for (const testCase of cases) {
    lines.push(
      [
        testCase.id.padEnd(27),
        testCase.model.ref.padEnd(27),
        testCase.model.variant.padEnd(8),
        testCase.anchor.padEnd(8),
        testCase.taskClass,
      ].join('  '),
    )
  }
  return lines.join('\n')
}

function requireValue(argv, index, option) {
  const value = argv[index]
  if (value == null || value.startsWith('--')) {
    throw new Error(`${option} requires a value`)
  }
  return value
}

function parseBaseUrl(value) {
  let url
  try {
    url = new URL(value)
  } catch {
    throw new Error(`invalid --base-url: ${value}`)
  }
  if (url.protocol !== 'http:' && url.protocol !== 'https:') {
    throw new Error(`--base-url must use http or https: ${value}`)
  }
  return value.replace(/\/$/, '')
}

function parseDaemonEnv(value) {
  if (!/^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(value)) {
    throw new Error(`invalid --daemon-env: ${value}`)
  }
  return value
}

function parseMaxCost(value) {
  const amount = Number(value)
  if (!Number.isFinite(amount) || amount < 0 || amount > MAX_HARD_COST_USD) {
    throw new Error(`--max-cost-usd must be between 0 and ${MAX_HARD_COST_USD}: ${value}`)
  }
  return amount
}
