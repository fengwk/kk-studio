import { readdirSync, readFileSync, statSync } from 'node:fs'
import koffi from 'koffi'

/**
 * POSIX **会话**原语：父进程与 scope helper 都只通过这里触达 libc / libproc。
 *
 * <p>收敛范围是会话而不是单个进程组：交互式 shell 的 job control 会为每个前台/后台作业建立自己的进程组，因此「只 kill helper
 * 所在的进程组」会漏掉其它作业组。helper 先成为新会话的 leader（`setsid`），因此会话 id 恒等于 helper 的 pid；用户命令与
 * 它的普通后代都继承该会话。
 *
 * <p>成员枚举必须是真实的内核查询：Linux 读 `/proc/<pid>/stat` 的 session 字段；macOS 用 libproc `proc_listpids` 枚举
 * 全部 pid 后逐个 `getsid(pid)` 判定归属，再用 `proc_pidinfo(PROC_PIDTBSDINFO)` 取状态与启动身份。两者都不猜原生结构体的
 * 字节偏移（macOS 结构体布局交给 Koffi 的 struct 定义）。没有真实成员查询的平台一律返回「不可判定」，绝不当成「已经收敛」。
 *
 * <p>身份（启动时刻）随快照带出：发信号之前必须重新核验「仍属于本会话」且「启动时刻未变」，因此 pid/sid 复用不会误杀无关进程。
 *
 * <p>故意 `setsid`、脱离本会话的后代不在会话枚举里。这里不靠定时扫描整棵进程树去「猜」归属，而是用内核的子进程收养：helper 先
 * 成为子进程收养者（`PR_SET_CHILD_SUBREAPER`），于是这类后代的父进程一旦退出就被内核重新挂到 helper 名下；父 daemon 也成为
 * 收养者时，可在 helper 崩溃之后接管。`directChildren` + `convergeAdoptedChildren` 只收敛这些「已被内核确认挂在本人名下」的
 * 直接子进程所属会话，属于收尾时刻的真实内核事实，而不是启发式猜测。
 *
 * <p>「不可判定」与「没有成员」必须严格区分：只要有一个已确认属于本会话、仍活着的成员读不到身份，整次枚举就返回 `null`
 * （不可判定）。把这种情况过滤成空列表会把「查不到」谎报成「已经收敛」。
 *
 * <p>本模块只依赖 Node 内建模块与 Koffi，不引用同目录模块：helper 以 `.ts` 源码直接运行时相对 import 的 `.js` 无法解析。
 */

/** 温和终止信号。 */
export const SIGTERM = 15

/** 不可忽略的强制终止信号。 */
export const SIGKILL = 9

/** 需要排除调用者时使用的「没有进程」取值。 */
export const NO_PROCESS = -1

/** Linux `prctl` 的 `PR_SET_CHILD_SUBREAPER`。 */
const PR_SET_CHILD_SUBREAPER = 36

/** `waitpid` 的非阻塞选项。 */
const WNOHANG = 1

/** Darwin `getsid`：内核中找不到活进程。 */
const ESRCH = 3

/** macOS `pbi_status`：僵尸进程（`<sys/proc.h>` 的 `SZOMB`）。 */
const MAC_STATUS_ZOMBIE = 5

/** libproc `PROC_ALL_PIDS`。 */
const PROC_ALL_PIDS = 1

/** libproc `PROC_PIDTBSDINFO`。 */
const PROC_PIDTBSDINFO = 3

/** macOS pid 枚举的安全上限：按几何增长重试时用来封顶，避免病态情况下无界循环。 */
const MAC_PID_LIMIT = 1 << 20

/** 会话成员的快照项：pid、启动身份和父进程 pid。 */
export interface SessionMember {
  pid: number
  identity: string
  parentPid: number
}

interface ProcessInfo {
  session: number
  parentPid: number
  live: boolean
  identity: string
}

interface LibC {
  setsid(): number
  getsid(pid: number): number
  getpgrp(): number
  kill(pid: number, signal: number): number
  waitpid(pid: number, status: unknown, options: number): number
}

/** Linux 专有的 `prctl(2)` 签名。 */
type PrctlFunction = (option: number, arg2: number, arg3: number, arg4: number, arg5: number) => number

