import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Grid2X2 } from 'lucide-react'
import { useRef, useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router'
import { createCanvas, listCanvases } from '@/shared/api/studio-service'
import { queryKeys } from '@/shared/lib/query-keys'
import { useI18n, type AppLocale } from '@/shared/i18n'
import { ResourceCard } from '@/shared/ui/cards/ResourceCard'
import { ResourceCardSkeleton } from '@/shared/ui/cards/ResourceCardSkeleton'
import { ResourceGrid } from '@/shared/ui/cards/ResourceGrid'
import { Button } from '@/shared/ui/controls/Button'
import { FieldLabel } from '@/shared/ui/controls/FieldLabel'
import { TextInput } from '@/shared/ui/controls/TextInput'
import { CreateCard } from '@/shared/ui/feedback/CreateCard'
import { StateBlock } from '@/shared/ui/feedback/StateBlock'
import { Dialog } from '@/shared/ui/overlays/Dialog'

/**
 * 画布库：与其它资源列表共用 ResourceGrid/ResourceCard/CreateCard。
 * 新建卡只打开确认表单，用户确认后才 POST 并进入；取消不产生任何写入。
 */
export function CanvasLibraryView() {
  const { t, locale } = useI18n()
  const navigate = useNavigate()
  const queryClient = useQueryClient()

  const [createOpen, setCreateOpen] = useState(false)
  const [createName, setCreateName] = useState('')
  const [createError, setCreateError] = useState<string | null>(null)
  /**
   * 提交围栏：isPending 要在下一次 render 才可见，双击/连按回车可能在同一批事件内
   * 再次进入提交路径，因此用 ref 作为权威门禁，保证只发出一次创建请求。
   */
  const createInFlightRef = useRef(false)

  const canvasesQuery = useQuery({
    queryKey: queryKeys.studio.canvases,
    queryFn: ({ signal }) => listCanvases({ signal }),
  })

  const createMutation = useMutation({
    mutationFn: (title: string) => createCanvas(title),
    onSuccess: async (created) => {
      await queryClient.invalidateQueries({ queryKey: queryKeys.studio.canvases })
      setCreateOpen(false)
      setCreateName('')
      setCreateError(null)
      navigate(`/canvas/${created.id}`)
    },
    onError: (error: Error) => {
      // 失败保留输入与弹窗，用户可修正后重试；不创建占位画布。
      setCreateError(error.message || t('canvas.toast.library.createError'))
    },
    onSettled: () => {
      createInFlightRef.current = false
    },
  })

  const canvases = canvasesQuery.data ?? []

  function openCreateDialog() {
    setCreateName('')
    setCreateError(null)
    setCreateOpen(true)
  }

  function closeCreateDialog() {
    if (createInFlightRef.current) {
      return
    }
    setCreateOpen(false)
    setCreateError(null)
  }

  function submitCreate(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (createInFlightRef.current) {
      return
    }
    const name = createName.trim()
    if (!name) {
      setCreateError(t('canvas.library.nameRequired'))
      return
    }
    setCreateError(null)
    createInFlightRef.current = true
    createMutation.mutate(name)
  }

  return (
    <section
      className="view library-view active"
      id="libraryView"
      tabIndex={-1}
      aria-labelledby="libraryTitle"
    >
      <div className="library-content">
        <header className="library-heading">
          <h1 id="libraryTitle">{t('canvas.library.title')}</h1>
          <p>{t('canvas.library.description')}</p>
        </header>

        {canvasesQuery.isLoading ? (
          <ResourceCardSkeleton label={t('canvas.library.loading')} />
        ) : null}
        {canvasesQuery.isError ? (
          <div className="library-load-error" role="alert">
            <StateBlock
              tone="danger"
              title={t('canvas.library.loadError', {
                message: (canvasesQuery.error as Error).message,
              })}
            />
            <Button variant="ghost" onClick={() => void canvasesQuery.refetch()}>
              {t('shared.retry')}
            </Button>
          </div>
        ) : null}

        {!canvasesQuery.isLoading && (
          <ResourceGrid>
            <CreateCard
              title={t('canvas.library.createTitle')}
              subtitle={t('canvas.library.createSubtitle')}
              onClick={openCreateDialog}
            />
            {canvases.map((canvas) => (
              <ResourceCard
                key={canvas.id}
                icon={<Grid2X2 aria-hidden="true" />}
                title={canvas.title}
                meta={[
                  [t('canvas.library.updatedAt'), formatUpdatedAt(canvas.updatedAt, locale)],
                ]}
                actions={
                  <Button
                    variant="ghost"
                    size="compact"
                    aria-label={t('canvas.library.openAria', { title: canvas.title })}
                    onClick={() => navigate(`/canvas/${canvas.id}`)}
                  >
                    {t('canvas.library.open')}
                  </Button>
                }
              />
            ))}
          </ResourceGrid>
        )}
      </div>

      {createOpen ? (
        <Dialog
          className="resource-modal-card"
          title={t('canvas.library.createTitle')}
          pending={createMutation.isPending}
          onClose={closeCreateDialog}
        >
          <form className="modal-card-form" onSubmit={submitCreate}>
            <div className="modal-body">
              <label className="form-group">
                <FieldLabel required>{t('canvas.library.name')}</FieldLabel>
                <TextInput
                  value={createName}
                  maxLength={120}
                  autoFocus
                  required
                  disabled={createMutation.isPending}
                  placeholder={t('canvas.library.namePlaceholder')}
                  onChange={(event) => setCreateName(event.target.value)}
                />
              </label>
              {createError ? (
                <p className="field-error" role="alert">
                  {createError}
                </p>
              ) : null}
            </div>
            <div className="modal-footer">
              <Button
                variant="ghost"
                onClick={closeCreateDialog}
                disabled={createMutation.isPending}
              >
                {t('shared.cancel')}
              </Button>
              <Button type="submit" loading={createMutation.isPending}>
                {t('canvas.library.createConfirm')}
              </Button>
            </div>
          </form>
        </Dialog>
      ) : null}
    </section>
  )
}

function formatUpdatedAt(value: string, locale: AppLocale): string {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) {
    return value
  }
  return date.toLocaleString(locale, { dateStyle: 'medium', timeStyle: 'short' })
}
