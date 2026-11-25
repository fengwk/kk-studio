import { useEffect } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { useApplicationEvents } from '@/shared/app-events'
import { workspaceKeys } from './queries'

export function useWorkspaceRealtime(spaceId: string) {
  const query = useQueryClient()
  const events = useApplicationEvents()
  useEffect(() => {
    let timer: ReturnType<typeof setTimeout> | undefined
    let tree = false, spaces = false
    function flush() {
      clearTimeout(timer)
      timer = undefined
      if (tree) void query.invalidateQueries({ queryKey: workspaceKeys.tree(spaceId), exact: true })
      if (spaces) void query.invalidateQueries({ queryKey: workspaceKeys.spaces(), exact: true })
      tree = spaces = false
    }
    function sync() { tree = spaces = true; flush() }
    const unsubscribe = events.subscribe({ kind: 'chat-space', id: spaceId }, {
      onSubscribed: sync, onResync: sync, onError: sync,
      onEvent: name => {
        if (name === 'space') spaces = true
        else if (name === 'tree' || name === 'threads') tree = true
        else return
        timer ??= setTimeout(flush, 500)
      },
    })
    return () => { unsubscribe(); clearTimeout(timer) }
  }, [events, query, spaceId])
}
