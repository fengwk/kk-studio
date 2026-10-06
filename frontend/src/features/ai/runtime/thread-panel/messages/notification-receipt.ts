/** 子代理回执信封的固定标签与属性；只解析当前协议，不做历史或通用 XML 兼容。 */
const ENVELOPE_TAG = 'subagent_result'
const ALLOWED_ATTRIBUTES = ['thread_id', 'agent', 'state']
const ALLOWED_CHILDREN = ['task', 'result', 'error', 'partial_result']
const STATES = ['completed', 'error', 'cancelled'] as const

export type SubagentReceiptState = (typeof STATES)[number]

export interface SubagentReceipt {
  threadId: string | null
  agent: string | null
  state: SubagentReceiptState
  result: string | null
  error: string | null
  partial: string | null
}

export type SubagentReceiptParseResult =
  | { ok: true; receipt: SubagentReceipt }
  | { ok: false }

/**
 * 用真实 XML 解析器解析固定 subagent completion 信封，并按固定契约做安全校验。
 *
 * <p>非法 XML、root 不是 {@code subagent_result}、出现信封之外的元素或属性、缺少关键属性、
 * state 不在协议枚举、section 内嵌套元素、重复 section；以及与通知来源不一致的 thread_id
 * 都判定为非法，绝不宽松兜底。section 只允许纯文本，避免任务正文被塞进 result 重复展示。
 *
 * @param expectedThreadId 通知声明的来源 Thread；提供时必须与信封 thread_id 一致
 */
export function parseSubagentReceipt(
  xml: string,
  expectedThreadId: string | null = null,
): SubagentReceiptParseResult {
  const document = new DOMParser().parseFromString(xml, 'application/xml')
  if (document.getElementsByTagName('parsererror').length > 0) {
    return { ok: false }
  }
  const root = document.documentElement
  if (root == null || root.nodeName !== ENVELOPE_TAG) {
    return { ok: false }
  }
  for (const attribute of Array.from(root.attributes)) {
    if (!ALLOWED_ATTRIBUTES.includes(attribute.name)) {
      return { ok: false }
    }
  }
  const threadId = root.getAttribute('thread_id')
  if (threadId == null || !threadId) {
    return { ok: false }
  }
  if (expectedThreadId != null && threadId !== expectedThreadId) {
    return { ok: false }
  }
  const state = root.getAttribute('state')
  if (state == null || !STATES.includes(state as SubagentReceiptState)) {
    return { ok: false }
  }
  const sections = new Map<string, string>()
  for (const child of Array.from(root.children)) {
    if (!ALLOWED_CHILDREN.includes(child.nodeName) || sections.has(child.nodeName)) {
      return { ok: false }
    }
    // section 只允许纯文本：嵌套元素会把其它 section（如 task 原文）混入本段展示。
    if (child.children.length > 0) {
      return { ok: false }
    }
    sections.set(child.nodeName, stripEnvelopeNewlines(child.textContent ?? ''))
  }
  return {
    ok: true,
    receipt: {
      threadId,
      agent: root.getAttribute('agent'),
      state: state as SubagentReceiptState,
      result: sections.get('result') ?? null,
      error: sections.get('error') ?? null,
      partial: sections.get('partial_result') ?? null,
    },
  }
}

/** 渲染器把每段正文包在标签内的换行之间，解析时只剥离这一层框架换行。 */
function stripEnvelopeNewlines(text: string): string {
  let value = text
  if (value.startsWith('\n')) {
    value = value.slice(1)
  }
  if (value.endsWith('\n')) {
    value = value.slice(0, -1)
  }
  return value
}
