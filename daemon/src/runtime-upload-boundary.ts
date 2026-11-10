import type { ResultContent, ResourceUploader } from './contracts.js'
import { isResourceUploadError, ResourceUploadError } from './contracts.js'
import type { UploadedResource } from './runtime-resources.js'

/**
 * 资源上传边界：强制任何 uploader（内置或注入）遵守同一退出契约。
 *
 * <p>契约（PLAN 13 节）：
 * - resolve：交付成功，上传范围已退场；
 * - `ResourceUploadError(cleanupConfirmed: true)`：交付失败，上传范围已退场；
 * - `ResourceUploadError(cleanupConfirmed: false)`：结果失败，退出仍未知；契约错误原样交付；
 * - 其它拒绝（未按契约实现的 uploader）：保守转换为 `cleanupConfirmed: false` 的边界违约，
 *   记录边界违约诊断，绝不自动认定释放成功。
 */
export function boundaryUpload(
  uploader: ResourceUploader,
  invocationId: string,
  content: Extract<ResultContent, { type: 'binary' }>,
  signal: AbortSignal,
): Promise<UploadedResource> {
  return uploader.upload(invocationId, content, signal).catch((error: unknown) => {
    if (isResourceUploadError(error)) {
      return Promise.reject(error)
    }
    // 未按契约实现的 uploader：保守视为未确认，并留下边界违约诊断。
    reportBoundaryViolation(invocationId, error)
    return Promise.reject(
      new ResourceUploadError('internal', 'boundary-violation', false, { cause: error }),
    )
  })
}

/** 边界违约只输出固定诊断：invocationId 与错误类型，不含 URL、headers 或令牌。 */
function reportBoundaryViolation(invocationId: string, error: unknown): void {
  const name = typeof error === 'object' && error !== null && 'name' in error
    ? String((error as { name?: unknown }).name)
    : typeof error
  try {
    process.stderr.write(
      `daemon resource upload boundary violation: invocation=${invocationId} error=${name}\n`,
    )
  } catch {
    // 诊断输出失败不影响上传失败事实的交付。
  }
}

/** 上传失败的安全诊断行：只含阶段、分类与清理事实，绝不输出 message 或 cause 正文。 */
export function describeUploadFailure(error: ResourceUploadError): string {
  return `stage=${error.stage} category=${error.category} cleanupConfirmed=${error.cleanupConfirmed}`
}
