import fs from 'node:fs'
import {
  errorResult,
  textResult,
  type CapabilityCall,
  type CapabilityContext,
  type CapabilityExecutor,
  type CapabilityGroup,
  type CapabilityResult,
  type DaemonConfig,
  type LspServerConfig,
  type ProcessSupervisor,
} from '../contracts.js'
import { LspDiscovery, type LspSupport } from './lsp-discovery.js'
import { LspError, LspTimeoutError } from './lsp-error.js'
import type { LspPoolOptions } from './lsp-pool.js'
import { LspService } from './lsp-service.js'
import { ToolInputRejectedError, resolveExistingPath } from './support.js'
import { isAbortError } from '../native/abort.js'

export interface LspCapabilityGroup extends CapabilityGroup {
  support(file: string): LspSupport
  fileChanged(file: string): void
}

interface LspGeneration {
  service: LspService | null
  active: number
  retired: boolean
  idleWaiters: Array<() => void>
  closePromise: Promise<void> | null
}

function unavailableSupport(): LspSupport {
  return { language: null, available: false, supported: false }
}

function isTimeout(error: unknown, signal?: AbortSignal): boolean {
  return signal?.reason === 'TIMED_OUT' || error instanceof LspTimeoutError
}

/** 目标文件必须存在且是绝对路径；判定与解析复用文件能力的共享实现。 */
async function validatePath(rawPath: unknown): Promise<string> {
  if (rawPath === undefined || rawPath === null || typeof rawPath !== 'string') {
    throw new LspError('path is required and must be a string')
  }
  try {
    return await resolveExistingPath(rawPath)
  } catch (error) {
    if (error instanceof ToolInputRejectedError) {
      throw new LspError(error.message)
    }
    throw error
  }
}

/**
 * 执行 LSP 能力的通用骨架：提前取消/超时判定、入参对象校验、参数提取与合法性校验、
 * 执行范围标记、统一异常映射（超时、取消、LspError 及通用脱敏错误）。
 */
async function runLspCapability<T>(
  call: CapabilityCall,
  context: CapabilityContext,
  validate: (args: Record<string, unknown>) => Promise<T> | T,
  execute: (validated: T, signal: AbortSignal, onExecutionExit: () => void) => Promise<string>,
): Promise<CapabilityResult> {
  const callId = call.invocationId
  if (context.signal.aborted) {
    context.confirmExecutionExit()
    if (isTimeout(null, context.signal)) {
      return errorResult(callId, 'Operation timed out')
    }
    return errorResult(callId, 'Operation cancelled')
  }

  let executionStarted = false
  try {
    const args = call.arguments
    if (!args || typeof args !== 'object' || Array.isArray(args)) {
      context.confirmExecutionExit()
      return errorResult(callId, 'tool arguments must be an object')
    }

    const validated = await validate(args as Record<string, unknown>)
    executionStarted = true
    const onExecutionExit = () => context.confirmExecutionExit()
    const result = await execute(validated, context.signal, onExecutionExit)
    return textResult(callId, result)
  } catch (error) {
    if (!executionStarted) {
      // 参数校验在租约与服务调用之前完成，确定没有启动执行范围。
      context.confirmExecutionExit()
    }
    if (isTimeout(error, context.signal)) {
      return errorResult(callId, 'Operation timed out')
    }
    if (context.signal.aborted || isAbortError(error)) {
      return errorResult(callId, 'Operation cancelled')
    }
    if (error instanceof LspError) {
      return errorResult(callId, error.message)
    }
    return errorResult(
      callId,
      'The capability failed while running and the underlying error is not repeated to avoid leaking details',
    )
  }
}

/**
 * LSP 热换代 facade：能力执行器对象保持稳定，每次读取当前 generation。
 *
 * <p>更新只替换后续调用使用的 discovery/pool；旧 generation 已接受的调用继续持有自己的租约，等这些调用全部结束后才关闭
 * 旧 pool。禁用 LSP 时新请求明确不可用，旧调用仍按既有超时/取消生命周期收敛。
 */
export class LspFacade implements LspCapabilityGroup {
  private generation: LspGeneration
  readonly capabilities: Record<string, CapabilityExecutor>

