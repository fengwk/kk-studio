/**
 * 严格校验服务器返回的 IDE entryUrl：
 * 1. 非空且为合法绝对 URL
 * 2. 协议必须为 http 或 https
 * 3. userinfo（username/password）必须为空
 * 4. 依据服务器契约，IDE 拥有独立 Origin 模板，不得与 Studio 同源
 */
export function validateIdeEntryUrl(entryUrl: string | null): string {
  if (!entryUrl || typeof entryUrl !== 'string') {
    throw new Error('Initial IDE view did not return entry URL')
  }
  let parsed: URL
  try {
    parsed = new URL(entryUrl)
  } catch {
    throw new Error('Invalid IDE entry URL: must be an absolute URL')
  }
  if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') {
    throw new Error('Invalid IDE entry URL: protocol must be http or https')
  }
  if (parsed.username !== '' || parsed.password !== '') {
    throw new Error('Invalid IDE entry URL: userinfo must be empty')
  }
  if (typeof window !== 'undefined' && parsed.origin === window.location.origin) {
    throw new Error('Invalid IDE entry URL: IDE origin must not equal Studio origin')
  }
  return parsed.toString()
}
