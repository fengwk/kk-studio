import './UserAvatar.css'

export function UserAvatar({ username }: { username: string }) {
  return <span className="user-avatar" title={username} aria-hidden="true">{Array.from(username)[0]?.toUpperCase()}</span>
}
