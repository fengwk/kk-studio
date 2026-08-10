import type { CanvasThreadMessage } from '@/features/canvas/types'

export function CanvasAgentMessage({ message }: { message: CanvasThreadMessage }) {
  return (
    <div className={`thread-message ${message.kind === 'user' ? 'user' : ''}`}>
      {message.text}
    </div>
  )
}
