import fs from 'node:fs'
import { syncBuiltinESMExports } from 'node:module'

const [file, explicitPid] = process.argv.slice(2)
const originalWrite = fs.writeFileSync
// 探针只在自己的子进程中拦截直写最终文件，避免未完成的内容对读者可见。
fs.writeFileSync = (path, ...args) => {
  if (String(path) === file) {
    throw new Error('PID content must be written before the final path is published')
  }
  return originalWrite(path, ...args)
}
syncBuiltinESMExports()

const { recordPid } = await import('../../native/fixtures/_util.mjs')
if (explicitPid === undefined) {
  await recordPid(file)
} else {
  await recordPid(file, Number(explicitPid))
}
if (fs.existsSync(`${file}.tmp`)) {
  throw new Error('temporary PID file was not renamed')
}
process.stdout.write(fs.readFileSync(file, 'utf8'))
