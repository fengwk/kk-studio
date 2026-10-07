import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { createRoot } from 'react-dom/client'
import '@/styles.css'
import { ResourceBlobUrlContext } from '@/features/ai/runtime/thread-panel/messages/ResourceBlobUrlContext'
import { ThreadConversationView } from '@/features/ai/runtime/thread-panel/ThreadConversationView'
import type {
  DialogueMessage,
  ToolDialogueMessage,
} from '@/features/ai/runtime/thread-timeline-types'

/**
 * Slice C 的真实浏览器 harness：挂载真实 ThreadConversationView + 工具卡片，
 * 用最少的夹具驱动「流式增长跟随 / 空闲高度变化不抢锚点 / 图片加载 / 流式转终态身份」。
 * 不启动后端与模型；资源解析使用固定夹具。
 */

type HarnessState = {
  messages: DialogueMessage[]
  streaming: boolean
  /** 恢复的阅读位置：与产品里「重新进入 Thread 恢复上次位置」同一条路径。 */
  initialScrollTop?: number | null
}

/**
 * 初始就是终态场景：任何夹具都会先 publish，避免「挂载时是流式卡片、随后被替换成
 * 静态卡片」影响「静态正文从顶部读」的断言。`currentState` 与初始渲染状态同源，
 * 使 `restorePosition` 这类基于当前状态的夹具在首帧也能工作。
 */
function initialState(): HarnessState {
  return {
    messages: [...filler(6), bashTool('call-bash', 40, false), bashResult('call-bash', 40)],
    streaming: false,
  }
}

let publish: (state: HarnessState) => void = () => {}
let currentState: HarnessState = initialState()

function assistant(id: string, text: string): DialogueMessage {
  return {
    id,
    role: 'assistant',
    subjectEntryId: id,
    text,
    createdAt: '2026-10-06T10:00:00Z',
    status: 'done',
  }
}

function filler(count: number): DialogueMessage[] {
  return Array.from({ length: count }, (_, index) =>
    assistant(`filler-${index + 1}`, `filler reply ${index + 1}\nsecond line ${index + 1}`),
  )
}

function bashOutput(lines: number): string {
  return Array.from({ length: lines }, (_, index) => `build step ${index + 1} completed`).join('\n')
}

function bashTool(id: string, lines: number, partial: boolean): ToolDialogueMessage {
  if (partial) {
    // 流式阶段只有调用草稿：partial 内容挂在 call 上，终态 result 尚未出现。
    return {
      id: `transient:tool-call:${id}`,
      role: 'tool',
      subjectEntryId: null,
      createdAt: '2026-10-06T10:00:01Z',
      status: 'streaming',
      phase: 'call',
      contents: [],
      partialContents: [{ type: 'text', text: bashOutput(lines) }],
      toolCallId: id,
      toolName: 'bash',
      rendererKey: 'bash',
      arguments: '{"command":"npm run build","timeout_seconds":600}',
    }
  }
  return {
    id: `40:tool-call:${id}`,
    role: 'tool',
    subjectEntryId: 'entry-40',
    createdAt: '2026-10-06T10:00:01Z',
    status: 'done',
    phase: 'call',
    contents: [],
    toolCallId: id,
    toolName: 'bash',
    rendererKey: 'bash',
    arguments: '{"command":"npm run build","timeout_seconds":600}',
  }
}

function bashResult(id: string, lines: number): ToolDialogueMessage {
  return {
    id: `40:tool-result:${id}`,
    role: 'tool',
    subjectEntryId: 'entry-41',
    createdAt: '2026-10-06T10:00:02Z',
    status: 'done',
    phase: 'result',
    contents: [{ type: 'text', text: bashOutput(lines) }],
    toolCallId: id,
    toolName: 'bash',
    rendererKey: 'bash',
    arguments: '{"command":"npm run build","timeout_seconds":600}',
  }
}

