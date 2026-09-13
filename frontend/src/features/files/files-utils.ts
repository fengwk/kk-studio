export function normalizePath(path: string): string {
  if (!path || path === '/') {
    return '/'
  }
  const clean = path.replace(/\/+/g, '/')
  return clean.endsWith('/') ? clean.slice(0, -1) : clean
}

export function getParentPath(path: string): string {
  const norm = normalizePath(path)
  if (norm === '/') {
    return '/'
  }
  const lastSlash = norm.lastIndexOf('/')
  if (lastSlash <= 0) {
    return '/'
  }
  return norm.slice(0, lastSlash)
}

export function getNodeDisplayName(path: string): string {
  const norm = normalizePath(path)
  if (norm === '/') {
    return '/'
  }
  const lastSlash = norm.lastIndexOf('/')
  return lastSlash >= 0 ? norm.slice(lastSlash + 1) : norm
}

export function isArtifactPath(path: string): boolean {
  const norm = normalizePath(path)
  return norm === '/.artifacts' || norm.startsWith('/.artifacts/')
}

export function formatBytes(sizeBytes: string | number | null): string {
  if (sizeBytes === null || sizeBytes === undefined) {
    return '0 B'
  }
  const bytes = typeof sizeBytes === 'string' ? Number(sizeBytes) : sizeBytes
  if (Number.isNaN(bytes) || bytes < 0) {
    return '0 B'
  }
  if (bytes === 0) {
    return '0 B'
  }
  const k = 1024
  const sizes = ['B', 'KB', 'MB', 'GB', 'TB']
  const i = Math.floor(Math.log(bytes) / Math.log(k))
  const formatted = (bytes / Math.pow(k, i)).toFixed(i === 0 ? 0 : 1)
  return `${formatted} ${sizes[i]}`
}

export type MediaCategory = 'image' | 'audio' | 'video' | 'text' | 'generic'

export function detectMediaCategory(mediaType: string | null, filename: string): MediaCategory {
  const type = mediaType?.toLowerCase() ?? ''
  const name = filename.toLowerCase()

  if (type.startsWith('image/') || /\.(png|jpg|jpeg|gif|webp|svg|bmp|ico)$/.test(name)) {
    return 'image'
  }
  if (type.startsWith('audio/') || /\.(mp3|wav|ogg|m4a|aac|flac)$/.test(name)) {
    return 'audio'
  }
  if (type.startsWith('video/') || /\.(mp4|webm|ogv|mov|mkv)$/.test(name)) {
    return 'video'
  }
  if (
    type.startsWith('text/')
    || type === 'application/json'
    || type === 'application/xml'
    || /\.(txt|md|json|xml|html|css|js|ts|jsx|tsx|yml|yaml|sql|sh)$/.test(name)
  ) {
    return 'text'
  }
  return 'generic'
}
