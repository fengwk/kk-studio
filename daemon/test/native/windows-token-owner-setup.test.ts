import { beforeEach, describe, expect, it, vi } from 'vitest'

const state = vi.hoisted(() => ({
  sameOwner: true,
  readFails: false,
  setFails: false,
  closeFails: false,
  closes: 0,
  sets: 0,
}))

vi.mock('koffi', async (importOriginal) => {
  const original = await importOriginal<typeof import('koffi')>()
  const functions: Record<string, (...args: any[]) => unknown> = {
    GetCurrentProcess: () => 1n,
    GetLastError: () => 5,
    CloseHandle: () => {
      state.closes += 1
      return state.closeFails ? 0 : 1
    },
    OpenProcessToken: (_process, _access, token) => {
      token[0] = 2n
      return 1
    },
    GetTokenInformation: (_token, kind, buffer, _length, required) => {
      required[0] = 16
      if (buffer === null || state.readFails) {
        return 0
      }
      buffer.writeBigUInt64LE(kind === 1 ? 3n : 4n, 0)
      return 1
    },
    SetTokenInformation: (_token, kind, value) => {
      expect(kind).toBe(4)
      expect(value.readBigUInt64LE()).toBe(3n)
      state.sets += 1
      return state.setFails ? 0 : 1
    },
    EqualSid: () => state.sameOwner ? 1 : 0,
  }
  return {
    ...original,
    load: () => ({
      func: (signature: string) => {
        const name = signature.slice(0, signature.indexOf('(')).split(/\s+/).at(-1)!.replace(/^\*/, '')
        if (functions[name] === undefined) {
          throw new Error(`unexpected binding: ${name}`)
        }
        return functions[name]
      },
    }),
  }
})

import { alignCurrentProcessTokenOwner } from '../windows-token-owner-setup.js'

beforeEach(() => {
  Object.assign(state, { sameOwner: true, readFails: false, setFails: false, closeFails: false, closes: 0, sets: 0 })
})

describe('Windows test process token ownership', () => {
  it('closes the token even when the owner already matches the user', async () => {
    await alignCurrentProcessTokenOwner()
    expect(state.sets).toBe(0)
    expect(state.closes).toBe(1)
  })

  it('changes only the default owner and then closes the token', async () => {
    state.sameOwner = false
    await alignCurrentProcessTokenOwner()
    expect(state.sets).toBe(1)
    expect(state.closes).toBe(1)
  })

  it('preserves the read failure and reports a simultaneous close failure', async () => {
    state.readFails = true
    state.closeFails = true
    await expect(alignCurrentProcessTokenOwner()).rejects.toThrow(/cannot read.*additionally cannot close/)
    expect(state.closes).toBe(1)
  })

  it('closes the token when setting the default owner fails', async () => {
    state.sameOwner = false
    state.setFails = true
    await expect(alignCurrentProcessTokenOwner()).rejects.toThrow(/cannot set the current test process token owner/)
    expect(state.closes).toBe(1)
  })
})
