import { useMemo, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowUpCircle, Edit2, Package, RefreshCw, Trash2 } from 'lucide-react'
import { AiConsoleFrame } from '@/features/ai/extensions/AiConsoleFrame'
import { CreateCard } from '@/shared/ui/console/AiConsoleCommonCards'
import { ModalBackdrop, ModalHeader } from '@/shared/ui/console/AiConsoleModalLayout'
import { ConfirmActionModal } from '@/shared/ui/console/ConfirmActionModal'
import { FieldLabel } from '@/shared/ui/console/FieldLabel'
import { agentService } from '@/shared/api/agent-service'
import { queryKeys } from '@/shared/lib/query-keys'
import { useI18n } from '@/shared/i18n'
import type {
  SkillPackageCheckStatus,
  SkillPackageCreateDTO,
  SkillPackageDTO,
  SkillPackageEditDTO,
} from '@/shared/api/contracts/ai-catalog'

function formatCommit(commit: string | null | undefined): string {
  if (!commit) {
    return '—'
  }
  return commit.length > 10 ? commit.slice(0, 10) : commit
}

function checkStatusPillClass(status: SkillPackageCheckStatus): string {
  switch (status) {
    case 'UP_TO_DATE':
      return 'status-pill is-ready'
    case 'UPDATE_AVAILABLE':
      return 'status-pill is-warning'
    case 'CHECK_FAILED':
      return 'status-pill is-error'
    case 'UNCHECKED':
    default:
      return 'status-pill is-offline'
  }
}

