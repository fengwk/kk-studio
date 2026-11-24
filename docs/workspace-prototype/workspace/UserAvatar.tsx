export function UserAvatar({ username, onClick }: { username: string; onClick?: (anchor: HTMLButtonElement) => void }) {
  const initial = <span className="avatar x-user-avatar" aria-hidden="true">{username[0].toUpperCase()}</span>
  return onClick ? <button type="button" className="x-account-trigger" aria-label={`账户：${username}`} title={username}
    aria-haspopup="menu" aria-expanded="false" aria-controls="x-menu" onClick={(event) => onClick(event.currentTarget)}>
    {initial}
  </button> : initial
}
