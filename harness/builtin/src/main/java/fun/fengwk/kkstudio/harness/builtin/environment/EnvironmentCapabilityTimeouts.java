package fun.fengwk.kkstudio.harness.builtin.environment;

import com.fasterxml.jackson.databind.JsonNode;

import fun.fengwk.kkstudio.harness.common.json.JsonValues;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolCall;

import java.time.Duration;
import java.util.Objects;

/**
 * 环境能力超时解析工具。
 *
 * <p>提供 arguments 级显式超时解析：携带显式 {@code timeout_seconds} 时严格使用该值，缺省时使用 capability 默认超时。非正数显式值抛出
 * {@link IllegalArgumentException}。
 */
final class EnvironmentCapabilityTimeouts {

  /** Environment capability arguments 中的显式超时字段；它是该调用唯一被识别的显式超时来源。 */
  static final String TIMEOUT_SECONDS_ARGUMENT = "timeout_seconds";

  private EnvironmentCapabilityTimeouts() {}

  /**
   * 解析工具调用的有效超时。
   *
   * @param capability 环境能力描述符，非空
   * @param call 工具调用，非空
   * @return 解析后的有效超时时间，非空
   * @throws IllegalArgumentException 当 {@code timeout_seconds} 为非正数时抛出
   */
  static Duration resolve(EnvironmentCapabilityDescriptor capability, ToolCall call) {
    Objects.requireNonNull(capability, "capability");
    Objects.requireNonNull(call, "call");
    JsonNode explicit = JsonValues.readTree(call.argumentsJson()).get(TIMEOUT_SECONDS_ARGUMENT);
    if (explicit == null || explicit.isNull()) {
      return capability.defaultTimeout();
    }
    long seconds = explicit.longValue();
    if (seconds <= 0) {
      throw new IllegalArgumentException("timeout_seconds must be positive: " + seconds);
    }
    return Duration.ofSeconds(seconds);
  }
}
