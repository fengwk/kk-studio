import { recordPid, readUntilEof } from './_util.mjs'

// 读 stdin 直到 EOF，再用给定退出码自然退出。
const [pidFile, exitCode] = process.argv.slice(2)
await recordPid(pidFile)
await readUntilEof()
process.exit(Number(exitCode))
