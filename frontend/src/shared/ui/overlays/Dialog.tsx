import { X } from 'lucide-react'
import { useEffect, useId, useRef, useState, type ReactNode } from 'react'
import { createPortal } from 'react-dom'
import { IconButton } from '@/shared/ui/controls/IconButton'
import { useI18n } from '@/shared/i18n'
import './overlays.css'

const FOCUSABLE_SELECTOR =
  'button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])'

/** 过滤 hidden/inert 造成的“名义可聚焦”元素，避免 Tab 落入不可见控件。 */
function focusableElements(card: HTMLElement | null): HTMLElement[] {
  if (!card) {
    return []
  }
  return Array.from(card.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR)).filter(
    (element) => !element.hasAttribute('hidden') && element.closest('[inert]') === null,
  )
}

/**
 * 弹窗栈。只有栈顶弹窗处理 Escape 与 Tab 循环，嵌套弹窗（含子流程弹窗）语义唯一，
 * 不会出现一次 Escape 同时关闭内外两层的问题。
 */
const dialogStack: symbol[] = []

export interface DialogProps {
  /** 卡片上的额外类名；用于沿用各 feature 既有的宽度/布局修饰类。 */
  className?: string
  /** 无障碍名称。与 ariaLabelledBy 二选一。 */
  ariaLabel?: string
  ariaLabelledBy?: string
  role?: 'dialog' | 'alertdialog'
  /** 标准头部标题；与 header 二选一。 */
  title?: ReactNode
  /** 标准头部标题前置图标。 */
  headerIcon?: ReactNode
  /** 完全自定义头部（自带操作与关闭按钮），替代 title/headerIcon。 */
  header?: ReactNode
  /** 关闭按钮的无障碍名称。 */
  closeLabel?: string
  /** 进行中的操作：禁止 Escape、遮罩点击与关闭按钮关闭。 */
  pending?: boolean
  onClose: () => void
  /** 初始聚焦元素选择器；默认取卡片内第一个可聚焦元素。 */
  initialFocusSelector?: string
  children: ReactNode
}

/**
 * 共享弹窗外壳：portal 到 body，避免被滚动容器裁剪；统一提供
 * 焦点陷阱、初始焦点与关闭后焦点归还、栈顶 Escape（含 IME 与 defaultPrevented 守卫）、
 * 遮罩外部点击关闭，以及 pending 时不可关闭。
 *
 * 弹窗内展开的 Select/菜单拥有更高的 Escape 优先权（先收起弹层，再由再次 Escape 关闭弹窗）。
 */
