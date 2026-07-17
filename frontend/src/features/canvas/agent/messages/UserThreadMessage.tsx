/** Pure presentation for a user thread message. */
export function UserThreadMessage({ text }: { text: string }) {
  return <div className="thread-message user">{text}</div>
}
