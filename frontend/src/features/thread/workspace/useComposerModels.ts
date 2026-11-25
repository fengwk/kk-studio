import { useQuery } from '@tanstack/react-query'
import { request } from '@/shared/api/client'
import type { ThreadComposerModelOption } from '@/features/thread/runtime/thread-panel/thread-composer-control-types'

export function useComposerModels() {
  return useQuery({
    queryKey: ['models', 'summaries'],
    queryFn: ({ signal }) => request('GET /api/platform/model-summaries', {}, { signal }),
    select: (models): ThreadComposerModelOption[] => models.flatMap(({ name, type, variantIds, defaultVariant }) =>
      type === 'MODEL' || type === 'GROUP' ? [{ name, type, config: {
        defaultVariant, variants: variantIds.map((id) => ({ id })),
      } }] : []),
  })
}
