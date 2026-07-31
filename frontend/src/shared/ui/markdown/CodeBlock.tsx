import { Check, Copy } from 'lucide-react'
import { memo, useCallback, useState, type ReactNode } from 'react'
import { useI18n } from '@/shared/i18n'

async function copyText(text: string): Promise<boolean> {
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text)
      return true
    }
  } catch {
    // fall through
  }
  try {
    const area = document.createElement('textarea')
    area.value = text
    area.setAttribute('readonly', '')
    area.style.position = 'fixed'
    area.style.left = '-9999px'
    document.body.appendChild(area)
    area.select()
    const ok = document.execCommand('copy')
    document.body.removeChild(area)
    return ok
  } catch {
    return false
  }
}

export type CopyButtonProps = {
  source: string
  className?: string
  label?: string
}

/** 纯复制按钮（可放在不同层级外壳上） */
export const CopyButton = memo(function CopyButton({
  source,
  className,
  label,
}: CopyButtonProps) {
  const { t } = useI18n()
  const [copied, setCopied] = useState(false)
  const effectiveLabel = label ?? t('shared.copy')
  const copiedLabel = t('shared.copied')

  const onCopy = useCallback(async () => {
    const ok = await copyText(source)
    if (!ok) {
      return
    }
    setCopied(true)
    window.setTimeout(() => setCopied(false), 1500)
  }, [source])

  return (
    <button
      type="button"
      className={['md-code-copy', className].filter(Boolean).join(' ')}
      aria-label={copied ? copiedLabel : effectiveLabel}
      title={copied ? copiedLabel : effectiveLabel}
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
