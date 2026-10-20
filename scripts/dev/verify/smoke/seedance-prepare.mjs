#!/usr/bin/env node
/**
 * Real Seedance prepare-only smoke against OpenCLI Hub.
 *
 * Usage:
 *   RUN_REAL_SEEDANCE_PREPARE_SMOKE=1 \
 *   SEEDANCE_WORKSPACE_ID=... \
 *   OPENCLI_HUB_BASE_URL=... \
 *   OPENCLI_API_KEY=... \
 *   scripts/dev/verify/smoke/seedance-prepare.mjs --confirm-prepare-only
 *
 * This script calls OpenCLI Hub directly. It is hard-coded to:
 *   model_version=seedance2.0fast
 *   duration=4
 *   submit=0
 *
 * It never starts a Canvas FunctionRun and never imports a generated video.
 */

import assert from 'node:assert/strict'
import path from 'node:path'
import { pathToFileURL } from 'node:url'

import { isValidPort, splitUrl } from '../../lib/url-split.mjs'

const USAGE = `Usage:
  RUN_REAL_SEEDANCE_PREPARE_SMOKE=1 \\
  SEEDANCE_WORKSPACE_ID=... \\
  OPENCLI_HUB_BASE_URL=... \\
  OPENCLI_API_KEY=... \\
  scripts/dev/verify/smoke/seedance-prepare.mjs --confirm-prepare-only

This script calls OpenCLI Hub directly. It is hard-coded to:
  model_version=seedance2.0fast
  duration=4
  submit=0

It never starts a Canvas FunctionRun and never imports a generated video.
`

const ORIGIN_ERROR = 'OPENCLI_HUB_BASE_URL must be an HTTP(S) origin'

/** Normalize a Hub origin (scheme + host[:port] only) or return null when it is not one. */
export function normalizeHubOrigin(value) {
  const parsed = splitUrl(value)
  if (
    !isValidPort(parsed.port) ||
    !['http', 'https'].includes(parsed.scheme.toLowerCase()) ||
    !parsed.hostname ||
    parsed.netloc.includes('@') ||
    value.includes('?') ||
    value.includes('#') ||
    !['', '/'].includes(parsed.path) ||
    parsed.netloc.endsWith(':') ||
    /\s/.test(parsed.netloc)
  ) {
    return null
  }
  return `${parsed.scheme.toLowerCase()}://${parsed.netloc}`
}

export function buildRequestBody({ workspaceId, prompt, instanceId }) {
  const body = {
    argv: [
      'jimeng-agent',
      'video',
      '--workspace',
      workspaceId,
      '--ratio',
      '16:9',
      '--model_version',
      'seedance2.0fast',
      '--duration',
      '4',
      '--prompt',
      prompt,
      '--submit',
      '0',
      '--retry',
      '0',
    ],
    timeoutMillis: 600000,
  }
  if (instanceId) {
    body.instanceId = instanceId
  }
  return body
}

async function hubJson(url, apiKey, { body, timeoutMillis }) {
  const response = await fetch(url, {
    method: body === undefined ? 'GET' : 'POST',
    headers: {
      ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
      Authorization: `Bearer ${apiKey}`,
    },
    body,
    signal: AbortSignal.timeout(timeoutMillis),
  })
  if (!response.ok) {
    throw new Error(`OpenCLI Hub request failed with HTTP ${response.status}`)
  }
  return response.json()
}

/** Resolves to the process exit code. */
export async function main(argv = process.argv.slice(2), env = process.env) {
  const fail = (message, code = 2) => {
    process.stderr.write(`${message}\n`)
    return code
  }
  if (argv.length !== 1 || argv[0] !== '--confirm-prepare-only') {
    process.stderr.write(USAGE)
    return 2
  }
  if (env.RUN_REAL_SEEDANCE_PREPARE_SMOKE !== '1') {
    return fail('RUN_REAL_SEEDANCE_PREPARE_SMOKE=1 is required')
  }
  if (!env.SEEDANCE_WORKSPACE_ID) {
    return fail('SEEDANCE_WORKSPACE_ID is required')
  }
  if (!env.OPENCLI_HUB_BASE_URL) {
    return fail('OPENCLI_HUB_BASE_URL is required; set it to the OpenCLI Hub HTTP(S) origin')
  }
  if (!env.OPENCLI_API_KEY) {
    return fail('OPENCLI_API_KEY is required; provide the explicit caller key')
  }
  const hubUrl = normalizeHubOrigin(env.OPENCLI_HUB_BASE_URL)
  if (hubUrl === null) {
    return fail(ORIGIN_ERROR)
  }
  const prompt = env.SEEDANCE_PREPARE_SMOKE_PROMPT || 'Prepare-only smoke. Do not submit generation.'
  const requestBody = JSON.stringify(
    buildRequestBody({
      workspaceId: env.SEEDANCE_WORKSPACE_ID,
      prompt,
      instanceId: env.OPENCLI_HUB_INSTANCE_ID,
    }),
  )

  const submitted = await hubJson(`${hubUrl}/api/opencli/execute`, env.OPENCLI_API_KEY, {
    body: requestBody,
    timeoutMillis: 620_000,
  })
  const executionId = submitted.data.id

  for (let attempt = 0; attempt < 80; attempt += 1) {
    const response = await hubJson(
      `${hubUrl}/api/executions/${executionId}?waitSeconds=10`,
      env.OPENCLI_API_KEY,
      { timeoutMillis: 20_000 },
    )
    const { status } = response.data
    if (status === 'PENDING' || status === 'RUNNING') {
      continue
    }
    if (status === 'SUCCEEDED') {
      const stdout = JSON.parse(response.data.stdout)
      const row = Array.isArray(stdout) ? stdout[0] : stdout
      assert.equal(row.status, 'prepared', JSON.stringify(row))
      assert.equal(row.submitted, false, JSON.stringify(row))
      process.stdout.write('Seedance prepare-only smoke passed: prepared=true, submitted=false\n')
      return 0
    }
    return fail(`Seedance prepare-only Hub execution ended as ${status}`, 1)
  }
  return fail('Seedance prepare-only smoke timed out', 1)
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  try {
    process.exitCode = await main()
  } catch (error) {
    process.stderr.write(`${error.message}\n`)
    process.exitCode = 1
  }
}
