import { afterEach, describe, expect, test, vi } from 'vitest'

/**
 * 用假 libc/libproc 在 Linux 主机上驱动 `posix-session.ts` 的 macOS 分支。
 *
 * <p>Linux 开发主机无法真实验收 macOS 的 libproc；但枚举算法（`proc_listpids` 枚举全部 pid，按
 * `getsid` 归属，再用 `proc_pidinfo(PROC_PIDTBSDINFO)` 取状态与启动身份）是安全收敛的依据，
 * 必须能被廉价回归。这里只提供调用序列，绝不声称已在真实 Darwin 上运行。
 */
const h = vi.hoisted(() => {
  const state = {
    pids: [] as number[],
    sessions: new Map<number, number>(),
    info: new Map<number, { status: number; ppid: number; sec: number; usec: number }>(),
    errno: 0,
    killResult: 0,
    killCalls: [] as number[],
  }
  return { state }
})

const ESRCH = 3
const MAC_STATUS_ZOMBIE = 5

vi.mock('koffi', async (importOriginal) => {
  const actual = await importOriginal<Record<string, unknown>>()
  const library = {
    func: (signature: string) => {
      if (signature.includes('proc_listpids')) {
        return (_type: number, _typeinfo: number, buffer: Buffer | null, size: number) => {
          const needed = h.state.pids.length * 4
          if (buffer === null || needed >= size) {
            return needed >= size ? size : needed
          }
          h.state.pids.forEach((pid, index) => buffer.writeInt32LE(pid, index * 4))
          return needed
        }
      }
      if (signature.includes('proc_pidinfo')) {
        return (pid: number, _flavor: number, _arg: number, buffer: Buffer, size: number) => {
          if (!h.state.info.has(pid)) {
            return 0
          }
          buffer.writeInt32LE(pid, 0)
          return size
        }
      }
      if (signature.includes('getsid')) {
        return (pid: number) => {
          const session = pid === 0 ? 100 : h.state.sessions.get(pid)
          return session === undefined ? -1 : session
        }
      }
      if (signature.includes('getpgrp')) {
        return () => 100
      }
      if (signature.includes('setsid')) {
        return () => 100
      }
      if (signature.includes('kill')) {
        return (pid: number) => {
          h.state.killCalls.push(pid)
          return h.state.killResult
        }
      }
      return () => 0
    },
  }
  const stubbed = {
    ...actual,
    load: () => library,
    struct: () => ({ size: 32 }),
    decode: (buffer: Buffer) => {
      const pid = buffer.readInt32LE(0)
      const info = h.state.info.get(pid)
      return {
        pbi_status: info?.status ?? 0,
        pbi_ppid: info?.ppid ?? 0,
        pbi_start_tvsec: info?.sec ?? 0,
        pbi_start_tvusec: info?.usec ?? 0,
      }
    },
    errno: () => h.state.errno,
  }
  return { ...stubbed, default: stubbed }
})

import {
  convergeSession,
  isDescendantOf,
  membersSnapshot,
  processIdentity,
  sessionOf,
  signalMembers,
} from '../../src/native/posix-session.js'

function asDarwin<T>(body: () => T): T {
  const spy = vi.spyOn(process, 'platform', 'get').mockReturnValue('darwin')
  try {
    return body()
  } finally {
    spy.mockRestore()
  }
}

/** 异步版本：平台桩必须在 Promise 解决之后才撤销，否则 await 期间会退回真实平台。 */
async function asDarwinAsync<T>(body: () => Promise<T>): Promise<T> {
  const spy = vi.spyOn(process, 'platform', 'get').mockReturnValue('darwin')
  try {
    return await body()
  } finally {
    spy.mockRestore()
  }
}

afterEach(() => {
  h.state.pids = []
  h.state.sessions.clear()
  h.state.info.clear()
  h.state.errno = 0
  h.state.killResult = 0
  h.state.killCalls = []
  vi.restoreAllMocks()
})

