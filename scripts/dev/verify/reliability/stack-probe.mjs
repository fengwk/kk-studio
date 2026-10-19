#!/usr/bin/env node
/**
 * JSON probes for the reliability stack shell helper: each command reads one API or Docker
 * response from stdin and either prints a value or fails with a one-line reason.
 *
 *   stack-probe.mjs environment-ready NAME     exit 0 only when the environment is READY and ready=true
 *   stack-probe.mjs environment-status NAME    print "environment=<n> status=<s> ready=<r>"
 *   stack-probe.mjs workspace-volume           print the named volume mounted at /workspace
 *   stack-probe.mjs daemon-mount               verify the daemon has exactly one writable /workspace volume
 *   stack-probe.mjs compose-status             print one "service= state= health=" line per container
 */

import { readFileSync } from 'node:fs'

/** Python-style rendering of a scalar (`None`, `True`, `False`), as these helpers always printed. */
const render = (value) => (value === null || value === undefined ? 'None' : value === true ? 'True' : value === false ? 'False' : String(value))

class ProbeError extends Error {}

const input = readFileSync(0, 'utf8')
const environments = (name) => ((JSON.parse(input).data ?? []).find((row) => row?.name === name))
const container = () => JSON.parse(input)[0]

const commands = {
  'environment-ready'(name) {
    const match = environments(name)
    process.exitCode = match && match.status === 'READY' && match.ready === true ? 0 : 1
  },
  'environment-status'(name) {
    const match = environments(name)
    if (!match) {
      throw new ProbeError(`environment not found: ${name}`)
    }
    console.log(`environment=${name} status=${render(match.status)} ready=${render(match.ready)}`)
  },
  'workspace-volume'() {
    const matches = (container().Mounts ?? []).filter(
      (mount) => mount.Type === 'volume' && mount.Destination === '/workspace',
    )
    if (matches.length !== 1 || !matches[0].Name) {
      throw new ProbeError('daemon workspace named volume was not found')
    }
    console.log(matches[0].Name)
  },
  'daemon-mount'() {
    const mounts = container().Mounts ?? []
    if (mounts.some((mount) => mount.Type === 'bind')) {
      throw new ProbeError('daemon has a forbidden bind mount')
    }
    if (mounts.length !== 1) {
      throw new ProbeError(`daemon expected exactly one mount, found ${mounts.length}`)
    }
    const [mount] = mounts
    if (mount.Type !== 'volume' || mount.Destination !== '/workspace') {
      throw new ProbeError(`unexpected daemon mount: ${render(mount.Type)}:${render(mount.Destination)}`)
    }
    if (mount.RW !== true) {
      throw new ProbeError('daemon workspace volume is not writable')
    }
    console.log('daemon_mount=volume:/workspace writable=true')
  },
  'compose-status'() {
    const raw = input.trim()
    if (!raw) {
      console.log('no reliability containers')
      return
    }
    let rows
    try {
      const decoded = JSON.parse(raw)
      rows = Array.isArray(decoded) ? decoded : [decoded]
    } catch {
      rows = raw
        .split('\n')
        .filter((line) => line.trim())
        .map((line) => JSON.parse(line))
    }
    const service = (row) => row.Service ?? ''
    for (const row of rows.sort((a, b) => (service(a) < service(b) ? -1 : service(a) > service(b) ? 1 : 0))) {
      console.log(`service=${render(row.Service)} state=${render(row.State)} health=${row.Health || '-'}`)
    }
  },
}

const [command, ...args] = process.argv.slice(2)
if (!(command in commands)) {
  process.stderr.write(`unknown probe: ${command}\n`)
  process.exit(2)
}
try {
  commands[command](...args)
} catch (error) {
  process.stderr.write(`${error.message}\n`)
  process.exitCode = 1
}
