package fun.fengwk.kkstudio.platform.harness.http;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.common.id.Uuids;
import fun.fengwk.kkstudio.common.json.StrictJson;
import fun.fengwk.kkstudio.common.number.DecimalLongs;
import fun.fengwk.kkstudio.harness.api.AcceptCommandsCommand;
import fun.fengwk.kkstudio.harness.api.AcceptCommandsTarget;
import fun.fengwk.kkstudio.harness.api.RenameSessionCommand;
import fun.fengwk.kkstudio.harness.api.RenameThreadCommand;
import fun.fengwk.kkstudio.harness.api.ToolApprovalCommand;
import fun.fengwk.kkstudio.harness.api.ToolInputSubmissionCommand;
import fun.fengwk.kkstudio.harness.api.command.GoalCommandPayload;
import fun.fengwk.kkstudio.harness.api.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.api.command.SetAgentCommandPayload;
import fun.fengwk.kkstudio.harness.api.command.SetEnvironmentCommandPayload;
import fun.fengwk.kkstudio.harness.api.command.SetExecutionCommandPayload;
import fun.fengwk.kkstudio.harness.api.command.ThreadCommandPayload;
import fun.fengwk.kkstudio.harness.api.command.ThreadCommandType;
import fun.fengwk.kkstudio.harness.api.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.harness.api.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.api.entry.ExecutionSelection;
import fun.fengwk.kkstudio.harness.api.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.api.session.AgentMessage;
import fun.fengwk.kkstudio.harness.api.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.api.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.api.session.AttachmentMessageContent;
import fun.fengwk.kkstudio.harness.api.session.ResourceMessageContent;
import fun.fengwk.kkstudio.harness.api.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.api.thread.CompactThreadCommand;
import fun.fengwk.kkstudio.harness.api.thread.SetThreadYoloCommand;
import fun.fengwk.kkstudio.harness.api.thread.StopCommand;
import fun.fengwk.kkstudio.harness.api.tool.ToolApprovalDecision;
import fun.fengwk.kkstudio.harness.codec.ThreadCommandPayloadJsonCodec;
import fun.fengwk.kkstudio.harness.model.ImageInputTier;
import fun.fengwk.kkstudio.platform.orchestration.SessionOwner;
import fun.fengwk.kkstudio.share.interaction.HarnessToolInputDTO;
import fun.fengwk.kkstudio.share.runtime.HarnessBranchSettingsDTO;
import fun.fengwk.kkstudio.share.runtime.HarnessCommandBatchDTO;
import fun.fengwk.kkstudio.share.runtime.HarnessCommandCreateDTO;
import fun.fengwk.kkstudio.share.runtime.HarnessCommandOwnerDTO;
import fun.fengwk.kkstudio.share.runtime.HarnessCommandTargetDTO;
import fun.fengwk.kkstudio.share.runtime.HarnessExecutionAbandonDTO;
import fun.fengwk.kkstudio.share.runtime.HarnessExecutionSelectionDTO;
import fun.fengwk.kkstudio.share.runtime.HarnessModelRequestDebugRequestDTO;
import fun.fengwk.kkstudio.share.runtime.HarnessModelSelectionDTO;
import fun.fengwk.kkstudio.share.runtime.HarnessNameUpdateDTO;
import fun.fengwk.kkstudio.share.runtime.HarnessThreadCommandBatchDTO;
import fun.fengwk.kkstudio.share.runtime.HarnessThreadCompactDTO;
import fun.fengwk.kkstudio.share.runtime.HarnessThreadStopDTO;
import fun.fengwk.kkstudio.share.runtime.HarnessThreadYoloUpdateDTO;
import fun.fengwk.kkstudio.share.runtime.HarnessToolApprovalDTO;
import fun.fengwk.kkstudio.share.runtime.HarnessUserMessageContentDTO;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Harness Runtime HTTP 请求的严格解析与 DTO-to-domain 映射。 */
public final class HarnessRuntimeRequestMapper {

  private HarnessRuntimeRequestMapper() {}

  /** 映射不透明身份，不信任客户端提供的展示字段。 */
  public static SessionOwner toOwner(HarnessCommandOwnerDTO dto) {
    requireNonNull(dto, "owner");
    return new SessionOwner(
        requireText(dto.getKind(), "owner.kind"), requireText(dto.getId(), "owner.id"));
  }

