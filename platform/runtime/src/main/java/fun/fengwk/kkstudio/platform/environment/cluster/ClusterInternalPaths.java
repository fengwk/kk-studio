package fun.fengwk.kkstudio.platform.environment.cluster;

import java.util.UUID;

/** 节点间内部 REST 的平台路径契约：realtime oversize payload 回拉。 */
public final class ClusterInternalPaths {

  /** 内部 realtime oversize payload 回拉前缀。 */
  public static final String INTERNAL_REALTIME_PREFIX = "/internal/harness-realtime";

  private ClusterInternalPaths() {}

  /** origin 短期 buffer 的 payload 回拉路径；只接受 canonical UUID。 */
  public static String realtimePayloadPath(UUID payloadId) {
    return INTERNAL_REALTIME_PREFIX + "/" + payloadId;
  }
}
