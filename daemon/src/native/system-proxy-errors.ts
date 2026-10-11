/**
 * 操作系统代理委托的**固定安全诊断**。
 *
 * <p>每条文案都是常量模板：绝不回显目标 URI、环境变量、请求头、操作系统返回的代理串，或原生异常原文。原生调用失败允许
 * 追加一个数值错误码——它是有限整数，不是调用方可控内容，便于真实平台定位而不泄露信息。
 */

/** 解析器已经关闭，不能再服务查询。 */
export const SYSTEM_PROXY_CLOSED = 'the operating system proxy resolver is closed'

/** 查询通过 AbortSignal 被取消。 */
export const SYSTEM_PROXY_CANCELLED = 'the operating system proxy lookup was cancelled'

/** 无法请求取消进行中的原生查询。 */
export const SYSTEM_PROXY_CANCEL_FAILED = 'the operating system proxy lookup cancellation could not be requested'

/** 操作系统选用的代理协议（或带认证的代理）是本守护进程不支持的，必须失败关闭。 */
export const SYSTEM_PROXY_UNSUPPORTED = 'the operating system selected a proxy that the daemon does not support'

/** 操作系统代理配置无法解释，必须失败关闭而不是回退直连。 */
export const SYSTEM_PROXY_MALFORMED = 'the operating system proxy configuration is malformed'

/** 操作系统没有暴露可用的代理来源。 */
export const SYSTEM_PROXY_UNAVAILABLE = 'the operating system proxy resolver is unavailable'

/** 无法创建操作系统代理会话。 */
export const SYSTEM_PROXY_SESSION_FAILED = 'the operating system proxy session could not be opened'

/** 无法读取当前用户的代理配置。 */
export const SYSTEM_PROXY_CONFIG_FAILED = 'the current-user proxy configuration could not be read'

/** 代理查询失败。 */
export const SYSTEM_PROXY_LOOKUP_FAILED = 'the operating system proxy lookup failed'

/** 无法创建操作系统代理解析句柄。 */
export const SYSTEM_PROXY_RESOLVER_FAILED = 'the operating system proxy resolver could not be created'

/** 无法读取操作系统代理结果。 */
export const SYSTEM_PROXY_RESULT_FAILED = 'the operating system proxy result could not be read'

/** 原生句柄释放失败。 */
export const SYSTEM_PROXY_HANDLE_FAILED = 'the operating system proxy handle could not be released'

/** 会话释放未被确认。 */
export const SYSTEM_PROXY_TEARDOWN_FAILED = 'the operating system proxy session teardown was not confirmed'

/** 一次未证实安全的释放之后，解析器保持不可用，绝不再创建任何句柄或回调注册。 */
export const SYSTEM_PROXY_FATAL = 'the operating system proxy resolver is unusable after a failed teardown'

/** 固定安全文案。 */
export function systemProxyError(message: string): Error {
  return new Error(message)
}

/** 固定安全文案加数值错误码：只附加有限整数，不回显任何原始文本。 */
export function systemProxyErrorWithCode(message: string, code: number): Error {
  return new Error(`${message} (code ${code})`)
}
