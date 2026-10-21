import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Download, Paperclip } from 'lucide-react'
import type { StorageService } from '@/shared/api/storage-service'
import { useI18n } from '@/shared/i18n'
import { Badge } from '@/shared/ui/data/Badge'
import type { IssueEvidenceDTO } from '../../types'

interface EvidenceRowProps {
  evidence: IssueEvidenceDTO
  storageService: StorageService
}

export function EvidenceRow({ evidence, storageService }: EvidenceRowProps) {
  const { t } = useI18n()
  const { data: presignedUrl, isLoading } = useQuery({
    queryKey: ['storage', 'blob', 'download-url', evidence.blobId],
    queryFn: async () => {
      try {
        return await storageService.getBlobDownloadUrl(evidence.blobId)
      } catch {
        return await storageService.getBlobPreviewUrl(evidence.blobId)
      }
    },
    enabled: Boolean(evidence.blobId),
    staleTime: 60_000,
  })

  const [isOpening, setIsOpening] = useState(false)

  const handleClick = async (e: React.MouseEvent<HTMLAnchorElement>) => {
    if (presignedUrl?.url) {
      return
    }
    e.preventDefault()
    setIsOpening(true)
    try {
      let res: { url: string } | null = null
      try {
        res = await storageService.getBlobDownloadUrl(evidence.blobId)
      } catch {
        res = await storageService.getBlobPreviewUrl(evidence.blobId)
      }
      if (res?.url) {
        window.open(res.url, '_blank', 'noreferrer')
      }
    } catch {
      // 容错处理
    } finally {
      setIsOpening(false)
    }
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
          className="ghost-btn"
          onClick={handleClick}
          title={t('projects.issue.previewOrDownload')}
        >
          <Download size={14} className={isLoading || isOpening ? 'animate-spin' : ''} aria-hidden="true" />
          <span>{isLoading || isOpening ? t('projects.issue.fetching') : t('projects.issue.downloadOrView')}</span>
        </a>
      </div>
    </div>
  )
}
