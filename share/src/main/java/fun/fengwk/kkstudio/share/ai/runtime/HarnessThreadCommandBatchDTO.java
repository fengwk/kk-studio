package fun.fengwk.kkstudio.share.ai.runtime;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonSetter;
import lombok.Data;

import java.util.List;

/**
 * 既有 Thread 的通用命令写请求 DTO。
 *
 * <p>入口携带目标 Thread 的并发游标与有序命令，不再携带 owner 或 target：服务端从 path 的 {@code threadId} 解析 Session，
 * 身份认证、资源授权与附件引用校验照常完成，客户端无需伪造产品 owner。
 *
 * <p>只有单条纯 {@code USER_MESSAGE} 批次可以同时省略 {@code expectedHeadEntryId} 与 {@code
 * expectedNextCommandSequence}， 由服务端按当前邮箱尾部入队；包含 SET_*、GOAL、CUSTOM 或 NOTIFICATION 的批次仍必须携带两者作为精确
 * CAS 游标。两个字段必须 同时给出或同时省略。{@code commands} 元素沿用 {@link HarnessCommandCreateDTO} 的严格 wire 格式。
 */
@Data
public class HarnessThreadCommandBatchDTO {

  /** 期望的当前 head Entry：canonical UUID string；仅纯 USER_MESSAGE 批次可省略。 */
  private String expectedHeadEntryId;

  /** 期望的下一条 command sequence：strict positive decimal string；仅纯 USER_MESSAGE 批次可省略。 */
  private String expectedNextCommandSequence;

  /** 非空、按产品顺序排列的 SET_* + USER_MESSAGE / GOAL 命令列表。 */
  private List<HarnessCommandCreateDTO> commands = List.of();

  @JsonSetter("expectedHeadEntryId")
  public void setExpectedHeadEntryId(Object value) {
    this.expectedHeadEntryId =
        HarnessRuntimeDtoSupport.requireJsonString(value, "expectedHeadEntryId");
  }

  @JsonSetter("expectedNextCommandSequence")
  public void setExpectedNextCommandSequence(Object value) {
    this.expectedNextCommandSequence =
        HarnessRuntimeDtoSupport.requireJsonString(value, "expectedNextCommandSequence");
  }

  @JsonSetter("commands")
  public void setCommands(List<HarnessCommandCreateDTO> value) {
    this.commands = value;
  }

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new HarnessRequestFormatException("unknown thread command batch field: " + name);
  }
}
