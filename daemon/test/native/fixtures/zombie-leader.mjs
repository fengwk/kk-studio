import { spawn } from 'node:child_process'
import { once } from 'node:events'
import { readFileSync, writeFileSync } from 'node:fs'
import { setTimeout as sleep } from 'node:timers/promises'
import { fileURLToPath } from 'node:url'
import { isMainThread, parentPort, Worker, workerData } from 'node:worker_threads'
import { libc } from './_libc.mjs'
import { holdForever, recordPid, waitForFile } from './_util.mjs'

// Linux 的线程组首线程可以先退出，其他线程和子进程仍存活；/proc/<pid>/stat 此时已经是 Z。
// 只调用 SYS_exit 退出当前线程，而不是 exit_group。冻结的两种 Linux 架构使用各自内核 ABI。
if (isMainThread) {
  const [pidFile, childPidFile, sessionMode] = process.argv.slice(2)
  const syscallNumber = { x64: 60, arm64: 93 }[process.arch]
  if (process.platform !== 'linux' || syscallNumber === undefined) {
    throw new Error('the zombie leader fixture requires a supported Linux architecture')
  }
  const { koffi, setsid } = await libc()
  if (sessionMode === 'new-session' && setsid() !== process.pid) {
    throw new Error('the zombie leader fixture could not establish its session')
  }
  process.on('SIGTERM', () => {})
  const worker = new Worker(new URL(import.meta.url), { workerData: { pidFile } })
  await once(worker, 'message')
  const child = spawn(process.execPath, [fileURLToPath(new URL('./escape-hold.mjs', import.meta.url)), childPidFile], { stdio: 'ignore' })
  child.unref()
  await waitForFile(childPidFile)
  await recordPid(pidFile)
  koffi.load(null).func('long syscall(long, int)')(syscallNumber, 0)
  throw new Error('SYS_exit unexpectedly returned')
} else {
  parentPort.postMessage('ready')
  const deadline = Date.now() + 20_000
  for (;;) {
    const stat = readFileSync(`/proc/${process.pid}/stat`, 'utf8')
    const fields = stat.slice(stat.lastIndexOf(')') + 2).split(' ')
    if (fields[0] === 'Z' && Number(fields[17]) > 1) {
      writeFileSync(`${workerData.pidFile}.leader-dead`, 'ready')
      break
    }
    if (Date.now() >= deadline) {
      throw new Error('the fixture leader did not exit while its worker remained alive')
    }
    await sleep(5)
  }
  holdForever()
}
