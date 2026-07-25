import { ChevronRight, ExternalLink, GitBranch } from 'lucide-react'
import { useState } from 'react'
import { formatBackendDate } from '@/features/ai/ai-console-utils'
import { getString, parsePayload } from '@/features/ai/payload-json'
import type { SubagentTaskNode } from '@/features/ai/subagent-task-tree'
import type { RootActivityDTO } from '@/shared/api/contracts'

const TIMELINE_TYPES = new Set([
  'subagent_started',
  'subagent_completed',
  'subagent_cancel_requested',
  'permission_requested',
  'permission_resolved',
])

export function TaskTimelinePanel({
  activities,
  taskTree,
  loading,
  error,
}: {
  activities: RootActivityDTO[]
  taskTree: SubagentTaskNode[]
  loading: boolean
  error: unknown
}) {
  const [selectedInvocationId, setSelectedInvocationId] = useState<string | null>(null)
  const selected = findTask(taskTree, selectedInvocationId)
  const timeline = activities.filter((activity) => TIMELINE_TYPES.has(activity.eventType))

  return (
    <section className="task-timeline-panel" aria-label="任务时间线">
      <div className="task-timeline-column">
        <h2>Root Activity</h2>
        {loading && <span className="task-empty">正在加载任务活动</span>}
        {Boolean(error) && <span className="task-error">任务活动加载失败</span>}
        {!loading && !error && timeline.length === 0 && <span className="task-empty">暂无子代理或控制活动</span>}
        {timeline.map((activity) => <ActivityRow key={activity.eventId} activity={activity} />)}
      </div>
      <div className="task-timeline-column task-tree-column">
        <h2>Subagents</h2>
        {taskTree.length === 0 && <span className="task-empty">暂无子代理任务</span>}
        {taskTree.map((node) => (
          <TaskNode key={node.task.parentInvocationId} node={node} selectedInvocationId={selectedInvocationId} onSelect={setSelectedInvocationId} />
        ))}
      </div>
      <div className="task-timeline-column task-detail-column">
        {selected ? <ChildTaskViewer node={selected} /> : <span className="task-empty">选择子代理查看报告、版本与产物</span>}
      </div>
    </section>
  )
}

function ActivityRow({ activity }: { activity: RootActivityDTO }) {
  return (
    <div className="task-activity-row">
      <span className="task-activity-time">{formatBackendDate(activity.createTime)}</span>
      <span>{activityLabel(activity)}</span>
    </div>
  )
}

function TaskNode({
  node,
  selectedInvocationId,
  onSelect,
}: {
  node: SubagentTaskNode
  selectedInvocationId: string | null
  onSelect: (invocationId: string) => void
}) {
  const { task } = node
  return (
    <div className="subagent-task-node">
      <button
        type="button"
        className={`subagent-task-button ${selectedInvocationId === task.parentInvocationId ? 'active' : ''}`}
        onClick={() => onSelect(task.parentInvocationId)}
      >
        <ChevronRight aria-hidden="true" />
        <span>{task.targetAgent}</span>
        <small>{task.status}</small>
      </button>
      {node.children.length > 0 && (
        <div className="subagent-task-children">
          {node.children.map((child) => (
            <TaskNode key={child.task.parentInvocationId} node={child} selectedInvocationId={selectedInvocationId} onSelect={onSelect} />
          ))}
        </div>
      )}
    </div>
  )
}

function ChildTaskViewer({ node }: { node: SubagentTaskNode }) {
  const { task } = node
  const report = task.report
  const artifacts = report?.artifacts ?? []
  const workingCopyPolicy = report?.workingCopyPolicy ?? task.workingCopyPolicy
  const workingCopyRevision = report?.workingCopyRevision ?? task.workingCopyRevision
  return (
    <div className="child-task-viewer">
      <div className="child-task-heading">
        <div>
          <strong>{task.targetAgent}</strong>
          <span>{report?.status ?? task.status}</span>
        </div>
        {task.childThreadId ? (
          <span className="child-task-thread-id" title={`子 Thread ${task.childThreadId}`}>
            <ExternalLink aria-hidden="true" />
            <span>{task.childThreadId}</span>
          </span>
        ) : null}
      </div>
      <div className="child-task-revision">
        <GitBranch aria-hidden="true" />
        <span>{workingCopyPolicy}{workingCopyRevision ? ` / ${workingCopyRevision}` : ''}</span>
      </div>
      {report?.finalReport && <pre className="child-task-report">{report.finalReport}</pre>}
      <div className="child-task-metrics">turns {report?.turnCount ?? '-'} · tools {report?.toolCount ?? '-'}</div>
      {artifacts.length > 0 && (
        <div className="child-task-artifacts">
          {artifacts.map((artifact) => (
            <a key={artifact.artifactId} href={`/api/artifacts/${encodeURIComponent(artifact.artifactId)}`} target="_blank" rel="noreferrer">
              {artifact.mediaType} · {artifact.artifactId}
            </a>
          ))}
        </div>
      )}
    </div>
  )
}

function activityLabel(activity: RootActivityDTO): string {
  const payload = parsePayload(activity.payloadJson)
  const childSessionId = getString(payload.childSessionId)
  // Backend SUBAGENT_STARTED payload uses `target`.
  const target = getString(payload.target)
  switch (activity.eventType) {
    case 'subagent_started':
      return `启动子代理 ${target || childSessionId}`
    case 'subagent_completed':
      return `子代理完成：${getString(payload.status) || childSessionId}`
    case 'subagent_cancel_requested':
      return `请求取消子代理：${getString(payload.reason) || childSessionId}`
    case 'permission_requested':
      return `等待工具授权：${getString(payload.tool) || getString(payload.invocationId)}`
    case 'permission_resolved':
      return `工具授权已${getString(payload.decision)}`
    default:
      return activity.eventType
  }
}

function findTask(nodes: SubagentTaskNode[], invocationId: string | null): SubagentTaskNode | null {
  if (!invocationId) {
    return null
  }
  for (const node of nodes) {
    if (node.task.parentInvocationId === invocationId) {
      return node
    }
    const child = findTask(node.children, invocationId)
    if (child) {
      return child
    }
  }
  return null
}