  /** 将唯一产品 HTTP 写请求映射为 sealed target 与有序 commands。 */
  public static AcceptCommandsCommand toAcceptCommandsCommand(HarnessCommandBatchDTO dto) {
    requireNonNull(dto, "commandBatchDTO");
    // 创建型 target 必须携带恰一条末尾 user-like 输入；纯设置批次只对既有 Thread 开放。
    List<NewThreadCommand> commands = toHttpCommands(dto.getCommands(), "commands", true);
    return new AcceptCommandsCommand(toTarget(dto.getTarget()), commands);
  }

  public static CompactThreadCommand toCompactThreadCommand(
      String threadId, HarnessThreadCompactDTO dto) {
    requireNonNull(dto, "compactDTO");
    return new CompactThreadCommand(
        Uuids.parseCanonical(threadId, "threadId"),
        DecimalLongs.parseNonNegative(dto.getExpectedVersion(), "expectedVersion"));
  }

  public static StopCommand toStopCommand(String threadId, HarnessThreadStopDTO dto) {
    requireNonNull(dto, "stopDTO");
    return new StopCommand(
        Uuids.parseCanonical(threadId, "threadId"),
        Uuids.parseCanonical(dto.getStopRequestId(), "stopRequestId"),
        DecimalLongs.parseNonNegative(dto.getExpectedVersion(), "expectedVersion"));
  }

  /** 解析放弃退出确认目标；执行 id 与 Thread id 都是 canonical UUID，expectedVersion 是 non-negative bigint。 */
  public static AbandonExecutionTarget toAbandonExecutionTarget(
      String threadId, String executionId, HarnessExecutionAbandonDTO dto) {
    requireNonNull(dto, "abandonDTO");
    return new AbandonExecutionTarget(
        Uuids.parseCanonical(threadId, "threadId"),
        Uuids.parseCanonical(executionId, "executionId"),
        DecimalLongs.parseNonNegative(dto.getExpectedVersion(), "expectedVersion"));
  }

  /** 已校验的放弃退出确认目标。 */
  public record AbandonExecutionTarget(UUID threadId, UUID executionId, long expectedVersion) {}

  public static SetThreadYoloCommand toSetThreadYoloCommand(
      String threadId, HarnessThreadYoloUpdateDTO dto) {
    requireNonNull(dto, "yoloUpdateDTO");
    return new SetThreadYoloCommand(
        Uuids.parseCanonical(threadId, "threadId"),
        requireBoolean(dto.getYoloEnabled(), "yoloEnabled"));
  }

  /** 把 PUT name 请求映射为 Session 重命名命令；只校验 name 存在且非 blank，规范化与长度上限由 Core {@code Names} 权威处理。 */
  public static RenameSessionCommand toRenameSessionCommand(
      UUID sessionId, HarnessNameUpdateDTO dto) {
    try {
      requireNonNull(dto, "nameUpdateDTO");
      return new RenameSessionCommand(sessionId, requireText(dto.getName(), "name"));
    } catch (IllegalArgumentException error) {
      throw DomainException.validation("session", error.getMessage());
    }
  }

  /** 把 PUT name 请求映射为 Thread 重命名命令；只校验 name 存在且非 blank，规范化与长度上限由 Core {@code Names} 权威处理。 */
  public static RenameThreadCommand toRenameThreadCommand(
      String threadId, HarnessNameUpdateDTO dto) {
    requireNonNull(dto, "nameUpdateDTO");
    return new RenameThreadCommand(
        Uuids.parseCanonical(threadId, "threadId"), requireText(dto.getName(), "name"));
  }