export function SkillPackagesPage() {
  const { t } = useI18n()
  const queryClient = useQueryClient()
  const [search, setSearch] = useState('')

  // Modals state
  const [createModalOpen, setCreateModalOpen] = useState(false)
  const [editTarget, setEditTarget] = useState<SkillPackageDTO | null>(null)
  const [deleteTarget, setDeleteTarget] = useState<SkillPackageDTO | null>(null)
  const [deleteError, setDeleteError] = useState<string | null>(null)
  const [checkingPackage, setCheckingPackage] = useState<string | null>(null)
  const [updatingPackage, setUpdatingPackage] = useState<string | null>(null)
  const [actionError, setActionError] = useState<string | null>(null)

  // Query packages
  const packagesQuery = useQuery({
    queryKey: queryKeys.skills.packages,
    queryFn: () => agentService.listSkillPackages(),
  })

  // Mutations
  const checkMutation = useMutation({
    mutationFn: ({ name, expectedVersion }: { name: string; expectedVersion: string }) =>
      agentService.checkSkillPackage(name, { expectedVersion }),
    onMutate: ({ name }) => {
      setCheckingPackage(name)
      setActionError(null)
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.skills.all })
    },
    onError: (err: unknown) => {
      setActionError(err instanceof Error ? err.message : String(err))
    },
    onSettled: () => {
      setCheckingPackage(null)
    },
  })

  const publishMutation = useMutation({
    mutationFn: ({
      name,
      expectedVersion,
      targetCommit,
    }: {
      name: string
      expectedVersion: string
      targetCommit: string
    }) => agentService.publishSkillPackage(name, { expectedVersion, targetCommit }),
    onMutate: ({ name }) => {
      setUpdatingPackage(name)
      setActionError(null)
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.skills.all })
    },
    onError: (err: unknown) => {
      setActionError(err instanceof Error ? err.message : String(err))
    },
    onSettled: () => {
      setUpdatingPackage(null)
    },
  })

  const deleteMutation = useMutation({
    mutationFn: ({ name, expectedVersion }: { name: string; expectedVersion: string }) =>
      agentService.deleteSkillPackage(name, expectedVersion),
    onSuccess: () => {
      setDeleteTarget(null)
      setDeleteError(null)
      void queryClient.invalidateQueries({ queryKey: queryKeys.skills.all })
    },
    onError: (err: unknown) => {
      setDeleteError(err instanceof Error ? err.message : String(err))
    },
  })

  const filteredPackages = useMemo(() => {
    const packages = packagesQuery.data ?? []
    const term = search.trim().toLowerCase()
    if (!term) {
      return packages
    }
    return packages.filter(
      (pkg) =>
        pkg.packageName.toLowerCase().includes(term) ||
        (pkg.description && pkg.description.toLowerCase().includes(term)) ||
        pkg.repositoryUrl.toLowerCase().includes(term),
    )
  }, [packagesQuery.data, search])

  const content = (
    <div className="cards-grid skill-packages-list">
      <CreateCard
        title={t('ai.skillPackages.create')}
        subtitle={t('ai.skillPackages.description')}
        onClick={() => setCreateModalOpen(true)}
      />
      {filteredPackages.map((pkg) => {
        const isChecking = checkingPackage === pkg.packageName
        const isUpdating = updatingPackage === pkg.packageName
        const hasUpdate =
          Boolean(pkg.observedHeadCommit) && pkg.observedHeadCommit !== pkg.currentCommit

        return (
          <article
            key={pkg.packageName}
            className="info-card skill-package-card"
            data-testid={`skill-package-card-${pkg.packageName}`}
          >
            <div className="chat-card-head">
              <div className="lead">
                <span className="card-glyph" aria-hidden="true">
                  <Package />
                </span>
                <div className="text-content">
                  <h3 title={pkg.packageName}>{pkg.packageName}</h3>
                  <p title={pkg.description || ''}>{pkg.description || '—'}</p>
                </div>
              </div>
              <span
                className={checkStatusPillClass(pkg.checkStatus)}
                data-testid="check-status-pill"
              >
                {pkg.checkStatus}
              </span>
            </div>

            <div className="meta-block">
              <div className="meta-row">
                <span className="lbl">{t('ai.skillPackages.repositoryUrl')}</span>
                <span className="val" title={pkg.repositoryUrl}>
                  {pkg.repositoryUrl}
                </span>
              </div>
              <div className="meta-row">
                <span className="lbl">{t('ai.skillPackages.branch')}</span>
                <span className="val">{pkg.branch}</span>
              </div>
              <div className="meta-row">
                <span className="lbl">{t('ai.skillPackages.currentCommit')}</span>
                <span className="val" title={pkg.currentCommit}>
                  <code>{formatCommit(pkg.currentCommit)}</code>
                </span>
              </div>
              <div className="meta-row">
                <span className="lbl">{t('ai.skillPackages.observedHeadCommit')}</span>
                <span className="val" title={pkg.observedHeadCommit || undefined}>
                  <code>{formatCommit(pkg.observedHeadCommit)}</code>
                </span>
              </div>
              {pkg.headCheckError ? (
                <div className="meta-row" role="alert">
                  <span className="lbl" style={{ color: 'var(--color-danger, #ef4444)' }}>
                    Error
                  </span>
                  <span className="val" style={{ color: 'var(--color-danger, #ef4444)' }}>
                    {pkg.headCheckError}
                  </span>
                </div>
              ) : null}
              <div className="meta-row">
                <span className="lbl">{t('ai.skillPackages.skillsCount')}</span>
                <span className="val">{pkg.skills.length}</span>
              </div>
              {pkg.skills.length > 0 ? (
                <div className="meta-row">
                  <span className="lbl">Skills</span>
                  <span className="val" style={{ display: 'flex', flexWrap: 'wrap', gap: '4px' }}>
                    {pkg.skills.map((s) => (
                      <code key={s.name} title={s.description}>
                        {s.name}
                      </code>
                    ))}
                  </span>
                </div>
              ) : null}
            </div>

            <div className="chat-card-foot split">
              <div style={{ display: 'flex', gap: '6px' }}>
                <button
                  type="button"
                  className="action-enter-btn"
                  aria-label={`${t('ai.skillPackages.check')} ${pkg.packageName}`}
                  disabled={isChecking || isUpdating}
                  onClick={() =>
                    checkMutation.mutate({
                      name: pkg.packageName,
                      expectedVersion: pkg.version,
                    })
                  }
                >
                  <RefreshCw
                    aria-hidden="true"
                    className={isChecking ? 'animate-spin' : undefined}
                  />
                  {t('ai.skillPackages.check')}
                </button>
                {hasUpdate ? (
                  <button
                    type="button"
                    className="action-enter-btn"
                    style={{ color: 'var(--color-primary, #3b82f6)' }}
                    aria-label={`${t('ai.skillPackages.update')} ${pkg.packageName}`}
                    disabled={isChecking || isUpdating}
                    onClick={() =>
                      publishMutation.mutate({
                        name: pkg.packageName,
                        expectedVersion: pkg.version,
                        targetCommit: pkg.observedHeadCommit!,
                      })
                    }
                  >
                    <ArrowUpCircle aria-hidden="true" />
                    {t('ai.skillPackages.update')}
                  </button>
                ) : null}
              </div>
              <div style={{ display: 'flex', gap: '6px' }}>
                <button
                  type="button"
                  className="action-enter-btn"
                  aria-label={`${t('ai.skillPackages.edit')} ${pkg.packageName}`}
                  disabled={isChecking || isUpdating}
                  onClick={() => setEditTarget(pkg)}
                >
                  <Edit2 aria-hidden="true" />
                  {t('ai.skillPackages.edit')}
                </button>
                <button
                  type="button"
                  className="action-enter-btn danger"
                  aria-label={`${t('ai.skillPackages.delete')} ${pkg.packageName}`}
                  disabled={isChecking || isUpdating}
                  onClick={() => {
                    setDeleteError(null)
                    setDeleteTarget(pkg)
                  }}
                >
                  <Trash2 aria-hidden="true" />
                  {t('ai.skillPackages.delete')}
                </button>
              </div>
            </div>
          </article>
        )
      })}
    </div>
  )

  return (
    <AiConsoleFrame
      search={search}
      onSearchChange={setSearch}
      busy={packagesQuery.isLoading}
      error={packagesQuery.error}
      mutationError={actionError ? new Error(actionError) : null}
      content={content}
    >
      {createModalOpen && (
        <CreatePackageModal
          onClose={() => setCreateModalOpen(false)}
          onSuccess={() => {
            setCreateModalOpen(false)
            void queryClient.invalidateQueries({ queryKey: queryKeys.skills.all })
          }}
        />
      )}

      {editTarget && (
        <EditPackageModal
          target={editTarget}
          onClose={() => setEditTarget(null)}
          onSuccess={() => {
            setEditTarget(null)
            void queryClient.invalidateQueries({ queryKey: queryKeys.skills.all })
          }}
        />
      )}

      {deleteTarget && (
        <ConfirmActionModal
          modal={{
            title: t('ai.skillPackages.delete'),
            description: t('ai.skillPackages.deleteConfirm', { name: deleteTarget.packageName }),
            confirmLabel: t('ai.skillPackages.delete'),
            tone: 'danger',
            error: deleteError,
            onConfirm: () => {
              deleteMutation.mutate({
                name: deleteTarget.packageName,
                expectedVersion: deleteTarget.version,
              })
            },
          }}
          pending={deleteMutation.isPending}
          onClose={() => {
            setDeleteTarget(null)
            setDeleteError(null)
          }}
        />
      )}
    </AiConsoleFrame>
  )
}

