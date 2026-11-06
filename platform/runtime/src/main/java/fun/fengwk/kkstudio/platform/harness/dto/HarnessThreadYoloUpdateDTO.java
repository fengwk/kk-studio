package fun.fengwk.kkstudio.platform.harness.dto;

import lombok.Data;

/** Thread YOLO 直接控制请求。它只携带目标策略，不携带 Thread version CAS cursor。 */
@Data
public class HarnessThreadYoloUpdateDTO {

  /** 必填目标 YOLO 策略。 */
  private Boolean yoloEnabled;
}
