package fun.fengwk.kkstudio.platform.environment.cluster;

import java.util.UUID;

/** 节点间内部 REST 的平台路径契约：realtime oversize payload 回拉与 Environment runtime 单跳。 */
public final class ClusterInternalPaths {

  /** 内部 realtime oversize payload 回拉前缀。 */
  public static final String INTERNAL_REALTIME_PREFIX = "/internal/harness-realtime";

  /** Environment owner 单跳的内部 runtime/update 前缀。 */
  public static final String INTERNAL_ENVIRONMENT_PREFIX = "/internal/environments";

  private ClusterInternalPaths() {}

  /** origin 短期 buffer 的 payload 回拉路径；只接受 canonical UUID。 */
  public static String realtimePayloadPath(UUID payloadId) {
    return INTERNAL_REALTIME_PREFIX + "/" + payloadId;
  }

  /** owner 返回当前运行快照的内部路径。 */
  public static String environmentRuntimePath(UUID environmentId) {
    return INTERNAL_ENVIRONMENT_PREFIX + "/" + environmentId + "/runtime";
  }

  /** owner 发起受管更新并返回当前运行快照的内部路径。 */
  public static String environmentUpdatePath(UUID environmentId) {
    return INTERNAL_ENVIRONMENT_PREFIX + "/" + environmentId + "/update";
  }
}
