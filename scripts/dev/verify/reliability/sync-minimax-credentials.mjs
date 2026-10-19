#!/usr/bin/env node
/** Synchronize the reliability matrix's MiniMax Responses provider. */

import path from 'node:path'
import { pathToFileURL } from 'node:url'
import { parseArgs } from 'node:util'

import { syncProviderSpecs } from '../../lib/provider-credentials.mjs'

export const TEST_MINIMAX_BASE_URL = 'TEST_MINIMAX_BASE_URL'
export const TEST_MINIMAX_API_KEY = 'TEST_MINIMAX_API_KEY'
const MINIMAX_SPEC = {
  name: 'minimax',
  providerType: 'openai_responses',
  baseUrlEnv: TEST_MINIMAX_BASE_URL,
  apiKeyEnv: TEST_MINIMAX_API_KEY,
  requiresV1Suffix: true,
  defaultDescription: 'MiniMax.',
}

/** Synchronize the reliability-only credential pair without widening E2E inputs. */
export async function syncMinimaxCredentials(backendUrl, env = process.env, request = undefined) {
  const envValues = {
    [TEST_MINIMAX_BASE_URL]: env[TEST_MINIMAX_BASE_URL],
    [TEST_MINIMAX_API_KEY]: env[TEST_MINIMAX_API_KEY],
  }
  return syncProviderSpecs(backendUrl, [MINIMAX_SPEC], envValues, request)
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
