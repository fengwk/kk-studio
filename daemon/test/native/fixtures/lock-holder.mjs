import { constants, closeSync, openSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { libc } from './_libc.mjs'
import { holdForever, recordPid } from './_util.mjs'

// 用独立实现持有目标目录的内核 flock：验证父进程的锁是真实内核锁，且崩溃时由内核释放。
const lockDir = process.argv[2]
const readyFile = process.argv[3]
const lockName = process.argv[4] ?? 'daemon.lock'
const { flock } = await libc()
const descriptor = openSync(join(lockDir, lockName), constants.O_RDWR | constants.O_CREAT, 0o600)
const LOCK_EX = 2
const LOCK_NB = 4
if (flock(descriptor, LOCK_EX | LOCK_NB) !== 0) {
  writeFileSync(readyFile, 'blocked')
  closeSync(descriptor)
  process.exit(3)
}
await recordPid(readyFile)
holdForever()
