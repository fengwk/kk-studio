package fun.fengwk.kkstudio.share.ai.runtime;

import lombok.Data;

import java.util.List;

/**
 * Thread mailbox 原子入队请求。
 *
 * <p>{@code expectedHeadEntryId} 和 {@code expectedNextCommandSequence} 是从最新 {@link
 * HarnessThreadDTO} 读取的精确 CAS 游标；{@code threadId} 来自请求路径。该批次特意不设置独立 identity。
 */
@Data
public class HarnessThreadCommandBatchDTO {
  /**
   * 必填 CAS 游标：strict positive decimal string，须等于最新 {@link HarnessThreadDTO#headEntryId}；不匹配返回 409。
   */
  private String expectedHeadEntryId;

  /**
   * 必填 CAS 游标：strict positive decimal string，须等于最新 {@link HarnessThreadDTO#nextCommandSequence}。
   */
  private String expectedNextCommandSequence;

  /** 必填非空命令列表（元素非 null）；默认不可变空列表，批内无独立 batch identity。 */
  private List<HarnessThreadCommandCreateDTO> commands = List.of();
}
