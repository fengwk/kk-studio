import { useCallback, useEffect, useMemo, useRef } from 'react'
import {
  createTextPart,
  partsKey,
  type ComposerPart,
} from '@/features/ai/composer/composer-parts'

interface ComposerMessageHistoryOptions {
  parts: ComposerPart[]
  historicalUserMessages: readonly string[]
  queuedUserMessages: readonly string[]
  onPartsChange: (parts: ComposerPart[]) => void
  onHistoryPartsChange: (parts: ComposerPart[]) => void
}

/**
 * Shell 风格的消息历史游标：
 * durable user messages -> queued user messages -> 当前未提交草稿。
 *
 * 历史浏览不会覆盖草稿槽位；只有真实编辑才会把当前内容提升为新的草稿。
 */
export function useComposerMessageHistory({
  parts,
  historicalUserMessages,
  queuedUserMessages,
  onPartsChange,
  onHistoryPartsChange,
}: ComposerMessageHistoryOptions) {
  const messages = useMemo(
    () => [...historicalUserMessages, ...queuedUserMessages]
      .filter((message) => message.length > 0),
    [historicalUserMessages, queuedUserMessages],
  )
  const messagesRef = useRef(messages)
  const cursorRef = useRef(messages.length)
  const scratchRef = useRef(parts)
  const partsRef = useRef(parts)
  const onPartsChangeRef = useRef(onPartsChange)
  const onHistoryPartsChangeRef = useRef(onHistoryPartsChange)
  const observedPartsKeyRef = useRef(partsKey(parts))
  const pendingHistoryPartsKeyRef = useRef<string | null>(null)

  useEffect(() => {
    onPartsChangeRef.current = onPartsChange
  }, [onPartsChange])

  useEffect(() => {
    onHistoryPartsChangeRef.current = onHistoryPartsChange
  }, [onHistoryPartsChange])

  useEffect(() => {
    const previousEnd = messagesRef.current.length
    const wasAtScratch = cursorRef.current === previousEnd
    messagesRef.current = messages
    cursorRef.current = wasAtScratch
      ? messages.length
      : Math.min(cursorRef.current, messages.length)
  }, [messages])

  useEffect(() => {
    partsRef.current = parts
    const currentKey = partsKey(parts)
    if (currentKey === observedPartsKeyRef.current) {
      return
    }
    observedPartsKeyRef.current = currentKey
    if (currentKey === pendingHistoryPartsKeyRef.current) {
      pendingHistoryPartsKeyRef.current = null
      return
    }
    pendingHistoryPartsKeyRef.current = null
    scratchRef.current = parts
    cursorRef.current = messagesRef.current.length
  }, [parts])

  const changeDraft = useCallback((next: ComposerPart[]) => {
    partsRef.current = next
    scratchRef.current = next
    cursorRef.current = messagesRef.current.length
    pendingHistoryPartsKeyRef.current = null
    observedPartsKeyRef.current = partsKey(next)
    onPartsChangeRef.current(next)
  }, [])

  const navigate = useCallback((direction: 'previous' | 'next'): boolean => {
    const currentMessages = messagesRef.current
    const scratchIndex = currentMessages.length
    const currentIndex = Math.max(0, Math.min(cursorRef.current, scratchIndex))
    if (
      currentIndex === scratchIndex
      && scratchRef.current.some((part) => part.type !== 'text')
    ) {
      return false
    }
    const nextIndex = direction === 'previous'
      ? currentIndex - 1
      : currentIndex + 1
    if (nextIndex < 0 || nextIndex > scratchIndex) {
      return false
    }

    const next = nextIndex === scratchIndex
      ? scratchRef.current
      : [createTextPart(currentMessages[nextIndex] ?? '')]
    cursorRef.current = nextIndex
    const nextKey = partsKey(next)
    if (nextKey !== partsKey(partsRef.current)) {
      partsRef.current = next
      pendingHistoryPartsKeyRef.current = nextKey
      onHistoryPartsChangeRef.current(next)
    }
    return true
  }, [])

  return { changeDraft, navigate }
}
