package fun.fengwk.kkstudio.web.controller;

import fun.fengwk.convention4j.api.result.Result;
import fun.fengwk.convention4j.common.result.Results;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import fun.fengwk.kkstudio.core.ai.chat.service.ChatThreadCommandService;
import fun.fengwk.kkstudio.core.ai.runtime.task.SystemPromptPreviewService;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeConflictException;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeNotFoundException;
import fun.fengwk.kkstudio.harness.runtime.StopResult;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandBatch;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessSessionEntryDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessSystemPromptPreviewDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadCommandBatchDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadCommandDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadHeadUpdateDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadSnapshotDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadStopDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadStopResultDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessToolApprovalDTO;
import fun.fengwk.kkstudio.share.ai.runtime.ToolInvocationDTO;
import fun.fengwk.kkstudio.web.runtime.HarnessRuntimeWebMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 基于根 {@link HarnessRuntime} 控制/查询平面的 Thread API。
 *
 * <p>统一返回 {@link Result}，HTTP 状态由 convention4j {@code ResultResponseBodyAdvice} 按 {@code
 * result.status} 对齐；命令入队使用 {@link Results#accepted}（202）。类型化 runtime 拒绝在此统一翻译：未找到 {@literal ->}
 * 404、业务冲突 {@literal ->} 409、非法请求/DTO {@literal ->} 400。
 */
@RestController
@RequestMapping("/api/ai/runtime/threads")
public class StudioHarnessThreadController {
  private final HarnessRuntime runtime;
  private final ChatThreadCommandService chatThreadCommandService;
  private final SystemPromptPreviewService systemPromptPreviewService;

  /** 创建 Thread API Controller。 */
  public StudioHarnessThreadController(
      HarnessRuntime runtime,
      ChatThreadCommandService chatThreadCommandService,
      SystemPromptPreviewService systemPromptPreviewService) {
    this.runtime = Objects.requireNonNull(runtime, "runtime");
    this.chatThreadCommandService =
        Objects.requireNonNull(chatThreadCommandService, "chatThreadCommandService");
    this.systemPromptPreviewService =
        Objects.requireNonNull(systemPromptPreviewService, "systemPromptPreviewService");
  }

  /** 查询一个一致性的 Thread 快照（单事务）。 */
  @GetMapping("/{threadId}/snapshot")
  public Result<HarnessThreadSnapshotDTO> getSnapshot(@PathVariable String threadId) {
    return Results.ok(
        withRuntimeTranslation(
            () -> {
              UUID id = HarnessRuntimeWebMapper.parseUuid(threadId, "threadId");
              return HarnessRuntimeWebMapper.toSnapshotDto(runtime.getThreadSnapshot(id));
            }));
  }

  /**
   * 查询 Thread 所属 Session 的全部不可变 Entry（含非当前 head 路径上的历史分支）。
   *
   * <p>{@code GET /snapshot} 仍只返回当前 root-to-head；本接口返回 Session 全树。
   */
  @GetMapping("/{threadId}/entries")
  public Result<List<HarnessSessionEntryDTO>> getEntries(@PathVariable String threadId) {
    return Results.ok(
        withRuntimeTranslation(
            () -> {
              UUID id = HarnessRuntimeWebMapper.parseUuid(threadId, "threadId");
              List<Entry> entries = runtime.getThreadSessionEntries(id);
              List<HarnessSessionEntryDTO> mapped = new ArrayList<>(entries.size());
              for (Entry entry : entries) {
                mapped.add(HarnessRuntimeWebMapper.toEntryDto(entry));
              }
              return List.copyOf(mapped);
            }));
  }

  /** 按当前 branch 最新 Agent / Environment 现算系统提示词预览。进入 Events 与 turn 结束后由前端按需读取。 */
  @GetMapping("/{threadId}/system-prompt")
  public Result<HarnessSystemPromptPreviewDTO> getSystemPrompt(@PathVariable String threadId) {
    return Results.ok(
        withRuntimeTranslation(
            () -> {
              UUID id = HarnessRuntimeWebMapper.parseUuid(threadId, "threadId");
              HarnessSystemPromptPreviewDTO dto = new HarnessSystemPromptPreviewDTO();
              dto.setText(systemPromptPreviewService.preview(id));
              return dto;
            }));
  }

  /**
   * 将一个 typed command batch 原子入队（应用 use-case 事务：幂等 hash 重放优先，新命令消费附件后入队）；202 仅表示已接受， 不代表模型已完成。所有 7
   * 类命令由 mapper 按 discriminator 严格校验后映射为一个 {@link ThreadCommandBatch}。
   */
  @PostMapping("/{threadId}/commands")
  public Result<List<HarnessThreadCommandDTO>> enqueueCommands(
      @PathVariable String threadId, @RequestBody HarnessThreadCommandBatchDTO batchDTO) {
    List<HarnessThreadCommandDTO> dto =
        withRuntimeTranslation(
            () -> {
              ThreadCommandBatch batch = HarnessRuntimeWebMapper.toCommandBatch(threadId, batchDTO);
              List<ThreadCommand> commands = chatThreadCommandService.submitCommands(batch);
              List<HarnessThreadCommandDTO> mapped = new ArrayList<>(commands.size());
              for (ThreadCommand command : commands) {
                mapped.add(HarnessRuntimeWebMapper.toCommandDto(command));
              }
              return List.copyOf(mapped);
            });
    return Results.accepted(dto);
  }

  /** 同步重定位 Thread head cursor（revision CAS）。 */
  @PutMapping("/{threadId}/head")
  public Result<HarnessThreadDTO> updateHead(
      @PathVariable String threadId, @RequestBody HarnessThreadHeadUpdateDTO request) {
    return Results.ok(
        withRuntimeTranslation(
            () -> {
              ThreadState moved =
                  runtime.moveHead(HarnessRuntimeWebMapper.toMoveHeadCommand(threadId, request));
              return HarnessRuntimeWebMapper.toThreadDto(runtime.getThreadSnapshot(moved.id()));
            }));
  }

  /** 原子停止当前 Turn 并取消 queued Commands（stopRequestId + revision CAS 幂等）。 */
  @PostMapping("/{threadId}/stop")
  public Result<HarnessThreadStopResultDTO> stop(
      @PathVariable String threadId, @RequestBody HarnessThreadStopDTO request) {
    return Results.ok(
        withRuntimeTranslation(
            () -> {
              StopResult result =
                  runtime.stop(HarnessRuntimeWebMapper.toStopCommand(threadId, request));
              HarnessThreadStopResultDTO dto = new HarnessThreadStopResultDTO();
              dto.setStatus(result.status().name());
              dto.setThread(
                  HarnessRuntimeWebMapper.toThreadDto(
                      runtime.getThreadSnapshot(result.thread().id())));
              dto.setStoppedTurnEndEntryId(
                  result.stoppedTurnEndEntryId() == null
                      ? null
                      : result.stoppedTurnEndEntryId().toString());
              dto.setCancelledCommandCount(result.cancelledCommandCount());
              return dto;
            }));
  }

  /** 决定一次 Tool approval（decisionId 幂等；冲突 decision 409）。 */
  @PostMapping("/{threadId}/tool-invocations/{toolInvocationId}/approval")
  public Result<ToolInvocationDTO> decideApproval(
      @PathVariable String threadId,
      @PathVariable String toolInvocationId,
      @RequestBody HarnessToolApprovalDTO request) {
    return Results.ok(
        withRuntimeTranslation(
            () ->
                HarnessRuntimeWebMapper.toToolInvocationDto(
                    runtime.decideToolApproval(
                        HarnessRuntimeWebMapper.toToolApprovalCommand(
                            threadId, toolInvocationId, request)))));
  }

  /** 将 Harness Runtime 异常翻译为统一 HTTP 错误响应。 */
  private static <T> T withRuntimeTranslation(Supplier<T> operation) {
    try {
      return operation.get();
    } catch (HarnessRuntimeNotFoundException error) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, error.getMessage(), error);
    } catch (HarnessRuntimeConflictException error) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, error.getMessage(), error);
    } catch (IllegalArgumentException error) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, error.getMessage(), error);
    }
  }
}
