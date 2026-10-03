/** 双节点 distributed capability 的最小上下文工具（Node 原生，无依赖）。 */

import { execFileSync } from 'node:child_process'
import { mkdirSync, mkdtempSync, readdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'

import { REPO_ROOT } from '../../../lib/repo-root.mjs'
import { redactSecrets } from './redact.mjs'

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
 * 执行受限的分布式测试控制命令（固定白名单守卫，复用 scripts/dev/verify/e2e/distributed.sh）。
 * 严禁通过 options 覆盖命令白名单。
 */
export function runDistributedCommand(command, options = {}) {
  if (!ALLOWED_DISTRIBUTED_COMMANDS.includes(command)) {
    throw new Error(
      `runDistributedCommand: command '${command}' is not allowed, expected one of: ${ALLOWED_DISTRIBUTED_COMMANDS.join(', ')}`,
    )
  }
  const repoRoot = options.repoRoot || REPO_ROOT
  const exec = options.exec || execFileSync
  const scriptPath = path.join(repoRoot, 'scripts/dev/verify/e2e/distributed.sh')
  return exec(scriptPath, [command], {
    cwd: repoRoot,
    encoding: 'utf8',
    timeout: options.timeout || 30_000,
    stdio: options.stdio || ['ignore', 'pipe', 'pipe'],
  })
}

/**
 * App 的文件日志不在 docker logs 中；在销毁前复制 /app/logs，只把脱敏后的文本写入报告。
 * 原始文件在临时目录中暂存并始终删除；缺失容器/目录不阻断其他节点的日志采集。
 */
export function copyDistributedAppLogs(runDir, options = {}) {
  const exec = options.exec || execFileSync
  const composeFile = path.join(options.repoRoot || REPO_ROOT, 'deploy/distributed/compose.yaml')
  for (const service of ['app-a', 'app-b']) {
    const staging = mkdtempSync(path.join(tmpdir(), 'kk-studio-app-logs-'))
    try {
      const commandOptions = { encoding: 'utf8', timeout: 30_000, stdio: ['ignore', 'pipe', 'ignore'] }
      const container = exec('docker', ['compose', '-f', composeFile, 'ps', '-q', service],
        commandOptions).trim()
      if (!container) continue
      exec('docker', ['cp', `${container}:/app/logs/.`, staging], commandOptions)
      copyRedactedLogs(staging, path.join(runDir, 'logs', `distributed-${service}`))
    } catch {
      // 尽力采集：某个节点已销毁或没有日志目录时仍继续另一个节点。
    } finally {
      rmSync(staging, { recursive: true, force: true })
    }
  }
}

function copyRedactedLogs(source, dest) {
  mkdirSync(dest, { recursive: true })
  for (const entry of readdirSync(source, { withFileTypes: true })) {
    const src = path.join(source, entry.name)
    const target = path.join(dest, entry.name)
    if (entry.isDirectory()) copyRedactedLogs(src, target)
    // 不跟随 symlink；二进制压缩归档不能直接按文本脱敏，不复制进报告。
    else if (entry.isFile() && entry.name.endsWith('.log')) {
      writeFileSync(target, redactSecrets(readFileSync(src, 'utf8')), 'utf8')
    }
  }
}
