/**
 * Invocation 去重与终态重放的最小 journal 端口及其内存实现。
 *
 * <p>journal 是 Daemon 进程内的执行事实源：WebSocket 连接只是消息管道。同实例重连由 RECOVER 取回在途调用：运行中的调用
 * 回放 STARTED 并继续执行，已终结的调用重放终态和独立退出事实；只有 Studio 已表达取消意图的调用才收到 CANCEL 并停止
 * scope。INVOKE 不重发：重复 INVOKE 只在条目存在时去重，条目缺失不证明该调用从未执行，不能把 INVOKE 用作恢复查询。
 * 终态一旦写入不可逆（terminal-once）。
 *
 * <p>内存实现至多保留 {@link DEFAULT_MAX_JOURNAL_ENTRIES} 条终态事实，超出后按 start 的插入顺序淘汰终态条目；
 * RUNNING 条目永不淘汰（其数量由运行时的并发上限约束）。
 *
 * <p>退出确认是与终态分开的事实：条目被容量淘汰后，重放不再包含该确认，Studio 侧保持未知，不在这里补造或重试。
 */

export type InvocationState = 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED'

export type TerminalMessageType = 'COMPLETED' | 'FAILED' | 'CANCELLED'

/** 可随已有 journal 条目重放的终态 wire 消息。 */
export interface TerminalMessage {
  messageType: TerminalMessageType
  payloadJson: string
}

export interface JournalEntry {
  invocationId: string
  state: InvocationState
  terminalMessage: TerminalMessage | null
  /**
   * 独立退出确认。与结果终态分开保存：命中条目的重复 INVOKE/CANCEL 可随条目回放，不能由 terminal 推断。
   */
  exitConfirmed: boolean
}

export interface JournalStart {
  created: boolean
  entry: JournalEntry
}

export interface InvocationJournal {
  /** 原子创建初始为 RUNNING 的条目，或返回既有条目。 */
  start(invocationId: string): JournalStart
  find(invocationId: string): JournalEntry | undefined
  /** 仅把 RUNNING 原子跃迁为终态；已终态或不存在时返回 false。 */
  complete(invocationId: string, terminalMessage: TerminalMessage): boolean
  /**
   * 记录一次正向退出确认。未知 invocation 返回 false；重复确认保持已确认并返回 false。
   * 终态之后仍可记录，二者不是同一事实。
   */
  confirmExit(invocationId: string): boolean
}

/** 内存 journal 默认保留的终态条目上界。 */
export const DEFAULT_MAX_JOURNAL_ENTRIES = 1024

export function isTerminalState(state: InvocationState): boolean {
  return state !== 'RUNNING'
}

function stateFor(messageType: TerminalMessageType): InvocationState {
  switch (messageType) {
    case 'COMPLETED':
      return 'COMPLETED'
    case 'FAILED':
      return 'FAILED'
    case 'CANCELLED':
      return 'CANCELLED'
  }
}

/** 单进程、有界的线程安全（Node 单线程）内存 journal。 */
export class InMemoryInvocationJournal implements InvocationJournal {
  private readonly entries = new Map<string, JournalEntry>()
  private readonly maxTerminalEntries: number

  constructor(maxTerminalEntries: number = DEFAULT_MAX_JOURNAL_ENTRIES) {
    if (!Number.isSafeInteger(maxTerminalEntries) || maxTerminalEntries <= 0) {
      throw new Error('maxTerminalEntries must be a positive integer')
    }
    this.maxTerminalEntries = maxTerminalEntries
  }

  start(invocationId: string): JournalStart {
    const existing = this.entries.get(invocationId)
    if (existing !== undefined) {
      return { created: false, entry: existing }
    }
    const entry: JournalEntry = {
      invocationId,
      state: 'RUNNING',
      terminalMessage: null,
      exitConfirmed: false,
    }
    this.entries.set(invocationId, entry)
    return { created: true, entry }
  }

  find(invocationId: string): JournalEntry | undefined {
    return this.entries.get(invocationId)
  }

  complete(invocationId: string, terminalMessage: TerminalMessage): boolean {
    const entry = this.entries.get(invocationId)
    if (entry === undefined || isTerminalState(entry.state)) {
      return false
    }
    this.entries.set(invocationId, {
      invocationId,
      state: stateFor(terminalMessage.messageType),
      terminalMessage,
      exitConfirmed: entry.exitConfirmed,
    })
    this.evictTerminalIfNeeded()
    return true
  }

  confirmExit(invocationId: string): boolean {
    const entry = this.entries.get(invocationId)
    if (entry === undefined || entry.exitConfirmed) {
      return false
    }
    this.entries.set(invocationId, { ...entry, exitConfirmed: true })
    return true
  }

  /** 仅淘汰最旧的终态条目；RUNNING 条目不计入上界、永不淘汰。 */
  private evictTerminalIfNeeded(): void {
    let terminalCount = 0
    for (const entry of this.entries.values()) {
      if (isTerminalState(entry.state)) {
        terminalCount++
      }
    }
    if (terminalCount <= this.maxTerminalEntries) {
      return
    }
    for (const [id, entry] of this.entries) {
      if (isTerminalState(entry.state)) {
        this.entries.delete(id)
        return
      }
    }
  }

  /** 当前条目数，供测试断言上界。 */
  size(): number {
    return this.entries.size
  }
}
