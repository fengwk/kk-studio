import { Check, Copy } from 'lucide-react'
import {
  memo,
  useCallback,
  useEffect,
  useRef,
  useState,
  type ReactNode,
} from 'react'
import { useI18n } from '@/shared/i18n'

async function copyText(text: string, isActive: () => boolean): Promise<boolean> {
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text)
      return true
    }
  } catch {
    // 失败则继续走兜底逻辑（fall through）
  }
  // 复制过程中卸载后不再插入 DOM 或选中文本，避免从新视图抢走焦点。
  if (!isActive()) {
    return false
  }
  let area: HTMLTextAreaElement | null = null
  try {
    area = document.createElement('textarea')
    area.value = text
    area.setAttribute('readonly', '')
    area.style.position = 'fixed'
    area.style.left = '-9999px'
    document.body.appendChild(area)
    area.select()
    return document.execCommand('copy')
  } catch {
    return false
  } finally {
    area?.remove()
  }
}

export type CopyButtonProps = {
  source: string
  className?: string
  label?: string
  disabled?: boolean
  /** 两种浏览器复制路径均失败时通知调用方展示可读反馈。 */
  onError?: () => void
}

/** 纯复制按钮（可放在不同层级外壳上） */
export const CopyButton = memo(function CopyButton({
  source,
  className,
  label,
  disabled = false,
  onError,
}: CopyButtonProps) {
  const { t } = useI18n()
  const [copied, setCopied] = useState(false)
  const [copying, setCopying] = useState(false)
  const mountedRef = useRef(true)
  const copyingRef = useRef(false)
  const resetTimerRef = useRef<number | null>(null)
  const effectiveLabel = label ?? t('shared.copy')
  const copiedLabel = t('shared.copied')

  useEffect(() => {
    mountedRef.current = true
    return () => {
      mountedRef.current = false
      if (resetTimerRef.current !== null) {
        window.clearTimeout(resetTimerRef.current)
      }
    }
  }, [])

  const onCopy = useCallback(async () => {
    if (disabled || copyingRef.current) {
      return
    }
    copyingRef.current = true
    setCopying(true)
    setCopied(false)
    if (resetTimerRef.current !== null) {
      window.clearTimeout(resetTimerRef.current)
      resetTimerRef.current = null
    }
    const ok = await copyText(source, () => mountedRef.current)
    copyingRef.current = false
    if (!mountedRef.current) {
      return
    }
    setCopying(false)
    if (!ok) {
      onError?.()
      return
    }
    setCopied(true)
    resetTimerRef.current = window.setTimeout(() => {
      resetTimerRef.current = null
      setCopied(false)
    }, 1500)
  }, [source, disabled, onError])

  return (
    <button
      type="button"
      className={['md-code-copy', className].filter(Boolean).join(' ')}
      aria-label={copied ? copiedLabel : effectiveLabel}
      title={copied ? copiedLabel : effectiveLabel}
      disabled={disabled || copying}
      onClick={(event) => {
        event.stopPropagation()
        void onCopy()
      }}
    >
      {copied ? <Check aria-hidden="true" /> : <Copy aria-hidden="true" />}
    </button>
  )
})

/** 悬停右上角复制；用于代码块、mermaid 等任意源码容器 */
export const CopyableShell = memo(function CopyableShell({
  source,
  className,
  children,
  copyLabel,
}: {
  source: string
  className?: string
  children: ReactNode
  copyLabel?: string
}) {
  const { t } = useI18n()

  return (
    <div className={['md-code-shell', className].filter(Boolean).join(' ')}>
      <CopyButton source={source} label={copyLabel ?? t('shared.copyCode')} />
      {children}
    </div>
  )
})

/**
 * fenced 代码块：高亮内容 + 悬停复制源码
 */
export const CodeBlock = memo(function CodeBlock({
  source,
  className,
  children,
}: {
  source: string
  className?: string
  children: ReactNode
}) {
  return (
    <CopyableShell source={source}>
      <pre className="md-code-block">
        <code className={className}>{children}</code>
      </pre>
    </CopyableShell>
  )
})
