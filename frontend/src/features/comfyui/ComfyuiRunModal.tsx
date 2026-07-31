import { useMemo, useState, type FormEventHandler } from 'react'
import { Download, RefreshCw, Square } from 'lucide-react'
import { ModalBackdrop, ModalHeader } from '@/shared/ui/console/AiConsoleModalLayout'
import { StateBlock } from '@/shared/ui/console/AiConsoleCommonCards'
import {
  buildComfyuiParameters,
  discoverComfyuiDownloads,
  errorMessage,
  initialBindingValues,
  isComfyuiTerminalStatus,
  parseComfyuiBindings,
  prettyJson,
} from '@/features/comfyui/comfyui-utils'
import { FieldLabel } from '@/shared/ui/console/FieldLabel'
import { useComfyuiRunLifecycle } from '@/features/comfyui/useComfyuiRunLifecycle'
import type {
  ComfyuiInputBinding,
  ComfyuiWorkflowApiDTO,
} from '@/shared/api/contracts/comfyui'

export function ComfyuiRunModal({ workflow, onClose }: { workflow: ComfyuiWorkflowApiDTO; onClose: () => void }) {
  const bindingResult = useMemo(() => {
    try {
      return { bindings: parseComfyuiBindings(workflow.inputBindingsJson), error: null }
    } catch (error) {
      return { bindings: [] as ComfyuiInputBinding[], error: errorMessage(error) }
    }
  }, [workflow.inputBindingsJson])
  const [values, setValues] = useState<Record<string, string>>(() => initialBindingValues(bindingResult.bindings))
  const [files, setFiles] = useState<Record<string, File | undefined>>({})
  const lifecycle = useComfyuiRunLifecycle({
    apiName: workflow.apiName,
    defaultSelector: workflow.defaultSelector,
  })

  const submitRun: FormEventHandler<HTMLFormElement> = (event) => {
    event.preventDefault()
    if (bindingResult.error) {
      lifecycle.setError(`无法运行：${bindingResult.error}`)
      return
    }
    lifecycle.setError(null)
    let parameters: Record<string, unknown>
    try {
      parameters = buildComfyuiParameters(bindingResult.bindings, values)
      validateRequiredFiles(bindingResult.bindings, files)
    } catch (error) {
      lifecycle.setError(errorMessage(error))
      return
    }
    void lifecycle.submit({ parameters, files })
  }

  const currentStatus = lifecycle.job?.status ?? lifecycle.run?.status ?? null
  const downloads = discoverComfyuiDownloads(lifecycle.job?.result)
  const canCancel = Boolean(lifecycle.run && currentStatus && !isComfyuiTerminalStatus(currentStatus))

  return (
    <ModalBackdrop onClose={onClose}>
      <form
        className="modal-card comfyui-run-modal"
        aria-label={`运行 ComfyUI Workflow ${workflow.name}`}
        onSubmit={submitRun}
        onMouseDown={(event) => event.stopPropagation()}
      >
        <ModalHeader title={`Run · ${workflow.name}`} onClose={onClose} />
        <div className="modal-body comfyui-modal-scroll">
          <div className="comfyui-run-endpoint">POST /api/comfyui/workflows/{workflow.apiName}/runs</div>
          {bindingResult.error && <StateBlock title={`输入绑定配置错误：${bindingResult.error}`} tone="danger" />}
          {lifecycle.error && <StateBlock title={lifecycle.error} tone="danger" />}
          {bindingResult.bindings.map((binding) =>
            binding.kind === 'file' ? (
              <FileBindingField
                key={binding.name}
                binding={binding}
                file={files[binding.name]}
                onChange={(file) => setFiles((current) => ({ ...current, [binding.name]: file }))}
              />
            ) : (
              <ParameterBindingField
                key={binding.name}
                binding={binding}
                value={values[binding.name] ?? ''}
                onChange={(value) => setValues((current) => ({ ...current, [binding.name]: value }))}
              />
            ),
          )}
          <div className="comfyui-selector-row">
            <label className="form-group">
              JSONPath selector
              <input aria-label="JSONPath selector" value={lifecycle.selector} onChange={(event) => lifecycle.setSelector(event.target.value)} placeholder="whole result" />
            </label>
            <button
              type="button"
              className="ghost-inline-btn"
              onClick={() => void lifecycle.refresh()}
              disabled={!lifecycle.run || lifecycle.operationPending}
            >
              <RefreshCw aria-hidden="true" />
              刷新结果
            </button>
          </div>
          {lifecycle.run && (
            <section className="comfyui-run-state" aria-label="Run status">
              <div className="metadata-grid metadata-grid-three">
                <RunMeta label="Run ID" value={lifecycle.run.runId} />
                <RunMeta label="Status" value={currentStatus || '-'} />
                <RunMeta label="Outputs" value={lifecycle.job?.outputsCount == null ? '-' : String(lifecycle.job.outputsCount)} />
              </div>
              {lifecycle.job?.executionStatus != null && <JsonBlock title="Execution status" value={lifecycle.job.executionStatus} />}
              {lifecycle.job?.executionError != null && <JsonBlock title="Execution error" value={lifecycle.job.executionError} tone="danger" />}
              <JsonBlock title="Result" value={lifecycle.job?.result} />
              {downloads.length > 0 && (
                <div className="comfyui-downloads">
                  <strong>Outputs</strong>
                  {downloads.map((download) => (
                    <a key={download.downloadUrl} href={download.downloadUrl} target="_blank" rel="noreferrer">
                      <Download aria-hidden="true" />
                      {download.filename}
                    </a>
                  ))}
                </div>
              )}
            </section>
          )}
        </div>
        <div className="modal-footer comfyui-run-actions">
          {canCancel && (
            <button type="button" className="ghost-btn danger" onClick={() => void lifecycle.cancel()} disabled={lifecycle.operationPending}>
              <Square aria-hidden="true" />
              Cancel
            </button>
          )}
          <button type="submit" className="btn-primary" disabled={lifecycle.operationPending || Boolean(bindingResult.error)}>
            {lifecycle.submitPending ? '上传并提交中...' : lifecycle.run ? 'Run again' : 'Run workflow'}
          </button>
        </div>
      </form>
    </ModalBackdrop>
  )
}

