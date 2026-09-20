package fun.fengwk.kkstudio.share.ai.skill;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;

/**
 * {@code POST /api/ai/catalog/skill-packages/{packageName}/update} 请求体：发布一个 exact commit。
 *
 * <p>{@code targetCommit} 必须是 Card 已展示的完整 commit：即使 branch 随后又前进，也不会暗中切换到用户未确认的 HEAD。服务端要求它等于该
 * {@code expectedVersion} 快照中的 observedHeadCommit，并在一次 CAS 中原子替换 current commit 与 manifest。
 */
@Data
public class SkillPackagePublishDTO {

  /** 客户端读到的当前 Package version（十进制字符串）；陈旧 Card 返回 version conflict。 */
  private String expectedVersion;

  /** 要发布的 exact Git object id（40 或 64 位小写 hex）。 */
  private String targetCommit;

  @JsonAnySetter
  public void rejectUnknownField(String fieldName, Object ignoredValue) {
    throw new IllegalArgumentException("unknown skill package publish field: " + fieldName);
  }
}
