import { describe, expect, test } from 'vitest'
import { processScopeCapabilities } from '../../src/native/index.js'

// 能力自述必须与平台一致：未验收/不可证明的限制要显式暴露，而不是用同一套断言假装所有平台等价。
describe('native process scope capabilities', () => {
  test('the reported capabilities match the current platform', () => {
    const capabilities = processScopeCapabilities()
    expect(capabilities.platform).toBe(process.platform)
    if (process.platform === 'linux') {
      expect(capabilities.sessionConvergence).toBe(true)
      expect(capabilities.orphanAdoption).toBe(true)
      expect(capabilities.jobMembership).toBe(false)
    }
    if (process.platform === 'darwin') {
      // macOS 没有子进程收养者：未登记且 setsid 的后代无法被证明收敛，必须如实说明。
      expect(capabilities.sessionConvergence).toBe(true)
      expect(capabilities.orphanAdoption).toBe(false)
      expect(capabilities.unverifiedLimits.length).toBeGreaterThan(0)
    }
    if (process.platform === 'win32') {
      expect(capabilities.jobMembership).toBe(true)
      expect(capabilities.sessionConvergence).toBe(false)
    }
    if (process.platform !== 'linux' && process.platform !== 'darwin' && process.platform !== 'win32') {
      expect(capabilities.unverifiedLimits.length).toBeGreaterThan(0)
    }
  })
})