  /** 把审批请求映射为命令。操作者身份由调用方从认证上下文解析后显式传入，绝不读取请求体中的身份字段。 */
  public static ToolApprovalCommand toToolApprovalCommand(
      String threadId, String toolInvocationId, HarnessToolApprovalDTO dto, String actor) {
    requireNonNull(dto, "approvalDTO");
    String decision = requireText(dto.getDecision(), "decision");
    ToolApprovalDecision parsed =
        switch (decision) {
          case "ALLOW" -> ToolApprovalDecision.ALLOWED;
          case "DENY" -> ToolApprovalDecision.DENIED;
          default -> throw new IllegalArgumentException(
              "decision must be ALLOW or DENY: " + decision);
        };
    return new ToolApprovalCommand(
        Uuids.parseCanonical(threadId, "threadId"),
        Uuids.parseCanonical(toolInvocationId, "toolInvocationId"),
        parsed,
        Uuids.parseCanonical(dto.getDecisionId(), "decisionId"),
        requireText(actor, "actor"),
        dto.getReason());
  }

  /**
   * 解析人工输入提交：{@code submissionId} 必须是 canonical UUID；{@code declined} 必须显式给出；拒答不得携带答案，非拒答必须给出
   * 答案数组。目标 Thread 由请求体提供，调用方不预先假定其产品归属。操作者身份由服务端认证上下文提供，不从请求体读取。
   */
  public static ToolInputSubmissionCommand toToolInputSubmissionCommand(
      String toolInvocationId, HarnessToolInputDTO dto, String actor) {
    requireNonNull(dto, "toolInputDTO");
    UUID submissionId = Uuids.parseCanonical(dto.getSubmissionId(), "submissionId");
    boolean declined = requireBoolean(dto.getDeclined(), "declined");
    List<List<String>> answers = dto.getAnswers();
    if (declined) {
      if (answers != null) {
        throw new IllegalArgumentException("declined submission must not carry answers");
      }
      answers = List.of();
    } else if (answers == null) {
      throw new IllegalArgumentException("answers must not be null");
    }
    return new ToolInputSubmissionCommand(
        Uuids.parseCanonical(dto.getThreadId(), "threadId"),
        Uuids.parseCanonical(toolInvocationId, "toolInvocationId"),
        submissionId,
        requireText(actor, "actor"),
        declined,
        answers);
  }

  private static AcceptCommandsTarget toTarget(HarnessCommandTargetDTO dto) {
    requireNonNull(dto, "target");
    String type = requireText(dto.getType(), "target.type");
    return switch (type) {
      case "NEW_SESSION" -> {
        requireForbidden(dto.hasStartEntryIdField(), "target.startEntryId", "target type " + type);
        requireForbidden(dto.hasThreadNameField(), "target.threadName", "target type " + type);
        requireForbidden(
            dto.hasSourceThreadIdField(), "target.sourceThreadId", "target type " + type);
        yield new AcceptCommandsTarget.NewRootSession(
            Uuids.parseCanonical(dto.getSessionId(), "target.sessionId"),
            Uuids.parseCanonical(dto.getThreadId(), "target.threadId"),
            toBranchSettings(requireNonNull(dto.getRootSettings(), "target.rootSettings")),
            requireBoolean(dto.getYoloEnabled(), "target.yoloEnabled"));
      }
      case "NEW_THREAD" -> {
        requireForbidden(dto.hasRootSettingsField(), "target.rootSettings", "target type " + type);
        requireForbidden(
            dto.hasSourceThreadIdField(), "target.sourceThreadId", "target type " + type);
        yield new AcceptCommandsTarget.NewThread(
            Uuids.parseCanonical(dto.getSessionId(), "target.sessionId"),
            Uuids.parseCanonical(dto.getStartEntryId(), "target.startEntryId"),
            Uuids.parseCanonical(dto.getThreadId(), "target.threadId"),
            requireText(dto.getThreadName(), "target.threadName"),
            requireBoolean(dto.getYoloEnabled(), "target.yoloEnabled"));
      }
      case "NEW_FORKED_SESSION" -> {
        requireForbidden(dto.hasRootSettingsField(), "target.rootSettings", "target type " + type);
        requireForbidden(dto.hasThreadNameField(), "target.threadName", "target type " + type);
        yield new AcceptCommandsTarget.NewForkedSession(
            Uuids.parseCanonical(dto.getSourceThreadId(), "target.sourceThreadId"),
            Uuids.parseCanonical(dto.getStartEntryId(), "target.startEntryId"),
            Uuids.parseCanonical(dto.getSessionId(), "target.sessionId"),
            Uuids.parseCanonical(dto.getThreadId(), "target.threadId"),
            requireBoolean(dto.getYoloEnabled(), "target.yoloEnabled"));
      }
      default -> throw new IllegalArgumentException("unknown target type: " + type);
    };
  }

