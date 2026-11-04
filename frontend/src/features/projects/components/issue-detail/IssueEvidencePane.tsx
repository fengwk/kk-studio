import { Upload } from 'lucide-react'
import { useI18n } from '@/shared/i18n'
import type { StorageService } from '@/shared/api/storage-service'
import { Button } from '@/shared/ui/controls/Button'
import { EmptyState } from '@/shared/ui/feedback/EmptyState'
import type { IssueEvidenceDTO } from '@/shared/api/generated/contracts'
import { EvidenceRow } from './EvidenceRow'

interface IssueEvidencePaneProps {
  evidences: IssueEvidenceDTO[]
  storageService: StorageService
  isUploading: boolean
  uploadDisabled: boolean
  fileInputRef: React.RefObject<HTMLInputElement | null>
  onFileSelected: (event: React.ChangeEvent<HTMLInputElement>) => void
}

/** 证据 tab：上传入口与公开证据列表。 */
export function IssueEvidencePane({
  evidences,
  storageService,
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

      {evidences.length === 0 ? (
        <EmptyState title={t('projects.issue.emptyEvidence')} />
      ) : (
        <div className="evidence-list">
          {evidences.map((evidence) => (
            <EvidenceRow key={evidence.blobId} evidence={evidence} storageService={storageService} />
          ))}
        </div>
      )}
    </div>
  )
}
