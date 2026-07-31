import { Pencil, Play, Trash2, Workflow } from 'lucide-react'
import { getBindingSummary } from '@/features/comfyui/comfyui-utils'
import type { ComfyuiWorkflowApiDTO } from '@/shared/api/contracts/comfyui'
import { useI18n } from '@/shared/i18n'

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
  const { t } = useI18n()
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
        <span className={`status-badge ${workflow.enabled ? 'enabled' : 'disabled'}`}>
          {workflow.enabled ? t('comfyui.card.enabled') : t('comfyui.card.disabled')}
        </span>
      </div>
      <div className="meta-block">
        <MetaRow label={t('comfyui.card.metaApi')} value={`POST /api/comfyui/workflows/${workflow.apiName}/runs`} />
        <MetaRow
          label={t('comfyui.card.metaBindings')}
          value={bindingSummary.error ? t('comfyui.card.bindingError') : String(bindingSummary.count)}
        />
        <MetaRow
          label={t('comfyui.card.metaSelector')}
          value={workflow.defaultSelector || t('comfyui.card.wholeResult')}
        />
      </div>
      {bindingSummary.error && (
        <div className="comfyui-card-error">
          {t('comfyui.card.bindingParseError', { error: bindingSummary.error })}
        </div>
      )}
      <div className="chat-card-foot split">
        <button
          className="action-enter-btn green"
          type="button"
          aria-label={t('comfyui.card.runAria', { name: workflow.name })}
          onClick={onRun}
          disabled={!workflow.enabled}
        >
          <Play aria-hidden="true" />
          {t('comfyui.card.run')}
        </button>
        <button
          className="action-enter-btn"
          type="button"
          aria-label={t('comfyui.card.editAria', { name: workflow.name })}
          onClick={onEdit}
        >
          <Pencil aria-hidden="true" />
          {t('comfyui.card.edit')}
        </button>
        <button
          className="action-enter-btn danger"
          type="button"
          aria-label={t('comfyui.card.deleteAria', { name: workflow.name })}
          onClick={onDelete}
          disabled={deletePending}
        >
          <Trash2 aria-hidden="true" />
          {t('comfyui.card.delete')}
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