  /**
   * 映射既有 Thread 的通用命令批：target 由 path 的 {@code threadId} 与请求体的 cursor 组合，不带 owner；附件物化与 Session
   * 归属校验由接受服务的 preflight 完成。单条纯 USER_MESSAGE 批次必须省略 cursor（给出即 400）；其余批次必须显式给出两个 cursor 字段。
   */
  public static AcceptCommandsCommand toAcceptThreadCommandsCommand(
      String threadId, HarnessThreadCommandBatchDTO dto) {
    return toThreadCommandsCommand(threadId, dto, false);
  }

  /** 映射发送前预览批：预览依赖当前 head / next-sequence 快照，因此即使只有一条 USER_MESSAGE 也必须携带精确 cursor。 */
  public static AcceptCommandsCommand toPreviewThreadCommandsCommand(
      String threadId, HarnessThreadCommandBatchDTO dto) {
    return toThreadCommandsCommand(threadId, dto, true);
  }

  private static AcceptCommandsCommand toThreadCommandsCommand(
      String threadId, HarnessThreadCommandBatchDTO dto, boolean requireCursor) {
    requireNonNull(dto, "threadCommandBatchDTO");
    // 既有 Thread 允许纯设置批次（0 条 user-like）；最多一条且必须位于末尾，由 Core 在应用边界保证。
    List<NewThreadCommand> commands = toHttpCommands(dto.getCommands(), "commands", false);
    boolean cursorRequired =
        requireCursor || !AcceptCommandsCommand.isPureUserMessageBatch(commands);
    String headEntryId = dto.getExpectedHeadEntryId();
    String nextCommandSequence = dto.getExpectedNextCommandSequence();
    if (cursorRequired) {
      if (headEntryId == null || nextCommandSequence == null) {
        throw new IllegalArgumentException(
            "expectedHeadEntryId and expectedNextCommandSequence are required for this command batch");
      }
    } else if (headEntryId != null || nextCommandSequence != null) {
      throw new IllegalArgumentException(
          "expectedHeadEntryId and expectedNextCommandSequence must be omitted for a single USER_MESSAGE batch");
    }
    AcceptCommandsTarget target =
        new AcceptCommandsTarget.Thread(
            Uuids.parseCanonical(threadId, "threadId"),
            headEntryId == null ? null : Uuids.parseCanonical(headEntryId, "expectedHeadEntryId"),
            nextCommandSequence == null
                ? null
                : DecimalLongs.parsePositive(nextCommandSequence, "expectedNextCommandSequence"));
    return new AcceptCommandsCommand(target, commands);
  }

  /**
   * 映射本地分支草稿预览的命令批：与正式创建完全同一套严格校验（固定 SET_AGENT/SET_MODEL/SET_ENVIRONMENT 顺序 + 恰好一条终止 USER_MESSAGE /
   * GOAL），因此预览不会接受任何正式创建会拒绝的形状；草稿尚无 Thread，故这里只返回 domain 命令而不构造 target。
   */
  public static List<NewThreadCommand> toNewThreadCommands(
      List<HarnessCommandCreateDTO> commands, String field) {
    return toHttpCommands(commands, field, true);
  }

  /** rootSettings 是新建 branch 的初始快照：Goal 只由 typed GOAL 用户命令设置，因此这里必须为空。 */
  private static BranchSettings toBranchSettings(HarnessBranchSettingsDTO dto) {
    requireNonNull(dto, "branchSettings");
    if (!dto.hasEnvironmentNameField()) {
      throw new IllegalArgumentException(
          "branchSettings requires an explicit nullable environmentName field");
    }
    if (dto.getGoal() != null) {
      throw new IllegalArgumentException(
          "branchSettings must not carry a goal; goals are set through a GOAL command");
    }
    return new BranchSettings(
        requireText(dto.getAgentName(), "branchSettings.agentName"),
        toExecutionSelection(dto.getExecution()),
        dto.getEnvironmentName());
  }

