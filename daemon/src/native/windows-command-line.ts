/**
 * Windows 命令行拼装：把已经解析好的 argv 还原成 `CreateProcessW` 需要的单个命令行字符串。
 *
 * <p>Windows 没有 argv 这种原生表示，子进程按 MSVC 运行时与 `CommandLineToArgvW` 的规则重新切分命令行。拼装规则因此必须是：
 * 空参数写成 `""`；不含空白与双引号的参数原样输出；反斜杠只在双引号前成对转义，参数尾部的反斜杠必须加倍，否则会吃掉紧随其后的
 * 结束引号。
 *
 * <p>纯字符串变换，不触碰任何原生 API，因此可在任意平台直接验证。
 */
export function joinCommandLine(argumentsList: readonly string[]): string {
  const parts: string[] = []
  for (const argument of argumentsList) {
    parts.push(quoteArgument(argument))
  }
  return parts.join(' ')
}

/** 按 MSVC 规则引用一个参数；空参数必须写成一对空引号，否则会被当成「没有这个参数」。 */
export function quoteArgument(argument: string): string {
  if (argument.length === 0 || requiresQuoting(argument)) {
    let target = '"'
    let backslashes = 0
    for (const character of argument) {
      if (character === '\\') {
        backslashes += 1
        continue
      }
      if (character === '"') {
        // 引号前的反斜杠加倍后再补一个，用来转义这个引号本身。
        target += '\\'.repeat(backslashes * 2 + 1)
        target += '"'
        backslashes = 0
        continue
      }
      target += '\\'.repeat(backslashes)
      backslashes = 0
      target += character
    }
    // 尾部反斜杠会紧邻结束引号，必须加倍。
    target += '\\'.repeat(backslashes * 2)
    return `${target}"`
  }
  return argument
}

function requiresQuoting(argument: string): boolean {
  for (const character of argument) {
    if (character === ' ' || character === '\t' || character === '"') {
      return true
    }
  }
  return false
}
