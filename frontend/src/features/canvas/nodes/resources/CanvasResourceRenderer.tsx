import type { Resource } from '@/features/canvas/domain'
import { CanvasAudioResource } from '@/features/canvas/nodes/resources/CanvasAudioResource'
import { CanvasTextResource } from '@/features/canvas/nodes/resources/CanvasTextResource'
import { CanvasVisualResource } from '@/features/canvas/nodes/resources/CanvasVisualResource'

/** ResourceLayoutFrame 的唯一 renderer 分发点；外层布局不感知具体资源组件。 */
export function CanvasResourceRenderer({ resource }: { resource: Resource }) {
  if (resource.kind === 'TEXT') {
    return <CanvasTextResource resource={resource} />
  }
  if (resource.kind === 'AUDIO') {
    return <CanvasAudioResource resource={resource} />
  }
  return <CanvasVisualResource resource={resource} />
}
