import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Trash2 } from 'lucide-react'
import { agentService } from '@/shared/api/agent-service'
import { errorDetailMessage as errorDetail } from '@/shared/lib/user-facing-error'
import { useI18n } from '@/shared/i18n'
import { Button } from '@/shared/ui/controls/Button'
import { ConfirmActionModal } from '@/shared/ui/overlays/ConfirmActionModal'
import { Dialog } from '@/shared/ui/overlays/Dialog'
import { skillPackageQueryKeys, triggerDownload } from './skill-package-model'
import {
  SkillPackageFilesSection,
  SkillPackageSkillsSection,
  SkillPackageSummarySection,
} from './skill-package-detail-sections'
import './skill-packages.css'

/**
 * 技能包详情：读取详情与归档清单，展示摘要/技能/文件树三节，并承载下载、编辑入口与删除确认。
 */
export function SkillPackageDetailModal({
  packageName,
  onClose,
  onEdit,
  onDeleted,
}: {
  packageName: string
  onClose: () => void
  onEdit: () => void
  onDeleted: () => void
}) {
  const { t } = useI18n()
  const queryClient = useQueryClient()
  const [deleteOpen, setDeleteOpen] = useState(false)

  const detailQuery = useQuery({
    queryKey: skillPackageQueryKeys.detail(packageName),
    queryFn: () => agentService.getSkillPackage(packageName),
    staleTime: 30_000,
  })
  const filesQuery = useQuery({
    queryKey: skillPackageQueryKeys.files(packageName),
    queryFn: () => agentService.listSkillPackageFiles(packageName),
    staleTime: 30_000,
  })
  const downloadMutation = useMutation({
    mutationFn: () => agentService.getSkillPackageArchiveUrl(packageName),
    onSuccess: (archive) => triggerDownload(archive.download.url, archive.filename),
  })
  const deleteMutation = useMutation({
    mutationFn: (expectedVersion: string) =>
      agentService.deleteSkillPackage(packageName, expectedVersion),
    onSuccess: () => {
      setDeleteOpen(false)
      void queryClient.invalidateQueries({ queryKey: skillPackageQueryKeys.list })
      onDeleted()
    },
  })

  const detail = detailQuery.data
  const loadError = detailQuery.error ?? filesQuery.error

  /** 每次打开确认框都清掉上一次的失败，避免旧错误串到新一次删除。 */
  function requestDelete() {
    deleteMutation.reset()
    setDeleteOpen(true)
  }

  function closeDelete() {
    setDeleteOpen(false)
    deleteMutation.reset()
  }

  return (
    <Dialog
      className="resource-modal-card skill-package-modal modal-touch-target"
      title={t('ai.skillPackages.detailTitle', { name: packageName })}
      pending={downloadMutation.isPending}
      onClose={onClose}
    >
      <div className="modal-body">
        {loadError ? (
          <div className="form-error-banner" role="alert">
            {errorDetail(loadError)}
          </div>
        ) : null}

        {!detail ? (
          <p className="inline-hint">{t('ai.skillPackages.loadingDetail')}</p>
        ) : (
          <>
            <SkillPackageSummarySection
              detail={detail}
              downloadPending={downloadMutation.isPending}
              downloadError={downloadMutation.error}
              onDownload={() => downloadMutation.mutate()}
              onEdit={onEdit}
            />
            <SkillPackageSkillsSection detail={detail} />
            <SkillPackageFilesSection
              packageName={packageName}
              files={filesQuery.data}
              loading={filesQuery.isLoading}
            />
          </>
        )}
      </div>

      <div className="modal-footer modal-footer-with-leading-action">
        <div className="modal-footer-leading-action">
          <Button
            variant="secondary"
            danger
            disabled={!detail}
            aria-label={`${t('ai.skillPackages.delete')} ${packageName}`}
            onClick={requestDelete}
          >
            <Trash2 aria-hidden="true" />
            {t('ai.skillPackages.delete')}
          </Button>
        </div>
        <Button variant="secondary" onClick={onClose}>
          {t('shared.close')}
        </Button>
      </div>

      {deleteOpen && detail ? (
        <ConfirmActionModal
          modal={{
            title: t('ai.skillPackages.deleteTitle', { name: packageName }),
            description: t('ai.skillPackages.deleteConfirm', { name: packageName }),
            confirmLabel: t('ai.skillPackages.delete'),
            tone: 'danger',
            error: deleteMutation.error ? errorDetail(deleteMutation.error) : null,
            onConfirm: () => deleteMutation.mutate(detail.version),
          }}
          pending={deleteMutation.isPending}
          onClose={closeDelete}
        />
      ) : null}
    </Dialog>
  )
}
