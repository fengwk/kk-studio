package fun.fengwk.kkstudio.core.storage.persistence;

import java.util.List;
import java.util.UUID;

/** {@code session_blob_ref} 持久化契约：Session 与 blob 的显式引用边。 */
public interface SessionBlobRefRepository {

  /** 幂等插入引用行（{@code on conflict do nothing}）；返回是否新插入。 */
  boolean insertIfAbsent(UUID sessionId, UUID blobId);

  /** 删除引用行；返回是否删除。 */
  boolean delete(UUID sessionId, UUID blobId);

  /** 该 Session 是否已引用指定 blob。 */
  boolean exists(UUID sessionId, UUID blobId);

  /** 该 Session 引用的全部 blob id（深删除与对账用）。 */
  List<UUID> listBlobIds(UUID sessionId);
}
