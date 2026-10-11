import { spawn } from 'node:child_process'
import { writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { holdForever, waitForFile } from './_util.mjs'

// 扮演「daemon 所有者」：用**专用 IPC 通道**拉起 scope helper，写好启动规格与许可，然后一直存活。
// 测试会对本进程发 SIGKILL，用来验证 helper 在父进程消失（IPC EOF）时收敛整个范围。
const [stateDir, helperPath, helperPidFile, command, ...commandArgs] = process.argv.slice(2)

writeFileSync(join(stateDir, 'launch'), JSON.stringify({ cwd: process.cwd(), args: [command, ...commandArgs] }))
const helper = spawn(process.execPath, ['--no-warnings', helperPath, stateDir], {
  stdio: ['pipe', 'pipe', 'pipe', 'ipc'],
})
writeFileSync(helperPidFile, String(helper.pid))
await waitForFile(join(stateDir, 'scope'))
writeFileSync(join(stateDir, 'permit'), 'permit')
holdForever()