interface Libproc {
  proc_listpids(type: number, typeinfo: number, buffer: Buffer | null, buffersize: number): number
  proc_pidinfo(pid: number, flavor: number, arg: number, buffer: Buffer, buffersize: number): number
}

let libcInstance: LibC | null = null
let libprocInstance: Libproc | null = null
let prctlInstance: PrctlFunction | null = null

function libc(): LibC {
  if (libcInstance === null) {
    const library = koffi.load(null)
    libcInstance = {
      setsid: library.func('int setsid()'),
      getsid: library.func('int getsid(int)'),
      getpgrp: library.func('int getpgrp()'),
      kill: library.func('int kill(int, int)'),
      waitpid: library.func('int waitpid(int, void *, int)'),
    }
  }
  return libcInstance
}

/**
 * Linux 专有：`prctl` 在 Darwin 的 libc 里不存在，因此**只**在 Linux 上解析该符号。
 *
 * <p>把它与 `libc()` 分开是必须的：若在 Darwin 上无条件绑定 `prctl`，符号解析会直接失败并连带拖垮同一批绑定的
 * `setsid`/`getsid`，让整套 POSIX 会话机制在 macOS 上完全不可用。
 */
function prctl(): PrctlFunction {
  if (process.platform !== 'linux') {
    throw new Error(`prctl is only available on Linux, not ${process.platform}`)
  }
  if (prctlInstance === null) {
    prctlInstance = koffi.load(null).func('int prctl(int, unsigned long, unsigned long, unsigned long, unsigned long)') as PrctlFunction
  }
  return prctlInstance
}

/** macOS libproc 绑定只在 darwin 上解析：Linux 的 libc 里没有这些符号。 */
function libproc(): Libproc {
  if (libprocInstance === null) {
    const library = koffi.load(null)
    libprocInstance = {
      proc_listpids: library.func('int proc_listpids(unsigned int, unsigned int, void *, int)'),
      proc_pidinfo: library.func('int proc_pidinfo(int, int, unsigned long long, void *, int)'),
    }
  }
  return libprocInstance
}

/** 本平台是否提供真实会话成员查询；不提供时所有枚举返回「不可判定」。 */
export function supportsSessionEnumeration(): boolean {
  return process.platform === 'linux' || process.platform === 'darwin'
}

/**
 * 让当前进程成为新 session 与进程组的 leader。失败必须显式报错：静默回退会让「整会话收敛」的承诺失真。
 */
export function establishSession(): number {
  if (!supportsSessionEnumeration()) {
    throw new Error(`the POSIX process scope is not supported on ${process.platform}`)
  }
  if (libc().setsid() < 0) {
    throw new Error(`cannot create a POSIX session for the command scope: setsid failed with errno ${koffi.errno()}`)
  }
  if (currentSession() !== process.pid || currentGroup() !== process.pid) {
    throw new Error('the scope helper is not the leader of its own session')
  }
  return process.pid
}

/** 当前进程的会话 id。 */
export function currentSession(): number {
  return libc().getsid(0) >>> 0
}

/** 当前进程组 id。 */
export function currentGroup(): number {
  return libc().getpgrp() >>> 0
}

/** 进程此刻所属的会话；进程不存在或本平台无查询能力时返回 `-1`。 */
export function sessionOf(pid: number): number {
  if (process.platform === 'linux') {
    const info = linuxProcessInfo(pid)
    return info === null ? -1 : info.session
  }
  if (process.platform === 'darwin') {
    const session = libc().getsid(pid)
    return session < 0 ? -1 : session >>> 0
  }
  return -1
}

/** 进程此刻的启动身份；进程不存在、已经僵尸或读不到时返回 `null`。 */
export function processIdentity(pid: number): string | null {
  if (process.platform === 'linux') {
    const info = linuxProcessInfo(pid)
    return info === null || !info.live ? null : info.identity
  }
  if (process.platform === 'darwin') {
    const info = macProcessInfo(pid)
    return info === null || !info.live ? null : info.identity
  }
  return null
}

/**
 * 会话里当前活着（非僵尸）的成员快照，排除 `excludedPid`。
 *
 * <p>返回 `null` 表示「不可判定」：本平台没有真实枚举能力、扫描失败，或某个已确认属于本会话且仍活着的成员读不到身份。
 * 调用方必须把 `null` 当成「还没有收敛」，但也绝不因此向任何 pid 发信号。
 */
