import { describe, expect, test } from 'vitest'
import { joinCommandLine, quoteArgument } from '../../src/native/windows-command-line.js'

// Windows 没有原生 argv：子进程按 MSVC/CommandLineToArgvW 重新切分命令行。纯字符串变换，可在任意平台验证。
describe('windows command line quoting', () => {
  test('joins safe arguments without adding quotes', () => {
    expect(joinCommandLine(['cmd'])).toBe('cmd')
    expect(joinCommandLine(['cmd', '/c', 'exit'])).toBe('cmd /c exit')
  })

  test('quotes arguments containing whitespace', () => {
    expect(joinCommandLine(['cmd', '/c', 'exit 3'])).toBe('cmd /c "exit 3"')
    expect(joinCommandLine(['tab\tsep'])).toBe('"tab\tsep"')
  })

  test('quotes an empty argument as an empty pair', () => {
    expect(joinCommandLine([''])).toBe('""')
    expect(joinCommandLine(['a', '', 'b'])).toBe('a "" b')
  })

  test('keeps backslashes that are not before a quote', () => {
    expect(joinCommandLine(['a\\b'])).toBe('a\\b')
    expect(joinCommandLine(['C:\\Program Files'])).toBe('"C:\\Program Files"')
  })

  test('doubles backslashes before the closing quote', () => {
    expect(joinCommandLine(['C:\\Program Files\\'])).toBe('"C:\\Program Files\\\\"')
  })

  test('escapes embedded quotes with backslashes', () => {
    expect(joinCommandLine(['say "hi"'])).toBe('"say \\"hi\\""')
    expect(joinCommandLine(['\\"'])).toBe('"\\\\\\""')
  })

  test('a single argument is quoted exactly like the joined form', () => {
    expect(quoteArgument('needs quote')).toBe('"needs quote"')
  })
})
