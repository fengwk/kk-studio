const labels = { needs: '需要你', running: '运行中', waiting: '等待', error: '失败', idle: '空闲' } as const
export type ThreadStatus = keyof typeof labels

export function ThreadStatusIcon({ status }: { status?: ThreadStatus }) {
  if (!status || status === 'idle') return <span className="x-st" aria-hidden="true" />
  return <span className={`x-st x-st-${status}`} role="img" aria-label={labels[status]} title={labels[status]}>
    <svg className="x-ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.8}
      strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false">
      {status === 'needs' && <circle cx="12" cy="12" r="5" fill="currentColor" stroke="none" />}
      {status === 'running' && <><circle cx="12" cy="12" r="7" strokeOpacity="0.35" /><path d="M12 5a7 7 0 0 1 7 7" /></>}
      {status === 'waiting' && <path d="M7 4h10M7 20h10M8 4v3l4 5-4 5v3m8-16v3l-4 5 4 5v3" />}
      {status === 'error' && <><circle cx="12" cy="12" r="7.5" /><path d="m9.5 9.5 5 5M14.5 9.5l-5 5" /></>}
    </svg>
  </span>
}
