import { mkdirSync, writeFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

import { decodeBinary, encodeBinary } from '../dist/protocol/control/codec.js'
import { GOLDEN_FIXTURES } from '../test/protocol/control-fixtures.mjs'

const HERE = dirname(fileURLToPath(import.meta.url))
const GOLDEN = resolve(HERE, '../../harness/environment/src/main/protocol/golden')

const fixtures = GOLDEN_FIXTURES.map(([name, factory]) => [name, factory()])

mkdirSync(GOLDEN, { recursive: true })
for (const [name, envelope] of fixtures) {
  const bytes = encodeBinary(envelope)
  const decoded = decodeBinary(bytes)
  const roundtrip = encodeBinary(decoded)
  if (!Buffer.from(bytes).equals(Buffer.from(roundtrip))) {
    throw new Error(`${name} does not roundtrip byte-for-byte`)
  }
  writeFileSync(resolve(GOLDEN, name), bytes)
  console.log(`${name}: ${bytes.length} bytes`)
}
