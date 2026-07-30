package fun.fengwk.kkstudio.core.ai.runtime.thread.reconcile;

import lombok.Data;

/** {@code loadPath} 的 Session Entry 投影；递归从 head 查到 root，最终按 root → head 返回。 */
@Data
public class SessionEntryPathRow {
  private long id;
  private Long parentEntryId;
  private String entryType;
  private String payloadJson;
}
