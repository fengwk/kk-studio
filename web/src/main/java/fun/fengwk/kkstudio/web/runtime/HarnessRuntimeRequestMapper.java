package fun.fengwk.kkstudio.web.runtime;

import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsCommand;
import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsTarget;
import fun.fengwk.kkstudio.harness.runtime.CompactThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.RenameSessionCommand;
import fun.fengwk.kkstudio.harness.runtime.RenameThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.SetThreadYoloCommand;
import fun.fengwk.kkstudio.harness.runtime.StopCommand;
import fun.fengwk.kkstudio.harness.runtime.ToolApprovalCommand;
import fun.fengwk.kkstudio.harness.runtime.ToolInputSubmissionCommand;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolApprovalDecision;
import fun.fengwk.kkstudio.harness.runtime.model.ImageInputTier;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AttachmentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ResourceMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.thread.command.GoalCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.SetAgentCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.SetEnvironmentCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.SetModelCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandPayloadJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandType;
import fun.fengwk.kkstudio.harness.runtime.thread.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.platform.orchestration.OwnerRef;
import fun.fengwk.kkstudio.share.ai.interaction.HarnessToolInputDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessBranchSettingsDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessCommandBatchDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessCommandCreateDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessCommandOwnerDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessCommandTargetDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessModelSelectionDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessNameUpdateDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadCommandBatchDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadCompactDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadStopDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadYoloUpdateDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessToolApprovalDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessUserMessageContentDTO;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Harness Runtime HTTP 请求的严格解析与 DTO-to-domain 映射。 */
public final class HarnessRuntimeRequestMapper {

  private static final Pattern POSITIVE_DECIMAL = Pattern.compile("[1-9][0-9]*");
  private static final Pattern NON_NEGATIVE_DECIMAL = Pattern.compile("0|[1-9][0-9]*");

  private HarnessRuntimeRequestMapper() {}

  /** 解析 canonical UUID 实体 id（{@code UUID.fromString} 往返一致）。 */
  public static UUID parseUuid(String value, String field) {
    Objects.requireNonNull(field, "field");
    if (value == null) {
      throw new IllegalArgumentException(field + " must not be null");
    }
    UUID parsed;
    try {
      parsed = UUID.fromString(value);
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException(field + " must be a canonical UUID: " + value, error);
    }
    if (!parsed.toString().equals(value)) {
      throw new IllegalArgumentException(field + " must be a canonical UUID: " + value);
    }
    return parsed;
  }

