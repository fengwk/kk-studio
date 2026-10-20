/** Guard CI image drift without skipping real database tests or starting a container. */

import assert from 'node:assert/strict'
import test from 'node:test'

import { POSTGRES_CLIENT_TOOLS, POSTGRES_MAJOR, requirePostgresClients } from './support/postgres-fixture.mjs'

const versionOutput = (version) => ({ status: 0, stdout: `psql (PostgreSQL) ${version}\n` })

test('test intent: complete current or newer toolchains are accepted', () => {
  // A newer pg_dump can read the fixture, provided pg_restore uses the same major.
  for (const major of [POSTGRES_MAJOR, POSTGRES_MAJOR + 1]) {
    const calls = []
    requirePostgresClients((tool, args) => {
      calls.push([tool, ...args])
      return versionOutput(`${major}.1`)
    })
    assert.deepEqual(calls, POSTGRES_CLIENT_TOOLS.map((tool) => [tool, '--version']))
  }
})

test('test intent: old, mixed or unknown versions are rejected', () => {
  // A new psql must not hide an old pg_dump or an incompatible restore binary.
  const cases = [
    [['17.1', '16.1', '17.1', '17.1', '17.1'], /pg_dump must/],
    [['17.1', '17.1', '18.1', '17.1', '17.1'], /same major/],
    [Array(5).fill('unknown'), /psql must/],
  ]
  for (const [versions, message] of cases) {
    const queue = [...versions]
    assert.throws(() => requirePostgresClients(() => versionOutput(queue.shift())), message)
  }
})

test('test intent: missing, broken or hung tooling fails with a bounded setup error', () => {
  const failures = [
    { error: new Error('ENOENT'), status: null, stdout: '' },
    { status: 1, stdout: '' },
    { error: new Error('ETIMEDOUT'), status: null, stdout: '' },
  ]
  for (const failure of failures) {
    const calls = []
    assert.throws(
      () =>
        requirePostgresClients((tool, args) => {
          calls.push([tool, ...args])
          return failure
        }),
      /PostgreSQL client unavailable: psql/,
    )
    assert.deepEqual(calls, [['psql', '--version']])
  }
})
