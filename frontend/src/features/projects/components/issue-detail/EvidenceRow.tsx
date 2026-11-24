import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { CircleAlert, Download, Paperclip } from 'lucide-react'
import type { StorageService } from '@/shared/api/storage-service'
import type { IssueEvidenceDTO, StoragePresignedUrlDTO } from '@/shared/api/generated/contracts'
import { useI18n } from '@/shared/i18n'
import { Button } from '@/shared/ui/controls/Button'
import { Badge } from '@/shared/ui/data/Badge'

interface EvidenceRowProps {
  evidence: IssueEvidenceDTO
  storageService: StorageService
}

export function EvidenceRow({ evidence, storageService }: EvidenceRowProps) {
  const { t } = useI18n()
  /** 先要下载地址，下载地址不可用时退到预览地址；两者都失败才算失败，由调用处决定怎么呈现。 */
  const requestUrl = async (): Promise<StoragePresignedUrlDTO> => {
    try {
      return await storageService.getBlobDownloadUrl(evidence.blobId)
    } catch {
      return await storageService.getBlobPreviewUrl(evidence.blobId)
    }
  }
  const { data: presignedUrl, isLoading } = useQuery({
    queryKey: ['storage', 'blob', 'download-url', evidence.blobId],
    queryFn: requestUrl,
    enabled: Boolean(evidence.blobId),
    staleTime: 60_000,
  })

  const [isOpening, setIsOpening] = useState(false)
  const [openFailed, setOpenFailed] = useState(false)

  const openEvidence = async () => {
    setIsOpening(true)
    setOpenFailed(false)
    try {
      const signedUrl = await requestUrl()
      window.open(signedUrl.url, '_blank', 'noreferrer')
    } catch {
      // 两个地址都拿不到：行内说明失败，重试入口就是同一个动作。
      setOpenFailed(true)
    } finally {
      setIsOpening(false)
    }
  }

  const handleClick = (e: React.MouseEvent<HTMLAnchorElement>) => {
    if (presignedUrl?.url) {
      return
    }
    e.preventDefault()
    void openEvidence()
  }

  return (
    <div key={evidence.blobId} className="evidence-card">
      <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
        <Paperclip size={14} aria-hidden="true" />
        <strong>{evidence.name || evidence.blobId}</strong>
        {evidence.actorAgentName && (
          <Badge tone="accent">{evidence.actorAgentName}</Badge>
        )}
      </div>
      <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
        <a
          href={presignedUrl?.url || '#'}
          target="_blank"
          rel="noreferrer"
          className="btn-secondary"
          onClick={handleClick}
          title={t('projects.issue.previewOrDownload')}
        >
          <Download size={14} className={isLoading || isOpening ? 'animate-spin' : ''} aria-hidden="true" />
          <span>{isLoading || isOpening ? t('projects.issue.fetching') : t('projects.issue.downloadOrView')}</span>
        </a>
      </div>

      {openFailed && (
        <div className="evidence-feedback" role="alert">
          <CircleAlert size={16} aria-hidden="true" />
          <span>{t('projects.issue.evidenceOpenFailed')}</span>
          <Button size="compact" variant="secondary" disabled={isOpening} onClick={() => void openEvidence()}>
            {t('shared.retry')}
          </Button>
        </div>
      )}
    </div>
  )
}
