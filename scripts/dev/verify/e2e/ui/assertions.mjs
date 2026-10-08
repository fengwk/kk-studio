export async function assertReadOnlyZeroFooter(scope, label) {
  const footer = scope.getByLabel('会话状态')
  await footer.waitFor({ state: 'visible', timeout: 10_000 })
  const text = (await footer.innerText()).replace(/\s+/g, ' ').trim()
  // 单行 Footer 分组只使用 U+2223；组内统计项由 U+00B7 分隔。
  // 无 Environment / 无用量事实：环境为「未选择环境」，用量段无定价/无测速样本如实显示 —，
  // 绝不伪造成 $0 或 0 tok/s。
  for (const expected of ['未选择环境', ' ∣ ', '↑0 · ↓0 · — · cache — · — tok/s']) {
    if (!text.includes(expected)) {
      throw new Error(`${label} missing "${expected}": ${text}`)
    }
  }
  const buttons = await footer.getByRole('button').count()
  if (buttons !== 0) {
    throw new Error(`${label} must stay read-only, found ${buttons} button(s)`)
  }
}

export async function waitForSettledAssistantText(scope, timeout = 90_000) {
  const text = scope.locator('.thread-turn-assistant .thread-assistant-text').last()
  await text.waitFor({ state: 'visible', timeout })
  await scope.locator('.thread-working').waitFor({ state: 'detached', timeout })
  return (await text.innerText()).trim()
}
