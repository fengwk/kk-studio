import { Pencil, Play, Trash2, Workflow } from 'lucide-react'
import { getBindingSummary } from '@/features/comfyui/comfyui-utils'
import type { ComfyuiWorkflowApiDTO } from '@/shared/api/contracts/comfyui'

export function ComfyuiWorkflowCard({
  workflow,
  deletePending,
  onRun,
  onEdit,
  onDelete,
}: {
  workflow: ComfyuiWorkflowApiDTO
  deletePending: boolean
  onRun: () => void
  onEdit: () => void
  onDelete: () => void
}) {
  const bindingSummary = getBindingSummary(workflow.inputBindingsJson)
  return (
    <article className={`info-card comfyui-workflow-card ${workflow.enabled ? '' : 'disabled'}`}>
      <div className="head">
        <div className="head-content">
          <div className="icon-box">
            <Workflow aria-hidden="true" />
          </div>
          <div className="text-content">
            <h3>{workflow.name}</h3>
            <p>{workflow.description || workflow.apiName}</p>
          </div>
        </div>
        <span className={`status-badge ${workflow.enabled ? 'enabled' : 'disabled'}`}>{workflow.enabled ? 'Enabled' : 'Disabled'}</span>
      </div>
      <div className="meta-block">
        <MetaRow label="API" value={`POST /api/comfyui/workflows/${workflow.apiName}/runs`} />
        <MetaRow label="Bindings" value={bindingSummary.error ? '配置错误' : String(bindingSummary.count)} />
        <MetaRow label="Selector" value={workflow.defaultSelector || 'whole result'} />
      </div>
      {bindingSummary.error && <div className="comfyui-card-error">绑定配置无法解析：{bindingSummary.error}</div>}
      <div className="chat-card-foot split">
        <button
          className="action-enter-btn green"
          type="button"
          aria-label={`运行 ${workflow.name}`}
          onClick={onRun}
          disabled={!workflow.enabled}
        >
          <Play aria-hidden="true" />
          Run
        </button>
        <button className="action-enter-btn" type="button" aria-label={`编辑 ${workflow.name}`} onClick={onEdit}>
          <Pencil aria-hidden="true" />
          编辑
        </button>
        <button
          className="action-enter-btn danger"
          type="button"
          aria-label={`删除 ${workflow.name}`}
          onClick={onDelete}
          disabled={deletePending}
        >
          <Trash2 aria-hidden="true" />
          删除
        </button>
      </div>
    </article>
  )
}

function MetaRow({ label, value }: { label: string; value: string }) {
  return (
    <div className="meta-row">
      <span className="lbl">{label}</span>
      <span className="val" title={value}>
        {value}
      </span>
    </div>
  )
}