/**
 * 未知/MCP 工具：Header 只做紧凑 JSON，没有二次正文可展开。
 * `rawArguments` 为不完整（流式）参数时原样呈现，换行不会被吞掉。
 */
function unknownTool(
  id: string,
  rawArguments: string,
  status: 'done' | 'streaming',
  withBody = false,
): ToolDialogueMessage {
  const streaming = status === 'streaming'
  return {
    id: streaming ? `transient:tool-call:${id}` : `40:tool-result:${id}`,
    role: 'tool',
    subjectEntryId: streaming ? null : 'entry-60',
    createdAt: '2026-10-06T10:00:05Z',
    status,
    phase: streaming ? 'call' : 'result',
    contents: withBody ? [{ type: 'text', text: 'no matches' }] : [],
    ...(streaming ? { partialContents: [] } : {}),
    toolCallId: id,
    toolName: 'mcp__filesystem__search_files',
    arguments: rawArguments,
  }
}

const IMAGE_BLOB_ID = 'blob-image'
const UNKNOWN_BLOB_ID = 'blob-unknown'

function readTool(id: string, blobId: string, name: string): {
  call: ToolDialogueMessage
  result: ToolDialogueMessage
} {
  return {
    call: {
      id: `40:tool-call:${id}`,
      role: 'tool',
      subjectEntryId: 'entry-50',
      createdAt: '2026-10-06T10:00:03Z',
      status: 'done',
      phase: 'call',
      contents: [],
      toolCallId: id,
      toolName: 'read',
      rendererKey: 'read',
      arguments: JSON.stringify({ path: `/tmp/${name}` }),
    },
    result: {
      id: `40:tool-result:${id}`,
      role: 'tool',
      subjectEntryId: 'entry-51',
      createdAt: '2026-10-06T10:00:04Z',
      status: 'done',
      phase: 'result',
      contents: [{
        type: 'resource',
        attachment: {
          type: 'file',
          name,
          mime: '',
          data: '',
          blobId,
        },
      }],
      toolCallId: id,
      toolName: 'read',
      rendererKey: 'read',
      arguments: JSON.stringify({ path: `/tmp/${name}` }),
    },
  }
}

/** read 文本卡片：默认收起（Header only），点开箭头就是「流式期间手动展开」。 */
function readTextTool(id: string): { call: ToolDialogueMessage; result: ToolDialogueMessage } {
  return {
    call: {
      id: `40:tool-call:${id}`,
      role: 'tool',
      subjectEntryId: 'entry-52',
      createdAt: '2026-10-06T10:00:06Z',
      status: 'done',
      phase: 'call',
      contents: [],
      toolCallId: id,
      toolName: 'read',
      rendererKey: 'read',
      arguments: JSON.stringify({ path: '/tmp/notes.md' }),
    },
    result: {
      id: `40:tool-result:${id}`,
      role: 'tool',
      subjectEntryId: 'entry-53',
      createdAt: '2026-10-06T10:00:07Z',
      status: 'done',
      phase: 'result',
      contents: [{
        type: 'text',
        text: Array.from({ length: 24 }, (_, index) => `note line ${index + 1}`).join('\n'),
      }],
      toolCallId: id,
      toolName: 'read',
      rendererKey: 'read',
      arguments: JSON.stringify({ path: '/tmp/notes.md' }),
    },
  }
}

/** 大尺寸图片：让「图片加载完成」确实改变卡片高度，才能验证外层锚点不被抢占。 */
function bigImageDataUrl(): string {
  const canvas = document.createElement('canvas')
  canvas.width = 900
  canvas.height = 700
  const context = canvas.getContext('2d')
  if (context) {
    context.fillStyle = '#1f6feb'
    context.fillRect(0, 0, canvas.width, canvas.height)
    context.fillStyle = '#ffffff'
    context.font = '48px sans-serif'
    context.fillText('tool card image', 40, 360)
  }
  return canvas.toDataURL('image/png')
}

