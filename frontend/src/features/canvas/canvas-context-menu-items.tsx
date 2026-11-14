import {
  Download,
  ExternalLink,
  Group as GroupIcon,
  Play,
  Square,
  Trash2,
  Type,
  Pencil,
} from 'lucide-react'
import { useEffect, type ReactNode } from 'react'
import { useCanvasRuntime } from '@/features/canvas/CanvasRuntimeContext'
import type { ContextMenuTarget } from '@/features/canvas/canvas-stage-model'
import type { Resource } from '@/features/canvas/domain'
import { useCanvasResourceActions } from '@/features/canvas/useCanvasResourceActions'
import { useI18n } from '@/shared/i18n'

export function ResourceMenuItems({
  target,
  onClose,
  onRename,
  onDelete,
}: {
  target: Extract<ContextMenuTarget, { kind: 'resource' }>
  onClose: () => void
  onRename: () => void
  onDelete: () => void
}) {
  const runtime = useCanvasRuntime()
  const { t } = useI18n()
  const { node } = target
  const run = node.run
  const active = run?.status === 'READY' || run?.status === 'RUNNING'
  const primaryResource = node.resources[0]
  const binaryResource = primaryResource
    && primaryResource.kind !== 'TEXT'
    ? primaryResource
    : null

  return (
    <>
      <MenuButton icon={<Pencil aria-hidden="true" />} label={t('canvas.menu.rename')} onClick={onRename} />
      {!node.function && primaryResource?.kind === 'TEXT' ? (
        <MenuButton
          icon={<Type aria-hidden="true" />}
          label={t('canvas.menu.editText')}
          onClick={() => {
            runtime.editTextNode(node)
            onClose()
          }}
        />
      ) : null}
      {node.function && !active ? (
        <MenuButton
          icon={<Play aria-hidden="true" />}
          label={t('canvas.menu.run')}
          onClick={() => {
            void runtime.startFunctionRun(node.id)
            onClose()
          }}
        />
      ) : null}
      {node.function && active ? (
        <MenuButton
          icon={<Square aria-hidden="true" />}
          label={t('canvas.menu.cancelRun')}
          onClick={() => {
            if (run?.requestId) {
              void runtime.cancelFunctionRun(node.id, run.requestId)
            }
            onClose()
          }}
        />
      ) : null}
      {!node.groupId ? (
        <MenuButton
          icon={<GroupIcon aria-hidden="true" />}
          label={t('canvas.menu.group')}
          onClick={() => runtime.createGroup()}
          onClose={onClose}
        />
      ) : null}
      {binaryResource ? (
        <ResourceActionItems resource={binaryResource} onClose={onClose} />
      ) : null}
      <MenuButton icon={<Trash2 aria-hidden="true" />} label={t('canvas.menu.delete')} danger onClick={onDelete} />
    </>
  )
}

/** 原件 open/download：菜单项内持有签名 hook，签名完成后自动触发并关闭菜单。 */
function ResourceActionItems({ resource, onClose }: { resource: Resource; onClose: () => void }) {
  const { t } = useI18n()
  const {
    loading,
    error,
    ensureSigned,
    fulfilled,
  } = useCanvasResourceActions(resource)

  useEffect(() => {
    if (fulfilled) {
      onClose()
    }
  }, [fulfilled, onClose])

  const requestAction = (intent: 'open' | 'download') => {
    ensureSigned(intent)
  }

  return (
    <>
      <MenuButton
        icon={loading ? <span className="context-menu-spinner" aria-hidden="true" /> : <ExternalLink aria-hidden="true" />}
        label={t('canvas.menu.openOriginal')}
        disabled={loading}
        onClick={() => requestAction('open')}
      />
      <MenuButton
        icon={<Download aria-hidden="true" />}
        label={t('canvas.menu.download')}
        disabled={loading}
        onClick={() => requestAction('download')}
      />
      {error ? <span className="context-menu-error" role="alert">{t('canvas.media.signFailed')}</span> : null}
    </>
  )
}

export function MenuButton({
  icon,
  label,
  onClick,
  disabled = false,
  danger = false,
  onClose,
}: {
  icon: ReactNode
  label: string
  onClick: () => void
  disabled?: boolean
  danger?: boolean
  onClose?: () => void
}) {
  return (
    <button
      type="button"
      role="menuitem"
      className={danger ? 'danger' : undefined}
      disabled={disabled}
      onClick={() => {
        onClick()
        onClose?.()
      }}
    >
      {icon}
      <span>{label}</span>
    </button>
  )
}
