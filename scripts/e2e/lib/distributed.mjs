/** 双节点 distributed capability 的最小上下文工具（Node 原生，无依赖）。 */

import { execFileSync } from 'node:child_process'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const __filename = fileURLToPath(import.meta.url)
const __dirname = path.dirname(__filename)
const DEFAULT_REPO_ROOT = path.resolve(__dirname, '../../..')

export const NODE_IDS = ['a', 'b']

/** 分布式测试栈受限控制命令白名单（只允许 DB 故障注入与状态检查，拒绝任意命令）。 */
export const ALLOWED_DISTRIBUTED_COMMANDS = Object.freeze([
  'disconnect-db-a',
  'reconnect-db-a',
  'status',
])

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

/**
 * 执行受限的分布式测试控制命令（固定白名单守卫，复用 deploy/distributed/run.sh）。
 * 严禁通过 options 覆盖命令白名单。
 */
export function runDistributedCommand(command, options = {}) {
  if (!ALLOWED_DISTRIBUTED_COMMANDS.includes(command)) {
    throw new Error(
      `runDistributedCommand: command '${command}' is not allowed, expected one of: ${ALLOWED_DISTRIBUTED_COMMANDS.join(', ')}`,
    )
  }
  const repoRoot = options.repoRoot || DEFAULT_REPO_ROOT
  const exec = options.exec || execFileSync
  const scriptPath = path.join(repoRoot, 'deploy/distributed/run.sh')
  return exec(scriptPath, [command], {
    cwd: repoRoot,
    encoding: 'utf8',
    timeout: options.timeout || 30_000,
    stdio: options.stdio || ['ignore', 'pipe', 'pipe'],
  })
}