/**
 * 资源解析夹具：图片 blob 在延迟后返回权威 image/png（模拟真实 storage 往返），
 * 未知 blob 返回 text/plain，用于验证「只有图片默认展开」。
 */
function createBlobResolver(delayMs: number, imageUrl: string) {
  return async (blobId: string) => {
    await new Promise((resolve) => setTimeout(resolve, delayMs))
    if (blobId === IMAGE_BLOB_ID) {
      return {
        original: imageUrl,
        preview: imageUrl,
        mediaType: 'image/png',
        sizeBytes: 1_024,
      }
    }
    if (blobId === UNKNOWN_BLOB_ID) {
      return {
        original: 'https://example.invalid/unknown.txt',
        preview: null,
        mediaType: 'text/plain',
        sizeBytes: 12,
      }
    }
    return null
  }
}

export function ToolCardHarnessApp() {
  const [state, setState] = useState<HarnessState>(initialState)
  const bodyRef = useRef<HTMLDivElement | null>(null)
  const imageUrl = useMemo(() => bigImageDataUrl(), [])
  const resolveBlobUrls = useMemo(() => createBlobResolver(600, imageUrl), [imageUrl])

  const release = useCallback((next: HarnessState) => {
    currentState = next
    setState(next)
  }, [])

  useEffect(() => {
    publish = release
    return () => {
      publish = () => {}
    }
  }, [release])

  return (
    <div className="harness-shell">
      <style>{`
        .harness-shell { height: 420px; display: flex; flex-direction: column; }
        .harness-hint { padding: 4px 16px; font: 12px sans-serif; color: #666; }
      `}</style>
      <div className="harness-hint">
        tool card browser harness
      </div>
      <ResourceBlobUrlContext.Provider value={resolveBlobUrls}>
        <ThreadConversationView
          messages={state.messages}
          loading={false}
          error={null}
          bodyRef={bodyRef}
          initialScrollTop={state.initialScrollTop ?? null}
        />
      </ResourceBlobUrlContext.Provider>
    </div>
  )
}