  private static ExecutionSelection toExecutionSelection(HarnessExecutionSelectionDTO dto) {
    requireNonNull(dto, "execution");
    String driver = requireText(dto.getDriver(), "execution.driver");
    Map<String, Object> settings = requireNonNull(dto.getSettings(), "execution.settings");
    String settingsJson = StrictJson.standard().write(settings, "execution.settings");
    return new ExecutionSelection(driver, settingsJson);
  }

  private static ModelSelection toModelSelection(HarnessModelSelectionDTO dto) {
    requireNonNull(dto, "model");
    return new ModelSelection(requireText(dto.getModelName(), "model.modelName"), dto.getVariant());
  }

  /**
   * Model Request Debug 的草稿选择：{@code model} 必填并复用 canonical {@link ModelSelection} 校验；{@code
   * environmentName} 可空但复用 branch settings 的 canonical Environment name 规则（null 明确表示未选择）。
   */
  public static ModelRequestDebugSelection toModelRequestDebugSelection(
      HarnessModelRequestDebugRequestDTO dto) {
    requireNonNull(dto, "modelRequestDebugRequest");
    return new ModelRequestDebugSelection(
        toModelSelection(dto.getModel()),
        BranchSettings.requireCanonicalEnvironmentName(
            dto.getEnvironmentName(), "environmentName"));
  }

  /** Model Request Debug 的草稿选择：canonical model selection 与可空 Environment name。 */
  public record ModelRequestDebugSelection(ModelSelection model, String environmentName) {}

  private static NewThreadCommand toNewHttpCommand(HarnessCommandCreateDTO dto) {
    requireNonNull(dto, "command");
    ThreadCommandPayload payload = toPayload(dto);
    return new NewThreadCommand(
        payload,
        Uuids.parseCanonical(dto.getIdempotencyKey(), "command.idempotencyKey"),
        ThreadCommandPayloadJsonCodec.requestHash(payload));
  }

  private static ThreadCommandPayload toPayload(HarnessCommandCreateDTO dto) {
    String type = requireText(dto.getType(), "command.type");
    return switch (type) {
      case "USER_MESSAGE" -> {
        requireForbidden(dto.hasAgentNameField(), "agentName", "command type " + type);
        requireForbidden(dto.hasExecutionField(), "execution", "command type " + type);
        requireForbidden(dto.hasEnvironmentNameField(), "environmentName", "command type " + type);
        requireForbidden(dto.hasTextField(), "text", "command type " + type);
        yield new UserMessageCommandPayload(
            new AgentMessage(AgentMessageRole.USER, toUserMessageContents(dto)));
      }
      case "GOAL" -> {
        requireForbidden(dto.hasContentsField(), "contents", "command type " + type);
        requireForbidden(dto.hasAgentNameField(), "agentName", "command type " + type);
        requireForbidden(dto.hasExecutionField(), "execution", "command type " + type);
        requireForbidden(dto.hasEnvironmentNameField(), "environmentName", "command type " + type);
        // GOAL 必须显式携带 text（显式 null 表示清除），与「字段缺失」严格区分。
        if (!dto.hasTextField()) {
          throw new IllegalArgumentException("GOAL requires an explicit nullable text field");
        }
        yield new GoalCommandPayload(dto.getText());
      }
      case "SET_AGENT" -> {
        requireForbidden(dto.hasContentsField(), "contents", "command type " + type);
        requireForbidden(dto.hasTextField(), "text", "command type " + type);
        requireForbidden(dto.hasExecutionField(), "execution", "command type " + type);
        requireForbidden(dto.hasEnvironmentNameField(), "environmentName", "command type " + type);
        yield new SetAgentCommandPayload(requireText(dto.getAgentName(), "agentName"));
      }
      case "SET_EXECUTION" -> {
        requireForbidden(dto.hasContentsField(), "contents", "command type " + type);
        requireForbidden(dto.hasTextField(), "text", "command type " + type);
        requireForbidden(dto.hasAgentNameField(), "agentName", "command type " + type);
        requireForbidden(dto.hasEnvironmentNameField(), "environmentName", "command type " + type);
        yield new SetExecutionCommandPayload(
            toExecutionSelection(requireNonNull(dto.getExecution(), "execution")));
      }
      case "SET_ENVIRONMENT" -> {
        requireForbidden(dto.hasContentsField(), "contents", "command type " + type);
        requireForbidden(dto.hasTextField(), "text", "command type " + type);
        requireForbidden(dto.hasAgentNameField(), "agentName", "command type " + type);
        requireForbidden(dto.hasExecutionField(), "execution", "command type " + type);
        // SET_ENVIRONMENT 必须显式携带 environmentName（显式 null 表示解除选择），与「字段缺失」严格区分。
        if (!dto.hasEnvironmentNameField()) {
          throw new IllegalArgumentException(
              "SET_ENVIRONMENT requires an explicit nullable environmentName field");
        }
        yield new SetEnvironmentCommandPayload(dto.getEnvironmentName());
      }
      case "CUSTOM_MESSAGE" -> throw new IllegalArgumentException(
          "CUSTOM_MESSAGE is not allowed on the product HTTP surface");
      case "NOTIFICATION" -> throw new IllegalArgumentException(
          "NOTIFICATION is internal-only and not allowed on the product HTTP surface");
      default -> throw new IllegalArgumentException("unknown command type: " + type);
    };
  }

