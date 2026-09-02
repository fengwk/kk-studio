/** 双节点 distributed capability 的最小上下文工具（Node 原生，无依赖）。 */

export const NODE_IDS = ['a', 'b']

/**
 * 构造双节点 baseUrls。baseUrl 是 node A，baseUrlB 是 node B；
 * 任一缺失都视为 distributed capability 未启用，返回 null。
 */
export function createBaseUrls(baseUrl, baseUrlB) {
  if (!baseUrl || !baseUrlB) return null
  return { a: baseUrl.replace(/\/$/, ''), b: baseUrlB.replace(/\/$/, '') }
}

/**
 * 构造按节点路由的 HTTP 调用器。node 只接受 'a'|'b'，
 * 复用 httpJson 的方法/路径/超时语义，不引入新的传输层。
 */
export function createNodeCall(baseUrls) {
  return async (node, method, requestPath, body, timeoutMs) => {
    if (node !== 'a' && node !== 'b') {
      throw new Error(`callNode: unknown node '${node}', expected 'a' or 'b'`)
    }
    const { httpJson } = await import('./http.mjs')
    return httpJson(baseUrls[node], method, requestPath, body, timeoutMs)
  }
}

/** distributed case 前置守卫：缺少双节点上下文时 fail fast。 */
export function assertDistributedContext(ctx) {
  if (!ctx.baseUrls) {
    throw new Error(`case '${ctx.caseId}' requires the distributed capability (--base-url-b missing)`)
  }
  for (const node of NODE_IDS) {
    if (!ctx.baseUrls[node]) {
      throw new Error(`case '${ctx.caseId}' is missing base URL for node ${node}`)
    }
  }
}
