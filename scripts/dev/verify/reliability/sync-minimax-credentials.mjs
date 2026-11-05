#!/usr/bin/env node
/** Synchronize the reliability matrix's MiniMax Responses provider from the shared credential inventory. */

import path from 'node:path'
import { pathToFileURL } from 'node:url'
import { parseArgs } from 'node:util'

import { syncProviderSpecs } from '../../lib/provider-credentials.mjs'
import {
  RELIABILITY_PROVIDER_SPECS,
  TEST_MINIMAX_API_KEY,
  TEST_MINIMAX_BASE_URL,
} from '../e2e/sync-provider-credentials.mjs'

export { TEST_MINIMAX_API_KEY, TEST_MINIMAX_BASE_URL }

/** Synchronize the reliability-only credential pair without widening E2E inputs. */
export async function syncMinimaxCredentials(backendUrl, env = process.env, request = undefined) {
  return syncProviderSpecs(backendUrl, RELIABILITY_PROVIDER_SPECS, env, request)
}

async function main() {
  const { values } = parseArgs({ options: { 'backend-url': { type: 'string' } } })
  if (!values['backend-url']) {
    process.stderr.write('error: the following arguments are required: --backend-url\n')
    return 2
  }
  try {
    for (const line of await syncMinimaxCredentials(values['backend-url'])) {
      process.stdout.write(`${line}\n`)
    }
  } catch (error) {
    process.stderr.write(`${error.name}: ${error.message}\n`)
    return 1
  }
  return 0
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  process.exitCode = await main()
}
