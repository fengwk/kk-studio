import { recordPid } from './_util.mjs'

// 双向模式：先把诊断写进 stderr，再把 stdin 的字节原样回写 stdout，最后等 EOF 自然退出。
const [pidFile, exitCode] = process.argv.slice(2)
await recordPid(pidFile)
const marker = 'FIXTURE-STDERR-MARKER'
process.stderr.write(`${marker}\n`)
process.stdin.pipe(process.stdout)
process.stdin.on('end', () => process.exit(Number(exitCode)))
process.stdin.on('close', () => process.exit(Number(exitCode)))
process.stdin.resume()
export { marker }
