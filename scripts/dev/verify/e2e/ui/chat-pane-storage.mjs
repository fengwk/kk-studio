// 与 frontend/src/features/chat/chat-pane-state.ts 的键和 ChatTabSnapshot 格式保持一致。
const CHAT_TAB_SESSION_PREFIX = 'kk-studio.tab-state:chat:'
const CHAT_TAB_LAST_PREFIX = 'kk-studio.last-state:chat:'

export async function seedSplitChatPanes(page, chatId, firstThreadId, secondThreadId) {
  await page.evaluate(({ sessionKey, lastKey, first, second }) => {
    const snapshot = JSON.stringify({
      presentation: {
        layout: 'split-2',
        focusedPaneId: 'pane-1',
        panes: Array.from({ length: 9 }, (_, index) => ({
          id: `pane-${index + 1}`,
          target: index < 2
            ? { kind: 'BOUND_THREAD', threadId: index === 0 ? first : second }
            : { kind: 'NEW_SESSION_DRAFT' },
        })),
      },
    })
    sessionStorage.setItem(sessionKey, snapshot)
    localStorage.setItem(lastKey, snapshot)
  }, {
    sessionKey: `${CHAT_TAB_SESSION_PREFIX}${chatId}`,
    lastKey: `${CHAT_TAB_LAST_PREFIX}${chatId}`,
    first: firstThreadId,
    second: secondThreadId,
  })
}

export async function clearChatPaneState(page, chatId) {
  await page.evaluate(({ sessionKey, lastKey }) => {
    sessionStorage.removeItem(sessionKey)
    localStorage.removeItem(lastKey)
  }, {
    sessionKey: `${CHAT_TAB_SESSION_PREFIX}${chatId}`,
    lastKey: `${CHAT_TAB_LAST_PREFIX}${chatId}`,
  })
}
