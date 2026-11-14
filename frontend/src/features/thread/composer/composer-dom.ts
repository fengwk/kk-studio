/**
 * contenteditable editor 的最小 DOM parts 契约。
 *
 * Editor 的 DOM 只包含三类节点：纯文本节点（text part）、`contenteditable=false`
 * 的 pill span（attachment/resource part），以及仅存在于 DOM 的 caret slot。
 * Pill 携带 `data-part-id`、`data-part-type` 与对应引用字段；caret slot 携带
 * `data-caret-slot`，初始时内部只有一个 U+200B 占位。
 *
 * Caret slot 存在的理由：不可编辑 pill 的相邻边界若没有任何可编辑文本位置，
 * 输入法会把组合输入送进 pill 内部而卡住首个字符。Slot 为这类边界提供原生编辑
 * 需要的文本位置，同时完全留在 parts 之外——不成为 part、不进剪贴板与草稿历史，
 * 逻辑字符长度为 0。已有文本节点的边界天然可编辑，不重复放置 slot。
 *
 * 这些是纯函数 DOM 辅助；组件负责事件驱动下的调用时机与状态回传。实现按关注点
 * 分在三个同目录模块里，这里只汇总 editor 侧使用的入口：
 *
 * - caret-slots：slot/pill 节点契约与 slot 的创建、提升；
 * - caret-space：逻辑字符空间（slot 占位长度为 0、pill 为 1 个单位）的定位与选区；
 * - editor-parts：parts 与 DOM 的相互转换；
 * - editor-normalize：浏览器 DOM 规范化与光标处插入。
 */
export {
  isCaretSlot,
  isEditorComposing,
  isPillElement,
  COMPOSING_ATTRIBUTE,
} from '@/features/thread/composer/caret-slots'
export {
  ensureCaretSlots,
  selectionTouchesCaretSlot,
  serializeSelectionText,
} from '@/features/thread/composer/caret-space'
export {
  extractPartsFromEditor,
  extractPartsKeyFromEditor,
  renderPartsToEditor,
} from '@/features/thread/composer/editor-parts'
export {
  findAdjacentPill,
  insertPillsAtCaret,
  insertTextAtCaret,
  normalizeEditorDom,
  placeCaretAtEnd,
} from '@/features/thread/composer/editor-normalize'
