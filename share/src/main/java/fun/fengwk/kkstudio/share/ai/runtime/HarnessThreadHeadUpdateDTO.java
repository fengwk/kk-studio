package fun.fengwk.kkstudio.share.ai.runtime;

import lombok.Data;

/** Thread head 重定位请求；{@code expectedRevision} 是 exact revision CAS cursor。 */
@Data
public class HarnessThreadHeadUpdateDTO {
  private String targetEntryId;
  private String expectedRevision;
}