function CreatePackageModal({
  onClose,
  onSuccess,
}: {
  onClose: () => void
  onSuccess: () => void
}) {
  const { t } = useI18n()
  const [packageName, setPackageName] = useState('')
  const [description, setDescription] = useState('')
  const [repositoryUrl, setRepositoryUrl] = useState('')
  const [branch, setBranch] = useState('main')
  const [formError, setFormError] = useState<string | null>(null)

  const createMutation = useMutation({
    mutationFn: (data: SkillPackageCreateDTO) => agentService.createSkillPackage(data),
    onSuccess,
    onError: (err: unknown) => {
      setFormError(err instanceof Error ? err.message : String(err))
    },
  })

  function validate(): string | null {
    const trimmedName = packageName.trim()
    if (!trimmedName) {
      return t('ai.skillPackages.nameRequired')
    }
    if (/[:/@\\]/.test(trimmedName)) {
      return 'Package name cannot contain : / @ \\'
    }
    if (!repositoryUrl.trim()) {
      return t('ai.skillPackages.repoRequired')
    }
    if (!branch.trim()) {
      return t('ai.skillPackages.branchRequired')
    }
    return null
  }

  function handleSubmit(event: React.FormEvent) {
    event.preventDefault()
    const error = validate()
    if (error) {
      setFormError(error)
      return
    }
    setFormError(null)
    createMutation.mutate({
      packageName: packageName.trim(),
      description: description.trim() || null,
      repositoryUrl: repositoryUrl.trim(),
      branch: branch.trim(),
    })
  }

  return (
    <ModalBackdrop onClose={onClose}>
      <div
        className="modal-card resource-modal-card"
        role="dialog"
        aria-modal="true"
        aria-label={t('ai.skillPackages.create')}
        onMouseDown={(event) => event.stopPropagation()}
      >
        <ModalHeader title={t('ai.skillPackages.create')} onClose={onClose} />
        <form onSubmit={handleSubmit} noValidate>
          <div className="modal-body">
            {formError ? (
              <div className="form-error-banner" role="alert">
                {formError}
              </div>
            ) : null}

            <label className="form-group">
              <FieldLabel required>{t('ai.skillPackages.name')}</FieldLabel>
              <input
                type="text"
                value={packageName}
                placeholder="my-skills"
                onChange={(event) => setPackageName(event.target.value)}
                required
              />
            </label>

            <label className="form-group">
              <FieldLabel>{t('ai.skillPackages.descriptionLabel')}</FieldLabel>
              <textarea
                value={description}
                placeholder={t('ai.catalog.form.descriptionPlaceholder')}
                rows={2}
                onChange={(event) => setDescription(event.target.value)}
              />
            </label>

            <label className="form-group">
              <FieldLabel required>{t('ai.skillPackages.repositoryUrl')}</FieldLabel>
              <input
                type="text"
                value={repositoryUrl}
                placeholder="https://github.com/org/repo.git"
                onChange={(event) => setRepositoryUrl(event.target.value)}
                required
              />
            </label>

            <label className="form-group">
              <FieldLabel required>{t('ai.skillPackages.branch')}</FieldLabel>
              <input
                type="text"
                value={branch}
                placeholder="main"
                onChange={(event) => setBranch(event.target.value)}
                required
              />
            </label>
          </div>

          <div className="modal-footer">
            <button
              type="button"
              className="ghost-inline-btn"
              onClick={onClose}
              disabled={createMutation.isPending}
            >
              Cancel
            </button>
            <button type="submit" className="btn-primary" disabled={createMutation.isPending}>
              {createMutation.isPending ? '...' : t('ai.catalog.action.confirmCreate')}
            </button>
          </div>
        </form>
      </div>
    </ModalBackdrop>
  )
}

