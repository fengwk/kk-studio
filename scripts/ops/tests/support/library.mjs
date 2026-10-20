/** Shared helpers for the database maintenance script tests. */

import { spawnSync } from 'node:child_process'
import { tmpdir } from 'node:os'
import path from 'node:path'

import { REPOSITORY_ROOT } from '../../../dev/tests/support/shell.mjs'

export { REPOSITORY_ROOT, functionBody, readText, shellQuote } from '../../../dev/tests/support/shell.mjs'

export const OPS_DIR = path.join(REPOSITORY_ROOT, 'scripts/ops')
export const RESET_SCRIPT = path.join(OPS_DIR, 'reset-database.sh')
export const LIBRARY = path.join(OPS_DIR, 'lib/database-maintenance.sh')

/** Every file that runs in production: the public entrypoint and its private sourced library. */
export const PRODUCTION_FILES = [RESET_SCRIPT, LIBRARY]

/** Run a bash snippet that sources the production library, with an explicit environment. */
export function runLibrarySnippet(snippet, environment = {}) {
  const child = {}
  for (const key of ['PATH', 'TMPDIR', 'LANG', 'LC_ALL', 'SYSTEMROOT', 'WINDIR']) {
    if (key in process.env) {
      child[key] = process.env[key]
    }
  }
  child.HOME = tmpdir()
  child.KK_STUDIO_REPO_ROOT = REPOSITORY_ROOT
  for (const [key, value] of Object.entries(environment)) {
    if (value === null) {
      delete child[key]
    } else {
      child[key] = value
    }
  }
  return spawnSync('bash', ['-c', `. '${LIBRARY}'\n${snippet}`], { cwd: REPOSITORY_ROOT, env: child, encoding: 'utf8' })
}
