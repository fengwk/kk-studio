package fun.fengwk.kkstudio.platform.harness.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import org.jspecify.annotations.Nullable;

import fun.fengwk.kkstudio.harness.api.thread.ThreadExecutionControl;
import fun.fengwk.kkstudio.harness.api.thread.ThreadRuntimeStatus;
import fun.fengwk.kkstudio.platform.catalog.dto.HarnessExecutionSelectionDTO;

import java.time.Instant;

/** Runtime Session 下的 Thread 摘要。 */
@Data
public class HarnessThreadSummaryDTO {

  /** Thread 主键：canonical UUID string。 */
  private String threadId;

  /** 执行父 Thread 的 canonical UUID string；根 Thread 显式为 null。 */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private @Nullable String parentThreadId;

  /** Thread 的必需非空展示名称（服务端权威值）；主展示文本，绝不回退为 id。 */
  private String name;

  /** Thread 创建时间（UTC Instant）。 */
  private Instant createdAt;

  /** Thread 最后更新时间（UTC Instant）。 */
  private Instant updatedAt;

  /** 从当前 Thread 快照派生的状态：{@code RUNNING / WAITING / IDLE}。 */
  private ThreadRuntimeStatus status;

  /** 持久执行控制：{@code RUNNABLE} 或 {@code STOPPED}。 */
  private ThreadExecutionControl executionControl;

  /** 当前 head branch 的完整 Execution selection。 */
  private HarnessExecutionSelectionDTO execution;

  /** 当前 head 上最近的用户可读消息预览；没有消息时显式为 null。 */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private @Nullable String headMessagePreview;
}
