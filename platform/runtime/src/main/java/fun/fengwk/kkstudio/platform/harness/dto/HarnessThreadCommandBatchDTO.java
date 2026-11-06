package fun.fengwk.kkstudio.platform.harness.dto;

import lombok.Data;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * 既有 Thread 的通用命令写请求 DTO。
 *
 * <p>入口携带目标 Thread 的并发游标与有序命令：服务端从 path 的 {@code threadId} 解析 Session， 身份认证、资源授权与附件引用校验照常完成。
 *
 * <p>单条纯 {@code USER_MESSAGE} 的发送批次必须同时省略 {@code expectedHeadEntryId} 与 {@code
 * expectedNextCommandSequence}（携带即 400），由服务端按当前邮箱尾部入队；包含 SET_* 或 GOAL 的批次必须携带两者作为精确 CAS 游标。{@code
 * commands} 元素沿用 {@link HarnessCommandCreateDTO} 的严格 wire 格式。
 */
@Data
public class HarnessThreadCommandBatchDTO {

  /** 期望的当前 head Entry：canonical UUID string；纯 USER_MESSAGE 发送批次必须省略，其余必须携带。 */
  private @Nullable String expectedHeadEntryId;

  /** 期望的下一条 command sequence：strict positive decimal string；纯 USER_MESSAGE 发送批次必须省略，其余必须携带。 */
  private @Nullable String expectedNextCommandSequence;

  /** 非空、按产品顺序排列的 SET_* + USER_MESSAGE / GOAL 命令列表。 */
  private List<HarnessCommandCreateDTO> commands = List.of();
}