  constructor(
    private readonly processes: ProcessSupervisor,
    private readonly options: LspPoolOptions = {},
  ) {
    this.generation = openGeneration({})
    this.capabilities = {
      'lsp.goto-definition': (call, context) =>
        runLspCapability(
          call,
          context,
          async (args) => {
            const realPath = await validatePath(args['path'])
            let stat: fs.Stats
            try {
              stat = fs.statSync(realPath)
            } catch {
              throw new LspError('path must exist: ' + realPath)
            }
            if (stat.isDirectory()) {
              throw new LspError('path must be a file: ' + realPath)
            }

            const rawLine = args['line']
            if (
              rawLine === undefined ||
              rawLine === null ||
              typeof rawLine !== 'number' ||
              !Number.isInteger(rawLine) ||
              rawLine < 1
            ) {
              throw new LspError('line is required and must be a positive integer')
            }

            let character = 0
            const rawChar = args['character']
            if (rawChar !== undefined && rawChar !== null) {
              if (typeof rawChar !== 'number' || !Number.isInteger(rawChar) || rawChar < 0) {
                throw new LspError('character must be a non-negative integer')
              }
              character = rawChar
            }

            return { realPath, line: rawLine, character }
          },
          ({ realPath, line, character }, signal, onExecutionExit) =>
            this.withService((service) => service.gotoDefinition(realPath, line, character, signal, onExecutionExit), onExecutionExit),
        ),
      'lsp.workspace-symbols': (call, context) =>
        runLspCapability(
          call,
          context,
          async (args) => {
            const realPath = await validatePath(args['path'])
            const rawQuery = args['query']
            if (rawQuery === undefined || rawQuery === null || typeof rawQuery !== 'string') {
              throw new LspError('query is required and must be a string')
            }
            if (rawQuery.trim().length === 0) {
              throw new LspError('query must not be blank')
            }

            let limit = 50
            const rawLimit = args['limit']
            if (rawLimit !== undefined && rawLimit !== null) {
              if (typeof rawLimit !== 'number' || !Number.isInteger(rawLimit) || rawLimit < 0) {
                throw new LspError('limit must be a non-negative integer')
              }
              if (rawLimit > 500) {
                throw new LspError('limit must be <= 500')
              }
              limit = rawLimit
            }

            return { realPath, query: rawQuery, limit }
          },
          ({ realPath, query, limit }, signal, onExecutionExit) =>
            this.withService((service) => service.workspaceSymbols(realPath, query, limit, signal, onExecutionExit), onExecutionExit),
        ),
      'lsp.java-decompile': (call, context) =>
        runLspCapability(
          call,
          context,
          async (args) => {
            const realPath = await validatePath(args['path'])
            const rawTarget = args['target']
            if (rawTarget === undefined || rawTarget === null || typeof rawTarget !== 'string') {
              throw new LspError('target is required and must be a string')
            }
            if (rawTarget.trim().length === 0) {
              throw new LspError('target must not be blank')
            }
            return { realPath, target: rawTarget }
          },
          ({ realPath, target }, signal, onExecutionExit) =>
            this.withService((service) => service.javaDecompile(realPath, target, signal, onExecutionExit), onExecutionExit),
        ),
    }
  }

  support(file: string): LspSupport {
    const service = this.generation.service
    return service === null ? unavailableSupport() : service.support(file)
  }

  fileChanged(file: string): void {
    this.generation.service?.fileChanged(file)
  }

  /** 发布新的 LSP 声明，返回旧 generation 的收尾 Promise；不在途硬杀。 */
  update(lsp: Record<string, LspServerConfig>): Promise<void> {
    const prepared = this.prepare(lsp)
    const retirement = prepared.publish()
    return retirement ?? Promise.resolve()
  }

  /** 准备新 generation；发布前仍使用当前 facade。 */
  prepare(lsp: Record<string, LspServerConfig>): {
    publish(): Promise<void>
    retire(): Promise<void>
  } {
    const candidate = openGeneration(lsp, this.processes, this.options)
    return {
      publish: () => {
        const previous = this.generation
        this.generation = candidate
        previous.retired = true
        return retire(previous)
      },
      retire: () => retire(candidate),
    }
  }

  async close(): Promise<void> {
    return this.update({})
  }

  private async withService<T>(
    action: (service: LspService) => Promise<T>,
    onExecutionExit: () => void,
  ): Promise<T> {
    const generation = this.generation
    const service = generation.service
    if (service === null) {
      onExecutionExit()
      throw new LspError('LSP is not configured for this environment')
    }
    generation.active++
    try {
      return await action(service)
    } finally {
      generation.active--
      if (generation.retired && generation.active === 0) {
        for (const resolve of generation.idleWaiters.splice(0)) {
          resolve()
        }
      }
    }
  }
}

function openGeneration(
  lsp: Record<string, LspServerConfig>,
  processes?: ProcessSupervisor,
  options: LspPoolOptions = {},
): LspGeneration {
  const configured = Object.keys(lsp).length > 0
  return {
    service: configured ? new LspService(LspDiscovery.fromConfiguration(lsp), processes!, options) : null,
    active: 0,
    retired: false,
    idleWaiters: [],
    closePromise: null,
  }
}

function retire(generation: LspGeneration): Promise<void> {
  if (generation.closePromise !== null) {
    return generation.closePromise
  }
  generation.closePromise = (async () => {
    if (generation.active > 0) {
      await new Promise<void>((resolve) => generation.idleWaiters.push(resolve))
    }
    await generation.service?.close()
  })()
  return generation.closePromise
}

/** 以初始卡片配置创建稳定 facade；调用方显式发布初始声明。 */
export function createLspFacade(processes: ProcessSupervisor, options?: LspPoolOptions): LspFacade {
  return new LspFacade(processes, options)
}
