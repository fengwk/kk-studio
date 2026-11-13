/**
 * 压缩摘要正文 fixture：模型摘要中的普通 Markdown 与标签文本按原样显示。
 *
 * <p>保留标签作为展示安全性的样例；运行时不会追加或解析这些标签。
 */
export const FULL_COMPACTION_SUMMARY = [
  '# 会话压缩摘要',
  '',
  '对话较长时，系统会将较早内容压缩为摘要，并保留最近的对话继续工作。',
  '',
  '## 关键决策',
  '',
  '- 只读取 `COMPACTION.payload.summaryText`',
  '- 成功结果在 `COMPACTION` Entry 中保存，并匹配 `TURN_END`',
  '',
  '## 代码示例',
  '',
  '```ts',
  'const ok = outcome === "COMPLETED" && continueModel',
  '```',
  '',
  '<read-files>',
  'src/features/thread/runtime/thread-timeline-builder.ts',
  'src/features/thread/runtime/thread-timeline/entry-event-projection.ts',
  '</read-files>',
  '',
  '<modified-files>',
  'src/features/thread/runtime/thread-panel/messages/CompactionEntryBlock.tsx',
  '</modified-files>',
].join('\n')

/**
 * 长正文 fixture：展开后完整显示且无内部滚动，用于验证统一容器不按行截断。
 */
export const LONG_COMPACTION_SUMMARY = [
  '# 长摘要',
  ...Array.from({ length: 40 }, (_value, index) =>
    `第 ${index + 1} 段：这是一段用于撑开压缩摘要正文高度的说明文字，验证只有一个有界纵向滚动区。`),
  '',
  '<read-files>',
  'src/features/thread/runtime/thread-timeline-builder.ts',
  '</read-files>',
].join('\n\n')

/**
 * 含原始 HTML 的摘要：安全渲染不得执行脚本，也不得产生图片请求。
 */
export const UNSAFE_COMPACTION_SUMMARY = [
  '# 安全渲染',
  '',
  '<script>window.__compactionExecuted = true</script>',
  '<img src="https://example.invalid/compaction-pixel.png" onerror="window.__compactionImgError = true" />',
].join('\n')
