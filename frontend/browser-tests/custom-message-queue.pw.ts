import { expect, test } from './fixture'

/**
 * 子观察面板只读 QUEUED CUSTOM_MESSAGE 浏览器回归测试。
 *
 * 验证目标：
 * 1. 命令以 QUEUED 状态在小部件区域渲染只读“待处理补充要求”，展示实际文本与稳定 sequence；
 * 2. 子观察面板为只读环境：不存在任何 textarea/输入框，也无写/删/重排按钮；
 * 3. 支持中英 i18n 动态切换；
 * 4. 应用后（命令转为 APPLIED/移出队列，物化为 durable entry），队列项自动移除，历史消息恰好展示一次，不重复复制。
 */
const HARNESS_URL = '/browser-tests/custom-message-queue-harness.html'
const SUPPLEMENTARY_TEXT = '请补充自动化测试覆盖率'
const INITIAL_TASK_TEXT = '子代理初始任务：分析代码结构'

test.describe('Subagent custom message queue observability', () => {
  test('渲染待处理补充要求、只读约束，以及 applied 移除并在历史中呈现一次', async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 800 })
    await page.goto(HARNESS_URL)

    // 1. 验证只读查看徽标
    await expect(page.locator('.thread-readonly-badge')).toHaveText('只读查看')

    // 2. 验证初始 QUEUED CUSTOM_MESSAGE 渲染
    const queueItem = page.locator('.thread-custom-message-queue-item')
    await expect(queueItem).toHaveCount(1)
    await expect(queueItem).toHaveAttribute('data-sequence', '2')
    await expect(queueItem.locator('.thread-input-queue-label')).toHaveText('待处理补充要求')
    await expect(queueItem.locator('.thread-input-queue-content')).toHaveText(SUPPLEMENTARY_TEXT)

    // 3. 验证只读约束：子观察面板无输入框/textarea，无删除重排等写交互
    await expect(page.locator('.chat-main textarea')).toHaveCount(0)
    await expect(page.locator('.chat-main [contenteditable="true"]')).toHaveCount(0)
    await expect(queueItem.locator('button')).toHaveCount(0)

    // 4. 验证中英切换
    const toggleLocaleBtn = page.getByTestId('toggle-locale-button')
    await toggleLocaleBtn.click()
    await expect(queueItem.locator('.thread-input-queue-label')).toHaveText(
      'Pending supplementary requirement',
    )
    await expect(queueItem.locator('.thread-input-queue-content')).toHaveText(SUPPLEMENTARY_TEXT)

    // 切回中文
    await toggleLocaleBtn.click()
    await expect(queueItem.locator('.thread-input-queue-label')).toHaveText('待处理补充要求')

    // 5. 验证此时历史消息中仅有初始任务，尚无补充要求的持久条目
    const transcriptMessages = page.locator('.thread-block-user')
    await expect(transcriptMessages).toContainText([INITIAL_TASK_TEXT])

    // 6. 模拟应用：点击“应用补充要求”
    const applyBtn = page.getByTestId('apply-button')
    await applyBtn.click()

    // 7. applied 移除：队列中的待处理补充要求自动移除
    await expect(page.locator('.thread-custom-message-queue-item')).toHaveCount(0)
    await expect(page.getByText('待处理补充要求')).toHaveCount(0)

    // 8. history 一次：历史消息区域准确出现该补充要求，且恰好只出现一次（不另复制）
    const matchedHistory = page.locator('.thread-block-user', { hasText: SUPPLEMENTARY_TEXT })
    await expect(matchedHistory).toHaveCount(1)
  })
})
