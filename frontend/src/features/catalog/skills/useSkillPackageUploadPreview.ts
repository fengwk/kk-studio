import { useEffect, useRef, useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { isNotFoundError } from '@/shared/api/client'
import { agentService } from '@/shared/api/agent-service'
import type {
  SkillPackageDetailDTO,
  SkillPackageStagingCreateDTO,
  SkillPackageStagingDTO,
  SkillPackageStagingPublishDTO,
} from '@/shared/api/generated/contracts'
import { errorDetailMessage as errorDetail } from '@/shared/lib/user-facing-error'
import { useI18n } from '@/shared/i18n'
import { normalizeDescription, skillPackageQueryKeys } from './skill-package-model'
import { hashSkillArchive, uploadSkillArchive, type UploadProgress } from './skill-package-upload'

export type SkillPackageUploadStage = 'HASHING' | 'UPLOADING' | 'PACKAGING'

export interface SkillPackageUploadInput {
  packageName: string
  packageVersion: string
  description: string
  file: File
}

function isAbortError(error: unknown): boolean {
  return error instanceof Error && error.name === 'AbortError'
}

/**
 * 上传的两阶段编排：先哈希并直传归档拿到 staging 预览，再由调用方确认发布。
 *
 * 同一时刻只允许一次上传：重新开始会中止上一次。每个 await 之后都检查 signal，
 * 中止后既不再推进阶段，也不再写入预览或错误。
 */
export function useSkillPackageUploadPreview(
  onCreated: (detail: SkillPackageDetailDTO) => void,
  setFormError: (message: string | null) => void,
) {
  const { t } = useI18n()
  const queryClient = useQueryClient()
  const [stage, setStage] = useState<SkillPackageUploadStage | null>(null)
  const [progress, setProgress] = useState<UploadProgress | null>(null)
  const [preview, setPreview] = useState<SkillPackageStagingDTO | null>(null)
  const [existing, setExisting] = useState<SkillPackageDetailDTO | null>(null)
  const [publishError, setPublishError] = useState<string | null>(null)
  const [cancelPending, setCancelPending] = useState(false)
  const abortRef = useRef<AbortController | null>(null)

  useEffect(() => () => abortRef.current?.abort(), [])

  const publishMutation = useMutation({
    mutationFn: (input: { stagingId: string; payload: SkillPackageStagingPublishDTO }) =>
      agentService.publishSkillPackageStaging(input.stagingId, input.payload),
    onSuccess: (detail) => {
      queryClient.setQueryData(skillPackageQueryKeys.detail(detail.packageName), detail)
      void queryClient.invalidateQueries({ queryKey: skillPackageQueryKeys.list })
      onCreated(detail)
    },
    onError: (error: unknown) => setPublishError(errorDetail(error)),
  })

  /** 同名包已存在时沿用它的 version 做 CAS；GIT 来源的包不能上传覆盖。 */
  async function loadExisting(packageName: string, signal: AbortSignal) {
    let current: SkillPackageDetailDTO | null = null
    try {
      current = await agentService.getSkillPackage(packageName)
    } catch (error) {
      if (!isNotFoundError(error)) {
        throw error
      }
    }
    if (current?.source === 'GIT') {
      return { blocked: true as const }
    }
    if (!signal.aborted) {
      setExisting(current)
    }
    return { blocked: false as const }
  }

  async function startUploadPreview(input: SkillPackageUploadInput) {
    const controller = new AbortController()
    abortRef.current?.abort()
    abortRef.current = controller
    setFormError(null)
    setStage('HASHING')
    setProgress(null)

    try {
      const existingResult = await loadExisting(input.packageName, controller.signal)
      if (existingResult.blocked) {
        setStage(null)
        setFormError(t('ai.skillPackages.uploadGitConflict'))
        return
      }
      const sha256 = await hashSkillArchive(input.file, controller.signal)
      if (controller.signal.aborted) {
        return
      }
      setStage('UPLOADING')
      setProgress({ loaded: 0, total: input.file.size })
      const uploadId = await uploadSkillArchive({
        file: input.file,
        sha256,
        signal: controller.signal,
        onProgress: setProgress,
      })
      if (controller.signal.aborted) {
        return
      }
      setStage('PACKAGING')
      const payload: SkillPackageStagingCreateDTO = {
        uploadId,
        packageName: input.packageName,
        packageVersion: input.packageVersion,
        description: normalizeDescription(input.description),
      }
      const result = await agentService.createSkillPackageStaging(payload)
      if (controller.signal.aborted) {
        return
      }
      setPreview(result)
      setStage(null)
      setProgress(null)
    } catch (error) {
      if (!isAbortError(error)) {
        setFormError(errorDetail(error))
      }
      setStage(null)
      setProgress(null)
    }
  }

  function publishPreview() {
    if (!preview) {
      return
    }
    setPublishError(null)
    publishMutation.mutate({
      stagingId: preview.id,
      payload: {
        packageName: preview.packageName,
        packageVersion: preview.packageVersion,
        description: preview.description ?? null,
        expectedVersion: existing?.version ?? null,
      },
    })
  }

  /** 取消预览必须先释放服务端占用的 staging 归档，失败时留在预览并展示原因。 */
  async function cancelPreview(): Promise<boolean> {
    if (!preview) {
      return true
    }
    setCancelPending(true)
    setPublishError(null)
    try {
      await agentService.cancelSkillPackageStaging(preview.id)
      setCancelPending(false)
      return true
    } catch (error) {
      setPublishError(errorDetail(error))
      setCancelPending(false)
      return false
    }
  }

  return {
    stage,
    progress,
    preview,
    existing,
    publishError,
    cancelPending,
    publishPending: publishMutation.isPending,
    setPublishError,
    startUploadPreview,
    publishPreview,
    cancelPreview,
    abortUpload: () => abortRef.current?.abort(),
  }
}
