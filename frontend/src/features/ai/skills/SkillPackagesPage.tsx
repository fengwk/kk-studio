import { useMemo, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowUpCircle, Edit2, Package, RefreshCw, Trash2 } from 'lucide-react'
import { AiConsoleFrame } from '@/features/ai/extensions/AiConsoleFrame'
import { Button } from '@/shared/ui/controls/Button'
import { Checkbox } from '@/shared/ui/controls/Checkbox'
import { TextArea } from '@/shared/ui/controls/TextArea'
import { TextInput } from '@/shared/ui/controls/TextInput'
import { ResourceCard, type ResourceCardMetaRow } from '@/shared/ui/cards/ResourceCard'
import { ResourceGrid } from '@/shared/ui/cards/ResourceGrid'
import { CreateCard } from '@/shared/ui/feedback/CreateCard'
import { Dialog } from '@/shared/ui/overlays/Dialog'
import { ConfirmActionModal } from '@/shared/ui/overlays/ConfirmActionModal'
import { FieldLabel } from '@/shared/ui/controls/FieldLabel'
import { agentService } from '@/shared/api/agent-service'
import { queryKeys } from '@/shared/lib/query-keys'
import { useI18n } from '@/shared/i18n'
import { trimToNull } from '@/features/ai/catalog/ai-resource-draft-primitives'
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

/**
 * 状态 pill 只用设计系统已定义的变体：ok=is-ready、warning=is-pending、error=is-failed。
 * 不在列表里发明新的皮肤类，颜色语义由 .status-pill 变体统一决定。
 */
