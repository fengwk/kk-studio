package fun.fengwk.kkstudio.share.ai.skill;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;

/**
 * {@code POST /api/ai/catalog/skill-packages/{packageName}/check} 请求体：只观察 branch HEAD。
 *
 * <p>检查只更新 observedHeadCommit / headCheckedAt / headCheckError（失败时保留 current commit 与
 * manifest），并在实际事实变化时推进 Package version，因此必须携带客户端 Card 已展示的 expectedVersion。
 */
@Data
public class SkillPackageCheckDTO {

  /** 客户端读到的当前 Package version（十进制字符串）；陈旧 Card 返回 version conflict。 */
  private String expectedVersion;

  @JsonAnySetter
  public void rejectUnknownField(String fieldName, Object ignoredValue) {
    throw new IllegalArgumentException("unknown skill package check field: " + fieldName);
  }
}
