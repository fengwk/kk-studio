import type { CanvasFunctionRunDTO, CanvasSnapshotDTO } from '@/shared/api/generated/contracts'

/**
 * 把 Function run 按 basis-CAS 投影进快照节点（绝不覆盖 document version）。
 *
 * 调用方在发请求前捕获该 node 当前 run.requestId（可 null）作为 basis：start/fallback 捕获现有
 * requestId（终态 A 后再 start B 时 basis=A），cancel 以被取消的 requestId 为 basis。响应只有在
 * 缓存当前仍是 basis、缓存已是本响应 request、或缓存仍无 run 时才投影；缓存变为第三个 request C
 * 时视为过期，一律忽略并交由 version 事件权威收敛。
 */
export function patchSnapshotRun(
  snapshot: CanvasSnapshotDTO | undefined,
  run: CanvasFunctionRunDTO,
  basisRequestId: string | null,
): CanvasSnapshotDTO | undefined {
  if (!snapshot) {
    return snapshot
  }
  const nodeIndex = snapshot.nodes.findIndex((node) => node.id === run.nodeId)
  if (nodeIndex < 0) {
    return snapshot
  }
  const currentRun = snapshot.nodes[nodeIndex]?.run
  const currentRequestId = currentRun?.requestId ?? null
  const projects =
    currentRequestId === basisRequestId
    || currentRequestId === run.requestId
    || currentRequestId === null
  if (!projects) {
    return snapshot
  }
  if (
    currentRun?.requestId === run.requestId
    && currentRun.status === run.status
    && currentRun.stage === run.stage
    && currentRun.error === run.error
    && currentRun.updatedAt === run.updatedAt
  ) {
    return snapshot
  }
  const nodes = [...snapshot.nodes]
  nodes[nodeIndex] = { ...nodes[nodeIndex], run }
  return { ...snapshot, nodes }
}
