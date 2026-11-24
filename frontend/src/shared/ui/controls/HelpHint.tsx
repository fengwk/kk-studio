import { HelpCircle } from 'lucide-react'
import { createPortal } from 'react-dom'
import { useEffect, useId, useLayoutEffect, useRef, useState, type CSSProperties, type ReactNode } from 'react'
import './HelpHint.css'

export interface HelpHintProps {
  /** `?` 按钮的无障碍名称，例如“帮助：YOLO 执行方式”。 */
  label: string
  children: ReactNode
  className?: string
}

/** 聚焦或悬停时展示简短说明。 */
export function HelpHint({ label, children, className }: HelpHintProps) {
  const id = `help-hint-${useId().replace(/[^a-zA-Z0-9_-]/g, '')}`
  const [open, setOpen] = useState(false)
  const [position, setPosition] = useState<CSSProperties>({})
  const rootRef = useRef<HTMLSpanElement>(null)
  const triggerRef = useRef<HTMLButtonElement>(null)
  const panelRef = useRef<HTMLDivElement>(null)
  const pointerTypeRef = useRef<string>('')
  const closeTimerRef = useRef<number | null>(null)

  useEffect(() => {
    if (!open) {
      return
    }
    const handlePointerDown = (event: PointerEvent) => {
      const target = event.target
      if (
        target instanceof Node &&
        !rootRef.current?.contains(target) &&
        !panelRef.current?.contains(target)
      ) {
        if (closeTimerRef.current !== null) {
          window.clearTimeout(closeTimerRef.current)
          closeTimerRef.current = null
        }
        setOpen(false)
      }
    }
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        if (closeTimerRef.current !== null) {
          window.clearTimeout(closeTimerRef.current)
          closeTimerRef.current = null
        }
        event.preventDefault()
        setOpen(false)
      }
    }
    document.addEventListener('pointerdown', handlePointerDown)
    document.addEventListener('keydown', handleKeyDown)
    return () => {
      document.removeEventListener('pointerdown', handlePointerDown)
      document.removeEventListener('keydown', handleKeyDown)
      if (closeTimerRef.current !== null) {
        window.clearTimeout(closeTimerRef.current)
        closeTimerRef.current = null
      }
    }
  }, [open])

  useLayoutEffect(() => {
    if (!open || !triggerRef.current || !panelRef.current) {
      return
    }
    const updatePosition = () => {
      const anchor = triggerRef.current?.getBoundingClientRect()
      const panel = panelRef.current?.getBoundingClientRect()
      if (!anchor || !panel) {
        return
      }
      const margin = 8
      const width = Math.min(320, window.innerWidth - margin * 2)
      const left = Math.max(margin, Math.min(anchor.left, window.innerWidth - width - margin))
      const below = window.innerHeight - anchor.bottom - margin
      const top = below >= panel.height || below >= anchor.top ? anchor.bottom + margin : anchor.top - panel.height - margin
      setPosition({ left, top: Math.max(margin, Math.min(top, window.innerHeight - panel.height - margin)), width })
    }
    updatePosition()
    window.addEventListener('resize', updatePosition)
    window.addEventListener('scroll', updatePosition, true)
    return () => {
      window.removeEventListener('resize', updatePosition)
      window.removeEventListener('scroll', updatePosition, true)
    }
  }, [open])

  const cancelPointerClose = () => {
    if (closeTimerRef.current !== null) {
      window.clearTimeout(closeTimerRef.current)
      closeTimerRef.current = null
    }
  }

  const closeOnPointerLeave = () => {
    if (!rootRef.current?.contains(document.activeElement) && !panelRef.current?.contains(document.activeElement)) {
      cancelPointerClose()
      closeTimerRef.current = window.setTimeout(() => {
        closeTimerRef.current = null
        setOpen(false)
      }, 120)
    }
  }

  return (
    <>
      <span
        ref={rootRef}
        className={['ui-help-hint', className].filter(Boolean).join(' ')}
        onMouseEnter={() => {
          cancelPointerClose()
          if (pointerTypeRef.current !== 'touch' && !window.matchMedia('(hover: none)').matches) {
            setOpen(true)
          }
        }}
        onMouseLeave={closeOnPointerLeave}
      >
        <button
          ref={triggerRef}
          type="button"
          className="ui-help-hint-trigger"
          aria-label={label}
          aria-expanded={open}
          aria-controls={id}
          aria-describedby={open ? id : undefined}
          onPointerDown={(event) => {
            pointerTypeRef.current = event.pointerType
          }}
          onPointerCancel={() => {
            pointerTypeRef.current = ''
          }}
          onFocus={() => {
            if (pointerTypeRef.current !== 'touch') {
              setOpen(true)
            }
          }}
          onBlur={(event) => {
            if (!rootRef.current?.contains(event.relatedTarget) && !panelRef.current?.contains(event.relatedTarget)) {
              setOpen(false)
            }
          }}
          onClick={(event) => {
            if (pointerTypeRef.current === 'touch') {
              setOpen((value) => !value)
            } else {
              setOpen(true)
            }
            pointerTypeRef.current = ''
            if (event.detail === 0) {
              setOpen(true)
            }
          }}
        >
          <HelpCircle size={14} aria-hidden="true" />
        </button>
      </span>
      {open
        ? createPortal(
            <div
              ref={panelRef}
              id={id}
              className="ui-help-hint-panel"
              role="tooltip"
              style={position}
              onMouseEnter={() => {
                cancelPointerClose()
                setOpen(true)
              }}
              onMouseLeave={closeOnPointerLeave}
            >
              {children}
            </div>,
            document.body,
          )
        : null}
    </>
  )
}
