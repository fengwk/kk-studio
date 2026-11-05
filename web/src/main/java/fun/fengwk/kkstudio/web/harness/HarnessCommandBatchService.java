package fun.fengwk.kkstudio.web.harness;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import fun.fengwk.kkstudio.harness.api.AcceptCommandsCommand;
import fun.fengwk.kkstudio.harness.api.AcceptCommandsTarget;
import fun.fengwk.kkstudio.harness.api.AcceptedCommands;
import fun.fengwk.kkstudio.harness.api.thread.ThreadSnapshot;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.platform.orchestration.HarnessCommandAcceptanceOrchestrator;
import fun.fengwk.kkstudio.platform.orchestration.SessionOwner;
import fun.fengwk.kkstudio.share.runtime.HarnessAcceptedCommandsDTO;
import fun.fengwk.kkstudio.web.runtime.HarnessRuntimeResponseMapper;

/**
 * 人工命令批的应用编排：分支创建与既有 Thread 续写都先过人工写入边界，再交给平台接受服务，最后回读权威快照。
 *
 * <p>owner 授权、附件物化与 Runtime 入队仍在 {@link HarnessCommandAcceptanceOrchestrator}
 * 的事务内完成；这里只编排人工控制面的前置判定与响应投影。
 */
@Service
@AllArgsConstructor
public class HarnessCommandBatchService {

  private final HarnessCommandAcceptanceOrchestrator acceptanceService;
  private final HarnessRuntime runtime;

  /** 接受 owner-aware 创建型命令批；只允许纯执行根会话参与人工 fork。 */
  public HarnessAcceptedCommandsDTO accept(SessionOwner owner, AcceptCommandsCommand command) {
    if (command.target() instanceof AcceptCommandsTarget.NewThread fork) {
      ManualThreadControlGuard.requireForkRootSession(runtime, fork.sessionId());
    } else if (command.target() instanceof AcceptCommandsTarget.NewForkedSession forkedSession) {
      ManualThreadControlGuard.requireForkRootSourceThread(runtime, forkedSession.sourceThreadId());
    }
    return toAcceptedDto(acceptanceService.accept(owner, command));
  }

  /** 接受既有 Thread 的 owner-free 续写；target 必须是执行根。 */
  public HarnessAcceptedCommandsDTO acceptOnThread(AcceptCommandsCommand command) {
    if (command.target() instanceof AcceptCommandsTarget.Thread thread) {
      ManualThreadControlGuard.requireExecutionRoot(runtime, thread.threadId());
    }
    return toAcceptedDto(acceptanceService.acceptOnThread(command));
  }

  private HarnessAcceptedCommandsDTO toAcceptedDto(AcceptedCommands accepted) {
    ThreadSnapshot currentSnapshot = runtime.getThreadSnapshot(accepted.thread().id());
    return HarnessRuntimeResponseMapper.toAcceptedCommandsDto(accepted, currentSnapshot);
  }
}
