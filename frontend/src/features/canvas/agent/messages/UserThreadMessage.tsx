/** 用户 thread 消息的纯展示组件。 */
export function UserThreadMessage({ text }: { text: string }) {
  return <div className="thread-message user">{text}</div>
}
