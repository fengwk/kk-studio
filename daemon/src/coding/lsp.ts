import fs from 'node:fs'
import path from 'node:path'
import {
  errorResult,
  textResult,
  type CapabilityCall,
  type CapabilityContext,
  type CapabilityGroup,
  type CapabilityResult,
  type DaemonConfig,
  type ProcessSupervisor,
} from '../contracts.js'
import { LspDiscovery, type LspSupport } from './lsp-discovery.js'
import { LspError, LspTimeoutError } from './lsp-error.js'
import type { LspPoolOptions } from './lsp-pool.js'
import { LspService } from './lsp-service.js'

export { LspDiscovery, type LspSupport } from './lsp-discovery.js'
export { LspError, LspTimeoutError } from './lsp-error.js'
export { LspService } from './lsp-service.js'
export { LspClient } from './lsp-client.js'
export { LspClientPool } from './lsp-pool.js'

export interface LspCapabilityGroup extends CapabilityGroup {
  support(file: string): LspSupport
  fileChanged(file: string): void
}

function isAbortError(error: unknown): boolean {
  return (
    error instanceof Error &&
    (error.name === 'AbortError' || error.message === 'Operation cancelled')
  )
}

function isTimeout(error: unknown, signal?: AbortSignal): boolean {
  return signal?.reason === 'TIMED_OUT' || error instanceof LspTimeoutError
}

function validatePath(rawPath: unknown): string {
  if (rawPath === undefined || rawPath === null || typeof rawPath !== 'string') {
    throw new LspError('path is required and must be a string')
  }
  if (rawPath.trim().length === 0) {
    throw new LspError('path is required')
  }
  if (!path.isAbsolute(rawPath)) {
    throw new LspError(
      'path must be an absolute path (a relative or non-absolute value was given)',
    )
  }
  if (!fs.existsSync(rawPath)) {
    throw new LspError('path does not exist: ' + rawPath)
  }
  try {
    return fs.realpathSync(rawPath)
  } catch {
    throw new LspError('path must exist: ' + rawPath)
  }
}

export function createLspCapabilities(
  config: DaemonConfig,
  processes: ProcessSupervisor,
  options?: LspPoolOptions,
): LspCapabilityGroup {
  const discovery = LspDiscovery.fromConfiguration(config.lsp)
  const service = new LspService(discovery, processes, options)

  const gotoDefinition = async (
    call: CapabilityCall,
    context: CapabilityContext,
  ): Promise<CapabilityResult> => {
    const callId = call.invocationId
    if (context.signal.aborted) {
      context.confirmExecutionExit()
      if (isTimeout(null, context.signal)) {
        return errorResult(callId, 'Operation timed out')
      }
      return errorResult(callId, 'Operation cancelled')
    }

    let requestSent = false
    try {
      const args = call.arguments
      if (!args || typeof args !== 'object' || Array.isArray(args)) {
        context.confirmExecutionExit()
        return errorResult(callId, 'tool arguments must be an object')
      }

      const realPath = validatePath(args['path'])
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
      const line = rawLine

      let character = 0
      const rawChar = args['character']
      if (rawChar !== undefined && rawChar !== null) {
        if (typeof rawChar !== 'number' || !Number.isInteger(rawChar) || rawChar < 0) {
          throw new LspError('character must be a non-negative integer')
        }
        character = rawChar
      }

      const result = await service.gotoDefinition(
        realPath,
        line,
        character,
        context.signal,
        () => context.confirmExecutionExit(),
        () => {
          requestSent = true
        },
      )
      return textResult(callId, result)
    } catch (error) {
      if (!requestSent) {
        // 校验、租约或发送前失败都没有发出这次请求。发送之后的失败不能当成未启动。
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

  const workspaceSymbols = async (
    call: CapabilityCall,
    context: CapabilityContext,
  ): Promise<CapabilityResult> => {
    const callId = call.invocationId
    if (context.signal.aborted) {
      context.confirmExecutionExit()
      if (isTimeout(null, context.signal)) {
        return errorResult(callId, 'Operation timed out')
      }
      return errorResult(callId, 'Operation cancelled')
    }

    let requestSent = false
    try {
      const args = call.arguments
      if (!args || typeof args !== 'object' || Array.isArray(args)) {
        context.confirmExecutionExit()
        return errorResult(callId, 'tool arguments must be an object')
      }

      const realPath = validatePath(args['path'])
      const rawQuery = args['query']
      if (rawQuery === undefined || rawQuery === null || typeof rawQuery !== 'string') {
        throw new LspError('query is required and must be a string')
      }
      if (rawQuery.trim().length === 0) {
        throw new LspError('query must not be blank')
      }
      const query = rawQuery

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

      const result = await service.workspaceSymbols(
        realPath,
        query,
        limit,
        context.signal,
        () => context.confirmExecutionExit(),
        () => {
          requestSent = true
        },
      )
      return textResult(callId, result)
    } catch (error) {
      if (!requestSent) {
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

  const javaDecompile = async (
    call: CapabilityCall,
    context: CapabilityContext,
  ): Promise<CapabilityResult> => {
    const callId = call.invocationId
    if (context.signal.aborted) {
      context.confirmExecutionExit()
      if (isTimeout(null, context.signal)) {
        return errorResult(callId, 'Operation timed out')
      }
      return errorResult(callId, 'Operation cancelled')
    }

    let requestSent = false
    try {
      const args = call.arguments
      if (!args || typeof args !== 'object' || Array.isArray(args)) {
        context.confirmExecutionExit()
        return errorResult(callId, 'tool arguments must be an object')
      }

      const realPath = validatePath(args['path'])
      const rawTarget = args['target']
      if (rawTarget === undefined || rawTarget === null || typeof rawTarget !== 'string') {
        throw new LspError('target is required and must be a string')
      }
      if (rawTarget.trim().length === 0) {
        throw new LspError('target must not be blank')
      }
      const target = rawTarget

      const result = await service.javaDecompile(
        realPath,
        target,
        context.signal,
        () => context.confirmExecutionExit(),
        () => {
          requestSent = true
        },
      )
      return textResult(callId, result)
    } catch (error) {
      if (!requestSent) {
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

  return {
    capabilities: {
      'lsp.goto-definition': gotoDefinition,
      'lsp.workspace-symbols': workspaceSymbols,
      'lsp.java-decompile': javaDecompile,
    },
    support(file: string): LspSupport {
      return service.support(file)
    },
    fileChanged(file: string): void {
      service.fileChanged(file)
    },
    async close(): Promise<void> {
      await service.close()
    },
  }
}
