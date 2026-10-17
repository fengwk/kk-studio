import { describe, expect, test, vi } from 'vitest'

// prctl 失败必须让 Linux 子进程收养者初始化**失败关闭**：静默降级成「没有收养者」会让「未登记且 setsid 的后代也能被核实」
// 的承诺失真。这里把 koffi 的 prctl 绑定替换为固定失败，验证真实分支而不是伪造返回值。
vi.mock('koffi', async (importOriginal) => {
  const actual = await importOriginal<Record<string, unknown>>()
  const library = {
    func: (signature: string) => (signature.includes('prctl') ? () => -1 : () => 0),
  }
  const stubbed = {
    ...actual,
    load: () => library,
    errno: () => 1,
  }
  return { ...stubbed, default: stubbed }
})

import { becomeChildSubreaper } from '../../src/native/posix-session.js'
import { createProcessSupervisor } from '../../src/native/index.js'

describe.runIf(process.platform === 'linux')('native subreaper failure', () => {
  test('becomeChildSubreaper fails closed when prctl fails', () => {
    expect(() => becomeChildSubreaper()).toThrow(/prctl failed with errno 1/)
  })

  test('the supervisor fails closed when it cannot become a child subreaper', () => {
    expect(() => createProcessSupervisor()).toThrow(/prctl failed/)
  })
})
