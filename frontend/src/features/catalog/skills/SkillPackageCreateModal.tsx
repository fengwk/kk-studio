import { useEffect, useRef, useState, type FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { GitBranch, Upload } from 'lucide-react'
import { isNotFoundError } from '@/shared/api/client'
import { agentService } from '@/shared/api/agent-service'
import type {
  SkillManifestEntryDTO,
  SkillPackageDetailDTO,
  SkillPackagePreviewDTO,
  SkillPackageUploadPreviewDTO,
  SkillPackageUploadPublishDTO,
} from '@/shared/api/generated/contracts'
import { useI18n, type TranslationKey } from '@/shared/i18n'
import { formatByteCount as formatBytes } from '@/shared/lib/format-bytes'
import { errorDetailMessage as errorDetail } from '@/shared/lib/user-facing-error'
import { skillPackageQueryKeys } from './skill-package-model'
import { Badge } from '@/shared/ui/data/Badge'
import { Button } from '@/shared/ui/controls/Button'
import { FieldLabel } from '@/shared/ui/controls/FieldLabel'
import { HelpHint } from '@/shared/ui/controls/HelpHint'
import { Tabs } from '@/shared/ui/controls/Tabs'
import { TextArea } from '@/shared/ui/controls/TextArea'
import { TextInput } from '@/shared/ui/controls/TextInput'
import { LoadingSpinner } from '@/shared/ui/feedback/LoadingSpinner'
import { Dialog } from '@/shared/ui/overlays/Dialog'
import {
  addedSkills,
  buildPreviewTree,
  formatCommit,
  isDescriptionWithinLimit,
  isSupportedSkillArchive,
  isValidPackageName,
  isValidPackageVersion,
  normalizeDescription,
  removedSkills,
  skillReferenceKey,
} from './skill-package-model'
import { SkillPackageTree } from './SkillPackageTree'
import { hashSkillArchive, uploadSkillArchive, type UploadProgress } from './skill-package-upload'
import './skill-packages.css'

type Source = 'GIT' | 'UPLOAD'
type UploadStage = 'HASHING' | 'UPLOADING' | 'PACKAGING'

const STAGE_LABEL_KEYS: Record<UploadStage, TranslationKey> = {
  HASHING: 'ai.skillPackages.stage.validating',
  UPLOADING: 'ai.skillPackages.stage.uploading',
  PACKAGING: 'ai.skillPackages.stage.packaging',
}

/** 全量读取 Agent 技能引用；预览只据此提示，最终发布仍由服务端校验。 */
async function fetchAgentSkillReferences(): Promise<Map<string, string[]>> {
  const references = new Map<string, Set<string>>()
  const agents = await agentService.listAgents()
  for (const agent of agents ?? []) {
    for (const skill of agent.config?.skills ?? []) {
      const key = skillReferenceKey(skill.packageName, skill.name)
      const names = references.get(key) ?? new Set<string>()
      names.add(agent.name)
      references.set(key, names)
    }
  }
  return new Map(
    Array.from(references, ([key, names]) => [key, Array.from(names).sort((a, b) => a.localeCompare(b))]),
  )
}

function isAbortError(error: unknown): boolean {
  return error instanceof Error && error.name === 'AbortError'
}

function UploadPreview({
  preview,
  existing,
  publishError,
  publishing,
}: {
  preview: SkillPackagePreviewDTO
  existing: SkillPackageDetailDTO | null
  publishError: string | null
  publishing: boolean
}) {
  const { t } = useI18n()
  const referencesQuery = useQuery({
    queryKey: skillPackageQueryKeys.agentSkillReferences,
    queryFn: fetchAgentSkillReferences,
    staleTime: 30_000,
  })
  const currentSkills: SkillManifestEntryDTO[] = existing?.skills ?? []
  const added = addedSkills(currentSkills, preview.skills)
  const removed = removedSkills(currentSkills, preview.skills)
  const references = referencesQuery.data
  const referenced = references
    ? removed.filter((skill) => references.has(skillReferenceKey(preview.packageName, skill.name)))
    : []
  const tree = buildPreviewTree(preview.packageName, preview.skills, preview.ignoredDirectories)

  return (
    <>
      {publishError ? (
        <div className="form-error-banner" role="alert">
          {publishError}
        </div>
      ) : null}

      <section className="skill-detail-section">
        <div className="skill-section-heading">
          <h3>{t('ai.skillPackages.previewSummary')}</h3>
          <span className="inline-hint">{preview.packageName}</span>
        </div>
        <dl className="skill-facts">
          <div>
            <dt>{t('ai.skillPackages.packageVersion')}</dt>
            <dd>{preview.packageVersion}</dd>
          </div>
          <div>
            <dt>{t('ai.skillPackages.fileCount')}</dt>
            <dd>{preview.fileCount}</dd>
          </div>
          <div>
            <dt>{t('ai.skillPackages.contentSize')}</dt>
            <dd>{formatBytes(preview.contentSizeBytes)}</dd>
          </div>
          <div>
            <dt>{t('ai.skillPackages.archiveSize')}</dt>
            <dd>{formatBytes(preview.archiveSizeBytes)}</dd>
          </div>
          <div>
            <dt>{t('ai.skillPackages.archiveSha')}</dt>
            <dd className="is-mono" title={preview.archiveSha256}>
              {formatCommit(preview.archiveSha256)}
            </dd>
          </div>
        </dl>
      </section>

      <section className="skill-detail-section">
        <div className="skill-section-heading">
          <h3>{t('ai.skillPackages.skills')}</h3>
          <span className="inline-hint">
            {t('ai.skillPackages.skillCountValue', { count: preview.skills.length })}
          </span>
        </div>
        <ul className="skill-list">
          {preview.skills.map((skill) => (
            <li key={skill.name}>
              <strong>{skill.name}</strong>
              <span>{skill.description}</span>
            </li>
          ))}
        </ul>
      </section>

      <section className="skill-detail-section">
        <div className="skill-section-heading">
          <h3>{t('ai.skillPackages.fileTree')}</h3>
          <HelpHint label={t('ai.skillPackages.fileTreeHelp')}>
            {t('ai.skillPackages.fileTreeHelpText')}
          </HelpHint>
        </div>
        <SkillPackageTree root={tree} />
        {preview.ignoredDirectories.length > 0 ? (
          <p className="inline-hint">
            {t('ai.skillPackages.ignoredDirectoriesValue', {
              directories: preview.ignoredDirectories.join(', '),
            })}
          </p>
        ) : null}
      </section>

      <section className="skill-detail-section">
        <div className="skill-section-heading">
          <h3>{t('ai.skillPackages.changeSummary')}</h3>
        </div>
        {existing === null ? (
          <p className="inline-hint">{t('ai.skillPackages.newPackageHint')}</p>
        ) : null}
        <div className="skill-change-group">
          <strong>{t('ai.skillPackages.addedSkills')}</strong>
          {added.length > 0 ? (
            <div className="skill-change-tags">
              {added.map((skill) => (
                <Badge tone="success" key={skill.name}>
                  {skill.name}
                </Badge>
              ))}
            </div>
          ) : (
            <span className="inline-hint">{t('ai.skillPackages.noAddedSkills')}</span>
          )}
        </div>
        <div className="skill-change-group">
          <strong>{t('ai.skillPackages.removedSkills')}</strong>
          {removed.length > 0 ? (
            <div className="skill-change-tags">
              {removed.map((skill) => (
                <Badge tone="warning" key={skill.name}>
                  {skill.name}
                </Badge>
              ))}
            </div>
          ) : (
            <span className="inline-hint">{t('ai.skillPackages.noRemovedSkills')}</span>
          )}
        </div>
        <div className="skill-change-group">
          <strong>{t('ai.skillPackages.referencedSkills')}</strong>
          {referencesQuery.isLoading ? (
            <span className="inline-hint">{t('ai.skillPackages.loadingReferences')}</span>
          ) : referencesQuery.isError ? (
            <span className="field-error" role="alert">
              {errorDetail(referencesQuery.error)}
            </span>
          ) : referenced.length > 0 ? (
            <>
              <div className="skill-change-tags">
                {referenced.map((skill) => (
                  <Badge tone="danger" key={skill.name}>
                    {skill.name}
                  </Badge>
                ))}
              </div>
              <p className="field-error" role="alert">
                {t('ai.skillPackages.referencedSkillsHint')}
              </p>
            </>
          ) : (
            <span className="inline-hint">{t('ai.skillPackages.noReferencedSkills')}</span>
          )}
        </div>
        {publishing ? (
          <div className="skill-upload-stage" role="status" aria-live="polite">
            <LoadingSpinner decorative />
            <span>{t('ai.skillPackages.stage.publishing')}</span>
          </div>
        ) : null}
      </section>
    </>
  )
}

/**
 * 新建技能包：同一个弹窗内选择 Git 仓库或上传压缩包。
 * 上传分两阶段：先解析预览，再在预览里确认技能、文件树与变化后发布。
 */
export function SkillPackageCreateModal({
  onClose,
  onCreated,
}: {
  onClose: () => void
  onCreated: (detail: SkillPackageDetailDTO) => void
}) {
  const { t } = useI18n()
  const queryClient = useQueryClient()
  const [source, setSource] = useState<Source>('GIT')
  const [packageName, setPackageName] = useState('')
  const [description, setDescription] = useState('')
  const [repositoryUrl, setRepositoryUrl] = useState('')
  const [branch, setBranch] = useState('main')
  const [token, setToken] = useState('')
  const [packageVersion, setPackageVersion] = useState('')
  const [file, setFile] = useState<File | null>(null)
  const [stage, setStage] = useState<UploadStage | null>(null)
  const [progress, setProgress] = useState<UploadProgress | null>(null)
  const [preview, setPreview] = useState<SkillPackagePreviewDTO | null>(null)
  const [existing, setExisting] = useState<SkillPackageDetailDTO | null>(null)
  const [formError, setFormError] = useState<string | null>(null)
  const [publishError, setPublishError] = useState<string | null>(null)
  const [cancelPending, setCancelPending] = useState(false)
  const fileInputRef = useRef<HTMLInputElement>(null)
  const abortRef = useRef<AbortController | null>(null)

  useEffect(() => () => abortRef.current?.abort(), [])

  const createGitMutation = useMutation({
    mutationFn: () =>
      agentService.createSkillPackage({
        packageName: packageName.trim(),
        description: normalizeDescription(description),
        repositoryUrl: repositoryUrl.trim(),
        branch: branch.trim(),
        ...(token.trim() ? { token: token.trim() } : {}),
      }),
    onSuccess: (detail) => {
      queryClient.setQueryData(skillPackageQueryKeys.detail(detail.packageName), detail)
      void queryClient.invalidateQueries({ queryKey: skillPackageQueryKeys.list })
      onCreated(detail)
    },
    onError: (error: unknown) => setFormError(errorDetail(error)),
  })

  const publishMutation = useMutation({
    mutationFn: (payload: SkillPackageUploadPublishDTO) => agentService.publishSkillPackageUpload(payload),
    onSuccess: (detail) => {
      queryClient.setQueryData(skillPackageQueryKeys.detail(detail.packageName), detail)
      void queryClient.invalidateQueries({ queryKey: skillPackageQueryKeys.list })
      onCreated(detail)
    },
    onError: (error: unknown) => setPublishError(errorDetail(error)),
  })

  const busy = stage !== null
  const pending = busy || publishMutation.isPending || cancelPending

  function validateShared(): string | null {
    if (!isValidPackageName(packageName.trim())) {
      return t('ai.skillPackages.packageNameInvalid')
    }
    if (!isDescriptionWithinLimit(description)) {
      return t('ai.skillPackages.descriptionTooLong')
    }
    return null
  }

  function validateGit(): string | null {
    const shared = validateShared()
    if (shared) {
      return shared
    }
    if (!repositoryUrl.trim()) {
      return t('ai.skillPackages.repoRequired')
    }
    if (!branch.trim()) {
      return t('ai.skillPackages.branchRequired')
    }
    return null
  }

  function validateUpload(): string | null {
    const shared = validateShared()
    if (shared) {
      return shared
    }
    if (!file) {
      return t('ai.skillPackages.fileRequired')
    }
    if (!isSupportedSkillArchive(file.name)) {
      return t('ai.skillPackages.fileTypeInvalid')
    }
    if (!isValidPackageVersion(packageVersion.trim())) {
      return t('ai.skillPackages.versionInvalid')
    }
    return null
  }

  async function startUploadPreview() {
    if (!file) {
      return
    }
    const name = packageName.trim()
    const version = packageVersion.trim()
    const controller = new AbortController()
    abortRef.current?.abort()
    abortRef.current = controller
    setFormError(null)
    setStage('HASHING')
    setProgress(null)
    try {
      let current: SkillPackageDetailDTO | null = null
      try {
        current = await agentService.getSkillPackage(name)
      } catch (error) {
        if (!isNotFoundError(error)) {
          throw error
        }
      }
      if (current?.source === 'GIT') {
        setStage(null)
        setFormError(t('ai.skillPackages.uploadGitConflict'))
        return
      }
      setExisting(current)
      const sha256 = await hashSkillArchive(file, controller.signal)
      if (controller.signal.aborted) {
        return
      }
      setStage('UPLOADING')
      setProgress({ loaded: 0, total: file.size })
      const uploadId = await uploadSkillArchive({
        file,
        sha256,
        signal: controller.signal,
        onProgress: setProgress,
      })
      if (controller.signal.aborted) {
        return
      }
      setStage('PACKAGING')
      const payload: SkillPackageUploadPreviewDTO = {
        uploadId,
        packageName: name,
        packageVersion: version,
        description: normalizeDescription(description),
      }
      const result = await agentService.previewSkillPackageUpload(payload)
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
      stagingUploadId: preview.stagingUploadId,
      packageName: preview.packageName,
      packageVersion: preview.packageVersion,
      description: preview.description ?? null,
      expectedVersion: existing?.version ?? null,
    })
  }

  async function cancelPreview() {
    if (!preview) {
      return
    }
    setCancelPending(true)
    setPublishError(null)
    try {
      await agentService.cancelSkillPackageUpload(preview.stagingUploadId)
      setCancelPending(false)
      onClose()
    } catch (error) {
      setPublishError(errorDetail(error))
      setCancelPending(false)
    }
  }

  function handleClose() {
    if (preview) {
      void cancelPreview()
      return
    }
    abortRef.current?.abort()
    onClose()
  }

  function handleSubmit(event: FormEvent) {
    event.preventDefault()
    if (preview) {
      publishPreview()
      return
    }
    setFormError(null)
    setPublishError(null)
    if (source === 'GIT') {
      const validation = validateGit()
      if (validation) {
        setFormError(validation)
        return
      }
      createGitMutation.mutate()
      return
    }
    const validation = validateUpload()
    if (validation) {
      setFormError(validation)
      return
    }
    void startUploadPreview()
  }

  const submitLabel = preview
    ? t('ai.skillPackages.publish')
    : source === 'GIT'
      ? t('ai.catalog.action.confirmCreate')
      : t('ai.skillPackages.parsePreview')

  return (
    <Dialog
      className="resource-modal-card skill-package-modal modal-touch-target"
      title={preview ? t('ai.skillPackages.previewTitle') : t('ai.skillPackages.create')}
      pending={pending}
      onClose={handleClose}
    >
      <form className="modal-card-form" onSubmit={handleSubmit} noValidate>
        <div className="modal-body">
          {formError ? (
            <div className="form-error-banner" role="alert">
              {formError}
            </div>
          ) : null}

          {preview ? (
            <UploadPreview
              preview={preview}
              existing={existing}
              publishError={publishError}
              publishing={publishMutation.isPending}
            />
          ) : (
            <Tabs
              ariaLabel={t('ai.skillPackages.source')}
              activeId={source === 'GIT' ? 'git' : 'upload'}
              onChange={(id) => {
                if (!busy) {
                  setSource(id === 'git' ? 'GIT' : 'UPLOAD')
                }
              }}
              className="skill-source-tabs"
              panelClassName="skill-source-panel"
              tabs={[
                {
                  id: 'git',
                  label: (
                    <span>
                      <GitBranch aria-hidden="true" />
                      {t('ai.skillPackages.source.GIT')}
                    </span>
                  ),
                },
                {
                  id: 'upload',
                  label: (
                    <span>
                      <Upload aria-hidden="true" />
                      {t('ai.skillPackages.source.UPLOAD')}
                    </span>
                  ),
                },
              ]}
            >
              <label className="form-group">
                <FieldLabel required>{t('ai.skillPackages.name')}</FieldLabel>
                <TextInput
                  value={packageName}
                  placeholder="my-skills"
                  disabled={busy}
                  required
                  onChange={(event) => setPackageName(event.target.value)}
                />
              </label>

              <label className="form-group">
                <FieldLabel>{t('ai.skillPackages.descriptionLabel')}</FieldLabel>
                <TextArea
                  value={description}
                  placeholder={t('ai.catalog.form.descriptionPlaceholder')}
                  rows={2}
                  disabled={busy}
                  onChange={(event) => setDescription(event.target.value)}
                />
              </label>

              {source === 'GIT' ? (
                <>
                  <label className="form-group">
                    <FieldLabel required>{t('ai.skillPackages.repositoryUrl')}</FieldLabel>
                    <TextInput
                      value={repositoryUrl}
                      placeholder="https://github.com/org/repo.git"
                      disabled={busy}
                      required
                      onChange={(event) => setRepositoryUrl(event.target.value)}
                    />
                  </label>

                  <label className="form-group">
                    <FieldLabel required>{t('ai.skillPackages.branch')}</FieldLabel>
                    <TextInput
                      value={branch}
                      placeholder="main"
                      disabled={busy}
                      required
                      onChange={(event) => setBranch(event.target.value)}
                    />
                  </label>

                  <label className="form-group">
                    <FieldLabel>{t('ai.skillPackages.token')}</FieldLabel>
                    <TextInput
                      type="password"
                      autoComplete="off"
                      value={token}
                      placeholder={t('ai.skillPackages.tokenPlaceholder')}
                      disabled={busy}
                      onChange={(event) => setToken(event.target.value)}
                    />
                    <small>{t('ai.skillPackages.tokenCreateHint')}</small>
                  </label>
                </>
              ) : (
                <>
                  <div className="form-group">
                    <FieldLabel required>{t('ai.skillPackages.archiveFile')}</FieldLabel>
                    <div className="skill-file-picker">
                      <Button
                        variant="ghost"
                        disabled={busy}
                        onClick={() => fileInputRef.current?.click()}
                      >
                        {t('ai.skillPackages.chooseFile')}
                      </Button>
                      <span className="skill-file-name" title={file?.name}>
                        {file
                          ? `${file.name} · ${formatBytes(file.size)}`
                          : t('ai.skillPackages.noFile')}
                      </span>
                      <input
                        ref={fileInputRef}
                        type="file"
                        accept=".zip,.tar.gz,application/zip,application/gzip"
                        className="skill-file-input"
                        aria-label={t('ai.skillPackages.archiveFile')}
                        disabled={busy}
                        onChange={(event) => {
                          setFile(event.target.files?.[0] ?? null)
                          event.target.value = ''
                        }}
                      />
                    </div>
                    <small>{t('ai.skillPackages.archiveFileHint')}</small>
                  </div>

                  <label className="form-group">
                    <FieldLabel required>{t('ai.skillPackages.packageVersion')}</FieldLabel>
                    <TextInput
                      value={packageVersion}
                      placeholder="1.0.0"
                      disabled={busy}
                      required
                      onChange={(event) => setPackageVersion(event.target.value)}
                    />
                  </label>
                </>
              )}

              {stage ? (
                <div className="skill-upload-stage" role="status" aria-live="polite">
                  <LoadingSpinner decorative />
                  <span>{t(STAGE_LABEL_KEYS[stage])}</span>
                  {stage === 'UPLOADING' && progress && progress.total > 0 ? (
                    <>
                      <progress
                        className="skill-upload-progress"
                        max={progress.total}
                        value={progress.loaded}
                        aria-label={t('ai.skillPackages.uploadProgress')}
                      />
                      <span className="skill-upload-percent">
                        {Math.min(100, Math.round((progress.loaded / progress.total) * 100))}%
                      </span>
                    </>
                  ) : null}
                </div>
              ) : null}
            </Tabs>
          )}
        </div>

        <div className="modal-footer">
          <Button variant="inline" disabled={pending} onClick={handleClose}>
            {t('shared.cancel')}
          </Button>
          <Button
            type="submit"
            loading={createGitMutation.isPending || busy || publishMutation.isPending}
          >
            {submitLabel}
          </Button>
        </div>
      </form>
    </Dialog>
  )
}
