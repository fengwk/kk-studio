package fun.fengwk.kkstudio.share.ai.runtime;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonSetter;
import lombok.Data;

/** 显式放弃一个具体执行代次退出确认的请求。 */
@Data
public class HarnessExecutionAbandonDTO {

  /** 必填 exact version CAS 游标：strict non-negative decimal string，读取自最新 Thread 快照。 */
  private String expectedVersion;

  @JsonSetter("expectedVersion")
  public void setExpectedVersion(Object value) {
    this.expectedVersion = HarnessRuntimeDtoSupport.requireJsonString(value, "expectedVersion");
  }

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new HarnessRequestFormatException("unknown execution abandon field: " + name);
  }
}
