import { useEffect, useState } from 'react'
import { Copy, Check, Download, File, Film, Image as ImageIcon, Music, AlertTriangle } from 'lucide-react'
import type { CloudFilesApi } from './cloud-files-api'
import { cloudFilesApi } from './cloud-files-api'
import { detectMediaCategory, formatBytes } from './files-utils'
import type { CloudNodeDTO } from './types'

interface BlobPreviewProps {
  node: CloudNodeDTO
  api?: CloudFilesApi
}

export function BlobPreview({ node, api = cloudFilesApi }: BlobPreviewProps) {
  const [previewUrl, setPreviewUrl] = useState<string | null>(null)
  const [previewLoading, setPreviewLoading] = useState(false)
  const [previewError, setPreviewError] = useState<string | null>(null)
  const [copiedSha, setCopiedSha] = useState(false)
  const [downloading, setDownloading] = useState(false)

  const category = detectMediaCategory(node.mediaType, node.name)
  const isPreviewable = category === 'image' || category === 'audio' || category === 'video'

  useEffect(() => {
    let active = true

    if (!isPreviewable || !node.blobId) {
      setPreviewUrl(null)
      setPreviewLoading(false)
      setPreviewError(null)
      return
    }

    setPreviewLoading(true)
    setPreviewError(null)

    api.getBlobPreviewUrl(node.blobId)
      .then((res) => {
        if (active) {
          setPreviewUrl(res.url)
          setPreviewLoading(false)
        }
      })
      .catch((err) => {
        if (active) {
          setPreviewError(err instanceof Error ? err.message : '加载预览失败')
          setPreviewLoading(false)
        }
      })

    return () => {
      active = false
    }
  }, [api, isPreviewable, node.blobId])

  const handleDownload = async () => {
    if (!node.blobId) {
      return
    }
    setDownloading(true)
    try {
      const res = await api.getBlobDownloadUrl(node.blobId)
      const link = document.createElement('a')
      link.href = res.url
      link.download = node.name
      document.body.appendChild(link)
      link.click()
      document.body.removeChild(link)
    } catch (err) {
      setPreviewError(err instanceof Error ? err.message : '下载失败')
    } finally {
      setDownloading(false)
    }
  }

  const handleCopySha = async () => {
    if (!node.sha256) {
      return
    }
    try {
      await navigator.clipboard.writeText(node.sha256)
      setCopiedSha(true)
      setTimeout(() => setCopiedSha(false), 2000)
    } catch {
      // Ignore clipboard write failures
    }
  }

  return (
    <div className="blob-preview-container" data-testid="blob-preview">
      <div className="blob-preview-header">
        <div className="blob-preview-title">
          <div className="blob-category-icon" aria-hidden="true">
            {category === 'image' && <ImageIcon />}
            {category === 'audio' && <Music />}
            {category === 'video' && <Film />}
            {category === 'generic' && <File />}
            {category === 'text' && <File />}
          </div>
          <div>
            <h3 className="blob-filename">{node.name}</h3>
            <span className="blob-path">{node.path}</span>
          </div>
        </div>
        <button
          type="button"
          className="btn-download"
          onClick={handleDownload}
          disabled={downloading || !node.blobId}
          aria-label={`下载 ${node.name}`}
        >
          <Download aria-hidden="true" size={16} />
          <span>{downloading ? '准备中...' : '下载文件'}</span>
        </button>
      </div>

      <div className="blob-preview-body">
        {previewLoading && (
          <div className="blob-preview-status" role="status">
            <span>正在加载媒体预览...</span>
          </div>
        )}

        {previewError && (
          <div className="blob-preview-error" role="alert">
            <AlertTriangle size={16} aria-hidden="true" />
            <span>{previewError}</span>
          </div>
        )}

        {!previewLoading && !previewError && previewUrl && (
          <div className="blob-media-viewer">
            {category === 'image' && (
              <img
                src={previewUrl}
                alt={node.name}
                className="blob-image-preview"
                data-testid="blob-image-element"
              />
            )}
            {category === 'audio' && (
              <audio
                controls
                src={previewUrl}
                className="blob-audio-preview"
                data-testid="blob-audio-element"
              >
                您的浏览器不支持音频播放。
              </audio>
            )}
            {category === 'video' && (
              <video
                controls
                src={previewUrl}
                className="blob-video-preview"
                data-testid="blob-video-element"
              >
                您的浏览器不支持视频播放。
              </video>
            )}
          </div>
        )}

        {!isPreviewable && (
          <div className="blob-unsupported-preview">
            <File size={48} aria-hidden="true" className="unsupported-icon" />
            <p>该文件类型不支持在线预览，请直接下载查看。</p>
          </div>
        )}
      </div>

      <div className="blob-metadata-panel">
        <h4>文件信息</h4>
        <dl className="blob-metadata-grid">
          <dt>文件大小</dt>
          <dd>{formatBytes(node.sizeBytes)}</dd>
          <dt>媒体类型</dt>
          <dd>{node.mediaType || '未知'}</dd>
          <dt>版本</dt>
          <dd>{node.version}</dd>
          {node.sha256 && (
            <>
              <dt>SHA-256</dt>
              <dd className="blob-sha-cell">
                <code className="blob-sha-code" title={node.sha256}>
                  {node.sha256}
                </code>
                <button
                  type="button"
                  className="btn-icon"
                  onClick={handleCopySha}
                  aria-label={copiedSha ? '已复制哈希' : '复制哈希'}
                >
                  {copiedSha ? <Check size={14} /> : <Copy size={14} />}
                </button>
              </dd>
            </>
          )}
        </dl>
      </div>
    </div>
  )
}
