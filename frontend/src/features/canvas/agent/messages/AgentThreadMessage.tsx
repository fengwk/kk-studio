/** 助手/agent 文本 thread 消息的纯展示组件。 */
export function AgentThreadMessage({ text }: { text: string }) {
  return <div className="thread-message">{text}</div>
}