export function membersSnapshot(session: number, excludedPid: number): SessionMember[] | null {
  if (process.platform === 'linux') {
    return linuxMembers(session, excludedPid)
  }
  if (process.platform === 'darwin') {
    return macMembers(session, excludedPid)
  }
  return null
}

/**
 * 会话里是否还有「活着」的成员。
 *
 * <p>不可判定（没有真实枚举能力、扫描失败或身份读不到）必须表现为「还有成员」：谎称已经收敛会让收尾跳过强杀阶段。
 */
export function hasLiveMember(session: number, excludedPid: number): boolean {
  const members = membersSnapshot(session, excludedPid)
  return members === null || members.length > 0
}

/**
 * 向会话里的成员逐个发信号，但只在身份被重新核验通过时动手；返回是否至少交付了一个信号。
 *
 * <p>核验不通过就不发信号：宁可报告未收敛，也绝不杀错进程。发信号之前先按内核快照里的父子关系排成「父先子后」。
 */
export function signalMembers(session: number, signal: number, excludedPid: number): boolean {
  const members = membersSnapshot(session, excludedPid)
  if (members === null) {
    return false
  }
  const ordered = parentsBeforeChildren(members)
  if (ordered === null) {
    return false
  }
  let delivered = false
  for (const member of ordered) {
    const info = process.platform === 'linux' ? linuxProcessInfo(member.pid) : macProcessInfo(member.pid)
    if (info === null || info.session !== session || info.identity !== member.identity) {
      continue
    }
    if (libc().kill(member.pid, signal) === 0) {
      delivered = true
    }
  }
  return delivered
}

/**
 * 收敛整个会话：先温和后强杀，并确认除 `excludedPid` 外没有活着的成员。
 *
 * <p>返回是否已经证明收敛；不可判定一律返回 `false`，绝不谎称已收敛。
 */
export async function convergeSession(session: number, excludedPid: number, graceMillis: number): Promise<boolean> {
  signalMembers(session, SIGTERM, excludedPid)
  if (await sessionGone(session, excludedPid, graceMillis)) {
    return true
  }
  signalMembers(session, SIGKILL, excludedPid)
  return sessionGone(session, excludedPid, graceMillis)
}

/**
 * 让本进程成为子进程收养者（Linux `PR_SET_CHILD_SUBREAPER`）。
 *
 * <p>Linux 上 `prctl` 失败会让「未登记且故意 `setsid` 的后代也能被核实」失真，因此**失败即抛出**。macOS 没有这个原语，返回
 * `false`：会话生命周期仍然可用，只是未登记且故意脱离的后代不在收养集合里。
 */
export function becomeChildSubreaper(): boolean {
  if (process.platform !== 'linux') {
    return false
  }
  if (prctl()(PR_SET_CHILD_SUBREAPER, 1, 0, 0, 0) !== 0) {
    throw new Error(`cannot become a child subreaper: prctl failed with errno ${koffi.errno()}`)
  }
  return true
}

/**
 * 回收已经被收养的孤儿后代（Linux）。
 *
 * <p>调用方必须等用户命令自己的退出码已经被 Node 回收之后再调用：`waitpid(-1)` 会回收任意子进程，过早调用会偷走命令的退出码。
 */
export function reapOrphans(): void {
  if (process.platform !== 'linux') {
    return
  }
  while (libc().waitpid(-1, null, WNOHANG) > 0) {
    // 循环回收所有已退出但还挂在进程表上的被收养后代。
  }
}

/** 后台回收被收养的孤儿，一直持续到本进程结束。 */
export function startOrphanReaper(intervalMillis = 5): () => void {
  if (process.platform !== 'linux') {
    return () => {}
  }
  const timer = setInterval(() => reapOrphans(), intervalMillis)
  timer.unref()
  return () => clearInterval(timer)
}

/** 被收养的直接子进程：pid、它此刻所属的会话，以及用于核验的启动身份。 */
export interface DirectChild {
  pid: number
  session: number
  identity: string
}

