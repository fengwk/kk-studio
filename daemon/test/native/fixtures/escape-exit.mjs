import { spawn } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import { recordPid, waitForFile } from './_util.mjs'

// 派生一个故意脱离会话（setsid）的子进程；等它写出 pid 后，根进程自己**自然退出**，把脱离会话的后代留给内核收养。
const [pidFile, childPidFile] = process.argv.slice(2)
await recordPid(pidFile)
const child = spawn(process.execPath, [fileURLToPath(new URL('./escape-hold.mjs', import.meta.url)), childPidFile], { stdio: 'ignore' })
child.unref()
await waitForFile(childPidFile)
process.exit(0)