function checkStatusPillClass(status: SkillPackageCheckStatus): string {
  switch (status) {
    case 'UP_TO_DATE':
      return 'status-pill is-ready'
    case 'UPDATE_AVAILABLE':
      return 'status-pill is-pending'
    case 'CHECK_FAILED':
      return 'status-pill is-failed'
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
    <ResourceGrid className="skill-packages-list">
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

        // 元信息按“标识/引用 → 检查诊断 → 技能清单”组织；错误与技能清单只在有数据时出现。
        const rows: ResourceCardMetaRow[] = [
          [t('ai.skillPackages.repositoryUrl'), pkg.repositoryUrl],
          [t('ai.skillPackages.branch'), pkg.branch],
          [
            t('ai.skillPackages.token'),
            pkg.hasToken
              ? t('ai.skillPackages.tokenConfigured')
              : t('ai.skillPackages.tokenNotConfigured'),
          ],
          [
            t('ai.skillPackages.currentCommit'),
            <code title={pkg.currentCommit ?? undefined}>{formatCommit(pkg.currentCommit)}</code>,
          ],
          [
            t('ai.skillPackages.observedHeadCommit'),
            <code title={pkg.observedHeadCommit ?? undefined}>
              {formatCommit(pkg.observedHeadCommit)}
            </code>,
          ],
          [t('ai.skillPackages.skillsCount'), String(pkg.skills.length)],
        ]
        if (pkg.headCheckError) {
          rows.push({
            label: t('shared.error'),
            value: (
              <span className="inline-hint danger" role="alert">
                {pkg.headCheckError}
              </span>
            ),
            wrap: true,
          })
        }
        if (pkg.skills.length > 0) {
          rows.push({
            label: t('ai.skillPackages.skills'),
            tags: pkg.skills.map((skill) => skill.name),
            limit: 3,
          })
        }

        return (
          <ResourceCard
            key={pkg.packageName}
            className="skill-package-card"
            icon={<Package aria-hidden="true" />}
            title={pkg.packageName}
            subtitle={pkg.description || '—'}
            badge={
              <span
                className={checkStatusPillClass(pkg.checkStatus)}
                data-testid="check-status-pill"
              >
                {t(`ai.skillPackages.status.${pkg.checkStatus}`)}
              </span>
            }
            meta={rows}
            actions={
              <>
                <Button
                  size="compact"
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
                </Button>
                {hasUpdate ? (
                  <Button
                    variant="ghost"
                    size="compact"
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
                  </Button>
                ) : null}
                <Button
                  variant="ghost"
                  size="compact"
                  aria-label={`${t('ai.skillPackages.edit')} ${pkg.packageName}`}
                  disabled={isChecking || isUpdating}
                  onClick={() => setEditTarget(pkg)}
                >
                  <Edit2 aria-hidden="true" />
                  {t('ai.skillPackages.edit')}
                </Button>
                <Button
                  variant="ghost"
                  size="compact"
                  danger
                  aria-label={`${t('ai.skillPackages.delete')} ${pkg.packageName}`}
                  disabled={isChecking || isUpdating}
                  onClick={() => {
                    setDeleteError(null)
                    setDeleteTarget(pkg)
                  }}
                >
                  <Trash2 aria-hidden="true" />
                  {t('ai.skillPackages.delete')}
                </Button>
              </>
            }
          />
        )
      })}
    </ResourceGrid>
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
  const [token, setToken] = useState('')
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
      return t('ai.skillPackages.nameInvalidChars')
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
    const payload: SkillPackageCreateDTO = {
      packageName: packageName.trim(),
      description: description.trim() || null,
      repositoryUrl: repositoryUrl.trim(),
      branch: branch.trim(),
    }
    const trimmedToken = trimToNull(token)
    if (trimmedToken) {
      payload.token = trimmedToken
    }
    createMutation.mutate(payload)
  }

  return (
    <Dialog
      className="resource-modal-card"
      title={t('ai.skillPackages.create')}
      pending={createMutation.isPending}
      onClose={onClose}
    >
      <form className="modal-card-form" onSubmit={handleSubmit} noValidate>
          <div className="modal-body">
            {formError ? (
              <div className="form-error-banner" role="alert">
                {formError}
              </div>
            ) : null}

            <label className="form-group">
              <FieldLabel required>{t('ai.skillPackages.name')}</FieldLabel>
              <TextInput
                value={packageName}
                placeholder="my-skills"
                onChange={(event) => setPackageName(event.target.value)}
                required
              />
            </label>

            <label className="form-group">
              <FieldLabel>{t('ai.skillPackages.descriptionLabel')}</FieldLabel>
              <TextArea
                value={description}
                placeholder={t('ai.catalog.form.descriptionPlaceholder')}
                rows={2}
                onChange={(event) => setDescription(event.target.value)}
              />
            </label>

            <label className="form-group">
              <FieldLabel required>{t('ai.skillPackages.repositoryUrl')}</FieldLabel>
              <TextInput
                value={repositoryUrl}
                placeholder="https://github.com/org/repo.git"
                onChange={(event) => setRepositoryUrl(event.target.value)}
                required
              />
            </label>

            <label className="form-group">
              <FieldLabel required>{t('ai.skillPackages.branch')}</FieldLabel>
              <TextInput
                value={branch}
                placeholder="main"
                onChange={(event) => setBranch(event.target.value)}
                required
              />
            </label>

            <label className="form-group">
              <FieldLabel>{t('ai.skillPackages.token')}</FieldLabel>
              <TextInput
                type="password"
                autoComplete="off"
                value={token}
                placeholder={t('ai.skillPackages.tokenPlaceholder')}
                onChange={(event) => setToken(event.target.value)}
                disabled={createMutation.isPending}
              />
              <small>{t('ai.skillPackages.tokenCreateHint')}</small>
            </label>
          </div>

          <div className="modal-footer">
            <Button variant="inline" onClick={onClose} disabled={createMutation.isPending}>
              {t('shared.cancel')}
            </Button>
            <Button type="submit" disabled={createMutation.isPending}>
              {createMutation.isPending ? '...' : t('ai.catalog.action.confirmCreate')}
            </Button>
          </div>
        </form>
    </Dialog>
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
  const [token, setToken] = useState('')
  const [clearToken, setClearToken] = useState(false)
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
    const payload: SkillPackageEditDTO = {
      expectedVersion: target.version,
      description: description.trim() || null,
      branch: branch.trim(),
    }
    if (clearToken) {
      payload.token = null
    } else {
      const trimmedToken = trimToNull(token)
      if (trimmedToken) {
        payload.token = trimmedToken
      }
    }
    editMutation.mutate(payload)
  }

  return (
    <Dialog
      className="resource-modal-card"
      title={`${t('ai.skillPackages.edit')}: ${target.packageName}`}
      pending={editMutation.isPending}
      onClose={onClose}
    >
      <form className="modal-card-form" onSubmit={handleSubmit} noValidate>
          <div className="modal-body">
            {formError ? (
              <div className="form-error-banner" role="alert">
                {formError}
              </div>
            ) : null}

            <label className="form-group">
              <FieldLabel>{t('ai.skillPackages.name')}</FieldLabel>
              <TextInput value={target.packageName} disabled readOnly />
            </label>

            <label className="form-group">
              <FieldLabel>{t('ai.skillPackages.repositoryUrl')}</FieldLabel>
              <TextInput value={target.repositoryUrl} disabled readOnly />
            </label>

            <label className="form-group">
              <FieldLabel required>{t('ai.skillPackages.branch')}</FieldLabel>
              <TextInput
                value={branch}
                onChange={(event) => setBranch(event.target.value)}
                required
              />
            </label>

            <label className="form-group">
              <FieldLabel>{t('ai.skillPackages.token')}</FieldLabel>
              <TextInput
                type="password"
                autoComplete="off"
                value={token}
                placeholder={t('ai.skillPackages.tokenEditPlaceholder')}
                onChange={(event) => {
                  setToken(event.target.value)
                  if (clearToken) {
                    setClearToken(false)
                  }
                }}
                disabled={clearToken || editMutation.isPending}
              />
              <small>{t('ai.skillPackages.tokenEditHint')}</small>
            </label>

            <Checkbox
              checked={clearToken}
              onChange={(checked) => {
                setClearToken(checked)
                if (checked) {
                  setToken('')
                }
              }}
              disabled={editMutation.isPending}
              label={t('ai.skillPackages.clearToken')}
            />

            <label className="form-group">
              <FieldLabel>{t('ai.skillPackages.descriptionLabel')}</FieldLabel>
              <TextArea
                value={description}
                rows={2}
                onChange={(event) => setDescription(event.target.value)}
              />
            </label>
          </div>

          <div className="modal-footer">
            <Button variant="inline" onClick={onClose} disabled={editMutation.isPending}>
              {t('shared.cancel')}
            </Button>
            <Button type="submit" disabled={editMutation.isPending}>
              {editMutation.isPending ? '...' : t('ai.catalog.action.saveChanges')}
            </Button>
          </div>
        </form>
    </Dialog>
  )
}