const toolCardHarness = {
  longPath() {
    publish({
      messages: [{
        ...bashTool('long-path', 1, false),
        toolName: 'read', rendererKey: 'read', invocationStatus: 'RUNNING',
        arguments: JSON.stringify({ path: `/srv/${'long-directory/'.repeat(30)}end.txt` }),
        partialContents: [{ type: 'text', text: 'reading' }],
      }],
      streaming: false,
    })
  },
  durableStates() {
    publish({
      messages: [
        'READY', 'WAITING_APPROVAL', 'WAITING_INPUT', 'DISPATCHING', 'RUNNING',
        'SUCCEEDED', 'FAILED', 'CANCELLED', 'UNKNOWN',
      ].map((status) => ({
        ...bashTool(status, 1, false), invocationStatus: status,
      })),
      streaming: false,
    })
  },
  /**
   * 流式且卡片在末尾：外部贴底时 bash 卡片与末尾 read 文本卡片都在可视区内。
   * 末尾 read 文本卡片默认收起，点开箭头就是「流式期间手动展开」。
   */
  streamingTail(lines = 40) {
    const read = readTextTool('call-read-text')
    publish({
      messages: [...filler(8), bashTool('call-bash', lines, true), read.call, read.result],
      streaming: true,
    })
  },
  /** 末尾流式卡片继续增长（外部内容并发增长），卡片构成不变。 */
  growTail(lines: number) {
    const read = readTextTool('call-read-text')
    publish({
      messages: [...filler(8), bashTool('call-bash', lines, true), read.call, read.result],
      streaming: true,
    })
  },
  /**
   * 布局变矮：填充消息减少，容器高度确实变小，且流式 revision 同时变化。
   * 用于验证「阅读意图 + 后续流式信号」也不会因为高度变矮而重开跟随。
   */
  shrinkTail(lines = 60) {
    const read = readTextTool('call-read-text')
    publish({
      messages: [...filler(2), bashTool('call-bash', lines, true), read.call, read.result],
      streaming: true,
    })
  },
  /** 流式期间追加一张新的 read 图片卡片：解析完成后默认预览，布局确实增大。 */
  addImageMid(lines: number) {
    const read = readTextTool('call-read-text')
    const image = readTool('call-read-late', IMAGE_BLOB_ID, 'late.png')
    publish({
      messages: [
        ...filler(8),
        bashTool('call-bash', lines, true),
        read.call,
        read.result,
        image.call,
        image.result,
      ],
      streaming: true,
    })
  },
  /**
   * 未知/MCP 工具：超长紧凑 JSON。第一张没有二次正文（不可展开），第二张有正文
   * （可展开，箭头固定在右侧留白里），第三张是带真实换行的流式原始参数。
   */
  unknownLong(chars = 4000) {
    const longValue = `start-${'x'.repeat(Math.max(0, chars - 40))}-tail-marker`
    publish({
      messages: [
        ...filler(2),
        unknownTool('call-mcp-long', JSON.stringify({ query: longValue, limit: chars }), 'done'),
        unknownTool(
          'call-mcp-body',
          `{"patch":"line one\nline two\n${'y'.repeat(1200)}`,
          'done',
          true,
        ),
        ...filler(3),
      ],
      streaming: false,
    })
  },
  /** 空闲且卡片位于中间：下方仍有内容，「不贴底」才有区分度。 */
  idleMid(lines = 40) {
    publish({
      messages: [
        ...filler(5),
        bashTool('call-bash', lines, false),
        bashResult('call-bash', lines),
        ...filler(8),
      ],
      streaming: false,
    })
  },
  /** 空闲场景：全部消息已终态，工具结果已到达。 */
  idle(lines = 40) {
    publish({
      messages: [...filler(6), bashTool('call-bash', lines, false), bashResult('call-bash', lines)],
      streaming: false,
    })
  },
  /** 流式场景：bash 调用持续输出中（只有调用草稿）。 */
  streaming(lines = 20) {
    publish({
      messages: [...filler(6), bashTool('call-bash', lines, true)],
      streaming: true,
    })
  },
  /** 流式增长：同一条 bash 调用继续产出。 */
  grow(lines: number) {
    publish({
      messages: [...filler(6), bashTool('call-bash', lines, true)],
      streaming: true,
    })
  },
  /** 流式转终态：同一 toolCallId，身份不变。 */
  settle(lines = 40) {
    publish({
      messages: [...filler(6), bashTool('call-bash', lines, false), bashResult('call-bash', lines)],
      streaming: false,
    })
  },
  /** 恢复阅读位置（与应用恢复保存位置同一路径，不经过用户滚动事件）。 */
  restorePosition(offset: number) {
    publish({ ...currentState, initialScrollTop: offset })
  },
  /**
   * 空闲时追加一张 read 卡片：图片 blob（权威 MIME image/png，延迟 600ms 解析）。
   * 卡片之后保留足量内容，让「恢复到中段阅读位置」真的远离底部。
   */
  readImage() {
    const tool = readTool('call-read-image', IMAGE_BLOB_ID, 'shot.png')
    publish({
      messages: [...filler(3), tool.call, tool.result, ...filler(11)],
      streaming: false,
    })
  },
  /** 空闲时追加一张 read 卡片：未知 MIME blob。 */
  readUnknown() {
    const tool = readTool('call-read-unknown', UNKNOWN_BLOB_ID, 'result.bin')
    publish({
      messages: [...filler(3), tool.call, tool.result, ...filler(11)],
      streaming: false,
    })
  },
}

declare global {
  interface Window {
    toolCardHarness: typeof toolCardHarness
  }
}

window.toolCardHarness = toolCardHarness


const rootElement = document.getElementById('root')
if (rootElement) {
  createRoot(rootElement).render(<ToolCardHarnessApp />)
}
