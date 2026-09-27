package fun.fengwk.kkstudio.harness.runtime.store;

import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;

import java.util.Objects;
import java.util.UUID;

/**
 * 待处理分页的 storage 投影：等待输入的 ToolInvocation 及其 owning Thread / Session 坐标。
 *
 * <p>ToolInvocation 本身不冗余 Thread 归属，分页查询必须沿 {@code model_invocation -> thread} 连接解析；本投影把两者一起返回， 由
 * Runtime 再投影为对外的 {@code PendingInteraction}。
 */
public record PendingToolInvocationRow(ToolInvocation invocation, UUID threadId, UUID sessionId) {

  public PendingToolInvocationRow {
    Objects.requireNonNull(invocation, "invocation");
    Objects.requireNonNull(threadId, "threadId");
    Objects.requireNonNull(sessionId, "sessionId");
  }
}
