export async function assertReadOnlyZeroFooter(scope, label) {
  const footer = scope.getByLabel('会话状态')
  await footer.waitFor({ state: 'visible', timeout: 10_000 })
  const text = (await footer.innerText()).replace(/\s+/g, ' ').trim()
  for (const expected of ['none env', '↑0', '↓0', '$0.000']) {
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
