import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useCanvasRuntime } from '@/features/canvas/CanvasRuntimeContext'
import {
  createCanvas,
  DEFAULT_WORKSPACE_ID,
  listCanvases,
  type CanvasDocumentDTO,
} from '@/shared/api/studio-service'

export function CanvasLibraryView() {
  const { openEditor, setResearchOpen, setToast } = useCanvasRuntime()
  const queryClient = useQueryClient()

  const canvasesQuery = useQuery({
    queryKey: ['studio', 'canvases', DEFAULT_WORKSPACE_ID],
    queryFn: () => listCanvases(DEFAULT_WORKSPACE_ID),
  })

  const createMutation = useMutation({
    mutationFn: (title: string) => createCanvas(title, DEFAULT_WORKSPACE_ID),
    onSuccess: async (created) => {
      await queryClient.invalidateQueries({ queryKey: ['studio', 'canvases'] })
      setToast(`已创建画布「${created.title}」`)
      openEditor()
    },
    onError: (error: Error) => {
      setToast(error.message || '创建画布失败')
    },
  })

  const canvases = canvasesQuery.data ?? []

  return (
    <section className="view library-view active" id="libraryView" tabIndex={-1} aria-labelledby="libraryTitle">
      <div className="library-content">
        <div className="eyebrow">
          <span className="status-dot" />
          Agent 原生工作区
        </div>
        <div className="library-heading">
          <div>
            <h1 id="libraryTitle">
              把想法、资料和结果
              <br />
              放在同一个空间。
            </h1>
            <p>创建画布，组织资源，调用 Function 与 Agent。</p>
          </div>
          <button
            className="text-button"
            type="button"
            onClick={(event) => setResearchOpen(true, event.currentTarget)}
          >
            查看调研结论
            {' '}
            <span>↗</span>
          </button>
        </div>

        <section className="canvas-library" aria-labelledby="canvasTitle">
          <div className="section-heading library-toolbar">
            <div>
              <h2 id="canvasTitle">你的画布</h2>
            </div>
          </div>

          {canvasesQuery.isLoading ? (
            <div className="state-block" role="status">正在加载画布</div>
          ) : null}
          {canvasesQuery.isError ? (
            <div className="state-block danger" role="alert">
              画布列表加载失败：
              {(canvasesQuery.error as Error).message}
            </div>
          ) : null}

          <div className="project-grid">
            <button
              type="button"
              className="project-card create-card"
              aria-label="创建新画布"
              disabled={createMutation.isPending}
              onClick={() => createMutation.mutate('未命名画布')}
            >
              <div className="project-preview create-preview">
                <span className="create-plus">+</span>
              </div>
              <strong>创建新画布</strong>
              <small>空白画布 · 立即开始</small>
            </button>

            {canvases.map((canvas) => (
              <button
                key={canvas.id}
                type="button"
                className="project-card"
                onClick={() => {
                  setToast(`打开「${canvas.title}」`)
                  openEditor()
                }}
              >
                <div className="project-preview research-preview">
                  <i />
                  <i />
                  <i />
                </div>
                <strong>{canvas.title}</strong>
                <small>
                  revision
                  {' '}
                  {canvas.revision}
                  {' '}
                  · 工作区
                  {' '}
                  {canvas.workspaceId}
                </small>
                <div className="project-footer">
                  <span>真实画布</span>
                  <span>
                    #
                    {shortId(canvas)}
                  </span>
                </div>
              </button>
            ))}
          </div>
        </section>
      </div>
    </section>
  )
}

function shortId(canvas: CanvasDocumentDTO) {
  return canvas.id.length > 6 ? canvas.id.slice(-6) : canvas.id
}