describe('native posix session macOS enumeration (mocked libproc)', () => {
  test('sessionOf and processIdentity read the session and launch identity', () => {
    h.state.sessions.set(11, 7)
    h.state.info.set(11, { status: 0, ppid: 1, sec: 1000, usec: 250 })
    asDarwin(() => {
      expect(sessionOf(11)).toBe(7)
      expect(processIdentity(11)).toBe('1000.250')
    })
  })

  test('a zombie process has a session but no usable identity', () => {
    h.state.sessions.set(12, 7)
    h.state.info.set(12, { status: MAC_STATUS_ZOMBIE, ppid: 1, sec: 5, usec: 0 })
    asDarwin(() => {
      expect(sessionOf(12)).toBe(7)
      expect(processIdentity(12)).toBeNull()
    })
  })

  test('an unknown pid resolves to no session and no identity', () => {
    asDarwin(() => {
      expect(sessionOf(99)).toBe(-1)
      expect(processIdentity(99)).toBeNull()
    })
  })

  test('membersSnapshot enumerates only live members of the requested session', () => {
    h.state.pids = [11, 12, 13]
    h.state.sessions.set(11, 7)
    h.state.sessions.set(13, 8)
    h.state.info.set(11, { status: 0, ppid: 1, sec: 1, usec: 1 })
    h.state.info.set(13, { status: 0, ppid: 1, sec: 2, usec: 2 })
    // 未知 pid 的 getsid 失败必须是 ESRCH（内核确认没有活成员），否则整次判定不可判定。
    h.state.errno = ESRCH
    asDarwin(() => {
      const members = membersSnapshot(7, -1)
      expect(members?.map((member) => member.pid)).toEqual([11])
      expect(members?.[0]?.identity).toBe('1.1')
      // 排除某个 pid 之后它不再出现在快照里。
      expect(membersSnapshot(7, 11)).toEqual([])
    })
  })

  test('membersSnapshot is undecidable when a live member cannot be read', () => {
    h.state.pids = [11]
    h.state.sessions.set(11, 7)
    // 没有 info：proc_pidinfo 失败；kill(pid, 0) 成功表示进程仍活着 -> 不可判定，绝不谎报空。
    h.state.killResult = 0
    asDarwin(() => {
      expect(membersSnapshot(7, -1)).toBeNull()
    })
  })

  test('membersSnapshot treats a non-ESRCH getsid failure as undecidable', () => {
    h.state.pids = [11]
    // sessions 里没有 11 -> getsid 返回 -1；errno 不是 ESRCH -> 不可靠，整次判定不可判定。
    h.state.errno = 1
    asDarwin(() => {
      expect(membersSnapshot(7, -1)).toBeNull()
    })
    // ESRCH 表示内核确认没有活成员：此时可判定为空。
    h.state.errno = ESRCH
    asDarwin(() => {
      expect(membersSnapshot(7, -1)).toEqual([])
    })
  })

  test('signalMembers only signals verified members', () => {
    h.state.pids = [11, 12]
    h.state.sessions.set(11, 7)
    h.state.sessions.set(12, 7)
    h.state.info.set(11, { status: 0, ppid: 1, sec: 1, usec: 1 })
    h.state.info.set(12, { status: 0, ppid: 1, sec: 1, usec: 1 })
    asDarwin(() => {
      expect(signalMembers(7, 9, -1)).toBe(true)
      expect(h.state.killCalls).toEqual([11, 12])
    })
  })

  test('convergeSession escalates and returns false when members never disappear', async () => {
    h.state.pids = [11]
    h.state.sessions.set(11, 7)
    h.state.info.set(11, { status: 0, ppid: 1, sec: 1, usec: 1 })
    const converged = await asDarwinAsync(() => convergeSession(7, -1, 30))
    expect(converged).toBe(false)
    // 宽限之后必须升级到 SIGKILL：成员身份未变，无法证明收敛。
    expect(h.state.killCalls.length).toBeGreaterThanOrEqual(2)
  })

  test('isDescendantOf walks the parent chain via libproc', () => {
    h.state.sessions.set(11, 7)
    h.state.sessions.set(12, 7)
    h.state.info.set(11, { status: 0, ppid: 1, sec: 1, usec: 1 })
    h.state.info.set(12, { status: 0, ppid: 11, sec: 1, usec: 2 })
    asDarwin(() => {
      expect(isDescendantOf(12, 11)).toBe(true)
      expect(isDescendantOf(11, 12)).toBe(false)
      // 读不到父子链时返回 null（不可判定），绝不默认「是」。
      expect(isDescendantOf(4242, 1)).toBeNull()
    })
  })
})
