/**
 * 入口与 Studio 共用可信浏览器边界，只接受指定环境与视图的同源 bootstrap 路径。
 * pathname 可含部署前缀；不接受跨源、网络路径、凭据或 fragment。
 */
const ENVIRONMENT_BOOTSTRAP_PATH = /^(?:\/[A-Za-z0-9_-]+)*\/environment-ide\/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\/__ide\/bootstrap$/

export function validateIdeEntryUrl(
  entryUrl: string | null | undefined,
  environmentId: string,
  viewId: string,
): string {
  if (!entryUrl || typeof entryUrl !== 'string') {
    throw new Error('Initial IDE view did not return entry URL')
  }
  if (entryUrl.startsWith('//') || /[\\\s]/.test(entryUrl)) {
    throw new Error('Invalid IDE entry URL: invalid path')
  }
  let parsed: URL
  try {
    parsed = new URL(entryUrl, window.location.origin)
  } catch {
    throw new Error('Invalid IDE entry URL: invalid URL')
  }
  if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') {
    throw new Error('Invalid IDE entry URL: protocol must be http or https')
  }
  if (parsed.username !== '' || parsed.password !== '') {
    throw new Error('Invalid IDE entry URL: userinfo must be empty')
  }
  if (parsed.origin !== window.location.origin) {
    throw new Error('Invalid IDE entry URL: must share Studio origin')
  }
  const match = ENVIRONMENT_BOOTSTRAP_PATH.exec(parsed.pathname)
  if (
    !match || match[1] !== environmentId || match[2] !== viewId || parsed.hash
    || !parsed.searchParams.get('token')
    || [...parsed.searchParams.keys()].some((key) => key !== 'token')
    || parsed.searchParams.getAll('token').length !== 1
  ) {
    throw new Error('Invalid IDE entry URL: invalid view bootstrap')
  }
  return `${parsed.pathname}${parsed.search}`
}
