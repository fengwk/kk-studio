/**
 * 把当前测试进程自己的默认新建对象属主改成当前用户。
 *
 * <p>Windows 管理员 runner 的 `TokenOwner` 可能是管理员组，而 `TokenUser` 才是登录用户。生产代码只为本次确证新建的对象显式
 * 设置当前用户属主；既有路径仍按 `TokenUser` 拒绝外部属主。这里只调整本进程令牌，使测试里 `mkdtemp`、`writeFile` 等夹具
 * 创建的文件与发行时 AtLogOn Limited 任务的属主语义一致，不改系统用户、不改已有文件、不放宽生产校验。
 */

const TOKEN_QUERY = 0x0000_0008
const TOKEN_ADJUST_DEFAULT = 0x0080
const TOKEN_USER = 1
const TOKEN_OWNER = 4

export async function alignCurrentProcessTokenOwner(): Promise<void> {
  const koffi = await import('koffi')
  const kernel32 = koffi.load('kernel32.dll')
  const advapi32 = koffi.load('advapi32.dll')
  // 匿名结构：Vitest 每个测试文件都会执行 setup，具名类型会与生产 TOKEN_USER 或上一次 setup 冲突。
  const sidAndAttributes = koffi.struct({ Sid: 'void *', Attributes: 'uint32_t' })
  const tokenUser = koffi.struct({ User: sidAndAttributes })
  const tokenOwner = koffi.struct({ Owner: 'void *' })
  const getCurrentProcess = kernel32.func('void *GetCurrentProcess()')
  const closeHandle = kernel32.func('int CloseHandle(void *handle)')
  const getLastError = kernel32.func('unsigned int GetLastError()')
  const openProcessToken = advapi32.func('int OpenProcessToken(void *process, unsigned int access, _Out_ void **token)')
  const getTokenInformation = advapi32.func('int GetTokenInformation(void *token, int infoClass, void *info, unsigned int length, _Out_ unsigned int *returnLength)')
  const setTokenInformation = advapi32.func('int SetTokenInformation(void *token, int infoClass, void *info, unsigned int length)')
  const equalSid = advapi32.func('int EqualSid(void *first, void *second)')

  const token: Array<bigint | null> = [null]
  if (openProcessToken(getCurrentProcess(), TOKEN_QUERY | TOKEN_ADJUST_DEFAULT, token) === 0 || token[0] == null) {
    throw new Error(`cannot open the current test process token: ${getLastError()}`)
  }
  const handle = token[0]
  const retained: Buffer[] = []
  try {
    const user = readTokenSid(getTokenInformation, getLastError, koffi, tokenUser, handle, TOKEN_USER, 'user')
    const owner = readTokenSid(getTokenInformation, getLastError, koffi, tokenOwner, handle, TOKEN_OWNER, 'owner')
    retained.push(user.buffer, owner.buffer)
    if (equalSid(user.sid, owner.sid) !== 0) {
      return
    }
    // user.buffer 持有 SID 字节，指针必须活过 SetTokenInformation。
    const ownerValue = Buffer.alloc(tokenOwner.size)
    retained.push(ownerValue)
    koffi.encode(ownerValue, tokenOwner, { Owner: user.sid })
    if (setTokenInformation(handle, TOKEN_OWNER, ownerValue, ownerValue.length) === 0) {
      throw new Error(`cannot set the current test process token owner: ${getLastError()}`)
    }
  } catch (error) {
    throw withTokenClose(closeHandle, getLastError, handle, error)
  }
  withTokenClose(closeHandle, getLastError, handle, null)
  retained.length = 0
}

/** 关闭令牌；已有失败保留为主错误，关闭失败只追加，不覆盖它。 */
function withTokenClose(closeHandle: (handle: bigint) => number, getLastError: () => number, handle: bigint, error: unknown): unknown {
  if (closeHandle(handle) !== 0) {
    if (error !== null) {
      throw error
    }
    return null
  }
  const cleanup = `cannot close the current test process token: ${getLastError()}`
  const message = error instanceof Error ? error.message : error === null ? null : String(error)
  throw new Error(message === null ? cleanup : `${message}; additionally ${cleanup}`)
}

function readTokenSid(
  getTokenInformation: (token: bigint, infoClass: number, info: Buffer | null, length: number, returnLength: number[]) => number,
  getLastError: () => number,
  koffi: typeof import('koffi'),
  type: { size: number },
  token: bigint,
  infoClass: number,
  kind: 'user' | 'owner',
): { sid: bigint; buffer: Buffer } {
  const required = [0]
  getTokenInformation(token, infoClass, null, 0, required)
  const length = required[0] ?? 0
  if (length === 0) {
    throw new Error(`cannot size the current test process token ${kind}`)
  }
  const buffer = Buffer.alloc(length)
  if (getTokenInformation(token, infoClass, buffer, length, required) === 0) {
    throw new Error(`cannot read the current test process token ${kind}: ${getLastError()}`)
  }
  const decoded = koffi.decode(buffer, type as never) as { User?: { Sid?: bigint | null }; Owner?: bigint | null }
  const sid = kind === 'user' ? decoded.User?.Sid ?? null : decoded.Owner ?? null
  if (sid == null) {
    throw new Error(`the current test process token has no ${kind} SID`)
  }
  return { sid, buffer }
}