function ParameterBindingField({
  binding,
  value,
  onChange,
}: {
  binding: ComfyuiInputBinding
  value: string
  onChange: (value: string) => void
}) {
  const valueType = binding.valueType ?? 'string'
  const labelNode = <FieldLabel required={Boolean(binding.required)}>{binding.name}</FieldLabel>
  const label = binding.name
  const hint = binding.description || `${binding.nodeId}.${binding.inputName} · ${valueType}`
  if (valueType === 'boolean') {
    return (
      <label className="form-group">
        {labelNode}
        <select aria-label={label} value={value} onChange={(event) => onChange(event.target.value)}>
          <option value="">{binding.required ? '请选择 true 或 false' : '未提供（保留 workflow 原值）'}</option>
          <option value="false">false</option>
          <option value="true">true</option>
        </select>
        <span className="inline-hint">{hint}</span>
      </label>
    )
  }
  if (valueType === 'json' || shouldUseTextarea(binding, value)) {
    return (
      <label className="form-group">
        {labelNode}
        <textarea aria-label={label} className={valueType === 'json' ? 'code-textarea' : undefined} value={String(value)} onChange={(event) => onChange(event.target.value)} />
        <span className="inline-hint">{hint}</span>
      </label>
    )
  }
  return (
    <label className="form-group">
      {labelNode}
      <input
        aria-label={label}
        type={valueType === 'integer' || valueType === 'number' ? 'number' : 'text'}
        step={valueType === 'integer' ? '1' : valueType === 'number' ? 'any' : undefined}
        value={String(value)}
        onChange={(event) => onChange(event.target.value)}
      />
      <span className="inline-hint">{hint}</span>
    </label>
  )
}

function FileBindingField({
  binding,
  file,
  onChange,
}: {
  binding: ComfyuiInputBinding
  file: File | undefined
  onChange: (file: File | undefined) => void
}) {
  return (
    <label className="form-group comfyui-file-field">
      <FieldLabel required={Boolean(binding.required)}>{binding.name}</FieldLabel>
      <input aria-label={binding.required ? `${binding.name} *` : binding.name} type="file" onChange={(event) => onChange(event.target.files?.[0])} />
      <span className="inline-hint">{binding.description || `${binding.nodeId}.${binding.inputName}`}</span>
      {file && <span className="comfyui-selected-file">已选择：{file.name}</span>}
    </label>
  )
}

function RunMeta({ label, value }: { label: string; value: string }) {
  return (
    <div className="comfyui-run-meta">
      <span>{label}</span>
      <strong title={value}>{value}</strong>
    </div>
  )
}

function JsonBlock({ title, value, tone }: { title: string; value: unknown; tone?: 'danger' }) {
  return (
    <div className={`comfyui-json-block ${tone === 'danger' ? 'danger' : ''}`}>
      <strong>{title}</strong>
      <pre>{prettyJson(value)}</pre>
    </div>
  )
}

function validateRequiredFiles(bindings: ComfyuiInputBinding[], files: Record<string, File | undefined>) {
  const missing = bindings.find((binding) => binding.kind === 'file' && binding.required && !files[binding.name])
  if (missing) {
    throw new Error(`文件 ${missing.name} 为必填项`)
  }
}

function shouldUseTextarea(binding: ComfyuiInputBinding, value: string): boolean {
  return /prompt|text|description/i.test(binding.name) || String(value).includes('\n')
}