export function Dialog({
  className,
  ariaLabel,
  ariaLabelledBy,
  role = 'dialog',
  title,
  headerIcon,
  header,
  closeLabel,
  pending = false,
  onClose,
  initialFocusSelector,
  children,
}: DialogProps) {
  const { t } = useI18n()
  const titleId = `dialog-title-${useId().replace(/[^a-zA-Z0-9_-]/g, '')}`
  const cardRef = useRef<HTMLDivElement>(null)
  const idRef = useRef<symbol>(Symbol('dialog'))
  const previousFocusRef = useRef<HTMLElement | null>(null)
  /**
   * 打开前的焦点元素必须在首次 render 时取样：React 的 autoFocus 在 DOM commit 期间
   * 就移动了焦点，等到 effect 里再读 document.activeElement 只能拿到弹窗内部元素，
   * 从而丢失 opener、导致关闭后焦点无处归还。
   */
  const [opener] = useState<HTMLElement | null>(() =>
    document.activeElement instanceof HTMLElement ? document.activeElement : null,
  )
  const onCloseRef = useRef(onClose)
  const pendingRef = useRef(pending)
  // 事件监听只注册一次，因此把最新回调写入 ref，避免重挂监听。
  useEffect(() => {
    onCloseRef.current = onClose
    pendingRef.current = pending
  })

  useEffect(() => {
    const id = idRef.current
    dialogStack.push(id)
    const card = cardRef.current
    previousFocusRef.current = opener
    const active = document.activeElement instanceof HTMLElement ? document.activeElement : null
    // 组件自身的 autoFocus 先于本 effect 生效；此时焦点已在卡片内，视为已就绪，不再抢焦点。
    const alreadyFocused = active !== null && card?.contains(active) === true
    // data-autofocus 供破坏性弹窗把初始焦点放在安全动作上（例如“取消”）。
    const preferred = card?.querySelector<HTMLElement>(
      initialFocusSelector ?? '[data-autofocus]',
    ) ?? null
    const target = alreadyFocused
      ? null
      : preferred ?? focusableElements(card)[0] ?? card ?? null
    target?.focus({ preventScroll: true })
    return () => {
      const index = dialogStack.indexOf(id)
      if (index >= 0) {
        dialogStack.splice(index, 1)
      }
      const previous = previousFocusRef.current
      if (previous?.isConnected) {
        previous.focus({ preventScroll: true })
      }
    }
  }, [initialFocusSelector, opener])

  useEffect(() => {
    function handleKeyDown(event: KeyboardEvent) {
      const card = cardRef.current
      if (dialogStack[dialogStack.length - 1] !== idRef.current) {
        return
      }
      if (event.key === 'Tab') {
        // 传入的 Select/菜单弹出层在 body portal 中；其自身负责把焦点收回触发器，
        // 此处不得抢先处理 Tab，否则会跳出弹窗。
        const activeElement = document.activeElement
        if (
          activeElement instanceof Element
          && activeElement.closest('[role="listbox"], [role="menu"]') !== null
        ) {
          return
        }
        const elements = focusableElements(card)
        const first = elements[0]
        const last = elements[elements.length - 1]
        if (!first || !last) {
          event.preventDefault()
          card?.focus()
        } else if (
          event.shiftKey
          && (document.activeElement === first || document.activeElement === card)
        ) {
          event.preventDefault()
          last.focus()
        } else if (!event.shiftKey && document.activeElement === last) {
          event.preventDefault()
          first.focus()
        }
        return
      }
      if (
        event.key !== 'Escape'
        || event.defaultPrevented
        || event.isComposing
        || event.keyCode === 229
      ) {
        return
      }
      // 弹窗内的下拉/菜单先消费 Escape（Select 在 document 冒泡阶段关闭弹层并 preventDefault），
      // 冒泡监听 + 尊重 defaultPrevented 保证“第一次 Escape 收弹层、第二次才关弹窗”。
      const target = event.target
      if (target instanceof Element && target.closest('[role="listbox"], [role="menu"]')) {
        return
      }
      event.preventDefault()
      if (!pendingRef.current) {
        onCloseRef.current()
      }
    }
    window.addEventListener('keydown', handleKeyDown)
    return () => window.removeEventListener('keydown', handleKeyDown)
  }, [])

  return createPortal(
    <div className="modal-backdrop" role="presentation" onMouseDown={handleBackdropMouseDown}>
      <div
        ref={cardRef}
        className={['modal-card', className].filter(Boolean).join(' ')}
        role={role}
        aria-modal="true"
        aria-label={ariaLabel}
        aria-labelledby={ariaLabel ? undefined : (ariaLabelledBy ?? (title !== undefined ? titleId : undefined))}
        tabIndex={-1}
        onMouseDown={(event) => event.stopPropagation()}
      >
        {header ?? (
          title !== undefined ? (
            <div className="modal-header">
              <div className="modal-header-title">
                {headerIcon}
                <h2 id={titleId}>{title}</h2>
              </div>
              <IconButton
                label={closeLabel ?? t('shared.close')}
                onClick={onClose}
                disabled={pending}
              >
                <X aria-hidden="true" />
              </IconButton>
            </div>
          ) : null
        )}
        {children}
      </div>
    </div>,
    document.body,
  )

  function handleBackdropMouseDown(event: React.MouseEvent<HTMLDivElement>) {
    if (event.target !== event.currentTarget || pendingRef.current) {
      return
    }
    onCloseRef.current()
  }
}