  /**
   * 产品 HTTP 命令批的唯一映射入口：null/空列表与逐项类型校验一致，随后按创建型或有 Thread 的 shape 规则准入。
   *
   * <p>返回不可变副本，调用方不再重复遍历或包装。
   */
  private static List<NewThreadCommand> toHttpCommands(
      List<HarnessCommandCreateDTO> requestCommands, String field, boolean requireUserLike) {
    List<HarnessCommandCreateDTO> required = requireList(requestCommands, field);
    if (required.isEmpty()) {
      throw new IllegalArgumentException("commands must not be empty");
    }
    List<NewThreadCommand> commands = new ArrayList<>(required.size());
    for (HarnessCommandCreateDTO command : required) {
      commands.add(toNewHttpCommand(command));
    }
    validateHttpCommandShape(commands, requireUserLike);
    return List.copyOf(commands);
  }

  /**
   * 产品 HTTP 命令批的 shape admission：SET_* 必须按固定顺序（SET_AGENT -&gt; SET_MODEL -&gt;
   * SET_ENVIRONMENT）出现，每种至多一次且位于消息之前；user-like 输入（USER_MESSAGE / GOAL）至多一条且必须位于末尾。
   *
   * <p>创建型 target（NEW_SESSION / NEW_THREAD）与本地分支草稿预览要求恰有一条 user-like 输入；既有 Thread 允许纯设置批次（0 条
   * user-like），额外保证由 Core 在应用边界完成。
   */
  private static void validateHttpCommandShape(
      List<NewThreadCommand> commands, boolean requireUserLike) {
    List<ThreadCommandType> prefixOrder =
        List.of(
            ThreadCommandType.SET_AGENT,
            ThreadCommandType.SET_EXECUTION,
            ThreadCommandType.SET_ENVIRONMENT);
    int lastSetOrder = -1;
    int userLikeCount = 0;
    for (int i = 0; i < commands.size(); i++) {
      ThreadCommandType type = commands.get(i).payload().type();
      if (type == ThreadCommandType.USER_MESSAGE || type == ThreadCommandType.GOAL) {
        // typed GOAL 与 USER_MESSAGE 都是 user-like 终止输入：至多一条且必须在最后。
        userLikeCount++;
        if (i != commands.size() - 1) {
          throw new IllegalArgumentException(
              "the terminal user-like command must be the final HTTP command");
        }
        continue;
      }
      int order = prefixOrder.indexOf(type);
      if (order <= lastSetOrder) {
        throw new IllegalArgumentException(
            "HTTP commands must use SET_AGENT, SET_MODEL, SET_ENVIRONMENT order");
      }
      lastSetOrder = order;
    }
    if (requireUserLike ? userLikeCount != 1 : userLikeCount > 1) {
      throw new IllegalArgumentException(
          requireUserLike
              ? "HTTP command batch must contain exactly one USER_MESSAGE or GOAL command"
              : "HTTP command batch may contain at most one USER_MESSAGE or GOAL command");
    }
  }

