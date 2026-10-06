package fun.fengwk.kkstudio.share.ai.runtime;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.time.Instant;

/** Session Entry 查询投影；实体 id 均为 canonical UUID string。 */
@Data
public class HarnessSessionEntryDTO {
  /** Entry 主键：canonical UUID string。 */
  private String entryId;

  /** 所属 Session 主键：canonical UUID string。 */
  private String sessionId;

  /**
   * 父 Entry 主键：canonical UUID string；仅 ROOT Entry 为 null（{@code @JsonInclude(ALWAYS)} 保证 null 也输出）。
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

  /**
   * 读取时按当前目录价现算的费用投影；绝不属于 durable 历史（不进入 payloadJson / assistantMetadata）。
   *
   * <p>只有记录了真实 model 用量、且祖先 TURN_START 的模型选择能在当前 catalog 命中价格的 ASSISTANT 结果才有值；其余情况 （非模型输出、模型已从
   * catalog 删除、价格不可得）显式输出 null，绝不伪装成 0 费用。{@code @JsonInclude(ALWAYS)} 让「未计价」 与「计价为 0」在 wire 上可区分。
   */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private HarnessUsageCostDTO usageCost;
}