function EditPackageModal({
  target,
  onClose,
  onSuccess,
}: {
  target: SkillPackageDTO
  onClose: () => void
  onSuccess: () => void
}) {
  const { t } = useI18n()
  const [description, setDescription] = useState(target.description ?? '')
  const [branch, setBranch] = useState(target.branch)
  const [formError, setFormError] = useState<string | null>(null)

  const editMutation = useMutation({
    mutationFn: (data: SkillPackageEditDTO) =>
      agentService.editSkillPackage(target.packageName, data),
    onSuccess,
    onError: (err: unknown) => {
      setFormError(err instanceof Error ? err.message : String(err))
    },
  })

  function validate(): string | null {
    if (!branch.trim()) {
      return t('ai.skillPackages.branchRequired')
    }
    return null
  }

  function handleSubmit(event: React.FormEvent) {
    event.preventDefault()
    const error = validate()
    if (error) {
      setFormError(error)
      return
    }
    setFormError(null)
    editMutation.mutate({
      expectedVersion: target.version,
      description: description.trim() || null,
      branch: branch.trim(),
    })
  }

  return (
    <ModalBackdrop onClose={onClose}>
      <div
        className="modal-card resource-modal-card"
        role="dialog"
        aria-modal="true"
        aria-label={t('ai.skillPackages.edit')}
        onMouseDown={(event) => event.stopPropagation()}
      >
        <ModalHeader
          title={`${t('ai.skillPackages.edit')}: ${target.packageName}`}
          onClose={onClose}
        />
        <form onSubmit={handleSubmit} noValidate>
          <div className="modal-body">
            {formError ? (
              <div className="form-error-banner" role="alert">
                {formError}
              </div>
            ) : null}

            <label className="form-group">
              <FieldLabel>{t('ai.skillPackages.name')}</FieldLabel>
              <input type="text" value={target.packageName} disabled readOnly />
            </label>

            <label className="form-group">
              <FieldLabel>{t('ai.skillPackages.repositoryUrl')}</FieldLabel>
              <input type="text" value={target.repositoryUrl} disabled readOnly />
            </label>

            <label className="form-group">
              <FieldLabel required>{t('ai.skillPackages.branch')}</FieldLabel>
              <input
                type="text"
                value={branch}
                onChange={(event) => setBranch(event.target.value)}
                required
              />
            </label>

            <label className="form-group">
              <FieldLabel>{t('ai.skillPackages.descriptionLabel')}</FieldLabel>
              <textarea
                value={description}
                rows={2}
                onChange={(event) => setDescription(event.target.value)}
              />
            </label>
          </div>

          <div className="modal-footer">
            <button
              type="button"
              className="ghost-inline-btn"
              onClick={onClose}
              disabled={editMutation.isPending}
            >
              Cancel
            </button>
            <button type="submit" className="btn-primary" disabled={editMutation.isPending}>
              {editMutation.isPending ? '...' : t('ai.catalog.action.saveChanges')}
            </button>
          </div>
        </form>
      </div>
    </ModalBackdrop>
  )
}