/**
 * 列出 `ownerPid` 当前活着的直接子进程（Linux 读 `/proc` 的 ppid）。
 *
 * <p>子进程收养者是这套机制的全部依据：helper 是子进程收养者，因此用户命令（以及它的普通后代）的父进程一旦退出，孤儿会被内核
 * 重新挂到 helper 名下——即使它已经 `setsid` 建立了自己的会话。父 daemon 成为收养者时同理，可在 helper 崩溃后接管这些孤儿。
 *
 * <p>没有收养机制的平台返回空列表：macOS 的孤儿被重新挂到 `launchd`，内核里不存在「属于本范围」的可核实集合。返回 `null`
 * 表示不可判定（扫描失败，或某个已确认是直接子进程的活进程读不到身份），调用方必须当成「还没有收敛」。
 */
export function directChildren(ownerPid: number, excludedPids: readonly number[]): DirectChild[] | null {
  if (process.platform !== 'linux') {
    return []
  }
  let entries: string[]
  try {
    entries = readdirSync('/proc')
  } catch {
    return null
  }
  const excluded = new Set(excludedPids)
  const children: DirectChild[] = []
  let undecidable = false
  for (const entry of entries) {
    const pid = parsePid(entry)
    if (pid <= 0 || pid === ownerPid || excluded.has(pid)) {
      continue
    }
    const info = linuxProcessInfo(pid)
    if (info === null) {
      undecidable = undecidable || exists(`/proc/${pid}`)
      continue
    }
    if (info.parentPid !== ownerPid || !info.live) {
      continue
    }
    children.push({ pid, session: info.session, identity: info.identity })
  }
  return undecidable ? null : children
}

/**
 * 收敛「被收养的直接子进程」所在的会话，用于覆盖故意 `setsid`、脱离本会话的后代。
 *
 * <p>这些后代本会话枚举看不到；只有当它的父进程退出、被内核重新挂到 `ownerPid` 名下之后才可核实。因此这里反复「列出直接子
 * 进程 -> 收敛它所属的会话」，直到不再有活的直接子进程为止，并只对刚确认为自己子进程的 pid 回收僵尸。
 *
 * <p>非 Linux 平台返回 `true`：没有收养集合可核实（见 `directChildren`），这也意味着未登记的后代无法在这里被证明收敛——
 * 这一限制由 `processScopeCapabilities().orphanAdoption` 明确暴露，不伪装成「已收敛」。
 */
export async function convergeAdoptedChildren(ownerPid: number, graceMillis: number, excludedPids: readonly number[]): Promise<boolean> {
  if (process.platform !== 'linux') {
    return true
  }
  const deadline = Date.now() + graceMillis * 4
  for (;;) {
    const children = directChildren(ownerPid, excludedPids)
    if (children === null) {
      return false
    }
    if (children.length === 0) {
      return true
    }
    for (const child of children) {
      await convergeSession(child.session, ownerPid, graceMillis)
      reapSpecific(child.pid)
    }
    if (Date.now() >= deadline) {
      const remaining = directChildren(ownerPid, excludedPids)
      return remaining !== null && remaining.length === 0
    }
  }
}

/** 回收一个**已经确认是自己子进程**的 pid 的僵尸；不是自己的孩子时内核返回 ECHILD，无副作用。 */
export function reapSpecific(pid: number): void {
  if (process.platform !== 'linux') {
    return
  }
  libc().waitpid(pid, null, WNOHANG)
}

/**
 * `childPid` 是否是 `ancestorPid` 的后代（含相等）。
 *
 * <p>登记「故意 `setsid` 的后代」之前必须走这条父子链核实：只有真的在本范围下的进程才允许把它的会话纳入收敛集合。读不到
 * 父子链时返回 `null`（不可判定），绝不默认「是」。
 */
export function isDescendantOf(childPid: number, ancestorPid: number): boolean | null {
  const seen = new Set<number>()
  let current = childPid
  for (let depth = 0; depth < 64; depth += 1) {
    if (current === ancestorPid) {
      return true
    }
    if (current <= 1 || seen.has(current)) {
      return current === ancestorPid
    }
    seen.add(current)
    const info = process.platform === 'linux' ? linuxProcessInfo(current) : process.platform === 'darwin' ? macProcessInfo(current) : null
    if (info === null) {
      return null
    }
    current = info.parentPid
  }
  return null
}

async function sessionGone(session: number, excludedPid: number, budgetMillis: number): Promise<boolean> {
  const deadline = Date.now() + budgetMillis
  while (hasLiveMember(session, excludedPid)) {
    if (Date.now() >= deadline) {
      return false
    }
    await delay(5)
  }
  return true
}

