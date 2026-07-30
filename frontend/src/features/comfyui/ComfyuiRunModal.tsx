import { useEffect, useMemo, useRef, useState, type FormEventHandler } from 'react'
import { Download, RefreshCw, Square } from 'lucide-react'
import { ModalBackdrop, ModalHeader } from '@/features/ai/shared/AiConsoleModalLayout'
import { StateBlock } from '@/features/ai/shared/AiConsoleCommonCards'
import {
  buildComfyuiParameters,
  discoverComfyuiDownloads,
  errorMessage,
  initialBindingValues,
  isComfyuiPollingStatus,
  isComfyuiTerminalStatus,
  parseComfyuiBindings,
  prettyJson,
} from '@/features/comfyui/comfyui-utils'
import { FieldLabel } from '@/features/ai/shared/FieldLabel'
import { comfyuiService } from '@/shared/api/comfyui-service'
import type {
  ComfyuiInputBinding,
  ComfyuiWorkflowApiDTO,
  ComfyuiWorkflowJobDTO,
  ComfyuiWorkflowRunDTO,
  ComfyuiWorkflowRunFileDTO,
} from '@/shared/api/contracts'

const pollIntervalMillis = 1500

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
  const [selector, setSelector] = useState(workflow.defaultSelector ?? '')
  const [run, setRun] = useState<ComfyuiWorkflowRunDTO | null>(null)
  const [job, setJob] = useState<ComfyuiWorkflowJobDTO | null>(null)
  const [submitPending, setSubmitPending] = useState(false)
  const [refreshPending, setRefreshPending] = useState(false)
  const [cancelPending, setCancelPending] = useState(false)
  const [runError, setRunError] = useState<string | null>(null)
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const generationRef = useRef(0)
  const requestRef = useRef(0)
  const mountedRef = useRef(true)
  const operationRef = useRef<'submit' | 'refresh' | 'cancel' | null>(null)
  const selectorRef = useRef(selector)
  const runIdRef = useRef(run?.runId ?? null)
  selectorRef.current = selector
  runIdRef.current = run?.runId ?? null

  useEffect(() => {
    mountedRef.current = true
    return () => {
      mountedRef.current = false
      operationRef.current = null
      generationRef.current += 1
      requestRef.current += 1
      if (timerRef.current) {
        clearTimeout(timerRef.current)
      }
    }
  }, [])

  function stopPolling() {
    if (timerRef.current) {
      clearTimeout(timerRef.current)
      timerRef.current = null
    }
  }

  function schedulePoll(runId: string, generation: number) {
    stopPolling()
    timerRef.current = setTimeout(() => {
      void loadJob(runId, generation)
    }, pollIntervalMillis)
  }

  async function loadJob(runId: string, generation: number) {
    const requestId = ++requestRef.current
    try {
      const nextJob = await comfyuiService.getRun(runId, selectorRef.current || undefined)
      if (!mountedRef.current || generation !== generationRef.current || requestId !== requestRef.current) {
        return
      }
      setJob(nextJob)
      setRun((current) => (current ? { ...current, status: nextJob.status } : current))
      setRunError(null)
      if (isComfyuiPollingStatus(nextJob.status)) {
        schedulePoll(runId, generation)
      } else {
        stopPolling()
      }
    } catch (error) {
      if (mountedRef.current && generation === generationRef.current && requestId === requestRef.current) {
        setRunError(errorMessage(error))
        stopPolling()
      }
    }
  }

  async function refreshRun() {
    if (!run || operationRef.current) {
      return
    }
    const generation = generationRef.current
    const runId = run.runId
    operationRef.current = 'refresh'
    requestRef.current += 1
    stopPolling()
    setRefreshPending(true)
    setRunError(null)
    try {
      await loadJob(runId, generation)
    } finally {
      if (mountedRef.current && generation === generationRef.current && operationRef.current === 'refresh') {
        operationRef.current = null
        setRefreshPending(false)
      }
    }
  }

  const submitRun: FormEventHandler<HTMLFormElement> = async (event) => {
    event.preventDefault()
    if (operationRef.current) {
      return
    }
    if (bindingResult.error) {
      setRunError(`无法运行：${bindingResult.error}`)
      return
    }
    setRunError(null)
    let parameters: Record<string, unknown>
    try {
      parameters = buildComfyuiParameters(bindingResult.bindings, values)
      validateRequiredFiles(bindingResult.bindings, files)
    } catch (error) {
      setRunError(errorMessage(error))
      return
    }

    const generation = generationRef.current + 1
    generationRef.current = generation
    operationRef.current = 'submit'
    requestRef.current += 1
    stopPolling()
    setSubmitPending(true)
    setRefreshPending(false)
    setCancelPending(false)
    runIdRef.current = null
    setRun(null)
    setJob(null)
    try {
      const fileEntries = bindingResult.bindings.filter((binding) => binding.kind === 'file' && files[binding.name])
      const uploadedFiles = await Promise.all(
        fileEntries.map(async (binding) => [binding.name, await comfyuiService.uploadFile(workflow.apiName, files[binding.name] as File)] as const),
      )
      if (!mountedRef.current || generation !== generationRef.current) {
        return
      }
      const fileReferences: Record<string, ComfyuiWorkflowRunFileDTO> = Object.fromEntries(uploadedFiles)
      const nextRun = await comfyuiService.runWorkflow(workflow.apiName, { parameters, files: fileReferences })
      if (!mountedRef.current || generation !== generationRef.current) {
        return
      }
      const nextSelector = selectorRef.current.trim() ? selectorRef.current : nextRun.defaultSelector ?? ''
      setSelector(nextSelector)
      selectorRef.current = nextSelector
      setRun(nextRun)
      runIdRef.current = nextRun.runId
      await loadJob(nextRun.runId, generation)
    } catch (error) {
      if (mountedRef.current && generation === generationRef.current) {
        setRunError(errorMessage(error))
      }
    } finally {
      if (mountedRef.current && generation === generationRef.current && operationRef.current === 'submit') {
        operationRef.current = null
        setSubmitPending(false)
      }
    }
  }

  async function cancelRun() {
    if (!run || operationRef.current) {
      return
    }
    const generation = generationRef.current
    const runId = run.runId
    operationRef.current = 'cancel'
    stopPolling()
    requestRef.current += 1
    setCancelPending(true)
    setRunError(null)
    try {
      const result = await comfyuiService.cancelRun(runId)
      if (!mountedRef.current || generation !== generationRef.current || runIdRef.current !== runId) {
        return
      }
      if (result.cancelled) {
        setRun((current) => (current?.runId === runId ? { ...current, status: 'cancelled' } : current))
        setJob((current) => (current?.runId === runId ? { ...current, status: 'cancelled' } : current))
      } else {
        await loadJob(runId, generation)
      }
    } catch (error) {
      if (mountedRef.current && generation === generationRef.current && runIdRef.current === runId) {
        setRunError(errorMessage(error))
      }
    } finally {
      if (mountedRef.current && generation === generationRef.current && operationRef.current === 'cancel') {
        operationRef.current = null
        setCancelPending(false)
      }
    }
  }

  const currentStatus = job?.status ?? run?.status ?? null
  const downloads = discoverComfyuiDownloads(job?.result)
  const canCancel = Boolean(run && currentStatus && !isComfyuiTerminalStatus(currentStatus))
  const operationPending = submitPending || refreshPending || cancelPending

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
          {runError && <StateBlock title={runError} tone="danger" />}
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
              <input aria-label="JSONPath selector" value={selector} onChange={(event) => setSelector(event.target.value)} placeholder="whole result" />
            </label>
            <button
              type="button"
              className="ghost-inline-btn"
              onClick={() => void refreshRun()}
              disabled={!run || operationPending}
            >
              <RefreshCw aria-hidden="true" />
              刷新结果
            </button>
          </div>
          {run && (
            <section className="comfyui-run-state" aria-label="Run status">
              <div className="metadata-grid metadata-grid-three">
                <RunMeta label="Run ID" value={run.runId} />
                <RunMeta label="Status" value={currentStatus || '-'} />
                <RunMeta label="Outputs" value={job?.outputsCount == null ? '-' : String(job.outputsCount)} />
              </div>
              {job?.executionStatus != null && <JsonBlock title="Execution status" value={job.executionStatus} />}
              {job?.executionError != null && <JsonBlock title="Execution error" value={job.executionError} tone="danger" />}
              <JsonBlock title="Result" value={job?.result} />
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
            <button type="button" className="ghost-btn danger" onClick={() => void cancelRun()} disabled={operationPending}>
              <Square aria-hidden="true" />
              Cancel
            </button>
          )}
          <button type="submit" className="btn-primary" disabled={operationPending || Boolean(bindingResult.error)}>
            {submitPending ? '上传并提交中...' : run ? 'Run again' : 'Run workflow'}
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
