import { Upload } from 'lucide-react'
import { useI18n } from '@/shared/i18n'
import type { StorageService } from '@/shared/api/storage-service'
import { Button } from '@/shared/ui/controls/Button'
import { EmptyState } from '@/shared/ui/feedback/EmptyState'
import { StateBlock } from '@/shared/ui/feedback/StateBlock'
import type { IssueEvidenceDTO } from '@/shared/api/generated/contracts'
import { EvidenceRow } from './EvidenceRow'

interface IssueEvidencePaneProps {
  evidences: IssueEvidenceDTO[]
  storageService: StorageService
  isLoading: boolean
  loadFailed: boolean
  isRetrying: boolean
  onRetry: () => void
  isUploading: boolean
  uploadDisabled: boolean
  fileInputRef: React.RefObject<HTMLInputElement | null>
  onFileSelected: (event: React.ChangeEvent<HTMLInputElement>) => void
}

/**
 * 证据 tab：上传入口与公开证据列表。
 *
 * <p>加载中、读取失败与真正的空列表分别呈现：失败就地说明并给重试，只有成功且为空才说没有证据。
 */
export function IssueEvidencePane({
  evidences,
  storageService,
  isLoading,
  loadFailed,
  isRetrying,
  onRetry,
  isUploading,
  uploadDisabled,
  fileInputRef,
  onFileSelected,
}: IssueEvidencePaneProps) {
  const { t } = useI18n()
  return (
    <div className="tab-pane">
      <div style={{ display: 'flex', justifyContent: 'flex-end', marginBottom: '12px' }}>
        <input
          ref={fileInputRef}
          type="file"
          style={{ display: 'none' }}
          onChange={onFileSelected}
          disabled={isUploading || uploadDisabled}
        />
        <Button
          onClick={() => fileInputRef.current?.click()}
          disabled={isUploading || uploadDisabled}
          style={{ fontSize: 'var(--font-size-sm)' }}
        >
          <Upload size={14} aria-hidden="true" />
          <span>{isUploading ? t('projects.issue.uploading') : t('projects.issue.uploadEvidenceAction')}</span>
        </Button>
      </div>

      {isLoading ? (
        <StateBlock title={t('projects.issue.loadingEvidence')} />
      ) : (
        <>
          {/* 整载失败保留已知证据，用统一的危险态失败块（与诊断事件读取失败一致）就地重试。 */}
          {loadFailed && (
            <div className="evidence-failure" role="alert">
              <StateBlock tone="danger" title={t('projects.issue.evidenceLoadFailed')} />
              <Button size="compact" variant="ghost" disabled={isRetrying} onClick={onRetry}>
                {t('shared.retry')}
              </Button>
            </div>
          )}

          {evidences.length > 0 ? (
            <div className="evidence-list">
              {evidences.map((evidence) => (
                <EvidenceRow key={evidence.blobId} evidence={evidence} storageService={storageService} />
              ))}
            </div>
          ) : loadFailed ? null : (
            <EmptyState title={t('projects.issue.emptyEvidence')} />
          )}
        </>
      )}
    </div>
  )
}
