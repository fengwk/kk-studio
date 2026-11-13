/**
 * Capability JSON Schema 的单一事实源同步：Java classpath 资源是源，
 * daemon-go/internal/capability/schemas 是生成副本。
 *
 * <p>只复制已提交的 schema 资源，不改 schema 内容。
 * `--check` 只比较；默认写入。多余副本视为漂移。
 */
import { readdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = dirname(fileURLToPath(import.meta.url))
const REPO = resolve(HERE, '../../../..')
const JAVA_DIR = resolve(
  REPO,
  'harness/environment/src/main/resources/fun/fengwk/kkstudio/harness/environment/capability/schemas',
)
const DAEMON_DIR = resolve(REPO, 'daemon-go/internal/capability/schemas')
const CHECK = process.argv.includes('--check')

function normalize(text) {
  return text.replace(/\s+$/u, '') + '\n'
}

const sources = readdirSync(JAVA_DIR)
  .filter((name) => name.endsWith('.schema.json'))
  .sort()
if (sources.length === 0) {
  throw new Error(`no capability schemas under ${JAVA_DIR}`)
}

const copies = new Set(
  readdirSync(DAEMON_DIR).filter((name) => name.endsWith('.schema.json')),
)
const drift = []

for (const name of sources) {
  const source = normalize(readFileSync(resolve(JAVA_DIR, name), 'utf8'))
  const target = resolve(DAEMON_DIR, name)
  copies.delete(name)
  let current = null
  try {
    current = normalize(readFileSync(target, 'utf8'))
  } catch {
    current = null
  }
  if (current === source) {
    continue
  }
  drift.push(name)
  if (!CHECK) {
    writeFileSync(target, source)
  }
}

for (const extra of copies) {
  drift.push(`extra:${extra}`)
  if (!CHECK) {
    rmSync(resolve(DAEMON_DIR, extra))
  }
}

if (drift.length > 0) {
  if (CHECK) {
    throw new Error(`capability schema drift: ${drift.join(', ')}`)
  }
  console.log(`updated capability schemas: ${drift.join(', ')}`)
} else {
  console.log('capability schemas match the Java resources')
}
