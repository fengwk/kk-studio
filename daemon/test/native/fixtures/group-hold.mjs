import { libc } from './_libc.mjs'
import { holdForever, recordPid } from './_util.mjs'

// 在**同一个会话内**加入一个新的进程组：验证收敛覆盖交互式 job control 产生的多组。
const { setpgid } = await libc()
if (setpgid(0, 0) !== 0) {
  process.exit(2)
}
await recordPid(process.argv[2])
holdForever()
