import { useEffect, useRef, useState } from 'react'
import { comfyuiService } from '@/shared/api/comfyui-service'
import { errorMessage, isComfyuiPollingStatus } from '@/features/comfyui/comfyui-utils'
import type {
  ComfyuiWorkflowRunFileDTO,
  ComfyuiWorkflowJobDTO,
  ComfyuiWorkflowRunDTO,
} from '@/shared/api/contracts/comfyui'

const pollIntervalMillis = 1500

type PendingOperation = 'submit' | 'refresh' | 'cancel'

export function useComfyuiRunLifecycle({
  apiName,
  defaultSelector,
}: {
  apiName: string
  defaultSelector: string | null
}) {
  const [selector, setSelector] = useState(defaultSelector ?? '')
  const [run, setRun] = useState<ComfyuiWorkflowRunDTO | null>(null)
  const [job, setJob] = useState<ComfyuiWorkflowJobDTO | null>(null)
  const [pendingOperation, setPendingOperation] = useState<PendingOperation | null>(null)
  const [error, setError] = useState<string | null>(null)
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const generationRef = useRef(0)
  const requestRef = useRef(0)
  const mountedRef = useRef(true)
  const operationRef = useRef<PendingOperation | null>(null)
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
      setError(null)
      if (isComfyuiPollingStatus(nextJob.status)) {
        schedulePoll(runId, generation)
      } else {
        stopPolling()
      }
    } catch (loadError) {
      if (mountedRef.current && generation === generationRef.current && requestId === requestRef.current) {
        setError(errorMessage(loadError))
        stopPolling()
      }
    }
  }

  async function submit({
    parameters,
    files,
  }: {
    parameters: Record<string, unknown>
    files: Record<string, File | undefined>
  }) {
    if (operationRef.current) {
      return
    }
    const generation = generationRef.current + 1
    generationRef.current = generation
    operationRef.current = 'submit'
    requestRef.current += 1
    stopPolling()
    setPendingOperation('submit')
    setError(null)
    runIdRef.current = null
    setRun(null)
    setJob(null)
    try {
      const uploadedFiles = await Promise.all(
        Object.entries(files)
          .filter((entry): entry is [string, File] => Boolean(entry[1]))
          .map(async ([name, file]) => [name, await comfyuiService.uploadFile(apiName, file)] as const),
      )
      if (!mountedRef.current || generation !== generationRef.current) {
        return
      }
      const fileReferences: Record<string, ComfyuiWorkflowRunFileDTO> = Object.fromEntries(uploadedFiles)
      const nextRun = await comfyuiService.runWorkflow(apiName, { parameters, files: fileReferences })
      if (!mountedRef.current || generation !== generationRef.current) {
        return
      }
      const nextSelector = selectorRef.current.trim() ? selectorRef.current : nextRun.defaultSelector ?? ''
      setSelector(nextSelector)
      selectorRef.current = nextSelector
      setRun(nextRun)
      runIdRef.current = nextRun.runId
      await loadJob(nextRun.runId, generation)
    } catch (submitError) {
      if (mountedRef.current && generation === generationRef.current) {
        setError(errorMessage(submitError))
      }
    } finally {
      if (mountedRef.current && generation === generationRef.current && operationRef.current === 'submit') {
        operationRef.current = null
        setPendingOperation(null)
      }
    }
  }

  async function refresh() {
    if (!run || operationRef.current) {
      return
    }
    const generation = generationRef.current
    const runId = run.runId
    operationRef.current = 'refresh'
    requestRef.current += 1
    stopPolling()
    setPendingOperation('refresh')
    setError(null)
    try {
      await loadJob(runId, generation)
    } finally {
      if (mountedRef.current && generation === generationRef.current && operationRef.current === 'refresh') {
        operationRef.current = null
        setPendingOperation(null)
      }
    }
  }

  async function cancel() {
    if (!run || operationRef.current) {
      return
    }
    const generation = generationRef.current
    const runId = run.runId
    operationRef.current = 'cancel'
    stopPolling()
    requestRef.current += 1
    setPendingOperation('cancel')
    setError(null)
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
    } catch (cancelError) {
      if (mountedRef.current && generation === generationRef.current && runIdRef.current === runId) {
        setError(errorMessage(cancelError))
      }
    } finally {
      if (mountedRef.current && generation === generationRef.current && operationRef.current === 'cancel') {
        operationRef.current = null
        setPendingOperation(null)
      }
    }
  }

  return {
    selector,
    setSelector,
    run,
    job,
    error,
    setError,
    submitPending: pendingOperation === 'submit',
    operationPending: pendingOperation !== null,
    submit,
    refresh,
    cancel,
  }
}