/**
 * 把成员快照重排成「父进程先于子进程」的顺序。
 *
 * <p>这是纯粹的拓扑排序；顺序完全由父子拓扑决定，绝不按 PID 数值排。PID 重复或父指针成环时整份快照无法消歧，返回 `null`。
 */
export function parentsBeforeChildren(members: SessionMember[]): SessionMember[] | null {
  if (members.length === 0) {
    return []
  }
  const pids = new Set<number>()
  for (const member of members) {
    if (pids.has(member.pid)) {
      return null
    }
    pids.add(member.pid)
  }
  const children = new Map<number, SessionMember[]>()
  const roots: SessionMember[] = []
  for (const member of members) {
    if (pids.has(member.parentPid)) {
      const bucket = children.get(member.parentPid)
      if (bucket === undefined) {
        children.set(member.parentPid, [member])
      } else {
        bucket.push(member)
      }
    } else {
      roots.push(member)
    }
  }
  const ordered: SessionMember[] = []
  while (roots.length > 0) {
    const member = roots.shift()
    if (member === undefined) {
      break
    }
    ordered.push(member)
    const descendants = children.get(member.pid)
    if (descendants !== undefined) {
      roots.push(...descendants)
    }
  }
  return ordered.length === members.length ? ordered : null
}

/** Linux：用 `/proc` 枚举会话成员；扫描失败或身份不可判定返回 `null`。 */
function linuxMembers(session: number, excludedPid: number): SessionMember[] | null {
  let entries: string[]
  try {
    entries = readdirSync('/proc')
  } catch {
    return null
  }
  const members: SessionMember[] = []
  let undecidable = false
  for (const entry of entries) {
    const pid = parsePid(entry)
    if (pid <= 0 || pid === excludedPid) {
      continue
    }
    const info = linuxProcessInfo(pid)
    if (info === null) {
      // 读不到 stat：进程可能已经消失；若它仍存在，这次枚举就无法确认「没有成员」。
      undecidable = undecidable || exists(`/proc/${pid}`)
      continue
    }
    if (info.session !== session || !info.live) {
      continue
    }
    members.push({ pid, identity: info.identity, parentPid: info.parentPid })
  }
  return undecidable ? null : members
}

/** Linux：读取 `/proc/<pid>/stat` 的会话、父进程、状态与启动时刻。 */
function linuxProcessInfo(pid: number): ProcessInfo | null {
  let content: string
  try {
    content = readFileSync(`/proc/${pid}/stat`, 'utf8')
  } catch {
    return null
  }
  // comm 可能包含空格与括号：字段解析必须从最后一个 ')' 之后开始。
  const closing = content.lastIndexOf(')')
  if (closing < 0) {
    return null
  }
  const fields = content.slice(closing + 2).split(' ')
  const state = fields[0]
  const parentPid = Number(fields[1])
  const session = Number(fields[3])
  const threads = Number(fields[17])
  const start = fields[19]
  if (state === undefined || start === undefined || !Number.isFinite(parentPid) || !Number.isFinite(session)
      || !Number.isSafeInteger(threads) || threads < 1) {
    return null
  }
  return {
    session,
    parentPid,
    // stat 的状态属于线程组首线程：它先变为 Z 时，其余线程和未被收养的子进程仍可能存活。
    // 只有线程组也只剩这一个死亡线程时，才能把进程当作已经退出。
    live: threads > 1 || (state !== 'Z' && state !== 'X' && state !== 'x'),
    identity: start,
  }
}

/** macOS：用 libproc 枚举会话成员。 */
function macMembers(session: number, excludedPid: number): SessionMember[] | null {
  const pids = macProcessIds()
  if (pids === null) {
    return null
  }
  const members: SessionMember[] = []
  let undecidable = false
  for (const pid of pids) {
    if (pid <= 0 || pid === excludedPid) {
      continue
    }
    const sessionId = libc().getsid(pid)
    if (sessionId < 0) {
      // ESRCH 已由内核证明没有活成员；其它错误码表示这次判定不可靠。
      undecidable = undecidable || koffi.errno() !== ESRCH
      continue
    }
    if ((sessionId >>> 0) !== session) {
      continue
    }
    const info = macProcessInfo(pid)
    if (info === null) {
      // 进程仍可能存在（`kill(pid, 0)` 成功）却读不到身份：不能把它过滤掉后谎报「没有成员」。
      undecidable = undecidable || libc().kill(pid, 0) === 0
      continue
    }
    if (!info.live) {
      continue
    }
    members.push({ pid, identity: info.identity, parentPid: info.parentPid })
  }
  return undecidable ? null : members
}

