package fun.fengwk.kkstudio.share.ai.runtime;

import lombok.Data;

/** Thread head 重定位请求；{@code expectedRevision} 是 exact revision CAS cursor。 */
@Data
public class HarnessThreadHeadUpdateDTO {
  /** 必填 head 重定位目标 Entry 主键：canonical UUID string。 */
  private String targetEntryId;

  /**
   * 必填 exact revision CAS 游标：strict non-negative decimal string（{@code 0|[1-9][0-9]*}），须等于最新
   * revision。
   */
  private String expectedRevision;
}
