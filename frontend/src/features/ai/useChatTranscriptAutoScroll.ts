import { useEffect, type RefObject } from 'react'

export function useChatTranscriptAutoScroll(
  bodyRef: RefObject<HTMLDivElement | null>,
  messageCount: number,
  eventCount?: number,
) {
  useEffect(() => {
    const chatBody = bodyRef.current
    if (chatBody) {
      chatBody.scrollTop = chatBody.scrollHeight
    }
  }, [bodyRef, eventCount, messageCount])
}
