import { useEffect, useRef, useState } from 'react'
import { comfyuiService as defaultComfyuiService } from '@/shared/api/comfyui-service'
import { storageService as defaultStorageService, type StorageService } from '@/shared/api/storage-service'
import { errorMessage, isComfyuiPollingStatus } from '@/features/comfyui/comfyui-utils'
import type {
  ComfyuiWorkflowJobDTO,
  ComfyuiWorkflowRunDTO,
  ComfyuiWorkflowRunFileDTO,
} from '@/shared/api/contracts/comfyui'

const pollIntervalMillis = 1500

type PendingOperation = 'submit' | 'refresh' | 'cancel'

async function defaultHashFile(file: File): Promise<string> {
  if (typeof crypto !== 'undefined' && crypto.subtle?.digest) {
    const buffer = await file.arrayBuffer()
    const digest = await crypto.subtle.digest('SHA-256', buffer)
    return Array.from(new Uint8Array(digest))
      .map((b) => b.toString(16).padStart(2, '0'))
      .join('')
  }
  throw new Error('SHA-256 calculation is not supported in this environment')
}

export interface UseComfyuiRunLifecycleOptions {
  workflowId?: string
  apiName?: string
  defaultSelector: string | null
  storageService?: StorageService
  hashFile?: (file: File) => Promise<string>
  comfyuiService?: typeof defaultComfyuiService
}

export function useComfyuiRunLifecycle({
  workflowId,
  apiName,
  defaultSelector,
  storageService = defaultStorageService,
  hashFile = defaultHashFile,
  comfyuiService = defaultComfyuiService,
}: UseComfyuiRunLifecycleOptions) {
  const targetWorkflowId = workflowId ?? apiName ?? ''
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

    const uploadedHandles: string[] = []
    try {
      const uploadedFiles = await Promise.all(
        Object.entries(files)
          .filter((entry): entry is [string, File] => Boolean(entry[1]))
          .map(async ([name, file]) => {
            const sha256 = await hashFile(file)
            const reservation = await storageService.reserveUpload({
              filename: file.name,
              mediaType: file.type || 'application/octet-stream',
              sizeBytes: file.size,
              sha256,
            })
            uploadedHandles.push(reservation.id)
            if (reservation.state === 'PENDING') {
              await storageService.uploadFile(reservation.presignedPut, file)
            }
            const completed = await storageService.completeUpload(reservation.id)
            if (!completed.blobId) {
              throw new Error('Upload completion did not return a valid blobId')
            }
            return [
              name,
              {
                blobId: completed.blobId,
                filename: file.name,
              } satisfies ComfyuiWorkflowRunFileDTO,
            ] as const
          }),
      )
      if (!mountedRef.current || generation !== generationRef.current) {
        return
      }
      const fileReferences: Record<string, ComfyuiWorkflowRunFileDTO> = Object.fromEntries(uploadedFiles)
      const nextRun = await comfyuiService.runWorkflow(targetWorkflowId, { parameters, files: fileReferences })
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
      if (uploadedHandles.length > 0) {
        await Promise.allSettled(uploadedHandles.map((id) => storageService.deleteUpload(id)))
      }
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
