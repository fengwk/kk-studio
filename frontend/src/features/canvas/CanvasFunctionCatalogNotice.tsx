import { useCanvasRuntime } from '@/features/canvas/CanvasRuntimeContext'
import { useI18n } from '@/shared/i18n'
import { Button } from '@/shared/ui/controls/Button'

/** 在依赖函数目录的操作旁反馈目录状态。 */
export function CanvasFunctionCatalogNotice({ showMissing = false }: { showMissing?: boolean }) {
  const { modelsQuery } = useCanvasRuntime()
  const { t } = useI18n()
  if (modelsQuery.isPending) {
    return <p role="status">{t('canvas.catalog.loading')}</p>
  }
  if (modelsQuery.isError) {
    return (
      <div className="canvas-catalog-error">
        <span role="alert">{t('canvas.catalog.failed')}</span>
        <Button variant="secondary" loading={modelsQuery.isFetching} onClick={() => void modelsQuery.refetch()}>
          {t('canvas.editor.retry')}
        </Button>
      </div>
    )
  }
  return showMissing ? <p role="status">{t('canvas.catalog.functionMissing')}</p> : null
}
