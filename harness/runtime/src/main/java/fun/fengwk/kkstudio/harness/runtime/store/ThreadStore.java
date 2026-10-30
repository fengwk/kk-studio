package fun.fengwk.kkstudio.harness.runtime.store;

import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Thread 聚合的事务内视图。
 *
 * <p>Thread 行携带当前 projection；更新必须已锁定且通过 {@link ThreadState#validateTransition}。批量删除同时清理其子事实。
 */
public interface ThreadStore {

  /** 插入新 Thread；head Entry 必须属于同一 Session。 */
  void insert(ThreadState thread);

  /** 按 id 读取 Thread；不存在返回 {@link Optional#empty()}。 */
  Optional<ThreadState> find(UUID id);

  /** 锁定 Thread 行（FOR UPDATE）；不存在返回空且不产生锁。 */
  Optional<ThreadState> lock(UUID id);

  /** 更新 Thread current state；要求行已在本事务锁定。 */
  void update(ThreadState thread);

  /** 按 {@code (createdAt, id)} 升序读取 Session 全部 Thread。 */
  List<ThreadState> listBySession(UUID sessionId);

  /** 按 UUID 升序读取直接子 Thread。 */
  List<ThreadState> listChildren(UUID parentThreadId);

  /** 由不可变 parent 链派生 head-to-root 祖先（包含自身）。 */
  List<UUID> findAncestorChain(UUID threadId);

  /** 读取执行根为 rootThreadId 的全部 Thread（含根自身）。 */
  List<ThreadState> listByRoot(UUID rootThreadId);

  /** 在 Join 准入锁之后、任何 Session/Thread/Work 锁之前获取执行树锁。 */
  void lockTree(UUID rootThreadId);

  /**
   * 批量删除 Thread 及其全部 Command / ModelInvocation / ToolInvocation / Work / Join / Stop 回执；要求所有 Thread
   * 已按 UUID 升序锁定。
   */
  int delete(List<UUID> threadIds);
}
