package fun.fengwk.kkstudio.share.ai.runtime;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/**
 * Thread 的 YOLO 策略查询投影。
 *
 * <p>{@code mode} 为 {@code ENABLE} / {@code DISABLE} / {@code FOLLOW}。执行根为 {@code ENABLE} 或 {@code
 * DISABLE} 且 {@code rootThreadId} 为 null；子代理恒为 {@code FOLLOW} 并携带真实执行根的 canonical UUID
 * string。该字段取代旧 的 {@code yoloEnabled} boolean，不对子代理暴露独立的 effective 开关。
 */
@Data
public class HarnessThreadYoloPolicyDTO {

  /** 持久 YOLO 策略模式：{@code ENABLE} / {@code DISABLE} / {@code FOLLOW}。 */
  private String mode;

  /** {@code FOLLOW} 的真实执行根（canonical UUID string）；执行根为 null。 */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String rootThreadId;
}
