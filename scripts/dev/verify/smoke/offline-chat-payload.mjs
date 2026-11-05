/** Canonical command batch request builder for offline chat smoke testing. */

/**
 * Build a canonical NEW_SESSION command batch payload for Chat owner.
 *
 * Branch settings 是精确三字段快照 agentName + execution + environmentName；
 * environmentName 为 nullable canonical 名称，null 表示未选择 Environment。
 */
export function buildOfflineChatBatchRequest({
  chatId,
  sessionId,
  threadId,
  commandId,
  marker,
  agentName = 'default-assistant',
  modelName = 'acceptance-stub',
  variant = 'default',
  ...unexpected
}) {
  // Unknown options must fail loudly instead of being ignored.
  if (Object.keys(unexpected).length > 0) {
    throw new TypeError(`unexpected option: ${Object.keys(unexpected).join(', ')}`)
  }
  return {
    owner: { type: 'CHAT', chatId },
    target: {
      type: 'NEW_SESSION',
      sessionId,
      threadId,
      rootSettings: {
        agentName,
        execution: { driver: 'builtin', settings: { modelName, variant } },
        environmentName: null,
      },
      yoloEnabled: false,
    },
    commands: [
      {
        type: 'USER_MESSAGE',
        idempotencyKey: commandId,
        contents: [{ type: 'TEXT', text: marker }],
      },
    ],
  }
}
