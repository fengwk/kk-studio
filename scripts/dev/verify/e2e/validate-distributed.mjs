#!/usr/bin/env node
/**
 * Static validator for the distributed Compose topology invariants.
 *
 * Validates deploy/distributed/compose.yaml via `docker compose config` without
 * starting containers:
 *
 * - postgres / minio / http-mock join both isolated node-a-db and node-b-db
 *   networks;
 * - app-a only joins node-a-db + daemon-a + app-ingress-a, app-b only joins
 *   node-b-db + daemon-b + app-ingress-b; each daemon joins only its own network;
 * - no network contains both app-a and app-b, so the two apps have no DNS or IP
 *   path to each other;
 * - the host publishes only the two app ports plus the disposable test
 *   dependency ports;
 * - both apps point at the same distributed PostgreSQL database and MinIO bucket.
 *
 * Usage:
 *     scripts/dev/verify/e2e/validate-distributed.mjs --compose-file deploy/distributed/compose.yaml
 */

import { spawnSync } from 'node:child_process'
import path from 'node:path'
import { pathToFileURL } from 'node:url'
import { parseArgs } from 'node:util'

const EXPECTED_SERVICE_NETWORKS = {
  postgres: ['node-a-db', 'node-b-db'],
  minio: ['node-a-db', 'node-b-db'],
  'http-mock': ['node-a-db', 'node-b-db'],
  'minio-init': ['node-a-db'],
  'workspace-init': ['daemon-a'],
  'app-a': ['node-a-db', 'daemon-a', 'app-ingress-a'],
  'app-b': ['node-b-db', 'daemon-b', 'app-ingress-b'],
  'daemon-a': ['daemon-a'],
  'daemon-b': ['daemon-b'],
}
const EXPECTED_NETWORKS = ['node-a-db', 'node-b-db', 'daemon-a', 'daemon-b', 'app-ingress-a', 'app-ingress-b']
const EXPECTED_HOST_PORTS = {
  postgres: ['15433'],
  minio: ['19001'],
  'http-mock': ['18090'],
  'app-a': ['18082'],
  'app-b': ['18083'],
}
const SHARED_APP_ENVIRONMENT = {
  KK_STUDIO_DB_URL: 'jdbc:postgresql://postgres:5432/kk_studio_distributed',
  KK_STUDIO_STORAGE_S3_BUCKET: 'kk-studio-distributed',
}

/** A topology invariant does not hold. */
export class TopologyError extends Error {
  constructor(message) {
    super(`topology invariant violated: ${message}`)
    this.name = 'TopologyError'
  }
}

/** Python-list style rendering (`['a', 'b']`), the format these messages have always used. */
const sortedList = (values) => `[${[...values].sort().map((value) => `'${value}'`).join(', ')}]`
const sameSet = (left, right) => sortedList(left) === sortedList(right)

export function expandConfig(composeFile) {
  const result = spawnSync('docker', ['compose', '-f', composeFile, 'config', '--format', 'json'], {
    encoding: 'utf8',
    maxBuffer: 1 << 26,
  })
  if (result.error || result.status !== 0) {
    throw new TopologyError(
      `compose config failed for ${composeFile}: ${(result.error?.message ?? result.stderr).trim()}`,
    )
  }
  if (!result.stdout.trim()) {
    throw new TopologyError('compose config produced no output')
  }
  return JSON.parse(result.stdout)
}

function serviceNetworks(config, service) {
  return Object.keys(config.services[service].networks || {})
}

function publishedPorts(config, service) {
  return (config.services[service].ports || []).map((port) => String(port.published))
}

/** Throw a TopologyError for the first violated invariant. */
export function validate(config) {
  const services = config.services
  for (const [service, expected] of Object.entries(EXPECTED_SERVICE_NETWORKS)) {
    if (!(service in services)) {
      throw new TopologyError(`missing service ${service}`)
    }
    const actual = serviceNetworks(config, service)
    if (!sameSet(actual, expected)) {
      throw new TopologyError(`${service} networks ${sortedList(actual)} != ${sortedList(expected)}`)
    }
  }

  if (!sameSet(Object.keys(config.networks), EXPECTED_NETWORKS)) {
    throw new TopologyError(`unexpected network set: ${sortedList(Object.keys(config.networks))}`)
  }

  for (const network of [...EXPECTED_NETWORKS].sort()) {
    const members = Object.keys(services).filter((service) => serviceNetworks(config, service).includes(network))
    if (members.includes('app-a') && members.includes('app-b')) {
      throw new TopologyError(`network ${network} contains both app-a and app-b`)
    }
  }

  for (const service of Object.keys(services).sort()) {
    const actual = publishedPorts(config, service)
    const expected = EXPECTED_HOST_PORTS[service] ?? []
    if (!sameSet(actual, expected)) {
      throw new TopologyError(`${service} host ports ${sortedList(actual)} != ${sortedList(expected)}`)
    }
  }

  for (const service of ['app-a', 'app-b']) {
    const environment = services[service].environment || {}
    for (const [variable, expected] of Object.entries(SHARED_APP_ENVIRONMENT)) {
      if (environment[variable] !== expected) {
        throw new TopologyError(`${service} ${variable} must be the shared ${expected}`)
      }
    }
  }
}

function main() {
  const { values } = parseArgs({ options: { 'compose-file': { type: 'string' } } })
  if (!values['compose-file']) {
    process.stderr.write('error: the following arguments are required: --compose-file\n')
    return 2
  }
  try {
    const config = expandConfig(values['compose-file'])
    validate(config)
    console.log(
      `PASS distributed topology: ${Object.keys(config.services).length} services, networks ${sortedList(Object.keys(config.networks))}`,
    )
    return 0
  } catch (error) {
    if (error instanceof TopologyError) {
      process.stderr.write(`${error.message}\n`)
      return 1
    }
    throw error
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  process.exitCode = main()
}
