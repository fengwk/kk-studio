/** Shared helpers for tests that pin the contracts of repository shell scripts. */

import { spawnSync } from 'node:child_process'
import { readFileSync } from 'node:fs'

import { repositoryRoot } from '../../lib/repo-root.mjs'

export const REPOSITORY_ROOT = repositoryRoot()

export const readText = (file) => readFileSync(file, 'utf8')

/** Environment without any inherited repository-root override or preview/data-plane input. */
export function cleanEnvironment(removals = ['KK_STUDIO_REPO_ROOT', 'SHARED_PREVIEW_ENV_FILE']) {
  const environment = { ...process.env }
  for (const name of removals) {
    delete environment[name]
  }
  return environment
}

/** Extract a top-level shell function body for command-contract assertions. */
export function functionBody(scriptPath, functionName) {
  const escaped = functionName.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const match = new RegExp(`^${escaped}\\(\\) \\{\\n([\\s\\S]*?)^\\}$`, 'm').exec(readText(scriptPath))
  if (match === null) {
    throw new Error(`missing shell function ${functionName} in ${scriptPath}`)
  }
  return match[1].replace(/\\\s*\n\s*/g, ' ')
}

/** Run `bash -c script`; resolves synchronously to `{ status, stdout, stderr }` as strings. */
export function runBash(script, { cwd = REPOSITORY_ROOT, env = process.env, args = [] } = {}) {
  return spawnSync('bash', ['-c', script, 'bash', ...args], { cwd, env, encoding: 'utf8' })
}

/** Quote a value for safe interpolation into a bash command line. */
export const shellQuote = (value) => `'${String(value).replace(/'/g, `'\\''`)}'`

/** Tracked-file `git grep -n -I -F` matches as `{ path, line, text }`; read-only. */
export function gitGrep(needles, paths = ['.']) {
  const args = ['grep', '-n', '-I', '-F', ...needles.flatMap((needle) => ['-e', needle]), '--', ...paths]
  const result = spawnSync('git', args, { cwd: REPOSITORY_ROOT, encoding: 'utf8', maxBuffer: 1 << 26 })
  if (![0, 1].includes(result.status)) {
    throw new Error(result.stderr)
  }
  return result.stdout
    .split('\n')
    .filter(Boolean)
    .map((line) => {
      const [path, lineNumber, ...text] = line.split(':')
      return { path, line: Number(lineNumber), text: text.join(':') }
    })
}
