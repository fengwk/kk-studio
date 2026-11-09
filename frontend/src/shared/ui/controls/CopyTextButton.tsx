import { useCallback, useEffect, useRef, useState } from 'react'
import { Check, CircleAlert, Copy } from 'lucide-react'
import { copyTextToClipboard } from '@/shared/lib/clipboard'
import { Button } from './Button'
import type { CopyTextButtonProps } from './copy-text-button.types'
import './copy-text-button.css'

type CopyFeedback = 'copied' | 'failed'

/** 原地反馈的停留时长：成功 1.5 秒，失败 3 秒；两者都只替换图标。 */
const FEEDBACK_DURATION_MS: Record<CopyFeedback, number> = {
  copied: 1500,
  failed: 3000,
}

/**
 * 固定尺寸的复制按钮：反馈只把图标换成 ✓ / !，不插入可见文字，按钮与所在工具栏布局不变。
 * 无障碍名称保持动作语义（label）；瞬时结果通过不占位的 sr-only role=status 播报，
 * 悬停提示（title）跟随当前状态说明图标含义。
 */
export function CopyTextButton({
  source,
  label,
  copiedLabel,
  failedLabel,
  className,
  variant = 'ghost',
  size = 'compact',
  ...rest
}: CopyTextButtonProps) {
  const [feedback, setFeedback] = useState<CopyFeedback | null>(null)
  const timerRef = useRef<number | null>(null)
  const requestRef = useRef(0)

  const clearTimer = useCallback(() => {
    if (timerRef.current !== null) {
      window.clearTimeout(timerRef.current)
      timerRef.current = null
    }
  }, [])

  // 复制目标变化或卸载后，旧计时器与在途结果都不再属于当前按钮。
  useEffect(() => {
    requestRef.current += 1
    clearTimer()
    setFeedback(null)
    return () => {
      requestRef.current += 1
      clearTimer()
    }
  }, [source, clearTimer])

  const handleClick = useCallback(() => {
    const request = requestRef.current + 1
    requestRef.current = request
    clearTimer()
    setFeedback(null)
    void copyTextToClipboard(source, {
      // 目标已切换、组件已卸载或有更新的点击时，旧请求不得再走 DOM 兜底插入 textarea 抢焦点。
      shouldFallback: () => request === requestRef.current,
    }).then((copied) => {
      // 旧目标的异步结果不得覆盖当前目标的反馈。
      if (request !== requestRef.current) {
        return
      }
      const outcome: CopyFeedback = copied ? 'copied' : 'failed'
      setFeedback(outcome)
      timerRef.current = window.setTimeout(() => {
        timerRef.current = null
        setFeedback(null)
      }, FEEDBACK_DURATION_MS[outcome])
    })
  }, [clearTimer, source])

  const statusMessage = feedback === 'copied'
    ? copiedLabel
    : feedback === 'failed'
      ? failedLabel
      : ''

  return (
    <>
      <Button
        {...rest}
        variant={variant}
        size={size}
        className={[
          'copy-text-button',
          feedback === 'failed' ? 'is-copy-failed' : '',
          className,
        ].filter(Boolean).join(' ')}
        aria-label={label}
        title={statusMessage === '' ? label : statusMessage}
        onClick={handleClick}
      >
        <span className="copy-text-button-icon" aria-hidden="true">
          {feedback === 'copied' ? <Check /> : feedback === 'failed' ? <CircleAlert /> : <Copy />}
        </span>
      </Button>
      <span className="sr-only" role="status">{statusMessage}</span>
    </>
  )
}
