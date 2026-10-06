/**
 * 压缩摘要正文 fixture：与 CompactionSummaryAssembler / CompactionFileSections 的真实输出形态一致。
 *
 * <p>正常 Markdown 标题/列表/代码块 + Runtime 追加的 &lt;read-files&gt;/&lt;modified-files&gt;
 * 保留标签；不做专用解析，标签与逐行路径必须原样可见。
 */
export const FULL_COMPACTION_SUMMARY = [
  '# 会话压缩摘要',
  '',
  '用户要求为 kk-studio 增加**上下文压缩视图**，同时保持后端压缩行为不变。',
  '',
  '## 关键决策',
  '',
  '- 只读取 `COMPACTION.payload.summaryText`',
  '- 成功判定依赖 `phase` 与匹配的 `TURN_END.outcome`',
  '',
  '## 代码示例',
  '',
  '```ts',
  "const ok = phase === 'FULL' || phase === 'TURN_PREFIX'",
  '```',
  '',
  '<read-files>',
  'src/features/ai/runtime/thread-timeline-builder.ts',
  'src/features/ai/runtime/thread-timeline/entry-event-projection.ts',
  '</read-files>',
  '',
  '<modified-files>',
  'src/features/ai/runtime/thread-panel/messages/CompactionEntryBlock.tsx',
  '</modified-files>',
].join('\n')

/**
 * 长正文 fixture：超出卡片正文最大高度，用于验证唯一有界滚动区。
 */
export const LONG_COMPACTION_SUMMARY = [
  '# 长摘要',
  ...Array.from({ length: 40 }, (_value, index) =>
    `第 ${index + 1} 段：这是一段用于撑开压缩摘要正文高度的说明文字，验证只有一个有界纵向滚动区。`),
  '',
  '<read-files>',
  'src/features/ai/runtime/thread-timeline-builder.ts',
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
