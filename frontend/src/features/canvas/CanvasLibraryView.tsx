import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useNavigate } from 'react-router'
import { useCanvasRuntime } from '@/features/canvas/CanvasRuntimeContext'
import {
  createCanvas,
  listCanvases,
} from '@/shared/api/studio-service'
import type { CanvasDocumentDTO } from '@/shared/api/contracts/studio'
import { queryKeys } from '@/shared/lib/query-keys'
import { useI18n } from '@/shared/i18n'

export function CanvasLibraryView() {
  const { setToast } = useCanvasRuntime()
  const { t } = useI18n()
  const navigate = useNavigate()
  const queryClient = useQueryClient()

  const canvasesQuery = useQuery({
    queryKey: queryKeys.studio.canvases,
    queryFn: ({ signal }) => listCanvases({ signal }),
  })

  const createMutation = useMutation({
    mutationFn: (title: string) => createCanvas(title),
    onSuccess: async (created) => {
      await queryClient.invalidateQueries({ queryKey: queryKeys.studio.canvases })
      navigate(`/canvas/${created.id}`)
    },
    onError: (error: Error) => {
      setToast(error.message || t('canvas.toast.library.createError'))
    },
  })

  const canvases = canvasesQuery.data ?? []

  return (
    <section className="view library-view active" id="libraryView" tabIndex={-1} aria-labelledby="libraryTitle">
      <div className="library-content">
        <div className="eyebrow">
          <span className="status-dot" />
          {t('canvas.library.eyebrow')}
        </div>
        <div className="library-heading">
          <div>
            <h1 id="libraryTitle">
              {t('canvas.library.headingLine1')}
              <br />
              {t('canvas.library.headingLine2')}
            </h1>
            <p>{t('canvas.library.description')}</p>
          </div>
        </div>

        <section className="canvas-library" aria-labelledby="canvasTitle">
          <div className="section-heading library-toolbar">
            <div>
              <h2 id="canvasTitle">{t('canvas.library.title')}</h2>
            </div>
          </div>

          {canvasesQuery.isLoading ? (
            <div className="state-block" role="status">{t('canvas.library.loading')}</div>
          ) : null}
          {canvasesQuery.isError ? (
            <div className="state-block danger" role="alert">
              {t('canvas.library.loadError', { message: (canvasesQuery.error as Error).message })}
              <button type="button" onClick={() => void canvasesQuery.refetch()}>重试</button>
            </div>
          ) : null}

          <div className="project-grid">
            <button
              type="button"
              className="project-card create-card"
              aria-label={t('canvas.library.createAria')}
              disabled={createMutation.isPending}
              onClick={() => createMutation.mutate('未命名画布')}
            >
              <div className="project-preview create-preview">
                <span className="create-plus">+</span>
              </div>
              <strong>{t('canvas.library.createTitle')}</strong>
              <small>{t('canvas.library.createSubtitle')}</small>
            </button>

            {canvases.map((canvas) => (
              <button
                key={canvas.id}
                type="button"
                className="project-card"
                onClick={() => navigate(`/canvas/${canvas.id}`)}
              >
                <div className="project-preview research-preview">
                  <i />
                  <i />
                  <i />
                </div>
                <strong>{canvas.title}</strong>
                <small>
                  {t('canvas.library.version')}
                  {' '}
                  {canvas.version}
                </small>
                <div className="project-footer">
                  <span>{t('canvas.library.realCanvas')}</span>
                  <span>
                    #
                    {shortId(canvas)}
                  </span>
                </div>
              </button>
            ))}
          </div>
          {!canvasesQuery.isLoading && !canvasesQuery.isError && canvases.length === 0 ? (
            <div className="canvas-empty-state">
              <strong>还没有画布</strong>
              <p>创建一个空白画布，开始组织资源与 Function。</p>
            </div>
          ) : null}
        </section>
      </div>
    </section>
  )
}

function shortId(canvas: CanvasDocumentDTO) {
  return canvas.id.length > 6 ? canvas.id.slice(-6) : canvas.id
}
