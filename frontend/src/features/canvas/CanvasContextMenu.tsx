import {
  Download,
  ExternalLink,
  Group as GroupIcon,
  Pencil,
  Play,
  Square,
  Trash2,
  Type,
  Ungroup,
} from 'lucide-react'
import {
  useEffect,
  useLayoutEffect,
  useRef,
  useState,
  type KeyboardEvent,
  type ReactNode,
} from 'react'
import { useCanvasRuntime } from '@/features/canvas/CanvasRuntimeContext'
import type {
  CanvasContextMenuState,
  ContextMenuTarget,
} from '@/features/canvas/canvas-stage-model'
import type { Resource } from '@/features/canvas/domain'
import { useCanvasResourceActions } from '@/features/canvas/useCanvasResourceActions'
import { useI18n } from '@/shared/i18n'

export type { CanvasContextMenuState, ContextMenuTarget } from '@/features/canvas/canvas-stage-model'

const MENU_MARGIN = 8

/** CanvasStage 级右键菜单：动作直接调用 controller，销毁/改名走菜单内确认/编辑模式。 */
export function CanvasContextMenu({
  state,
  onClose,
}: {
  state: CanvasContextMenuState
  onClose: () => void
}) {
  const runtime = useCanvasRuntime()
  const { t } = useI18n()
  const menuRef = useRef<HTMLDivElement>(null)
  const [mode, setMode] = useState<'menu' | 'rename' | 'confirm'>('menu')
  const [position, setPosition] = useState({ x: state.x, y: state.y })
  const initialRenameValue = state.target.kind === 'resource'
    ? state.target.node.name
    : state.target.kind === 'group'
      ? state.target.group.title
      : ''
  const [renameValue, setRenameValue] = useState(initialRenameValue)

  useLayoutEffect(() => {
    const menu = menuRef.current
    if (!menu) {
      return
    }
    const rect = menu.getBoundingClientRect()
    setPosition({
      x: Math.min(Math.max(MENU_MARGIN, state.x), window.innerWidth - rect.width - MENU_MARGIN),
      y: Math.min(Math.max(MENU_MARGIN, state.y), window.innerHeight - rect.height - MENU_MARGIN),
    })
  }, [state.x, state.y])

  useEffect(() => {
    if (mode === 'rename') {
      menuRef.current?.querySelector<HTMLInputElement>('.context-menu-rename input')?.focus()
    } else if (mode === 'confirm') {
      menuRef.current?.querySelector<HTMLButtonElement>('.context-menu-confirm-cancel')?.focus()
    } else {
      menuRef.current?.querySelector<HTMLButtonElement>('[role="menuitem"]')?.focus()
    }
  }, [mode, state.target])

  // 菜单外任意 pointerdown（含画布/视口）关闭菜单。
  useEffect(() => {
    const handlePointerDown = (event: PointerEvent) => {
      const target = event.target as Node | null
      if (target && menuRef.current?.contains(target)) {
        return
      }
      onClose()
    }
    window.addEventListener('pointerdown', handlePointerDown)
    return () => window.removeEventListener('pointerdown', handlePointerDown)
  }, [onClose])

  function handleMenuKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    if (mode !== 'menu') {
      if (event.key === 'Escape') {
        event.preventDefault()
        event.stopPropagation()
        setMode('menu')
      }
      return
    }
    const items = [...menuRef.current?.querySelectorAll<HTMLButtonElement>('[role="menuitem"]') ?? []]
    if (items.length === 0) {
      return
    }
    const currentIndex = items.indexOf(document.activeElement as HTMLButtonElement)
    if (event.key === 'ArrowDown') {
      event.preventDefault()
      items[(currentIndex + 1) % items.length]?.focus()
    } else if (event.key === 'ArrowUp') {
      event.preventDefault()
      items[(currentIndex - 1 + items.length) % items.length]?.focus()
    } else if (event.key === 'Home') {
      event.preventDefault()
      items[0]?.focus()
    } else if (event.key === 'End') {
      event.preventDefault()
      items[items.length - 1]?.focus()
    } else if (event.key === 'Escape') {
      event.preventDefault()
      onClose()
    }
  }

  const saveRename = () => {
    const value = renameValue.trim()
    if (!value) {
      return
    }
    if (state.target.kind === 'resource') {
      runtime.renameNode(state.target.node.id, value)
    } else if (state.target.kind === 'group') {
      runtime.renameGroup(state.target.group.id, value)
    }
    onClose()
  }

  const confirmDelete = () => {
    if (state.target.kind === 'resource') {
      runtime.deleteNode(state.target.node.id)
    } else if (state.target.kind === 'group') {
      runtime.deleteGroup(state.target.group.id)
    }
    onClose()
  }

  const { target } = state
  const confirmTitle = target.kind === 'resource' || target.kind === 'group'
    ? t(
      target.kind === 'group'
        ? 'canvas.menu.groupDeleteConfirmTitle'
        : 'canvas.node.deleteConfirmTitle',
      target.kind === 'group'
        ? { title: target.group.title }
        : { name: target.node.name },
    )
    : ''
  const confirmDescription = target.kind === 'group'
    ? t('canvas.menu.groupDeleteConfirmDescription')
    : t('canvas.node.deleteConfirmDescription')
  return (
    <div
      ref={menuRef}
      className="canvas-context-menu"
      role="menu"
      aria-label={t('canvas.menu.ariaLabel')}
      style={{ left: position.x, top: position.y }}
      onKeyDown={handleMenuKeyDown}
      onPointerDown={(event) => event.stopPropagation()}
    >
      {mode === 'rename' ? (
        <div className="context-menu-rename" role="presentation">
          <label>
            <span>{t('canvas.menu.renameInputAria')}</span>
            <input
              className="nodrag nowheel"
              value={renameValue}
              onChange={(event) => setRenameValue(event.target.value)}
              onKeyDown={(event) => {
                if (event.key === 'Enter') {
                  event.preventDefault()
                  saveRename()
                }
              }}
            />
          </label>
          <div>
            <button
              type="button"
              className="context-menu-cancel"
              onClick={() => setMode('menu')}
            >
              {t('canvas.menu.cancel')}
            </button>
            <button
              type="button"
              className="context-menu-save"
              disabled={!renameValue.trim()}
              onClick={saveRename}
            >
              {t('canvas.menu.save')}
            </button>
          </div>
        </div>
      ) : mode === 'confirm' ? (
        <div className="context-menu-confirm" role="alertdialog" aria-label={confirmTitle}>
          <strong>{confirmTitle}</strong>
          <p>{confirmDescription}</p>
          <div>
            <button
              type="button"
              className="context-menu-confirm-cancel"
              onClick={() => setMode('menu')}
            >
              {t('canvas.node.deleteCancel')}
            </button>
            <button type="button" className="danger" onClick={confirmDelete}>
              {t('canvas.node.deleteConfirm')}
            </button>
          </div>
        </div>
      ) : target.kind === 'resource' ? (
        <ResourceMenuItems target={target} onClose={onClose} onRename={() => setMode('rename')} onDelete={() => setMode('confirm')} />
      ) : target.kind === 'group' ? (
        <>
          <MenuButton icon={<Pencil aria-hidden="true" />} label={t('canvas.menu.rename')} onClick={() => setMode('rename')} />
          <MenuButton icon={<Ungroup aria-hidden="true" />} label={t('canvas.menu.ungroup')} onClick={() => runtime.ungroupGroup(target.group.id)} onClose={onClose} />
          <MenuButton icon={<Trash2 aria-hidden="true" />} label={t('canvas.menu.delete')} danger onClick={() => setMode('confirm')} />
        </>
      ) : target.hasUngroupedResource ? (
        <MenuButton icon={<GroupIcon aria-hidden="true" />} label={t('canvas.menu.group')} onClick={() => runtime.createGroup()} onClose={onClose} />
      ) : null}
    </div>
  )
}

function ResourceMenuItems({
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

function MenuButton({
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
