import { useEffect, useMemo, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Edit2, Package, Plus, Trash2 } from 'lucide-react'
import { AiConsoleFrame } from '@/features/ai/extensions/AiConsoleFrame'
import { CreateCard } from '@/shared/ui/console/AiConsoleCommonCards'
import { ModalBackdrop, ModalHeader } from '@/shared/ui/console/AiConsoleModalLayout'
import { ConfirmActionModal } from '@/shared/ui/console/ConfirmActionModal'
import { FieldLabel } from '@/shared/ui/console/FieldLabel'
import { agentService } from '@/shared/api/agent-service'
import { queryKeys } from '@/shared/lib/query-keys'
import { useI18n } from '@/shared/i18n'
import type {
  SkillPackageCreateDTO,
  SkillPackageDTO,
  SkillPackageDetailDTO,
  SkillPackageUpdateDTO,
} from '@/shared/api/contracts/ai-catalog'

interface PackageSkillDraft {
  name: string
  description: string
  content: string
}

function emptySkillDraft(): PackageSkillDraft {
  return {
    name: '',
    description: '',
    content: '',
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

  // Query packages
  const packagesQuery = useQuery({
    queryKey: queryKeys.skills.packages,
    queryFn: () => agentService.listSkillPackages(),
  })

  // Query package detail when editing
  const detailQuery = useQuery({
    queryKey: queryKeys.skills.packageDetail(editTarget?.name ?? ''),
    queryFn: () => agentService.getSkillPackage(editTarget!.name),
    enabled: !!editTarget,
  })

  // Delete mutation
  const deleteMutation = useMutation({
    mutationFn: ({ name, expectedPackageVersion }: { name: string; expectedPackageVersion: string }) =>
      agentService.deleteSkillPackage(name, expectedPackageVersion),
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
        pkg.name.toLowerCase().includes(term) ||
        (pkg.description && pkg.description.toLowerCase().includes(term)),
    )
  }, [packagesQuery.data, search])

  const content = (
    <div className="cards-grid skill-packages-list">
      <CreateCard
        title={t('ai.skillPackages.create')}
        subtitle={t('ai.skillPackages.description')}
        onClick={() => setCreateModalOpen(true)}
      />
      {filteredPackages.map((pkg) => (
        <article key={pkg.name} className="info-card skill-package-card">
          <div className="chat-card-head">
            <div className="lead">
              <span className="card-glyph" aria-hidden="true">
                <Package />
              </span>
              <div className="text-content">
                <h3 title={pkg.name}>{pkg.name}</h3>
                <p title={pkg.description || ''}>
                  {pkg.description || '—'}
                </p>
              </div>
            </div>
            <span className="status-pill is-ready">
              v{pkg.packageVersion}
            </span>
          </div>

          <div className="meta-block">
            <div className="meta-row">
              <span className="lbl">{t('ai.skillPackages.skillsCount')}</span>
              <span className="val">{pkg.skills.length}</span>
            </div>
          </div>

          <div className="chat-card-foot split">
            <button
              type="button"
              className="action-enter-btn"
              aria-label={`${t('ai.skillPackages.edit')} ${pkg.name}`}
              onClick={() => setEditTarget(pkg)}
            >
              <Edit2 aria-hidden="true" />
              {t('ai.skillPackages.edit')}
            </button>
            <button
              type="button"
              className="action-enter-btn danger"
              aria-label={`${t('ai.skillPackages.delete')} ${pkg.name}`}
              onClick={() => {
                setDeleteError(null)
                setDeleteTarget(pkg)
              }}
            >
              <Trash2 aria-hidden="true" />
              {t('ai.skillPackages.delete')}
            </button>
          </div>
        </article>
      ))}
    </div>
  )

  return (
    <AiConsoleFrame
      search={search}
      onSearchChange={setSearch}
      busy={packagesQuery.isLoading}
      error={packagesQuery.error}
      mutationError={null}
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
          detail={detailQuery.data ?? null}
          loading={detailQuery.isLoading}
          error={detailQuery.error ? String(detailQuery.error) : null}
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
            description: t('ai.skillPackages.deleteConfirm', { name: deleteTarget.name }),
            confirmLabel: t('ai.skillPackages.delete'),
            tone: 'danger',
            error: deleteError,
            onConfirm: () => {
              deleteMutation.mutate({
                name: deleteTarget.name,
                expectedPackageVersion: deleteTarget.packageVersion,
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
  const [name, setName] = useState('')
  const [packageVersion, setPackageVersion] = useState('1.0.0')
  const [description, setDescription] = useState('')
  const [skills, setSkills] = useState<PackageSkillDraft[]>([emptySkillDraft()])
  const [formError, setFormError] = useState<string | null>(null)

  const createMutation = useMutation({
    mutationFn: (data: SkillPackageCreateDTO) => agentService.createSkillPackage(data),
    onSuccess,
    onError: (err: unknown) => {
      setFormError(err instanceof Error ? err.message : String(err))
    },
  })

  function validate(): string | null {
    if (!name.trim()) {
      return t('ai.skillPackages.nameRequired')
    }
    if (!packageVersion.trim()) {
      return t('ai.skillPackages.versionRequired')
    }
    if (skills.length === 0) {
      return t('ai.skillPackages.atLeastOneSkill')
    }
    const skillNames = new Set<string>()
    for (const skill of skills) {
      const sName = skill.name.trim()
      if (!sName) {
        return t('ai.skillPackages.skillNameRequired')
      }
      if (!skill.description.trim()) {
        return t('ai.skillPackages.skillDescriptionRequired')
      }
      if (skill.content.length === 0) {
        return t('ai.skillPackages.skillContentRequired')
      }
      if (skillNames.has(sName)) {
        return t('ai.skillPackages.duplicateSkillName', { name: sName })
      }
      skillNames.add(sName)
    }
    return null
  }

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    setFormError(null)
    const err = validate()
    if (err) {
      setFormError(err)
      return
    }

    createMutation.mutate({
      name: name.trim(),
      packageVersion: packageVersion.trim(),
      description: description.trim() || undefined,
      skills: skills.map((s) => ({
        name: s.name.trim(),
        description: s.description.trim(),
        content: s.content,
      })),
    })
  }

  return (
    <ModalBackdrop onClose={onClose}>
      <div
        className="modal-card skill-package-editor-modal"
        role="dialog"
        aria-modal="true"
        aria-label={t('ai.skillPackages.create')}
        onMouseDown={(e) => e.stopPropagation()}
      >
        <ModalHeader
          title={t('ai.skillPackages.create')}
          onClose={onClose}
          closeDisabled={createMutation.isPending}
        />
        <form onSubmit={handleSubmit}>
          <div className="modal-body" style={{ maxHeight: '70vh', overflowY: 'auto' }}>
            <label className="form-group">
              <FieldLabel required>{t('ai.skillPackages.name')}</FieldLabel>
              <input
                value={name}
                onChange={(e) => setName(e.target.value)}
                placeholder="core-tools"
                required
                autoFocus
              />
            </label>

            <label className="form-group">
              <FieldLabel required>{t('ai.skillPackages.version')}</FieldLabel>
              <input
                value={packageVersion}
                onChange={(e) => setPackageVersion(e.target.value)}
                placeholder="1.0.0"
                required
              />
            </label>

            <label className="form-group">
              <FieldLabel>{t('ai.skillPackages.descriptionLabel')}</FieldLabel>
              <textarea
                value={description}
                onChange={(e) => setDescription(e.target.value)}
                rows={2}
              />
            </label>

            <SkillDefinitionsEditor
              skills={skills}
              onChange={setSkills}
              disabled={createMutation.isPending}
            />

            {formError && (
              <p className="field-error" role="alert">
                {formError}
              </p>
            )}
          </div>

          <div className="modal-footer">
            <button
              type="button"
              className="ghost-btn"
              onClick={onClose}
              disabled={createMutation.isPending}
            >
              {t('shared.cancel')}
            </button>
            <button
              type="submit"
              className="btn-primary"
              disabled={createMutation.isPending}
            >
              {createMutation.isPending ? t('shared.saving') : t('shared.save')}
            </button>
          </div>
        </form>
      </div>
    </ModalBackdrop>
  )
}

function EditPackageModal({
  target,
  detail,
  loading,
  error,
  onClose,
  onSuccess,
}: {
  target: SkillPackageDTO
  detail: SkillPackageDetailDTO | null
  loading: boolean
  error: string | null
  onClose: () => void
  onSuccess: () => void
}) {
  const { t } = useI18n()
  const [newPackageVersion, setNewPackageVersion] = useState('')
  const [description, setDescription] = useState('')
  const [skills, setSkills] = useState<PackageSkillDraft[]>([])
  const [formError, setFormError] = useState<string | null>(null)

  useEffect(() => {
    if (detail) {
      setDescription(detail.description || '')
      setSkills(
        detail.skills.length > 0
          ? detail.skills.map((s) => ({
              name: s.name,
              description: s.description || '',
              content: s.content || '',
            }))
          : [emptySkillDraft()],
      )
    }
  }, [detail])

  const updateMutation = useMutation({
    mutationFn: (data: SkillPackageUpdateDTO) =>
      agentService.updateSkillPackage(target.name, data),
    onSuccess,
    onError: (err: unknown) => {
      setFormError(err instanceof Error ? err.message : String(err))
    },
  })

  function validate(): string | null {
    if (!newPackageVersion.trim()) {
      return t('ai.skillPackages.versionRequired')
    }
    if (skills.length === 0) {
      return t('ai.skillPackages.atLeastOneSkill')
    }
    const skillNames = new Set<string>()
    for (const skill of skills) {
      const sName = skill.name.trim()
      if (!sName) {
        return t('ai.skillPackages.skillNameRequired')
      }
      if (!skill.description.trim()) {
        return t('ai.skillPackages.skillDescriptionRequired')
      }
      if (skill.content.length === 0) {
        return t('ai.skillPackages.skillContentRequired')
      }
      if (skillNames.has(sName)) {
        return t('ai.skillPackages.duplicateSkillName', { name: sName })
      }
      skillNames.add(sName)
    }
    return null
  }

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    setFormError(null)
    const err = validate()
    if (err) {
      setFormError(err)
      return
    }

    updateMutation.mutate({
      expectedPackageVersion: target.packageVersion,
      newPackageVersion: newPackageVersion.trim(),
      description: description.trim() || undefined,
      skills: skills.map((s) => ({
        name: s.name.trim(),
        description: s.description.trim(),
        content: s.content,
      })),
    })
  }

  return (
    <ModalBackdrop onClose={onClose}>
      <div
        className="modal-card skill-package-editor-modal"
        role="dialog"
        aria-modal="true"
        aria-label={`${t('ai.skillPackages.edit')} - ${target.name}`}
        onMouseDown={(e) => e.stopPropagation()}
      >
        <ModalHeader
          title={`${t('ai.skillPackages.edit')} - ${target.name}`}
          onClose={onClose}
          closeDisabled={updateMutation.isPending}
        />
        {loading ? (
          <div className="modal-body">
            <div className="state-block">{t('ai.skillPackages.loading')}</div>
          </div>
        ) : error ? (
          <div className="modal-body">
            <div className="state-block error">{error}</div>
          </div>
        ) : (
          <form onSubmit={handleSubmit}>
            <div className="modal-body" style={{ maxHeight: '70vh', overflowY: 'auto' }}>
              <label className="form-group">
                <FieldLabel>{t('ai.skillPackages.name')}</FieldLabel>
                <input value={target.name} disabled readOnly />
              </label>

              <label className="form-group">
                <FieldLabel>{t('ai.skillPackages.version')}</FieldLabel>
                <input value={target.packageVersion} disabled readOnly />
              </label>

              <label className="form-group">
                <FieldLabel required>{t('ai.skillPackages.newVersion')}</FieldLabel>
                <input
                  value={newPackageVersion}
                  onChange={(e) => setNewPackageVersion(e.target.value)}
                  placeholder="1.1.0"
                  required
                  autoFocus
                />
              </label>

              <label className="form-group">
                <FieldLabel>{t('ai.skillPackages.descriptionLabel')}</FieldLabel>
                <textarea
                  value={description}
                  onChange={(e) => setDescription(e.target.value)}
                  rows={2}
                />
              </label>

              <SkillDefinitionsEditor
                skills={skills}
                onChange={setSkills}
                disabled={updateMutation.isPending}
              />

              {formError && (
                <p className="field-error" role="alert">
                  {formError}
                </p>
              )}
            </div>

            <div className="modal-footer">
              <button
                type="button"
                className="ghost-btn"
                onClick={onClose}
                disabled={updateMutation.isPending}
              >
                {t('shared.cancel')}
              </button>
              <button
                type="submit"
                className="btn-primary"
                disabled={updateMutation.isPending}
              >
                {updateMutation.isPending ? t('shared.saving') : t('shared.save')}
              </button>
            </div>
          </form>
        )}
      </div>
    </ModalBackdrop>
  )
}

function SkillDefinitionsEditor({
  skills,
  onChange,
  disabled = false,
}: {
  skills: PackageSkillDraft[]
  onChange: (skills: PackageSkillDraft[]) => void
  disabled?: boolean
}) {
  const { t } = useI18n()

  function updateSkill(index: number, patch: Partial<PackageSkillDraft>) {
    const next = [...skills]
    next[index] = { ...next[index]!, ...patch }
    onChange(next)
  }

  function addSkill() {
    onChange([...skills, emptySkillDraft()])
  }

  function removeSkill(index: number) {
    if (skills.length <= 1) {
      return
    }
    onChange(skills.filter((_, i) => i !== index))
  }

  return (
    <fieldset className="form-group skill-definitions-editor" style={{ marginTop: 16 }}>
      <legend style={{ fontWeight: 600, marginBottom: 8 }}>
        {t('ai.skillPackages.skillsSection')}
      </legend>

      <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
        {skills.map((skill, index) => (
          <div
            key={index}
            className="skill-definition-row"
            style={{
              padding: 12,
              border: '1px solid var(--border)',
              borderRadius: 'var(--radius-md)',
              background: 'var(--surface-raised, var(--surface))',
            }}
          >
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 }}>
              <span style={{ fontWeight: 600, fontSize: 13 }}>
                #{index + 1}
              </span>
              {skills.length > 1 && (
                <button
                  type="button"
                  className="action-enter-btn danger btn-sm"
                  onClick={() => removeSkill(index)}
                  disabled={disabled}
                  aria-label={`${t('ai.skillPackages.removeSkill')} #${index + 1}`}
                >
                  <Trash2 aria-hidden="true" />
                  {t('ai.skillPackages.removeSkill')}
                </button>
              )}
            </div>

            <label className="form-group" style={{ marginBottom: 8 }}>
              <FieldLabel required>{t('ai.skillPackages.skillName')}</FieldLabel>
              <input
                value={skill.name}
                onChange={(e) => updateSkill(index, { name: e.target.value })}
                placeholder="browse-web"
                disabled={disabled}
                required
              />
            </label>

            <label className="form-group" style={{ marginBottom: 8 }}>
              <FieldLabel required>{t('ai.skillPackages.skillDescription')}</FieldLabel>
              <input
                value={skill.description}
                onChange={(e) => updateSkill(index, { description: e.target.value })}
                placeholder="Skill description"
                disabled={disabled}
                required
              />
            </label>

            <label className="form-group" style={{ marginBottom: 0 }}>
              <FieldLabel required>{t('ai.skillPackages.skillContent')}</FieldLabel>
              <textarea
                value={skill.content}
                onChange={(e) => updateSkill(index, { content: e.target.value })}
                rows={4}
                placeholder="Instructions or prompt content for this skill"
                disabled={disabled}
                required
              />
            </label>
          </div>
        ))}

        <button
          type="button"
          className="ghost-btn"
          style={{ alignSelf: 'flex-start' }}
          onClick={addSkill}
          disabled={disabled}
        >
          <Plus aria-hidden="true" />
          {t('ai.skillPackages.addSkill')}
        </button>
      </div>
    </fieldset>
  )
}
