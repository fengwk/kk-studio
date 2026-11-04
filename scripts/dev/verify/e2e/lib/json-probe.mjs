#!/usr/bin/env node
/**
 * JSON probes for the E2E shell helpers: each command reads one API response from stdin.
 *
 *   json-probe.mjs model-config-print          assert + print the model config default variant
 *   json-probe.mjs model-config-check          exit 0 when the first model carries a structured config
 *   json-probe.mjs environment-status NAME     print the status of the named environment ("" if absent)
 *   json-probe.mjs providers-ready             print "ok" or "missing:<names>" for the four real providers
 */

import { readFileSync } from 'node:fs'

import { render, runProbe } from '../../../lib/json-probe.mjs'
import { PROVIDER_SPECS } from '../sync-provider-credentials.mjs'

const isObject = (value) => value !== null && typeof value === 'object' && !Array.isArray(value)

const document = JSON.parse(readFileSync(0, 'utf8'))
const firstModel = () => document?.data?.results?.[0] ?? null

const commands = {
  'model-config-print'() {
    const model = firstModel()
    if (!(model && isObject(model.config) && model.config.defaultVariant)) {
      throw new Error(`model config is missing defaultVariant: ${JSON.stringify(model)}`)
    }
    console.log(`frontend proxy model.config.defaultVariant= ${model.config.defaultVariant}`)
  },
  'model-config-check'() {
    const model = firstModel()
    process.exitCode = model && isObject(model.config) ? 0 : 1
  },
  'environment-status'(name) {
    const match = (document?.data ?? []).find((row) => row?.name === name)
    console.log(match ? render(match.status) : '')
  },
  'providers-ready'() {
    const rows = document?.data?.results ?? []
    const configured = new Set(rows.filter((row) => row.configured && row.baseUrl).map((row) => row.name))
    const missing = PROVIDER_SPECS.map((spec) => spec.name).filter((name) => !configured.has(name)).sort()
    console.log(missing.length > 0 ? `missing:${missing.join(',')}` : 'ok')
  },
}

runProbe(commands)
