package fun.fengwk.kkstudio.harness.daemon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.environment.daemon.DaemonProtocolException;

/** 入站控制帧的共享字段读取规则：连接握手（WELCOME）与运行时控制帧（TEMPORARY_RESOURCE_POLICY）使用同一套 fail-closed 校验，避免两处漂移。 */
final class DaemonProtocolPayloads {

  private DaemonProtocolPayloads() {}

  /** 严格读取一个正 long 字段：缺失、非整数或非正都是协议错误。 */
  static long requiredPositiveLong(ObjectNode payload, String frame, String field) {
    JsonNode value = payload.get(field);
    if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
      throw new DaemonProtocolException(frame + " must declare integer '" + field + "'");
    }
    long parsed = value.longValue();
    if (parsed <= 0) {
      throw new DaemonProtocolException(frame + " '" + field + "' must be positive");
    }
    return parsed;
  }
}
