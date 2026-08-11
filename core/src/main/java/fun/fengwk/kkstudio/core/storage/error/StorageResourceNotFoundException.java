package fun.fengwk.kkstudio.core.storage.error;

/**
 * 存储资源不存在：上传或 blob 未找到、或 blob 已进入终态 DELETING。
 *
 * <p>Web 层映射为 404（重复删除/重复 complete 等重试场景下对调用方幂等）。
 *
 * @author fengwk
 */
public class StorageResourceNotFoundException extends RuntimeException {

  public StorageResourceNotFoundException(String resource, String id) {
    super("unknown " + resource + ": " + id);
  }
}
