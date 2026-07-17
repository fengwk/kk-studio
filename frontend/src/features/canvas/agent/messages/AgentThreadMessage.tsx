/** Pure presentation for an assistant/agent text thread message. */
export function AgentThreadMessage({ text }: { text: string }) {
  return <div className="thread-message">{text}</div>
}
