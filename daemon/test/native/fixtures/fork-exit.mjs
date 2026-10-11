import { spawn } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import { holdForever, recordPid, waitForFile } from './_util.mjs'

// 派生子进程、等它写出 pid、等调用方许可后自己退出，留下当时仍活着的子进程。
const [pidFile, childPidFile, permitFile] = process.argv.slice(2)
await recordPid(pidFile)
const child = spawn(process.execPath, [fileURLToPath(new URL('./hold.mjs', import.meta.url)), childPidFile], { stdio: 'ignore' })
child.unref()
await waitForFile(childPidFile)
await waitForFile(permitFile)
process.exit(0)
void holdForever
