package fun.fengwk.kkstudio.share.ai.runtime;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.time.Instant;

/** Session Entry 查询投影；id 均为 strict positive decimal string。 */
@Data
public class HarnessSessionEntryDTO {
  /** Entry 主键：strict positive decimal string（{@code [1-9][0-9]*}，底层 bigint）。 */
  private String entryId;

  /** 所属 Session 主键：strict positive decimal string。 */
  private String sessionId;

  /**
   * 父 Entry 主键：strict positive decimal string；仅 ROOT Entry 为 null（{@code @JsonInclude(ALWAYS)} 保证
   * null 也输出）。
   */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String parentEntryId;

  /**
   * Entry 语义类型，取 {@code EntryType}
   * 枚举名：ROOT/TURN_START/MESSAGE/CUSTOM/CUSTOM_MESSAGE/ASSISTANT_ERROR/ASSISTANT_ABORTED/TURN_END。
   */
  private String entryType;

  /** canonical JSON（HistoryEntryPayloadJsonCodec 编码），内容形态随 entryType 变化。 */
  private String payloadJson;

  /** Entry 创建时间（UTC Instant）。 */
  private Instant createTime;
}
