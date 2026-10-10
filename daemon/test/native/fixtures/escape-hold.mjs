import { libc } from './_libc.mjs'
import { holdForever, recordPid } from './_util.mjs'

// 故意脱离本会话（setsid）：收敛不能靠单个会话覆盖它，只能由显式登记（trackDescendant）接管。
const { setsid } = await libc()
if (setsid() < 0) {
  process.exit(2)
}
await recordPid(process.argv[2])
holdForever()
