import { libc } from './_libc.mjs'
import { holdForever, recordPid } from './_util.mjs'

// 故意脱离本会话（setsid）并忽略温和信号：验证收敛在宽限之后升级到 SIGKILL。
const { setsid } = await libc()
if (setsid() < 0) {
  process.exit(2)
}
process.on('SIGTERM', () => {
  // 明确忽略温和信号。
})
await recordPid(process.argv[2])
holdForever()