  private static List<AgentMessageContent> toUserMessageContents(HarnessCommandCreateDTO dto) {
    if (!dto.hasContentsField()) {
      throw new IllegalArgumentException(
          "USER_MESSAGE must contain exactly one non-empty contents list of "
              + "TEXT/ATTACHMENT/RESOURCE");
    }
    List<HarnessUserMessageContentDTO> required = requireList(dto.getContents(), "contents");
    if (required.isEmpty()) {
      throw new IllegalArgumentException("contents must not be empty");
    }
    List<AgentMessageContent> mapped = new ArrayList<>(required.size());
    for (int i = 0; i < required.size(); i++) {
      mapped.add(toUserMessageContent(required.get(i), i));
    }
    return List.copyOf(mapped);
  }

  private static AgentMessageContent toUserMessageContent(
      HarnessUserMessageContentDTO dto, int index) {
    String context = "contents[" + index + "]";
    String type = requireText(dto.getType(), context + ".type");
    return switch (type) {
      case "TEXT" -> {
        requireForbidden(dto.hasUploadIdField(), context + ".uploadId", "content type " + type);
        requireForbidden(dto.hasBlobIdField(), context + ".blobId", "content type " + type);
        requireForbidden(dto.hasNameField(), context + ".name", "content type " + type);
        requireForbidden(dto.hasPreviewField(), context + ".preview", "content type " + type);
        requireForbidden(dto.hasImageTierField(), context + ".imageTier", "content type " + type);
        yield new TextMessageContent(requireText(dto.getText(), context + ".text"));
      }
      case "ATTACHMENT" -> {
        requireForbidden(dto.hasTextField(), context + ".text", "content type " + type);
        requireForbidden(dto.hasBlobIdField(), context + ".blobId", "content type " + type);
        requireForbidden(dto.hasNameField(), context + ".name", "content type " + type);
        requireForbidden(dto.hasPreviewField(), context + ".preview", "content type " + type);
        yield new AttachmentMessageContent(
            Uuids.parseCanonical(dto.getUploadId(), context + ".uploadId"),
            toImageTier(dto, context));
      }
      case "RESOURCE" -> {
        requireForbidden(dto.hasTextField(), context + ".text", "content type " + type);
        requireForbidden(dto.hasUploadIdField(), context + ".uploadId", "content type " + type);
        yield ResourceMessageContent.media(
            Uuids.parseCanonical(dto.getBlobId(), context + ".blobId"),
            requireText(dto.getName(), context + ".name"),
            dto.getPreview(),
            toImageTier(dto, context));
      }
      default -> throw new IllegalArgumentException(
          context + ".type must be one of TEXT, ATTACHMENT, RESOURCE: " + type);
    };
  }

  /**
   * 解析 ATTACHMENT / RESOURCE 的图片输入档位：字段缺省时使用平台默认 720P，显式提供时必须是受支持的档位名（非图片媒体由 Platform 在权威 MIME 上收敛为
   * null）。
   */
  private static ImageInputTier toImageTier(HarnessUserMessageContentDTO dto, String context) {
    if (!dto.hasImageTierField()) {
      return ImageInputTier.P720;
    }
    String value = requireText(dto.getImageTier(), context + ".imageTier");
    try {
      return ImageInputTier.fromWireName(value);
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException(
          context + ".imageTier must be one of 720P, 1080P, ORIGINAL: " + value, error);
    }
  }

  private static void requireForbidden(boolean present, String field, String context) {
    if (present) {
      throw new IllegalArgumentException("field " + field + " is forbidden for " + context);
    }
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value;
  }

  private static boolean requireBoolean(Boolean value, String field) {
    if (value == null) {
      throw new IllegalArgumentException(field + " must not be null");
    }
    return value;
  }

  private static <T> T requireNonNull(T value, String field) {
    if (value == null) {
      throw new IllegalArgumentException(field + " must not be null");
    }
    return value;
  }

  private static <T> List<T> requireList(List<T> value, String field) {
    if (value == null) {
      throw new IllegalArgumentException(field + " must not be null");
    }
    for (T element : value) {
      if (element == null) {
        throw new IllegalArgumentException(field + " must not contain null elements");
      }
    }
    return List.copyOf(value);
  }
}
