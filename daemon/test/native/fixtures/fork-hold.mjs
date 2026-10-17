import { spawn } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import { holdForever, recordPid, waitForFile } from './_util.mjs'

// 派生子进程并在它写出 pid 后继续存活。
const [pidFile, childPidFile] = process.argv.slice(2)
await recordPid(pidFile)
const child = spawn(process.execPath, [fileURLToPath(new URL('./hold.mjs', import.meta.url)), childPidFile], { stdio: 'ignore' })
child.unref()
await waitForFile(childPidFile)
holdForever()