  static long parsePositiveDecimal(String value, String field) {
    Objects.requireNonNull(field, "field");
    if (value == null || !POSITIVE_DECIMAL.matcher(value).matches()) {
      throw new IllegalArgumentException(field + " must be an unsigned positive decimal: " + value);
    }
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException error) {
      throw new IllegalArgumentException(field + " exceeds bigint range: " + value, error);
    }
  }

  static long parseNonNegativeDecimal(String value, String field) {
    Objects.requireNonNull(field, "field");
    if (value == null || !NON_NEGATIVE_DECIMAL.matcher(value).matches()) {
      throw new IllegalArgumentException(
          field + " must be a non-negative decimal bigint: " + value);
    }
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException error) {
      throw new IllegalArgumentException(field + " exceeds bigint range: " + value, error);
    }
  }

  /** 映射 owner，只允许 CHAT 与 ISSUE_AGENT：Canvas 不持有 Harness Session，公共 batch 端点不接受 Issue+Agent。 */
  public static OwnerRef toOwner(HarnessCommandOwnerDTO dto) {
    requireNonNull(dto, "owner");
    String type = requireText(dto.getType(), "owner.type");
    return switch (type) {
      case "CHAT" -> new OwnerRef.Chat(parseUuid(dto.getChatId(), "owner.chatId"));
      case "ISSUE_AGENT" -> new OwnerRef.IssueAgent(
          parseUuid(dto.getIssueId(), "owner.issueId"),
          requireText(dto.getAgentName(), "owner.agentName"));
      default -> throw new IllegalArgumentException(
          "owner.type must be CHAT or ISSUE_AGENT: " + type);
    };
  }

  /** 将唯一产品 HTTP 写请求映射为 sealed target 与有序 commands。 */
  public static AcceptCommandsCommand toAcceptCommandsCommand(HarnessCommandBatchDTO dto) {
    requireNonNull(dto, "commandBatchDTO");
    List<HarnessCommandCreateDTO> requestCommands = requireList(dto.getCommands(), "commands");
    if (requestCommands.isEmpty()) {
      throw new IllegalArgumentException("commands must not be empty");
    }
    List<NewThreadCommand> commands = new ArrayList<>(requestCommands.size());
    for (HarnessCommandCreateDTO command : requestCommands) {
      commands.add(toNewHttpCommand(command));
    }
    validateHttpCommandShape(commands);
    return new AcceptCommandsCommand(toTarget(dto.getTarget()), commands);
  }

  public static CompactThreadCommand toCompactThreadCommand(
      String threadId, HarnessThreadCompactDTO dto) {
    requireNonNull(dto, "compactDTO");
    return new CompactThreadCommand(
        parseUuid(threadId, "threadId"),
        parseNonNegativeDecimal(dto.getExpectedVersion(), "expectedVersion"));
  }

  public static StopCommand toStopCommand(String threadId, HarnessThreadStopDTO dto) {
    requireNonNull(dto, "stopDTO");
    return new StopCommand(
        parseUuid(threadId, "threadId"),
        parseUuid(dto.getStopRequestId(), "stopRequestId"),
        parseNonNegativeDecimal(dto.getExpectedVersion(), "expectedVersion"));
  }

  public static SetThreadYoloCommand toSetThreadYoloCommand(
      String threadId, HarnessThreadYoloUpdateDTO dto) {
    requireNonNull(dto, "yoloUpdateDTO");
    return new SetThreadYoloCommand(
        parseUuid(threadId, "threadId"), requireBoolean(dto.getYoloEnabled(), "yoloEnabled"));
  }

  /** 把 PUT name 请求映射为 Session 重命名命令；只校验 name 存在且非 blank，规范化与长度上限由 Core {@code Names} 权威处理。 */
  public static RenameSessionCommand toRenameSessionCommand(
      String sessionId, HarnessNameUpdateDTO dto) {
    requireNonNull(dto, "nameUpdateDTO");
    return new RenameSessionCommand(
        parseUuid(sessionId, "sessionId"), requireText(dto.getName(), "name"));
  }

  /** 把 PUT name 请求映射为 Thread 重命名命令；只校验 name 存在且非 blank，规范化与长度上限由 Core {@code Names} 权威处理。 */
  public static RenameThreadCommand toRenameThreadCommand(
      String threadId, HarnessNameUpdateDTO dto) {
    requireNonNull(dto, "nameUpdateDTO");
    return new RenameThreadCommand(
        parseUuid(threadId, "threadId"), requireText(dto.getName(), "name"));
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
        parseUuid(threadId, "threadId"),
        parseUuid(toolInvocationId, "toolInvocationId"),
        parsed,
        parseUuid(dto.getDecisionId(), "decisionId"),
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
    UUID submissionId = parseUuid(dto.getSubmissionId(), "submissionId");
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
        parseUuid(dto.getThreadId(), "threadId"),
        parseUuid(toolInvocationId, "toolInvocationId"),
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
        yield new AcceptCommandsTarget.NewRootSession(
            parseUuid(dto.getSessionId(), "target.sessionId"),
            parseUuid(dto.getThreadId(), "target.threadId"),
            toBranchSettings(requireNonNull(dto.getRootSettings(), "target.rootSettings")),
            requireBoolean(dto.getYoloEnabled(), "target.yoloEnabled"));
      }
      case "NEW_THREAD" -> {
        requireForbidden(dto.hasRootSettingsField(), "target.rootSettings", "target type " + type);
        yield new AcceptCommandsTarget.NewThread(
            parseUuid(dto.getSessionId(), "target.sessionId"),
            parseUuid(dto.getStartEntryId(), "target.startEntryId"),
            parseUuid(dto.getThreadId(), "target.threadId"),
            requireBoolean(dto.getYoloEnabled(), "target.yoloEnabled"));
      }
      default -> throw new IllegalArgumentException("unknown target type: " + type);
    };
  }

  /**
   * 映射既有 Thread 的通用命令批：target 由 path 的 {@code threadId} 与请求体的精确 cursor 组合，不带 owner；附件物化与 Session
   * 归属校验由接受服务的 preflight 完成。
   */
  public static AcceptCommandsCommand toAcceptThreadCommandsCommand(
      String threadId, HarnessThreadCommandBatchDTO dto) {
    requireNonNull(dto, "threadCommandBatchDTO");
    List<HarnessCommandCreateDTO> requestCommands = requireList(dto.getCommands(), "commands");
    if (requestCommands.isEmpty()) {
      throw new IllegalArgumentException("commands must not be empty");
    }
    List<NewThreadCommand> commands = new ArrayList<>(requestCommands.size());
    for (HarnessCommandCreateDTO command : requestCommands) {
      commands.add(toNewHttpCommand(command));
    }
    validateHttpCommandShape(commands);
    AcceptCommandsTarget target =
        new AcceptCommandsTarget.Thread(
            parseUuid(threadId, "threadId"),
            parseUuid(dto.getExpectedHeadEntryId(), "expectedHeadEntryId"),
            parsePositiveDecimal(
                dto.getExpectedNextCommandSequence(), "expectedNextCommandSequence"));
    return new AcceptCommandsCommand(target, commands);
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
        toModelSelection(dto.getModel()),
        dto.getEnvironmentName());
  }

  private static ModelSelection toModelSelection(HarnessModelSelectionDTO dto) {
    requireNonNull(dto, "model");
    return new ModelSelection(
        requireText(dto.getProviderName(), "model.providerName"),
        requireText(dto.getModelName(), "model.modelName"),
        requireText(dto.getVariant(), "model.variant"));
  }

  private static NewThreadCommand toNewHttpCommand(HarnessCommandCreateDTO dto) {
    requireNonNull(dto, "command");
    ThreadCommandPayload payload = toPayload(dto);
    return new NewThreadCommand(
        payload,
        parseUuid(dto.getIdempotencyKey(), "command.idempotencyKey"),
        ThreadCommandPayloadJsonCodec.requestHash(payload));
  }

  private static ThreadCommandPayload toPayload(HarnessCommandCreateDTO dto) {
    String type = requireText(dto.getType(), "command.type");
    return switch (type) {
      case "USER_MESSAGE" -> {
        requireForbidden(dto.hasAgentNameField(), "agentName", "command type " + type);
        requireForbidden(dto.hasModelField(), "model", "command type " + type);
        requireForbidden(dto.hasEnvironmentNameField(), "environmentName", "command type " + type);
        requireForbidden(dto.hasTextField(), "text", "command type " + type);
        yield new UserMessageCommandPayload(
            new AgentMessage(AgentMessageRole.USER, toUserMessageContents(dto)));
      }
      case "GOAL" -> {
        requireForbidden(dto.hasContentsField(), "contents", "command type " + type);
        requireForbidden(dto.hasAgentNameField(), "agentName", "command type " + type);
        requireForbidden(dto.hasModelField(), "model", "command type " + type);
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
        requireForbidden(dto.hasModelField(), "model", "command type " + type);
        requireForbidden(dto.hasEnvironmentNameField(), "environmentName", "command type " + type);
        yield new SetAgentCommandPayload(requireText(dto.getAgentName(), "agentName"));
      }
      case "SET_MODEL" -> {
        requireForbidden(dto.hasContentsField(), "contents", "command type " + type);
        requireForbidden(dto.hasTextField(), "text", "command type " + type);
        requireForbidden(dto.hasAgentNameField(), "agentName", "command type " + type);
        requireForbidden(dto.hasEnvironmentNameField(), "environmentName", "command type " + type);
        yield new SetModelCommandPayload(toModelSelection(requireNonNull(dto.getModel(), "model")));
      }
      case "SET_ENVIRONMENT" -> {
        requireForbidden(dto.hasContentsField(), "contents", "command type " + type);
        requireForbidden(dto.hasTextField(), "text", "command type " + type);
        requireForbidden(dto.hasAgentNameField(), "agentName", "command type " + type);
        requireForbidden(dto.hasModelField(), "model", "command type " + type);
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
      case "SET_CONTRIBUTOR_STATE" -> throw new IllegalArgumentException(
          "SET_CONTRIBUTOR_STATE is internal-only and not allowed on the product HTTP surface");
      default -> throw new IllegalArgumentException("unknown command type: " + type);
    };
  }

  private static void validateHttpCommandShape(List<NewThreadCommand> commands) {
    List<ThreadCommandType> prefixOrder =
        List.of(
            ThreadCommandType.SET_AGENT,
            ThreadCommandType.SET_MODEL,
            ThreadCommandType.SET_ENVIRONMENT);
    int lastSetOrder = -1;
    int userLikeCount = 0;
    for (int i = 0; i < commands.size(); i++) {
      ThreadCommandType type = commands.get(i).payload().type();
      if (type == ThreadCommandType.USER_MESSAGE || type == ThreadCommandType.GOAL) {
        // typed GOAL 与 USER_MESSAGE 都是 user-like 终止输入：恰有一条且必须在最后。
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
    if (userLikeCount != 1) {
      throw new IllegalArgumentException(
          "HTTP command batch must contain exactly one USER_MESSAGE or GOAL command");
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
            parseUuid(dto.getUploadId(), context + ".uploadId"), toImageTier(dto, context));
      }
      case "RESOURCE" -> {
        requireForbidden(dto.hasTextField(), context + ".text", "content type " + type);
        requireForbidden(dto.hasUploadIdField(), context + ".uploadId", "content type " + type);
        yield ResourceMessageContent.media(
            parseUuid(dto.getBlobId(), context + ".blobId"),
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
