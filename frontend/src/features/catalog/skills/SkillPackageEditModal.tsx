import { useState, type FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { agentService } from '@/shared/api/agent-service'
import type { SkillPackageDetailDTO, SkillPackageEditDTO } from '@/shared/api/generated/contracts'
import { useI18n } from '@/shared/i18n'
import { errorDetailMessage as errorDetail } from '@/shared/lib/user-facing-error'
import { Button } from '@/shared/ui/controls/Button'
import { Checkbox } from '@/shared/ui/controls/Checkbox'
import { FieldLabel } from '@/shared/ui/controls/FieldLabel'
import { TextArea } from '@/shared/ui/controls/TextArea'
import { TextInput } from '@/shared/ui/controls/TextInput'
import { Dialog } from '@/shared/ui/overlays/Dialog'
import {
  isDescriptionWithinLimit,
  isGitPackage,
  normalizeDescription,
  skillPackageQueryKeys,
} from './skill-package-model'
import './skill-packages.css'

/**
 * 编辑技能包：Git 渠道可改描述、分支与令牌，仓库地址只读；
 * 上传渠道只能改描述。所有编辑都带上服务端当前版本的乐观锁。
 */
export function SkillPackageEditModal({
  packageName,
  onClose,
  onSuccess,
}: {
  packageName: string
  onClose: () => void
  onSuccess: (detail: SkillPackageDetailDTO) => void
}) {
  const { t } = useI18n()
  const queryClient = useQueryClient()
  const detailQuery = useQuery({
    queryKey: skillPackageQueryKeys.detail(packageName),
    queryFn: () => agentService.getSkillPackage(packageName),
    staleTime: 0,
  })
  const editMutation = useMutation({
    mutationFn: (data: SkillPackageEditDTO) => agentService.editSkillPackage(packageName, data),
    onSuccess: (detail) => {
      queryClient.setQueryData(skillPackageQueryKeys.detail(detail.packageName), detail)
      void queryClient.invalidateQueries({ queryKey: skillPackageQueryKeys.list })
      onSuccess(detail)
    },
  })
  const target = detailQuery.data

  return (
    <Dialog
      className="resource-modal-card skill-package-modal modal-touch-target"
      title={`${t('ai.skillPackages.edit')}: ${packageName}`}
      pending={editMutation.isPending}
      onClose={onClose}
    >
      {!target ? (
        <>
          <div className="modal-body">
            {detailQuery.error ? (
              <div className="form-error-banner" role="alert">
                {errorDetail(detailQuery.error)}
              </div>
            ) : (
              <p className="inline-hint">{t('ai.skillPackages.loadingDetail')}</p>
            )}
          </div>
          <div className="modal-footer">
            <Button variant="inline" onClick={onClose}>
              {t('shared.close')}
            </Button>
          </div>
        </>
      ) : (
        <EditPackageForm
          target={target}
          pending={editMutation.isPending}
          error={editMutation.error ? errorDetail(editMutation.error) : null}
          onClose={onClose}
          onSubmit={(data) => editMutation.mutate(data)}
        />
      )}
    </Dialog>
  )
}

function EditPackageForm({
  target,
  pending,
  error,
  onClose,
  onSubmit,
}: {
  target: SkillPackageDetailDTO
  pending: boolean
  error: string | null
  onClose: () => void
  onSubmit: (data: SkillPackageEditDTO) => void
}) {
  const { t } = useI18n()
  const gitPackage = isGitPackage(target)
  const [description, setDescription] = useState(target.description ?? '')
  const [branch, setBranch] = useState(target.git?.branch ?? '')
  const [token, setToken] = useState('')
  const [clearToken, setClearToken] = useState(false)
  const [formError, setFormError] = useState<string | null>(null)

  function handleSubmit(event: FormEvent) {
    event.preventDefault()
    if (!isDescriptionWithinLimit(description)) {
      setFormError(t('ai.skillPackages.descriptionTooLong'))
      return
    }
    if (gitPackage && !branch.trim()) {
      setFormError(t('ai.skillPackages.branchRequired'))
      return
    }
    setFormError(null)
    const payload: SkillPackageEditDTO = {
      expectedVersion: target.version,
      description: normalizeDescription(description),
      // 令牌字段必填：未修改时显式 KEEP；上传渠道同样只接受 KEEP。
      token: { operation: 'KEEP' },
    }
    if (gitPackage) {
      payload.branch = branch.trim()
      if (clearToken) {
        payload.token = { operation: 'CLEAR' }
      } else if (token.trim()) {
        payload.token = { operation: 'SET', value: token.trim() }
      }
    }
    onSubmit(payload)
  }

  return (
    <form className="modal-card-form" onSubmit={handleSubmit} noValidate>
        <div className="modal-body">
          {formError || error ? (
            <div className="form-error-banner" role="alert">
              {formError ?? error}
            </div>
          ) : null}

          <label className="form-group">
            <FieldLabel>{t('ai.skillPackages.name')}</FieldLabel>
            <TextInput value={target.packageName} disabled readOnly />
          </label>

          {gitPackage ? (
            <label className="form-group">
              <FieldLabel>{t('ai.skillPackages.repositoryUrl')}</FieldLabel>
              <TextInput value={target.git?.repositoryUrl ?? '—'} disabled readOnly />
            </label>
          ) : null}

          <label className="form-group">
            <FieldLabel>{t('ai.skillPackages.descriptionLabel')}</FieldLabel>
            <TextArea
              value={description}
              placeholder={t('ai.catalog.form.descriptionPlaceholder')}
              rows={2}
              disabled={pending}
              onChange={(event) => setDescription(event.target.value)}
            />
          </label>

          {gitPackage ? (
            <>
              <label className="form-group">
                <FieldLabel required>{t('ai.skillPackages.branch')}</FieldLabel>
                <TextInput
                  value={branch}
                  onChange={(event) => setBranch(event.target.value)}
                  disabled={pending}
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
                  disabled={clearToken || pending}
                  onChange={(event) => {
                    setToken(event.target.value)
                    if (clearToken) {
                      setClearToken(false)
                    }
                  }}
                />
                <small>{t('ai.skillPackages.tokenEditHint')}</small>
              </label>

              <Checkbox
                checked={clearToken}
                disabled={pending}
                label={t('ai.skillPackages.clearToken')}
                onChange={(checked) => {
                  setClearToken(checked)
                  if (checked) {
                    setToken('')
                  }
                }}
              />
            </>
          ) : null}
        </div>

        <div className="modal-footer">
          <Button variant="inline" onClick={onClose} disabled={pending}>
            {t('shared.cancel')}
          </Button>
          <Button type="submit" loading={pending}>
            {t('ai.catalog.action.saveChanges')}
          </Button>
        </div>
    </form>
  )
}
