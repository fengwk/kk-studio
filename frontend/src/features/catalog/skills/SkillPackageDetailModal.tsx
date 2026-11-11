import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Download, Trash2 } from 'lucide-react'
import { agentService } from '@/shared/api/agent-service'
import { formatByteCount as formatBytes } from '@/shared/lib/format-bytes'
import { formatBackendDate } from '@/shared/lib/date-format'
import { errorDetailMessage as errorDetail } from '@/shared/lib/user-facing-error'
import { useI18n } from '@/shared/i18n'
import { Badge } from '@/shared/ui/data/Badge'
import { Button } from '@/shared/ui/controls/Button'
import { ConfirmActionModal } from '@/shared/ui/overlays/ConfirmActionModal'
import { Dialog } from '@/shared/ui/overlays/Dialog'
import {
  buildFileTree,
  displayPackageVersion,
  formatCommit,
  isGitPackage,
  skillPackageQueryKeys,
  triggerDownload,
} from './skill-package-model'
import { SkillPackageTree } from './SkillPackageTree'
import './skill-packages.css'

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
    mutationFn: (expectedVersion: string) => agentService.deleteSkillPackage(packageName, expectedVersion),
    onSuccess: () => {
      setDeleteOpen(false)
      void queryClient.invalidateQueries({ queryKey: skillPackageQueryKeys.list })
      onDeleted()
    },
  })

  const detail = detailQuery.data
  const tree = detail && filesQuery.data
    ? buildFileTree(packageName, filesQuery.data)
    : null
  const loadError = detailQuery.error ?? filesQuery.error

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
            <section className="skill-detail-section">
              <div className="skill-section-heading">
                <h3>{t('ai.skillPackages.summary')}</h3>
                <Badge tone="accent">
                  {isGitPackage(detail)
                    ? t('ai.skillPackages.source.GIT')
                    : t('ai.skillPackages.source.UPLOAD')}
                </Badge>
              </div>
              <dl className="skill-facts">
                <div>
                  <dt>{t('ai.skillPackages.packageVersion')}</dt>
                  <dd className="is-mono" title={detail.packageVersion}>
                    {displayPackageVersion(detail)}
                  </dd>
                </div>
                <div>
                  <dt>{t('ai.skillPackages.publishedAt')}</dt>
                  <dd>{formatBackendDate(detail.publishedAt)}</dd>
                </div>
                <div>
                  <dt>{t('ai.skillPackages.skillsCount')}</dt>
                  <dd>{detail.skills.length}</dd>
                </div>
                <div>
                  <dt>{t('ai.skillPackages.archiveSize')}</dt>
                  <dd>{formatBytes(detail.artifactSizeBytes)}</dd>
                </div>
                <div>
                  <dt>{t('ai.skillPackages.archiveSha')}</dt>
                  <dd className="is-mono" title={detail.artifactSha256}>
                    {formatCommit(detail.artifactSha256)}
                  </dd>
                </div>
                {detail.git ? (
                  <>
                    <div>
                      <dt>{t('ai.skillPackages.repositoryUrl')}</dt>
                      <dd className="is-wrap">{detail.git.repositoryUrl}</dd>
                    </div>
                    <div>
                      <dt>{t('ai.skillPackages.branch')}</dt>
                      <dd className="is-mono">{detail.git.branch}</dd>
                    </div>
                    <div>
                      <dt>{t('ai.skillPackages.token')}</dt>
                      <dd>
                        {detail.git.token.configured
                          ? t('ai.skillPackages.tokenConfigured')
                          : t('ai.skillPackages.tokenNotConfigured')}
                      </dd>
                    </div>
                    <div>
                      <dt>{t('ai.skillPackages.observedHeadCommit')}</dt>
                      <dd className="is-mono" title={detail.headCommit ?? undefined}>
                        {formatCommit(detail.headCommit)}
                      </dd>
                    </div>
                  </>
                ) : null}
              </dl>
              {detail.headCheckError ? (
                <p className="field-error" role="alert">
                  {detail.headCheckError}
                </p>
              ) : null}
              <div className="skill-detail-actions">
                <Button
                  variant="ghost"
                  loading={downloadMutation.isPending}
                  onClick={() => downloadMutation.mutate()}
                >
                  {!downloadMutation.isPending && <Download aria-hidden="true" />}
                  {t('ai.skillPackages.downloadArchive')}
                </Button>
                <Button variant="ghost" onClick={onEdit}>
                  {t('ai.skillPackages.edit')}
                </Button>
              </div>
              {downloadMutation.error ? (
                <p className="field-error" role="alert">
                  {errorDetail(downloadMutation.error)}
                </p>
              ) : null}
            </section>

            <section className="skill-detail-section">
              <div className="skill-section-heading">
                <h3>{t('ai.skillPackages.skills')}</h3>
                <span className="inline-hint">
                  {t('ai.skillPackages.skillCountValue', { count: detail.skills.length })}
                </span>
              </div>
              <ul className="skill-list">
                {detail.skills.map((skill) => (
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
                <span className="inline-hint">
                  {t('ai.skillPackages.fileCountValue', { count: filesQuery.data?.length ?? 0 })}
                </span>
              </div>
              {filesQuery.isLoading ? (
                <p className="inline-hint">{t('ai.skillPackages.loadingFiles')}</p>
              ) : tree ? (
                <SkillPackageTree root={tree} />
              ) : null}
            </section>

          </>
        )}
      </div>

      <div className="modal-footer modal-footer-with-leading-action">
        <div className="modal-footer-leading-action">
          <Button
            variant="ghost"
            danger
            disabled={!detail}
            aria-label={`${t('ai.skillPackages.delete')} ${packageName}`}
            onClick={() => {
              deleteMutation.reset()
              setDeleteOpen(true)
            }}
          >
            <Trash2 aria-hidden="true" />
            {t('ai.skillPackages.delete')}
          </Button>
        </div>
        <Button variant="inline" onClick={onClose}>
          {t('shared.close')}
        </Button>
      </div>

      {deleteOpen && detail ? (
        <ConfirmActionModal
          modal={{
            title: t('ai.skillPackages.delete'),
            description: t('ai.skillPackages.deleteConfirm', { name: packageName }),
            confirmLabel: t('ai.skillPackages.delete'),
            tone: 'danger',
            error: deleteMutation.error ? errorDetail(deleteMutation.error) : null,
            onConfirm: () => deleteMutation.mutate(detail.version),
          }}
          pending={deleteMutation.isPending}
          onClose={() => {
            setDeleteOpen(false)
            deleteMutation.reset()
          }}
        />
      ) : null}
    </Dialog>
  )
}
