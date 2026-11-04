package fun.fengwk.kkstudio.harness.builtin.port;

import java.util.UUID;

/** 向本节点持有的指定模型执行代次请求流式 checkpoint。 */
@FunctionalInterface
public interface ModelCheckpointRequester {

  /** 不存在活跃 owner 或 attempt 不匹配时返回 false。 */
  boolean requestCheckpoint(UUID invocationId, int attempt);
}
