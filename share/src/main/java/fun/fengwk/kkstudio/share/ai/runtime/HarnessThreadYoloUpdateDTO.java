package fun.fengwk.kkstudio.share.ai.runtime;

import lombok.Data;

/** Thread YOLO 直接控制请求；{@code expectedVersion} 是 exact version CAS cursor。 */
@Data
public class HarnessThreadYoloUpdateDTO {
  /**
   * 必填 exact version CAS 游标：strict non-negative decimal string（{@code 0|[1-9][0-9]*}），须等于最新
   * version；与当前值相同（no-op）时不要求匹配。
   */
  private String expectedVersion;

  /** 必填目标 YOLO 策略。 */
  private Boolean yoloEnabled;
}
