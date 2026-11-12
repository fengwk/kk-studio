import type { ReactNode } from 'react'
import { ResourceUri } from '@/shared/ui/markdown/ResourceUri'

const RESOURCE_URI = /resource:[^\s<>"'`]+/gi
const FILE_URI_WITH_TRAILING_PUNCTUATION =
  /^(resource:\/\/files\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})([),.;!?]+)$/i

/** Renders inline resource references in tool text without treating them as browser URLs. */
export function ResourceUriText({ text }: { text: string }) {
  const parts: ReactNode[] = []
  let offset = 0
  for (const match of text.matchAll(RESOURCE_URI)) {
    const rawUri = match[0]
    const index = match.index
    const fileUriMatch = rawUri.match(FILE_URI_WITH_TRAILING_PUNCTUATION)
    const uri = fileUriMatch?.[1] ?? rawUri
    parts.push(text.slice(offset, index))
    parts.push(<ResourceUri key={`${index}:${uri}`} uri={uri} label={uri} />)
    parts.push(fileUriMatch?.[2] ?? '')
    offset = index + rawUri.length
  }
  parts.push(text.slice(offset))
  return <>{parts}</>
}
