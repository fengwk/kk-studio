import type { CanvasResourceKind } from '@/shared/api/contracts/studio'

export interface CanvasFileDescriptor {
  kind: Exclude<CanvasResourceKind, 'TEXT'>
  mediaType: string
}

const EXTENSION_DESCRIPTORS: Record<string, CanvasFileDescriptor> = {
  jpg: { kind: 'IMAGE', mediaType: 'image/jpeg' },
  jpeg: { kind: 'IMAGE', mediaType: 'image/jpeg' },
  png: { kind: 'IMAGE', mediaType: 'image/png' },
  webp: { kind: 'IMAGE', mediaType: 'image/webp' },
  heic: { kind: 'IMAGE', mediaType: 'image/heic' },
  heif: { kind: 'IMAGE', mediaType: 'image/heif' },
  mp4: { kind: 'VIDEO', mediaType: 'video/mp4' },
  mov: { kind: 'VIDEO', mediaType: 'video/quicktime' },
  wav: { kind: 'AUDIO', mediaType: 'audio/wav' },
  mp3: { kind: 'AUDIO', mediaType: 'audio/mpeg' },
}

export function canvasFileDescriptor(file: Pick<File, 'name' | 'type'>): CanvasFileDescriptor | null {
  const extension = file.name.split('.').at(-1)?.toLowerCase()
  const fallback = extension ? EXTENSION_DESCRIPTORS[extension] : undefined
  if (!file.type) {
    return fallback ?? null
  }
  if (file.type.startsWith('image/')) {
    return { kind: 'IMAGE', mediaType: file.type }
  }
  if (file.type.startsWith('video/')) {
    return { kind: 'VIDEO', mediaType: file.type }
  }
  if (file.type.startsWith('audio/')) {
    return { kind: 'AUDIO', mediaType: file.type }
  }
  return fallback ?? null
}
