import { spawn } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import { holdForever, recordPid, waitForFile } from './_util.mjs'

// 派生一个故意脱离会话（setsid）的子进程；等它写出 pid 后继续存活。
const [pidFile, childPidFile] = process.argv.slice(2)
await recordPid(pidFile)
const child = spawn(process.execPath, [fileURLToPath(new URL('./escape-hold.mjs', import.meta.url)), childPidFile], { stdio: 'ignore' })
child.unref()
await waitForFile(childPidFile)
holdForever()
