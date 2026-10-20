#!/usr/bin/env node
/**
 * Synchronize E2E credential pairs for the four real target providers.
 *
 * Usage:
 *     sync-provider-credentials.mjs --backend-url http://localhost:18080
 *
 * Targets:
 *     google:            TEST_GOOGLE_BASE_URL / TEST_GOOGLE_API_KEY
 *     openai:            TEST_OPENAI_RESPONSES_BASE_URL / TEST_OPENAI_RESPONSES_API_KEY
 *     minimax-anthropic: TEST_ANTHROPIC_BASE_URL / TEST_ANTHROPIC_API_KEY
 *     deepseek:          TEST_OPENAI_BASE_URL / TEST_OPENAI_API_KEY
 *
 * When all credential pairs are absent, the script skips without contacting the backend.
 * When any provider has an incomplete (half) pair, it throws a ValidationError before any HTTP request.
 * Preconditions (database seed row, non-empty version, matching providerType) for all configured
 * providers are validated before any PUT request is performed.
 * No secrets, raw URLs, or response bodies are printed or exposed in exceptions.
 */

import path from 'node:path'
import { pathToFileURL } from 'node:url'
import { parseArgs } from 'node:util'

import { syncProviderSpecs } from '../../lib/provider-credentials.mjs'

export const TEST_GOOGLE_BASE_URL = 'TEST_GOOGLE_BASE_URL'
export const TEST_GOOGLE_API_KEY = 'TEST_GOOGLE_API_KEY'
export const TEST_OPENAI_RESPONSES_BASE_URL = 'TEST_OPENAI_RESPONSES_BASE_URL'
export const TEST_OPENAI_RESPONSES_API_KEY = 'TEST_OPENAI_RESPONSES_API_KEY'
export const TEST_ANTHROPIC_BASE_URL = 'TEST_ANTHROPIC_BASE_URL'
export const TEST_ANTHROPIC_API_KEY = 'TEST_ANTHROPIC_API_KEY'
export const TEST_OPENAI_BASE_URL = 'TEST_OPENAI_BASE_URL'
export const TEST_OPENAI_API_KEY = 'TEST_OPENAI_API_KEY'

export const PROVIDER_SPECS = [
  {
    name: 'google',
    providerType: 'google',
    baseUrlEnv: TEST_GOOGLE_BASE_URL,
    apiKeyEnv: TEST_GOOGLE_API_KEY,
    requiresV1Suffix: false,
    defaultDescription: 'Google Gemini.',
  },
  {
    name: 'openai',
    providerType: 'openai_responses',
    baseUrlEnv: TEST_OPENAI_RESPONSES_BASE_URL,
    apiKeyEnv: TEST_OPENAI_RESPONSES_API_KEY,
    requiresV1Suffix: true,
    defaultDescription: 'OpenAI (OpenAI Responses).',
  },
  {
    name: 'minimax-anthropic',
    providerType: 'anthropic',
    baseUrlEnv: TEST_ANTHROPIC_BASE_URL,
    apiKeyEnv: TEST_ANTHROPIC_API_KEY,
    requiresV1Suffix: false,
    defaultDescription: 'MiniMax (Anthropic).',
  },
  {
    name: 'deepseek',
    providerType: 'openai',
    baseUrlEnv: TEST_OPENAI_BASE_URL,
    apiKeyEnv: TEST_OPENAI_API_KEY,
    requiresV1Suffix: true,
    defaultDescription: 'DeepSeek (OpenAI Chat Completions).',
  },
]

/**
 * Synchronize real provider credentials from environment to backend.
 *
 * Resolves to a list of status-line strings suitable for printing.
 */
export async function syncProviderCredentials(backendUrl, env = process.env, request = undefined) {
  // Explicit reads to satisfy static guard inspections
  const envValues = {
    [TEST_GOOGLE_BASE_URL]: env[TEST_GOOGLE_BASE_URL],
    [TEST_GOOGLE_API_KEY]: env[TEST_GOOGLE_API_KEY],
    [TEST_OPENAI_RESPONSES_BASE_URL]: env[TEST_OPENAI_RESPONSES_BASE_URL],
    [TEST_OPENAI_RESPONSES_API_KEY]: env[TEST_OPENAI_RESPONSES_API_KEY],
    [TEST_ANTHROPIC_BASE_URL]: env[TEST_ANTHROPIC_BASE_URL],
    [TEST_ANTHROPIC_API_KEY]: env[TEST_ANTHROPIC_API_KEY],
    [TEST_OPENAI_BASE_URL]: env[TEST_OPENAI_BASE_URL],
    [TEST_OPENAI_API_KEY]: env[TEST_OPENAI_API_KEY],
  }
  return syncProviderSpecs(backendUrl, PROVIDER_SPECS, envValues, request)
}

async function main() {
  const { values } = parseArgs({ options: { 'backend-url': { type: 'string' } } })
  if (!values['backend-url']) {
    process.stderr.write('error: the following arguments are required: --backend-url\n')
    return 2
  }
  try {
    for (const line of await syncProviderCredentials(values['backend-url'])) {
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
