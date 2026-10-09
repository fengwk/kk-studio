/**
 * 终端输入编码器（terminal-input-encoder）全覆盖与真实字节断言测试。
 *
 * 验证 CONTRACT.md 规定的纯函数 API：
 * - encodeTerminalKey
 * - encodeTerminalText
 * - encodeTerminalPaste
 * - splitTerminalInput
 * - encodeTerminalMouse
 * - encodeTerminalFocus
 */

import { describe, expect, it } from 'vitest'
import {
  encodeTerminalFocus,
  encodeTerminalKey,
  encodeTerminalMouse,
  encodeTerminalPaste,
  encodeTerminalText,
  INPUT_CHUNK_MAX_BYTES,
  MAX_INPUT_BUFFER_BYTES,
  splitTerminalInput,
  TerminalInputError,
  TERMINAL_INPUT_INVALID_MESSAGE,
  type TerminalMouseEvent,
} from './terminal-input-encoder'
import type { TerminalInputModes } from './terminal-view-codec'

function createModes(overrides: Partial<TerminalInputModes> = {}): TerminalInputModes {
  return {
    applicationCursor: false,
    applicationKeypad: false,
    bracketedPaste: false,
    autoNewLine: false,
    altSendsEscape: true,
    mouseMode: 'NORMAL',
    mouseFormat: 'SGR',
    ...overrides,
  }
}

function toAsciiString(bytes: Uint8Array | null): string | null {
  if (!bytes) return null
  return String.fromCharCode(...bytes)
}

