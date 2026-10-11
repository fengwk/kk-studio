import { setTimeout as sleep } from 'node:timers/promises'

/** 写出本进程 pid；每个夹具都先登记自己，测试据此用原生 pid 观察存活。 */
export function recordPid(file) {
  return import('node:fs').then((fs) => fs.writeFileSync(file, String(process.pid)))
}

/** 有界等待一个文件出现（或抛出），避免夹具无限阻塞。 */
export async function waitForFile(file, budgetMillis = 20_000) {
  const { existsSync } = await import('node:fs')
  const deadline = Date.now() + budgetMillis
  while (!existsSync(file)) {
    if (Date.now() >= deadline) {
      throw new Error(`fixture timed out waiting for ${file}`)
    }
    await sleep(5)
  }
}

/** 让夹具长时间存活；存活时间远大于任何测试窗口，因此「已经消失」只能由收敛解释。 */
export function holdForever() {
  setInterval(() => {}, 1 << 30)
}

/** 读 stdin 直到 EOF。 */
export function readUntilEof() {
  return new Promise((resolve) => {
    process.stdin.resume()
    process.stdin.on('end', resolve)
    process.stdin.on('close', resolve)
  })
}
