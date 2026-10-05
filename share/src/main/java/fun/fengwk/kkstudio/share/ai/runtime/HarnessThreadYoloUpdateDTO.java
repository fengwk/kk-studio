package fun.fengwk.kkstudio.share.ai.runtime;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonSetter;
import lombok.Data;

/** Thread YOLO 直接控制请求。它只携带目标策略，不携带 Thread version CAS cursor。 */
@Data
public class HarnessThreadYoloUpdateDTO {

  /** 必填目标 YOLO 策略。 */
  private Boolean yoloEnabled;

  @JsonSetter("yoloEnabled")
  public void setYoloEnabled(Object value) {
    if (!(value instanceof Boolean enabled)) {
      throw new HarnessRequestFormatException("yoloEnabled must be a JSON boolean");
    }
    this.yoloEnabled = enabled;
  }

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new HarnessRequestFormatException("unknown harness thread yolo field: " + name);
  }
}