describe('terminal-input-encoder', () => {
  describe('encodeTerminalKey', () => {
    it('isComposing / altGraph / meta 抑制所有键字节', () => {
      const modes = createModes()
      expect(
        encodeTerminalKey({ key: 'Enter', isComposing: true }, modes),
      ).toBeNull()
      expect(
        encodeTerminalKey({ key: 'a', altGraph: true }, modes),
      ).toBeNull()
      expect(
        encodeTerminalKey({ key: 'c', meta: true, ctrl: true }, modes),
      ).toBeNull()
    })

    describe('控制键：Enter / Backspace / Esc / Tab', () => {
      it('Enter 在不同 autoNewLine 与 altSendsEscape 下正确编码', () => {
        const defaultModes = createModes({ autoNewLine: false, altSendsEscape: true })
        expect(encodeTerminalKey({ key: 'Enter' }, defaultModes)).toEqual(
          new Uint8Array([0x0d]),
        )

        const autoNlModes = createModes({ autoNewLine: true })
        expect(encodeTerminalKey({ key: 'Enter' }, autoNlModes)).toEqual(
          new Uint8Array([0x0d, 0x0a]),
        )

        const altModes = createModes({ autoNewLine: false, altSendsEscape: true })
        expect(encodeTerminalKey({ key: 'Enter', alt: true }, altModes)).toEqual(
          new Uint8Array([0x1b, 0x0d]),
        )

        const noAltEscapeModes = createModes({ autoNewLine: false, altSendsEscape: false })
        expect(encodeTerminalKey({ key: 'Enter', alt: true }, noAltEscapeModes)).toEqual(
          new Uint8Array([0x0d]),
        )
      })

      it('Backspace 在普通、Ctrl 及 Alt 下正确编码', () => {
        const modes = createModes({ altSendsEscape: true })
        // DEL: 0x7f
        expect(encodeTerminalKey({ key: 'Backspace' }, modes)).toEqual(
          new Uint8Array([0x7f]),
        )
        // Ctrl+Backspace -> BS: 0x08
        expect(encodeTerminalKey({ key: 'Backspace', ctrl: true }, modes)).toEqual(
          new Uint8Array([0x08]),
        )
        // Alt+Backspace -> ESC + DEL
        expect(encodeTerminalKey({ key: 'Backspace', alt: true }, modes)).toEqual(
          new Uint8Array([0x1b, 0x7f]),
        )
        // Alt+Ctrl+Backspace -> ESC + BS
        expect(
          encodeTerminalKey({ key: 'Backspace', alt: true, ctrl: true }, modes),
        ).toEqual(new Uint8Array([0x1b, 0x08]))

        const noAltEscape = createModes({ altSendsEscape: false })
        expect(encodeTerminalKey({ key: 'Backspace', alt: true }, noAltEscape)).toEqual(
          new Uint8Array([0x7f]),
        )
      })

      it('Escape 及其 Alt 修饰正确编码', () => {
        const modes = createModes({ altSendsEscape: true })
        expect(encodeTerminalKey({ key: 'Escape' }, modes)).toEqual(
          new Uint8Array([0x1b]),
        )
        expect(encodeTerminalKey({ key: 'Escape', alt: true }, modes)).toEqual(
          new Uint8Array([0x1b, 0x1b]),
        )

        const noAltEscape = createModes({ altSendsEscape: false })
        expect(encodeTerminalKey({ key: 'Escape', alt: true }, noAltEscape)).toEqual(
          new Uint8Array([0x1b]),
        )
      })

      it('Tab 与 ShiftTab 正确编码', () => {
        const modes = createModes({ altSendsEscape: true })
        // Tab -> HT (0x09)
        expect(encodeTerminalKey({ key: 'Tab' }, modes)).toEqual(
          new Uint8Array([0x09]),
        )
        // Alt+Tab -> ESC + HT
        expect(encodeTerminalKey({ key: 'Tab', alt: true }, modes)).toEqual(
          new Uint8Array([0x1b, 0x09]),
        )
        // Shift+Tab -> ESC [ Z
        expect(encodeTerminalKey({ key: 'Tab', shift: true }, modes)).toEqual(
          new Uint8Array([0x1b, 0x5b, 0x5a]),
        )
        // Alt+Shift+Tab -> ESC ESC [ Z
        expect(
          encodeTerminalKey({ key: 'Tab', shift: true, alt: true }, modes),
        ).toEqual(new Uint8Array([0x1b, 0x1b, 0x5b, 0x5a]))
      })
    })

    describe('导航键：ArrowUp/Down/Right/Left 与 Home/End', () => {
      it('Arrow 键在 normal CSI 与 applicationCursor 下无修饰编码', () => {
        const normalModes = createModes({ applicationCursor: false })
        expect(toAsciiString(encodeTerminalKey({ key: 'ArrowUp' }, normalModes))).toBe(
          '\x1b[A',
        )
        expect(toAsciiString(encodeTerminalKey({ key: 'ArrowDown' }, normalModes))).toBe(
          '\x1b[B',
        )
        expect(toAsciiString(encodeTerminalKey({ key: 'ArrowRight' }, normalModes))).toBe(
          '\x1b[C',
        )
        expect(toAsciiString(encodeTerminalKey({ key: 'ArrowLeft' }, normalModes))).toBe(
          '\x1b[D',
        )

        const appModes = createModes({ applicationCursor: true })
        expect(toAsciiString(encodeTerminalKey({ key: 'ArrowUp' }, appModes))).toBe(
          '\x1bOA',
        )
        expect(toAsciiString(encodeTerminalKey({ key: 'ArrowDown' }, appModes))).toBe(
          '\x1bOB',
        )
        expect(toAsciiString(encodeTerminalKey({ key: 'ArrowRight' }, appModes))).toBe(
          '\x1bOC',
        )
        expect(toAsciiString(encodeTerminalKey({ key: 'ArrowLeft' }, appModes))).toBe(
          '\x1bOD',
        )
      })

      it('Home/End 键在 normal CSI 与 applicationKeypad 下无修饰编码', () => {
        const normalModes = createModes({ applicationKeypad: false })
        expect(toAsciiString(encodeTerminalKey({ key: 'Home' }, normalModes))).toBe(
          '\x1b[H',
        )
        expect(toAsciiString(encodeTerminalKey({ key: 'End' }, normalModes))).toBe(
          '\x1b[F',
        )

        const appModes = createModes({ applicationKeypad: true })
        expect(toAsciiString(encodeTerminalKey({ key: 'Home' }, appModes))).toBe(
          '\x1bOH',
        )
        expect(toAsciiString(encodeTerminalKey({ key: 'End' }, appModes))).toBe(
          '\x1bOF',
        )
      })

      it('导航键在有 Shift/Alt/Ctrl 修饰时使用 xterm 参数且不叠 ESC', () => {
        const modes = createModes({ altSendsEscape: true })
        // m = 1 + shift*1 + alt*2 + ctrl*4
        // Shift (m=2)
        expect(
          toAsciiString(encodeTerminalKey({ key: 'ArrowUp', shift: true }, modes)),
        ).toBe('\x1b[1;2A')
        // Alt (m=3) - 不额外再叠 ESC
        expect(
          toAsciiString(encodeTerminalKey({ key: 'ArrowDown', alt: true }, modes)),
        ).toBe('\x1b[1;3B')
        // Shift+Alt (m=4)
        expect(
          toAsciiString(
            encodeTerminalKey({ key: 'ArrowRight', shift: true, alt: true }, modes),
          ),
        ).toBe('\x1b[1;4C')
        // Ctrl (m=5)
        expect(
          toAsciiString(encodeTerminalKey({ key: 'ArrowLeft', ctrl: true }, modes)),
        ).toBe('\x1b[1;5D')
        // Shift+Ctrl (m=6)
        expect(
          toAsciiString(
            encodeTerminalKey({ key: 'Home', shift: true, ctrl: true }, modes),
          ),
        ).toBe('\x1b[1;6H')
        // Alt+Ctrl (m=7)
        expect(
          toAsciiString(
            encodeTerminalKey({ key: 'End', alt: true, ctrl: true }, modes),
          ),
        ).toBe('\x1b[1;7F')
        // Shift+Alt+Ctrl (m=8)
        expect(
          toAsciiString(
            encodeTerminalKey(
              { key: 'ArrowUp', shift: true, alt: true, ctrl: true },
              modes,
            ),
          ),
        ).toBe('\x1b[1;8A')
      })
    })

    describe('编辑键：Insert / Delete / PageUp / PageDown', () => {
      it('无修饰与修饰时使用 CSI n~ 与 CSI n;m~', () => {
        const modes = createModes()
        expect(toAsciiString(encodeTerminalKey({ key: 'Insert' }, modes))).toBe(
          '\x1b[2~',
        )
        expect(toAsciiString(encodeTerminalKey({ key: 'Delete' }, modes))).toBe(
          '\x1b[3~',
        )
        expect(toAsciiString(encodeTerminalKey({ key: 'PageUp' }, modes))).toBe(
          '\x1b[5~',
        )
        expect(toAsciiString(encodeTerminalKey({ key: 'PageDown' }, modes))).toBe(
          '\x1b[6~',
        )

        // 带修饰 Ctrl+Delete -> m=5 -> CSI 3;5~
        expect(
          toAsciiString(encodeTerminalKey({ key: 'Delete', ctrl: true }, modes)),
        ).toBe('\x1b[3;5~')
        // Shift+PageUp -> m=2 -> CSI 5;2~
        expect(
          toAsciiString(encodeTerminalKey({ key: 'PageUp', shift: true }, modes)),
        ).toBe('\x1b[5;2~')
      })
    })

    describe('功能键：F1..F12', () => {
      it('F1..F4 在无修饰时 SS3，有修饰时 CSI 1;m', () => {
        const modes = createModes()
        expect(toAsciiString(encodeTerminalKey({ key: 'F1' }, modes))).toBe('\x1bOP')
        expect(toAsciiString(encodeTerminalKey({ key: 'F2' }, modes))).toBe('\x1bOQ')
        expect(toAsciiString(encodeTerminalKey({ key: 'F3' }, modes))).toBe('\x1bOR')
        expect(toAsciiString(encodeTerminalKey({ key: 'F4' }, modes))).toBe('\x1bOS')

        // Shift+F1 -> m=2
        expect(
          toAsciiString(encodeTerminalKey({ key: 'F1', shift: true }, modes)),
        ).toBe('\x1b[1;2P')
        // Ctrl+F4 -> m=5
        expect(
          toAsciiString(encodeTerminalKey({ key: 'F4', ctrl: true }, modes)),
        ).toBe('\x1b[1;5S')
      })

      it('F5..F12 在无修饰时 CSI n~，有修饰时 CSI n;m~', () => {
        const modes = createModes()
        const expected = [
          ['F5', 15],
          ['F6', 17],
          ['F7', 18],
          ['F8', 19],
          ['F9', 20],
          ['F10', 21],
          ['F11', 23],
          ['F12', 24],
        ] as const

        for (const [key, n] of expected) {
          expect(toAsciiString(encodeTerminalKey({ key }, modes))).toBe(`\x1b[${n}~`)
          expect(
            toAsciiString(encodeTerminalKey({ key, ctrl: true }, modes)),
          ).toBe(`\x1b[${n};5~`)
        }
      })
    })

    describe('数字小键盘（Numpad）', () => {
      it('applicationKeypad 开启时无修饰映射为 SS3', () => {
        const modes = createModes({ applicationKeypad: true })
        expect(toAsciiString(encodeTerminalKey({ key: '0', code: 'Numpad0' }, modes))).toBe('\x1bOp')
        expect(toAsciiString(encodeTerminalKey({ key: '1', code: 'Numpad1' }, modes))).toBe('\x1bOq')
        expect(toAsciiString(encodeTerminalKey({ key: '9', code: 'Numpad9' }, modes))).toBe('\x1bOy')
        expect(toAsciiString(encodeTerminalKey({ key: '.', code: 'NumpadDecimal' }, modes))).toBe('\x1bOn')
        expect(toAsciiString(encodeTerminalKey({ key: '/', code: 'NumpadDivide' }, modes))).toBe('\x1bOo')
        expect(toAsciiString(encodeTerminalKey({ key: '*', code: 'NumpadMultiply' }, modes))).toBe('\x1bOj')
        expect(toAsciiString(encodeTerminalKey({ key: '-', code: 'NumpadSubtract' }, modes))).toBe('\x1bOm')
        expect(toAsciiString(encodeTerminalKey({ key: '+', code: 'NumpadAdd' }, modes))).toBe('\x1bOk')
        expect(toAsciiString(encodeTerminalKey({ key: 'Enter', code: 'NumpadEnter' }, modes))).toBe('\x1bOM')
        expect(toAsciiString(encodeTerminalKey({ key: '=', code: 'NumpadEqual' }, modes))).toBe('\x1bOX')
      })

      it('applicationKeypad 开启时带修饰采用 CSI 1;m final', () => {
        const modes = createModes({ applicationKeypad: true })
        // Shift+Numpad0 -> m=2
        expect(
          toAsciiString(encodeTerminalKey({ key: '0', code: 'Numpad0', shift: true }, modes)),
        ).toBe('\x1b[1;2p')
        // Ctrl+NumpadEnter -> m=5
        expect(
          toAsciiString(
            encodeTerminalKey({ key: 'Enter', code: 'NumpadEnter', ctrl: true }, modes),
          ),
        ).toBe('\x1b[1;5M')
      })

      it('applicationKeypad 关闭时 NumpadEnter 仍按普通 Enter 处理', () => {
        const modes = createModes({ applicationKeypad: false, autoNewLine: false })
        expect(
          encodeTerminalKey({ key: 'Enter', code: 'NumpadEnter' }, modes),
        ).toEqual(new Uint8Array([0x0d]))

        const modesAutoNl = createModes({ applicationKeypad: false, autoNewLine: true })
        expect(
          encodeTerminalKey({ key: 'Enter', code: 'NumpadEnter' }, modesAutoNl),
        ).toEqual(new Uint8Array([0x0d, 0x0a]))
      })

      it('applicationKeypad 关闭时普通 Numpad 无修饰交 text，返回 null', () => {
        const modes = createModes({ applicationKeypad: false })
        expect(encodeTerminalKey({ key: '0', code: 'Numpad0' }, modes)).toBeNull()
        expect(encodeTerminalKey({ key: '+', code: 'NumpadAdd' }, modes)).toBeNull()
      })
    })

    describe('Ctrl 组合键与 Alt+Ctrl 映射', () => {
      it('Ctrl A..Z 映射为 1..26（不区分大小写）', () => {
        const modes = createModes()
        expect(encodeTerminalKey({ key: 'a', ctrl: true }, modes)).toEqual(
          new Uint8Array([1]),
        )
        expect(encodeTerminalKey({ key: 'A', ctrl: true }, modes)).toEqual(
          new Uint8Array([1]),
        )
        expect(encodeTerminalKey({ key: 'c', ctrl: true }, modes)).toEqual(
          new Uint8Array([3]),
        )
        expect(encodeTerminalKey({ key: 'z', ctrl: true }, modes)).toEqual(
          new Uint8Array([26]),
        )
      })

      it('Ctrl 特殊字符映射至 0, 27..31, 127', () => {
        const modes = createModes()
        // space / @ / 2 -> 0
        expect(encodeTerminalKey({ key: ' ', ctrl: true }, modes)).toEqual(
          new Uint8Array([0]),
        )
        expect(encodeTerminalKey({ key: '@', ctrl: true }, modes)).toEqual(
          new Uint8Array([0]),
        )
        expect(encodeTerminalKey({ key: '2', ctrl: true }, modes)).toEqual(
          new Uint8Array([0]),
        )

        // [ / 3 -> 27
        expect(encodeTerminalKey({ key: '[', ctrl: true }, modes)).toEqual(
          new Uint8Array([27]),
        )
        expect(encodeTerminalKey({ key: '3', ctrl: true }, modes)).toEqual(
          new Uint8Array([27]),
        )

        // backslash / 4 -> 28
        expect(encodeTerminalKey({ key: '\\', ctrl: true }, modes)).toEqual(
          new Uint8Array([28]),
        )
        expect(encodeTerminalKey({ key: '4', ctrl: true }, modes)).toEqual(
          new Uint8Array([28]),
        )

        // ] / 5 -> 29
        expect(encodeTerminalKey({ key: ']', ctrl: true }, modes)).toEqual(
          new Uint8Array([29]),
        )
        expect(encodeTerminalKey({ key: '5', ctrl: true }, modes)).toEqual(
          new Uint8Array([29]),
        )

        // ^ / 6 -> 30
        expect(encodeTerminalKey({ key: '^', ctrl: true }, modes)).toEqual(
          new Uint8Array([30]),
        )
        expect(encodeTerminalKey({ key: '6', ctrl: true }, modes)).toEqual(
          new Uint8Array([30]),
        )

        // _ / slash / 7 -> 31
        expect(encodeTerminalKey({ key: '_', ctrl: true }, modes)).toEqual(
          new Uint8Array([31]),
        )
        expect(encodeTerminalKey({ key: '/', ctrl: true }, modes)).toEqual(
          new Uint8Array([31]),
        )
        expect(encodeTerminalKey({ key: '7', ctrl: true }, modes)).toEqual(
          new Uint8Array([31]),
        )

        // ? / 8 -> 127
        expect(encodeTerminalKey({ key: '?', ctrl: true }, modes)).toEqual(
          new Uint8Array([127]),
        )
        expect(encodeTerminalKey({ key: '8', ctrl: true }, modes)).toEqual(
          new Uint8Array([127]),
        )
      })

      it('Ctrl 无法映射的字符不产生 bytes，返回 null', () => {
        const modes = createModes()
        expect(encodeTerminalKey({ key: '1', ctrl: true }, modes)).toBeNull()
        expect(encodeTerminalKey({ key: '9', ctrl: true }, modes)).toBeNull()
        expect(encodeTerminalKey({ key: ';', ctrl: true }, modes)).toBeNull()
        expect(encodeTerminalKey({ key: 'F13', ctrl: true }, modes)).toBeNull()
      })

      it('Alt+Ctrl 按 altSendsEscape 决定前置 ESC', () => {
        const withEsc = createModes({ altSendsEscape: true })
        expect(
          encodeTerminalKey({ key: 'c', ctrl: true, alt: true }, withEsc),
        ).toEqual(new Uint8Array([0x1b, 0x03]))

        const noEsc = createModes({ altSendsEscape: false })
        expect(
          encodeTerminalKey({ key: 'c', ctrl: true, alt: true }, noEsc),
        ).toEqual(new Uint8Array([0x03]))
      })
    })

    describe('普通打印键与 Alt 打印键', () => {
      it('普通无 Ctrl/Alt 可打印 key 在 keydown 返回 null（独占交 text 提交）', () => {
        const modes = createModes()
        expect(encodeTerminalKey({ key: 'a' }, modes)).toBeNull()
        expect(encodeTerminalKey({ key: 'A', shift: true }, modes)).toBeNull()
        expect(encodeTerminalKey({ key: '1' }, modes)).toBeNull()
        expect(encodeTerminalKey({ key: ' ' }, modes)).toBeNull()
        expect(encodeTerminalKey({ key: '中' }, modes)).toBeNull()
        expect(encodeTerminalKey({ key: '🚀' }, modes)).toBeNull()
      })

      it('Alt 打印键在 altSendsEscape=true 时发 ESC + UTF8(key)', () => {
        const modes = createModes({ altSendsEscape: true })
        expect(encodeTerminalKey({ key: 'x', alt: true }, modes)).toEqual(
          new Uint8Array([0x1b, 0x78]),
        )

        // 中文字符
        const zhUtf8 = new TextEncoder().encode('中')
        expect(encodeTerminalKey({ key: '中', alt: true }, modes)).toEqual(
          new Uint8Array([0x1b, ...zhUtf8]),
        )

        // Emoji 代理对
        const emojiUtf8 = new TextEncoder().encode('🚀')
        expect(encodeTerminalKey({ key: '🚀', alt: true }, modes)).toEqual(
          new Uint8Array([0x1b, ...emojiUtf8]),
        )
      })

      it('Alt 打印键在 altSendsEscape=false 时返回 null（交文字提交）', () => {
        const modes = createModes({ altSendsEscape: false })
        expect(encodeTerminalKey({ key: 'x', alt: true }, modes)).toBeNull()
        expect(encodeTerminalKey({ key: '中', alt: true }, modes)).toBeNull()
      })

      it('单独修饰键（Shift/Alt/Control）不产生字节', () => {
        const modes = createModes()
        expect(encodeTerminalKey({ key: 'Shift' }, modes)).toBeNull()
        expect(encodeTerminalKey({ key: 'Control' }, modes)).toBeNull()
        expect(encodeTerminalKey({ key: 'Alt' }, modes)).toBeNull()
        expect(encodeTerminalKey({ key: 'CapsLock' }, modes)).toBeNull()
      })
    })
  })

  describe('encodeTerminalText', () => {
    it('空文本返回 null', () => {
      expect(encodeTerminalText('')).toBeNull()
    })

    it('合法 UTF-8 文本正确编码，不做 Unicode normalization', () => {
      const ascii = 'Hello, world!\r\n'
      expect(encodeTerminalText(ascii)).toEqual(new TextEncoder().encode(ascii))

      const cjk = '你好，终端！'
      expect(encodeTerminalText(cjk)).toEqual(new TextEncoder().encode(cjk))

      const emoji = '🚀🔥'
      expect(encodeTerminalText(emoji)).toEqual(new TextEncoder().encode(emoji))

      // 不做 Unicode normalization：e + 组合音符 与 单一 é 字节应保持原样
      const combined = 'e\u0301'
      expect(encodeTerminalText(combined)).toEqual(new TextEncoder().encode(combined))
      expect(encodeTerminalText(combined)?.length).toBe(3) // 'e'(1) + 0x0301(2)
    })

    it('拒绝 lone surrogate 并抛出固定 TerminalInputError', () => {
      // 孤立 high surrogate
      expect(() => encodeTerminalText('\ud83d')).toThrow(TerminalInputError)
      expect(() => encodeTerminalText('\ud83d')).toThrow(
        TERMINAL_INPUT_INVALID_MESSAGE,
      )

      // 孤立 low surrogate
      expect(() => encodeTerminalText('\ude80')).toThrow(TerminalInputError)

      // high surrogate 后跟非 low surrogate
      expect(() => encodeTerminalText('\ud83dA')).toThrow(TerminalInputError)
    })
  })

  describe('encodeTerminalPaste', () => {
    it('空内容返回 null，不生成括号帧', () => {
      const modesBracketed = createModes({ bracketedPaste: true })
      expect(encodeTerminalPaste('', modesBracketed)).toBeNull()

      const modesNormal = createModes({ bracketedPaste: false })
      expect(encodeTerminalPaste('', modesNormal)).toBeNull()
    })

    it('规范换行 CRLF/LF -> CR，保留原有 CR', () => {
      const modes = createModes({ bracketedPaste: false })
      const input = 'line1\r\nline2\nline3\rline4'
      const expected = new Uint8Array(new TextEncoder().encode('line1\rline2\rline3\rline4'))
      expect(encodeTerminalPaste(input, modes)).toEqual(expected)
    })

    it('bracketedPaste 为 true 时包裹 ESC[200~ 与 ESC[201~', () => {
      const modes = createModes({ bracketedPaste: true })
      const text = 'hello'
      const encoded = encodeTerminalPaste(text, modes)
      expect(encoded).not.toBeNull()

      // 前 6 字节: ESC[200~
      expect(encoded?.slice(0, 6)).toEqual(
        new Uint8Array([0x1b, 0x5b, 0x32, 0x30, 0x30, 0x7e]),
      )
      // 中间: hello
      expect(encoded?.slice(6, 11)).toEqual(
        new Uint8Array([0x68, 0x65, 0x6c, 0x6c, 0x6f]),
      )
      // 后 6 字节: ESC[201~
      expect(encoded?.slice(11)).toEqual(
        new Uint8Array([0x1b, 0x5b, 0x32, 0x30, 0x31, 0x7e]),
      )
    })

    it('拒绝 lone surrogate 粘贴', () => {
      const modes = createModes()
      expect(() => encodeTerminalPaste('bad\ud800text', modes)).toThrow(
        TerminalInputError,
      )
    })

    it('计算包含括号在内的总字节数，超过 64KiB 抛错且不静默截断', () => {
      const modes = createModes({ bracketedPaste: true })
      // 括号占 12 字节，允许文本最多 65536 - 12 = 65524 字节
      const exactText = 'a'.repeat(MAX_INPUT_BUFFER_BYTES - 12)
      const validBytes = encodeTerminalPaste(exactText, modes)
      expect(validBytes?.length).toBe(MAX_INPUT_BUFFER_BYTES)

      // 超过 1 字节 -> 65537
      const overflowText = 'a'.repeat(MAX_INPUT_BUFFER_BYTES - 12 + 1)
      expect(() => encodeTerminalPaste(overflowText, modes)).toThrow(
        TerminalInputError,
      )
    })

    it('无 bracketedPaste 时在 64KiB 边界精准断言', () => {
      const modes = createModes({ bracketedPaste: false })
      const exactText = 'a'.repeat(MAX_INPUT_BUFFER_BYTES)
      const valid = encodeTerminalPaste(exactText, modes)
      expect(valid?.length).toBe(MAX_INPUT_BUFFER_BYTES)

      const overflowText = 'a'.repeat(MAX_INPUT_BUFFER_BYTES + 1)
      expect(() => encodeTerminalPaste(overflowText, modes)).toThrow(
        TerminalInputError,
      )
    })
  })

  describe('splitTerminalInput', () => {
    it('空数组返回空 list', () => {
      expect(splitTerminalInput(new Uint8Array(0))).toEqual([])
    })

    it('小于等于 4096 字节保留单段并完成深复制', () => {
      const original = new Uint8Array([1, 2, 3, 4])
      const chunks = splitTerminalInput(original)
      expect(chunks.length).toBe(1)
      expect(chunks[0]).toEqual(original)

      // 验证深复制独立性：修改 chunk 不影响原 buffer
      chunks[0][0] = 99
      expect(original[0]).toBe(1)
    })

    it('多段深复制拆分，每段 <= 4096 bytes，可跨 UTF-8 边界', () => {
      const totalSize = 5000
      const source = new Uint8Array(totalSize)
      for (let i = 0; i < totalSize; i++) {
        source[i] = i % 256
      }

      const chunks = splitTerminalInput(source)
      expect(chunks.length).toBe(2)
      expect(chunks[0].byteLength).toBe(INPUT_CHUNK_MAX_BYTES)
      expect(chunks[1].byteLength).toBe(totalSize - INPUT_CHUNK_MAX_BYTES)

      // 校验拼接内容一致性
      const concatenated = new Uint8Array(totalSize)
      concatenated.set(chunks[0], 0)
      concatenated.set(chunks[1], chunks[0].byteLength)
      expect(concatenated).toEqual(source)
    })

    it('总量超过 64KiB (65536) 抛出 TerminalInputError', () => {
      const overflow = new Uint8Array(MAX_INPUT_BUFFER_BYTES + 1)
      expect(() => splitTerminalInput(overflow)).toThrow(TerminalInputError)
    })

    it('恰好 64KiB 成功拆分为 16 段', () => {
      const maxData = new Uint8Array(MAX_INPUT_BUFFER_BYTES)
      const chunks = splitTerminalInput(maxData)
      expect(chunks.length).toBe(16)
      for (const chunk of chunks) {
        expect(chunk.byteLength).toBe(INPUT_CHUNK_MAX_BYTES)
      }
    })
  })

  describe('encodeTerminalMouse', () => {
    const cols = 80
    const rows = 24

    describe('尺寸与字段非法校验（抛 TerminalInputError）', () => {
      const validEvent: TerminalMouseEvent = {
        action: 'press',
        button: 0,
        x: 10,
        y: 10,
      }
      const modes = createModes()

      it('event 为空或非对象抛 TerminalInputError', () => {
        expect(() =>
          // @ts-expect-error 测试 null event
          encodeTerminalMouse(null, modes, cols, rows),
        ).toThrow(TerminalInputError)
        expect(() =>
          // @ts-expect-error 测试非对象 event
          encodeTerminalMouse('invalid', modes, cols, rows),
        ).toThrow(TerminalInputError)
      })

      it('非法 cols / rows 抛 TerminalInputError', () => {
        expect(() => encodeTerminalMouse(validEvent, modes, 0, rows)).toThrow(
          TerminalInputError,
        )
        expect(() => encodeTerminalMouse(validEvent, modes, cols, -1)).toThrow(
          TerminalInputError,
        )
        expect(() => encodeTerminalMouse(validEvent, modes, 80.5, rows)).toThrow(
          TerminalInputError,
        )
      })

      it('非法 action 或非法坐标数值抛错', () => {
        expect(() =>
          // @ts-expect-error 测试非法 action
          encodeTerminalMouse({ ...validEvent, action: 'click' }, modes, cols, rows),
        ).toThrow(TerminalInputError)

        expect(() =>
          encodeTerminalMouse({ ...validEvent, x: 10.5 }, modes, cols, rows),
        ).toThrow(TerminalInputError)
      })

      it('press / release 必须包含有效 button(0/1/2) 且不能有 wheel', () => {
        expect(() =>
          // @ts-expect-error 测试缺少 button
          encodeTerminalMouse({ action: 'press', x: 1, y: 1 }, modes, cols, rows),
        ).toThrow(TerminalInputError)

        expect(() =>
          encodeTerminalMouse(
            // @ts-expect-error 测试非法 button
            { action: 'press', button: 3, x: 1, y: 1 },
            modes,
            cols,
            rows,
          ),
        ).toThrow(TerminalInputError)

        expect(() =>
          encodeTerminalMouse(
            { action: 'press', button: 0, wheel: 'up', x: 1, y: 1 },
            modes,
            cols,
            rows,
          ),
        ).toThrow(TerminalInputError)
      })

      it('wheel 必须包含合法 wheel(up/down)', () => {
        expect(() =>
          encodeTerminalMouse({ action: 'wheel', x: 1, y: 1 }, modes, cols, rows),
        ).toThrow(TerminalInputError)

        expect(() =>
          encodeTerminalMouse(
            // @ts-expect-error 测试非法 wheel
            { action: 'wheel', wheel: 'left', x: 1, y: 1 },
            modes,
            cols,
            rows,
          ),
        ).toThrow(TerminalInputError)
      })

      it('move 只能有 0/1/2/null/undefined button 且不能有 wheel', () => {
        expect(() =>
          encodeTerminalMouse(
            // @ts-expect-error 测试非法 move button
            { action: 'move', button: 4, x: 1, y: 1 },
            modes,
            cols,
            rows,
          ),
        ).toThrow(TerminalInputError)

        expect(() =>
          encodeTerminalMouse(
            { action: 'move', button: null, wheel: 'up', x: 1, y: 1 },
            modes,
            cols,
            rows,
          ),
        ).toThrow(TerminalInputError)
      })
    })

    describe('权威 screen 槽坐标越界与不可表达（返回 null，不 clamp）', () => {
      const modes = createModes()

      it('x/y < 1 或 x > cols 或 y > rows 返回 null', () => {
        expect(
          encodeTerminalMouse(
            { action: 'press', button: 0, x: 0, y: 1 },
            modes,
            cols,
            rows,
          ),
        ).toBeNull()
        expect(
          encodeTerminalMouse(
            { action: 'press', button: 0, x: 1, y: 0 },
            modes,
            cols,
            rows,
          ),
        ).toBeNull()
        expect(
          encodeTerminalMouse(
            { action: 'press', button: 0, x: cols + 1, y: 1 },
            modes,
            cols,
            rows,
          ),
        ).toBeNull()
        expect(
          encodeTerminalMouse(
            { action: 'press', button: 0, x: 1, y: rows + 1 },
            modes,
            cols,
            rows,
          ),
        ).toBeNull()
      })
    })

    describe('mouseMode 模式过滤', () => {
      it('NONE 与 FOCUS 模式不生成鼠标事件', () => {
        const noneModes = createModes({ mouseMode: 'NONE' })
        const focusModes = createModes({ mouseMode: 'FOCUS' })
        const evt: TerminalMouseEvent = { action: 'press', button: 0, x: 1, y: 1 }

        expect(encodeTerminalMouse(evt, noneModes, cols, rows)).toBeNull()
        expect(encodeTerminalMouse(evt, focusModes, cols, rows)).toBeNull()
      })

      it('NORMAL 模式响应 press/release/wheel，抑制 move', () => {
        const modes = createModes({ mouseMode: 'NORMAL' })
        expect(
          encodeTerminalMouse(
            { action: 'move', button: 0, x: 1, y: 1 },
            modes,
            cols,
            rows,
          ),
        ).toBeNull()
        expect(
          encodeTerminalMouse(
            { action: 'press', button: 0, x: 1, y: 1 },
            modes,
            cols,
            rows,
          ),
        ).not.toBeNull()
      })

      it('HILITE 模式仅响应 press，抑制 release/wheel/move', () => {
        const modes = createModes({ mouseMode: 'HILITE' })
        expect(
          encodeTerminalMouse(
            { action: 'press', button: 0, x: 1, y: 1 },
            modes,
            cols,
            rows,
          ),
        ).not.toBeNull()
        expect(
          encodeTerminalMouse(
            { action: 'release', button: 0, x: 1, y: 1 },
            modes,
            cols,
            rows,
          ),
        ).toBeNull()
        expect(
          encodeTerminalMouse(
            { action: 'wheel', wheel: 'up', x: 1, y: 1 },
            modes,
            cols,
            rows,
          ),
        ).toBeNull()
        expect(
          encodeTerminalMouse(
            { action: 'move', button: 0, x: 1, y: 1 },
            modes,
            cols,
            rows,
          ),
        ).toBeNull()
      })

      it('BUTTON_MOTION 响应有 button 的 move，抑制无 button 的 move', () => {
        const modes = createModes({ mouseMode: 'BUTTON_MOTION' })
        expect(
          encodeTerminalMouse(
            { action: 'move', button: null, x: 1, y: 1 },
            modes,
            cols,
            rows,
          ),
        ).toBeNull()
        expect(
          encodeTerminalMouse(
            { action: 'move', button: 0, x: 1, y: 1 },
            modes,
            cols,
            rows,
          ),
        ).not.toBeNull()
      })

      it('ALL_MOTION 响应包括无 button 在内的所有 move', () => {
        const modes = createModes({ mouseMode: 'ALL_MOTION' })
        expect(
          encodeTerminalMouse(
            { action: 'move', button: null, x: 1, y: 1 },
            modes,
            cols,
            rows,
          ),
        ).not.toBeNull()
      })
    })

    describe('SGR 鼠标格式编码', () => {
      const modes = createModes({ mouseMode: 'ALL_MOTION', mouseFormat: 'SGR' })

      it('press 编码为 ESC[<button;x;yM', () => {
        expect(
          toAsciiString(
            encodeTerminalMouse(
              { action: 'press', button: 0, x: 12, y: 15 },
              modes,
              cols,
              rows,
            ),
          ),
        ).toBe('\x1b[<0;12;15M')
        expect(
          toAsciiString(
            encodeTerminalMouse(
              { action: 'press', button: 1, x: 12, y: 15 },
              modes,
              cols,
              rows,
            ),
          ),
        ).toBe('\x1b[<1;12;15M')
        expect(
          toAsciiString(
            encodeTerminalMouse(
              { action: 'press', button: 2, x: 12, y: 15 },
              modes,
              cols,
              rows,
            ),
          ),
        ).toBe('\x1b[<2;12;15M')
      })

      it('release 保留真实 button 且使用 final m', () => {
        expect(
          toAsciiString(
            encodeTerminalMouse(
              { action: 'release', button: 0, x: 12, y: 15 },
              modes,
              cols,
              rows,
            ),
          ),
        ).toBe('\x1b[<0;12;15m')
        expect(
          toAsciiString(
            encodeTerminalMouse(
              { action: 'release', button: 2, x: 12, y: 15 },
              modes,
              cols,
              rows,
            ),
          ),
        ).toBe('\x1b[<2;12;15m')
      })

      it('move 在有无 button 时分别增加 32', () => {
        // button 0 + motion 32 = 32
        expect(
          toAsciiString(
            encodeTerminalMouse(
              { action: 'move', button: 0, x: 5, y: 8 },
              modes,
              cols,
              rows,
            ),
          ),
        ).toBe('\x1b[<32;5;8M')
        // 无 button: 3 + motion 32 = 35
        expect(
          toAsciiString(
            encodeTerminalMouse(
              { action: 'move', button: null, x: 5, y: 8 },
              modes,
              cols,
              rows,
            ),
          ),
        ).toBe('\x1b[<35;5;8M')
      })

      it('wheel up/down 编码为 64/65', () => {
        expect(
          toAsciiString(
            encodeTerminalMouse(
              { action: 'wheel', wheel: 'up', x: 2, y: 3 },
              modes,
              cols,
              rows,
            ),
          ),
        ).toBe('\x1b[<64;2;3M')
        expect(
          toAsciiString(
            encodeTerminalMouse(
              { action: 'wheel', wheel: 'down', x: 2, y: 3 },
              modes,
              cols,
              rows,
            ),
          ),
        ).toBe('\x1b[<65;2;3M')
      })

      it('修饰键 shift(4), alt(8), ctrl(16) 正确叠加', () => {
        // button 0 + shift 4 = 4
        expect(
          toAsciiString(
            encodeTerminalMouse(
              { action: 'press', button: 0, shift: true, x: 1, y: 1 },
              modes,
              cols,
              rows,
            ),
          ),
        ).toBe('\x1b[<4;1;1M')
        // button 0 + alt 8 = 8
        expect(
          toAsciiString(
            encodeTerminalMouse(
              { action: 'press', button: 0, alt: true, x: 1, y: 1 },
              modes,
              cols,
              rows,
            ),
          ),
        ).toBe('\x1b[<8;1;1M')
        // button 0 + ctrl 16 = 16
        expect(
          toAsciiString(
            encodeTerminalMouse(
              { action: 'press', button: 0, ctrl: true, x: 1, y: 1 },
              modes,
              cols,
              rows,
            ),
          ),
        ).toBe('\x1b[<16;1;1M')
        // 全部叠加: 0 + 4 + 8 + 16 = 28
        expect(
          toAsciiString(
            encodeTerminalMouse(
              {
                action: 'press',
                button: 0,
                shift: true,
                alt: true,
                ctrl: true,
                x: 1,
                y: 1,
              },
              modes,
              cols,
              rows,
            ),
          ),
        ).toBe('\x1b[<28;1;1M')
      })
    })

    describe('URXVT (1015) 鼠标格式编码', () => {
      const modes = createModes({ mouseMode: 'ALL_MOTION', mouseFormat: 'URXVT' })

      it('press 编码为 ESC[button+32;x;yM', () => {
        expect(
          toAsciiString(
            encodeTerminalMouse(
              { action: 'press', button: 0, x: 10, y: 20 },
              modes,
              cols,
              rows,
            ),
          ),
        ).toBe('\x1b[32;10;20M')
      })

      it('release 使用 legacy 3 -> ESC[35;x;yM', () => {
        expect(
          toAsciiString(
            encodeTerminalMouse(
              { action: 'release', button: 0, x: 10, y: 20 },
              modes,
              cols,
              rows,
            ),
          ),
        ).toBe('\x1b[35;10;20M')
      })

      it('wheel up 编码为 64 + 32 = 96', () => {
        expect(
          toAsciiString(
            encodeTerminalMouse(
              { action: 'wheel', wheel: 'up', x: 5, y: 5 },
              modes,
              cols,
              rows,
            ),
          ),
        ).toBe('\x1b[96;5;5M')
      })

      it('move 在有无 button 时正确编码', () => {
        expect(
          toAsciiString(
            encodeTerminalMouse(
              { action: 'move', button: 0, x: 10, y: 20 },
              modes,
              cols,
              rows,
            ),
          ),
        ).toBe('\x1b[64;10;20M')
        expect(
          toAsciiString(
            encodeTerminalMouse(
              { action: 'move', button: null, x: 10, y: 20 },
              modes,
              cols,
              rows,
            ),
          ),
        ).toBe('\x1b[67;10;20M')
      })
    })

    describe('XTERM 格式编码与 223 限制', () => {
      const wideCols = 300
      const wideRows = 300
      const modes = createModes({ mouseMode: 'ALL_MOTION', mouseFormat: 'XTERM' })

      it('6 字节原始 byte，坐标不超过 223', () => {
        const bytes = encodeTerminalMouse(
          { action: 'press', button: 0, x: 10, y: 20 },
          modes,
          wideCols,
          wideRows,
        )
        expect(bytes).toEqual(
          new Uint8Array([0x1b, 0x5b, 0x4d, 0 + 32, 10 + 32, 20 + 32]),
        )
      })

      it('release, wheel 及 move 正确编码为原始字节', () => {
        // release -> legacy 3
        expect(
          encodeTerminalMouse(
            { action: 'release', button: 0, x: 10, y: 20 },
            modes,
            wideCols,
            wideRows,
          ),
        ).toEqual(new Uint8Array([0x1b, 0x5b, 0x4d, 3 + 32, 10 + 32, 20 + 32]))

        // wheel up -> 64
        expect(
          encodeTerminalMouse(
            { action: 'wheel', wheel: 'up', x: 10, y: 20 },
            modes,
            wideCols,
            wideRows,
          ),
        ).toEqual(new Uint8Array([0x1b, 0x5b, 0x4d, 64 + 32, 10 + 32, 20 + 32]))

        // move with button 0 -> 32
        expect(
          encodeTerminalMouse(
            { action: 'move', button: 0, x: 10, y: 20 },
            modes,
            wideCols,
            wideRows,
          ),
        ).toEqual(new Uint8Array([0x1b, 0x5b, 0x4d, 32 + 32, 10 + 32, 20 + 32]))

        // move without button -> 35
        expect(
          encodeTerminalMouse(
            { action: 'move', button: null, x: 10, y: 20 },
            modes,
            wideCols,
            wideRows,
          ),
        ).toEqual(new Uint8Array([0x1b, 0x5b, 0x4d, 35 + 32, 10 + 32, 20 + 32]))
      })

      it('高字节原始保留，不被 UTF-8 双字节扩展', () => {
        // x = 150 -> x + 32 = 182 (0xB6)
        const bytes = encodeTerminalMouse(
          { action: 'press', button: 0, x: 150, y: 20 },
          modes,
          wideCols,
          wideRows,
        )
        expect(bytes?.length).toBe(6)
        expect(bytes?.[4]).toBe(182)
      })

      it('x > 223 或 y > 223 不可表达返回 null', () => {
        expect(
          encodeTerminalMouse(
            { action: 'press', button: 0, x: 224, y: 20 },
            modes,
            wideCols,
            wideRows,
          ),
        ).toBeNull()
        expect(
          encodeTerminalMouse(
            { action: 'press', button: 0, x: 20, y: 224 },
            modes,
            wideCols,
            wideRows,
          ),
        ).toBeNull()
      })
    })

    describe('XTERM_EXT (1005) 格式编码与 2015 限制', () => {
      const ultraCols = 2500
      const ultraRows = 2500
      const modes = createModes({ mouseMode: 'ALL_MOTION', mouseFormat: 'XTERM_EXT' })

      it('坐标作为 UTF-8 字符值编码', () => {
        // x = 10 -> 10 + 32 = 42 (单字节 ASCII '*')
        const bytes = encodeTerminalMouse(
          { action: 'press', button: 0, x: 10, y: 20 },
          modes,
          ultraCols,
          ultraRows,
        )
        expect(bytes).toEqual(
          new Uint8Array([0x1b, 0x5b, 0x4d, 32, 42, 52]),
        )

        // x = 150 -> 150 + 32 = 182 (2 字节 UTF-8: 0xc2, 0xb6)
        const bytesExt = encodeTerminalMouse(
          { action: 'press', button: 0, x: 150, y: 20 },
          modes,
          ultraCols,
          ultraRows,
        )
        expect(bytesExt).not.toBeNull()
        expect(bytesExt?.slice(0, 4)).toEqual(new Uint8Array([0x1b, 0x5b, 0x4d, 32]))
        expect(bytesExt?.slice(4, 6)).toEqual(new Uint8Array([0xc2, 0xb6]))
        expect(bytesExt?.slice(6)).toEqual(new Uint8Array([52]))
      })

      it('release, wheel 及 move 在 XTERM_EXT 下编码为 UTF-8 字符序列', () => {
        // release (legacy button 3 + 32 = 35)
        const relBytes = encodeTerminalMouse(
          { action: 'release', button: 0, x: 10, y: 20 },
          modes,
          ultraCols,
          ultraRows,
        )
        expect(relBytes).toEqual(new Uint8Array([0x1b, 0x5b, 0x4d, 35, 42, 52]))

        // wheel up (64 + 32 = 96)
        const wheelBytes = encodeTerminalMouse(
          { action: 'wheel', wheel: 'up', x: 10, y: 20 },
          modes,
          ultraCols,
          ultraRows,
        )
        expect(wheelBytes).toEqual(new Uint8Array([0x1b, 0x5b, 0x4d, 96, 42, 52]))

        // move with button 0 (0 + 32 + 32 = 64)
        const moveBtnBytes = encodeTerminalMouse(
          { action: 'move', button: 0, x: 10, y: 20 },
          modes,
          ultraCols,
          ultraRows,
        )
        expect(moveBtnBytes).toEqual(new Uint8Array([0x1b, 0x5b, 0x4d, 64, 42, 52]))

        // move without button (3 + 32 + 32 = 67)
        const moveNoBtnBytes = encodeTerminalMouse(
          { action: 'move', button: null, x: 10, y: 20 },
          modes,
          ultraCols,
          ultraRows,
        )
        expect(moveNoBtnBytes).toEqual(new Uint8Array([0x1b, 0x5b, 0x4d, 67, 42, 52]))
      })

      it('x > 2015 或 y > 2015 不可表达返回 null', () => {
        expect(
          encodeTerminalMouse(
            { action: 'press', button: 0, x: 2016, y: 20 },
            modes,
            ultraCols,
            ultraRows,
          ),
        ).toBeNull()
        expect(
          encodeTerminalMouse(
            { action: 'press', button: 0, x: 20, y: 2016 },
            modes,
            ultraCols,
            ultraRows,
          ),
        ).toBeNull()
      })
    })
  })

  describe('encodeTerminalFocus', () => {
    it('mouseMode === FOCUS 时生成 ESC[I 与 ESC[O', () => {
      const focusModes = createModes({ mouseMode: 'FOCUS' })
      expect(encodeTerminalFocus(true, focusModes)).toEqual(
        new Uint8Array([0x1b, 0x5b, 0x49]),
      )
      expect(encodeTerminalFocus(false, focusModes)).toEqual(
        new Uint8Array([0x1b, 0x5b, 0x4f]),
      )
    })

    it('mouseMode !== FOCUS 时返回 null', () => {
      const normalModes = createModes({ mouseMode: 'NORMAL' })
      expect(encodeTerminalFocus(true, normalModes)).toBeNull()
      expect(encodeTerminalFocus(false, normalModes)).toBeNull()

      const sgrModes = createModes({ mouseMode: 'BUTTON_MOTION', mouseFormat: 'SGR' })
      expect(encodeTerminalFocus(true, sgrModes)).toBeNull()
    })

    it('输入非法 boolean 抛 TerminalInputError', () => {
      const focusModes = createModes({ mouseMode: 'FOCUS' })
      expect(() =>
        // @ts-expect-error 测试非法输入
        encodeTerminalFocus('true', focusModes),
      ).toThrow(TerminalInputError)
    })
  })
})
