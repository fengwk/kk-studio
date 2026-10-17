// Temporary CI observation: leave incomplete coverage data and the provider's failure untouched.
import fs from 'node:fs/promises'
import { syncBuiltinESMExports } from 'node:module'
import { basename } from 'node:path'
import { threadId } from 'node:worker_threads'

const readFile = fs.readFile
fs.readFile = async function (...args) {
  const data = await Reflect.apply(readFile, this, args)
  const path = typeof args[0] === 'string' ? args[0] : ''
  const writer = /^coverage-(\d+)-\d+-(\d+)\.json$/.exec(basename(path))
  if (!path.includes('/coverage/') || writer === null) return data
  try {
    JSON.parse(data.toString())
  } catch {
    const size = await fs.stat(path).then((value) => value.size, () => 'unavailable')
    const status = await readFile(`/proc/${writer[1]}/status`, 'utf8').catch(() => '')
    const state = /^State:\s+(\S+)/m.exec(status)?.[1] ?? 'gone'
    console.error('[v8-coverage-observation]', JSON.stringify({
      readerPid: process.pid,
      readerThread: threadId,
      testFile: globalThis.__vitest_worker__?.filepath,
      file: path,
      readBytes: Buffer.byteLength(data),
      statBytes: size,
      writerPid: writer[1],
      writerThread: writer[2],
      writerState: state,
    }))
  }
  return data
}
syncBuiltinESMExports()
