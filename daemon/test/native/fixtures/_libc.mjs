import { setTimeout as sleep } from 'node:timers/promises'

/** 调用 libc 的 setpgid/setsid：夹具要能真实建立新的进程组/会话，才能验证收敛覆盖多组与显式登记。 */
export async function libc() {
  const koffi = (await import('koffi')).default
  const library = koffi.load(null)
  return {
    koffi,
    setpgid: library.func('int setpgid(int, int)'),
    setsid: library.func('int setsid()'),
    flock: library.func('int flock(int, int)'),
  }
}

export { sleep }
