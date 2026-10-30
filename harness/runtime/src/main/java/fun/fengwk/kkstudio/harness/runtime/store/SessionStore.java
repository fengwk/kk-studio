package fun.fengwk.kkstudio.harness.runtime.store;

import fun.fengwk.kkstudio.harness.runtime.session.Session;

import java.util.Optional;
import java.util.UUID;

/**
 * Session 聚合的事务内视图。
 *
 * <p>Session 是 append-only 身份记录，只有显示名称可更新。锁序见 {@link HarnessStore}：Session 必须先于 Thread。
 */
public interface SessionStore {

  /** 插入新 Session；id 冲突抛 {@link IllegalArgumentException}。 */
  void insert(Session session);

  /** 按 id 读取 Session；不存在返回 {@link Optional#empty()}。 */
  Optional<Session> find(UUID id);

  /** 更新 Session 显示名称。要求行已在本事务锁定，并通过 {@link Session#validateTransition}。 */
  void update(Session session);

  /** 锁定 Session 行（FOR KEY SHARE）；不存在返回空且不产生锁。 */
  Optional<Session> lockForKeyShare(UUID id);

  /** 锁定 Session 行（FOR UPDATE），仅用于删除 / 归属独占操作；不存在返回空且不产生锁。 */
  Optional<Session> lockForUpdate(UUID id);

  /** 删除 Session 行并返回是否删除；全部 Entry 必须先删除。 */
  boolean delete(UUID sessionId);
}
