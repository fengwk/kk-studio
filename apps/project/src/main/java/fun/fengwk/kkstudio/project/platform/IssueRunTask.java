package fun.fengwk.kkstudio.project.platform;

import fun.fengwk.kkstudio.harness.runtime.SpawnRequest;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NewThreadCommand;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Issue Run 提交给宿主的执行任务：Run id 同时是回执票据身份。
 *
 * <p>首次接受时携带预分配的 {@code sessionId} 与 {@code threadId} 创建 Issue 的稳定执行根，{@code continuation} 为空；已有稳定
 * Thread 时 {@code sessionId} 为空，{@code continuation} 给出冻结的 head 与下一条命令序号。{@code commands} 是首批输入（可含
 * SET_* 前缀，恰以一条任务输入结尾），{@code requestHash} 是该任务输入的指纹。
 */
public record IssueRunTask(
    UUID runId,
    UUID threadId,
    UUID sessionId,
    BranchSettings settings,
    boolean yoloEnabled,
    SpawnRequest.Continuation continuation,
    List<NewThreadCommand> commands,
    String requestHash) {

  public IssueRunTask {
    Objects.requireNonNull(runId, "runId");
    Objects.requireNonNull(threadId, "threadId");
    Objects.requireNonNull(settings, "settings");
    commands = List.copyOf(Objects.requireNonNull(commands, "commands"));
    Objects.requireNonNull(requestHash, "requestHash");
  }
}