/** macOS：读取进程的会话状态与启动身份（僵尸不算活）。 */
function macProcessInfo(pid: number): ProcessInfo | null {
  const info = macBsdInfo(pid)
  const session = libc().getsid(pid)
  if (info === null || session < 0) {
    return null
  }
  return {
    session: session >>> 0,
    parentPid: info.ppid,
    live: info.status !== MAC_STATUS_ZOMBIE,
    identity: `${info.startSec}.${info.startUsec}`,
  }
}

interface MacBsdInfo {
  status: number
  ppid: number
  startSec: number
  startUsec: number
}

let macBsdInfoType: ReturnType<typeof koffi.struct> | null = null

/**
 * macOS：`proc_pidinfo(PROC_PIDTBSDINFO)` 读取进程状态与启动时刻。
 *
 * <p>结构体布局由 Koffi 按平台 ABI 处理；这里只声明需要的字段并保持它们的相对顺序与官方 `struct proc_bsdinfo` 一致。
 */
function macBsdInfo(pid: number): MacBsdInfo | null {
  if (macBsdInfoType === null) {
    macBsdInfoType = koffi.struct('proc_bsdinfo', {
      pbi_flags: 'uint32_t',
      pbi_status: 'uint32_t',
      pbi_xstatus: 'uint32_t',
      pbi_pid: 'uint32_t',
      pbi_ppid: 'uint32_t',
      pbi_uid: 'uint32_t',
      pbi_gid: 'uint32_t',
      pbi_ruid: 'uint32_t',
      pbi_rgid: 'uint32_t',
      pbi_svuid: 'uint32_t',
      pbi_svgid: 'uint32_t',
      rfu_1: 'uint32_t',
      pbi_comm: 'char[16]',
      pbi_name: 'char[32]',
      pbi_nfiles: 'uint32_t',
      pbi_pgid: 'uint32_t',
      pbi_pjobc: 'uint32_t',
      e_tdev: 'uint32_t',
      e_tpgid: 'int32_t',
      pbi_nice: 'int32_t',
      pbi_start_tvsec: 'uint64_t',
      pbi_start_tvusec: 'uint64_t',
    })
  }
  const buffer = Buffer.alloc(macBsdInfoType.size)
  const written = libproc().proc_pidinfo(pid, PROC_PIDTBSDINFO, 0, buffer, buffer.length)
  if (written < buffer.length) {
    return null
  }
  const parsed = koffi.decode(buffer, macBsdInfoType) as {
    pbi_status: number
    pbi_ppid: number
    pbi_start_tvsec: number | bigint
    pbi_start_tvusec: number | bigint
  }
  return {
    status: parsed.pbi_status,
    ppid: parsed.pbi_ppid,
    startSec: Number(parsed.pbi_start_tvsec),
    startUsec: Number(parsed.pbi_start_tvusec),
  }
}

/**
 * macOS：全部 pid 的完整枚举：先问所需字节数，再按需扩大缓冲，直到写入字节数小于容量。
 *
 * <p>`proc_listpids` 的参数和返回值都以字节计；缓冲填满表示可能被截断，不能认定枚举完整。
 */
function macProcessIds(): number[] | null {
  let capacity = 4096
  while (capacity <= MAC_PID_LIMIT) {
    const buffer = Buffer.alloc(capacity)
    const written = libproc().proc_listpids(PROC_ALL_PIDS, 0, buffer, capacity)
    if (written <= 0) {
      return null
    }
    if (written < capacity) {
      const count = written >> 2
      const result: number[] = []
      for (let index = 0; index < count; index += 1) {
        result.push(buffer.readInt32LE(index * 4))
      }
      return result
    }
    capacity *= 2
  }
  return null
}

function parsePid(value: string): number {
  if (!/^\d+$/.test(value)) {
    return -1
  }
  const pid = Number(value)
  return Number.isSafeInteger(pid) ? pid : -1
}

function exists(path: string): boolean {
  try {
    statSync(path)
    return true
  } catch {
    return false
  }
}

function delay(millis: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, millis))
}
