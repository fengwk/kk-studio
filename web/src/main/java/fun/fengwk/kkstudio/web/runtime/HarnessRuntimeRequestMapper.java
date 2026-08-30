package fun.fengwk.kkstudio.web.runtime;

import fun.fengwk.kkstudio.harness.environment.EnvironmentBinding;
import fun.fengwk.kkstudio.harness.environment.EnvironmentName;
import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsCommand;
import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsTarget;
import fun.fengwk.kkstudio.harness.runtime.CompactThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.SetThreadYoloCommand;
import fun.fengwk.kkstudio.harness.runtime.StopCommand;
import fun.fengwk.kkstudio.harness.runtime.ToolApprovalCommand;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolApprovalDecision;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AttachmentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ResourceMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.SetAgentCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.SetEnvironmentCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.SetModelCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandPayloadJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandType;
import fun.fengwk.kkstudio.harness.runtime.thread.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.platform.orchestration.OwnerRef;
import fun.fengwk.kkstudio.platform.orchestration.OwnerType;
import fun.fengwk.kkstudio.share.ai.runtime.EnvironmentBindingDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessBranchSettingsDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessCommandBatchDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessCommandCreateDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessCommandOwnerDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessCommandTargetDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessModelSelectionDTO;
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

  /** 映射 owner，并只允许产品公开的 CHAT/CANVAS discriminator。 */
  public static OwnerRef toOwner(HarnessCommandOwnerDTO dto) {
    requireNonNull(dto, "owner");
    String type = requireText(dto.getType(), "owner.type");
    OwnerType ownerType;
    try {
      ownerType = OwnerType.valueOf(type);
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException("owner.type must be CHAT or CANVAS: " + type, error);
    }
    return new OwnerRef(ownerType, parseUuid(dto.getId(), "owner.id"));
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
        parseUuid(threadId, "threadId"),
        parseNonNegativeDecimal(dto.getExpectedVersion(), "expectedVersion"),
        requireBoolean(dto.getYoloEnabled(), "yoloEnabled"));
  }

  public static ToolApprovalCommand toToolApprovalCommand(
      String threadId, String toolInvocationId, HarnessToolApprovalDTO dto) {
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
        requireText(dto.getActor(), "actor"),
        dto.getReason());
  }

  private static AcceptCommandsTarget toTarget(HarnessCommandTargetDTO dto) {
    requireNonNull(dto, "target");
    String type = requireText(dto.getType(), "target.type");
    return switch (type) {
      case "NEW_SESSION" -> {
        requireForbidden(dto.hasStartEntryIdField(), "target.startEntryId", "target type " + type);
        requireForbidden(
            dto.hasExpectedHeadEntryIdField(), "target.expectedHeadEntryId", "target type " + type);
        requireForbidden(
            dto.hasExpectedNextCommandSequenceField(),
            "target.expectedNextCommandSequence",
            "target type " + type);
        yield new AcceptCommandsTarget.NewSession(
            parseUuid(dto.getSessionId(), "target.sessionId"),
            parseUuid(dto.getThreadId(), "target.threadId"),
            toBranchSettings(requireNonNull(dto.getRootSettings(), "target.rootSettings")),
            null,
            requireBoolean(dto.getYoloEnabled(), "target.yoloEnabled"));
      }
      case "ENTRY" -> {
        requireForbidden(dto.hasRootSettingsField(), "target.rootSettings", "target type " + type);
        requireForbidden(
            dto.hasExpectedHeadEntryIdField(), "target.expectedHeadEntryId", "target type " + type);
        requireForbidden(
            dto.hasExpectedNextCommandSequenceField(),
            "target.expectedNextCommandSequence",
            "target type " + type);
        yield new AcceptCommandsTarget.Entry(
            parseUuid(dto.getSessionId(), "target.sessionId"),
            parseUuid(dto.getStartEntryId(), "target.startEntryId"),
            parseUuid(dto.getThreadId(), "target.threadId"),
            requireBoolean(dto.getYoloEnabled(), "target.yoloEnabled"));
      }
      case "THREAD" -> {
        requireForbidden(dto.hasSessionIdField(), "target.sessionId", "target type " + type);
        requireForbidden(dto.hasStartEntryIdField(), "target.startEntryId", "target type " + type);
        requireForbidden(dto.hasRootSettingsField(), "target.rootSettings", "target type " + type);
        requireForbidden(dto.hasYoloEnabledField(), "target.yoloEnabled", "target type " + type);
        yield new AcceptCommandsTarget.Thread(
            parseUuid(dto.getThreadId(), "target.threadId"),
            parseUuid(dto.getExpectedHeadEntryId(), "target.expectedHeadEntryId"),
            parsePositiveDecimal(
                dto.getExpectedNextCommandSequence(), "target.expectedNextCommandSequence"));
      }
      default -> throw new IllegalArgumentException("unknown target type: " + type);
    };
  }

  private static BranchSettings toBranchSettings(HarnessBranchSettingsDTO dto) {
    requireNonNull(dto, "branchSettings");
    return new BranchSettings(
        toEnvironmentBinding(dto.getEnvironment()),
        requireText(dto.getAgentName(), "branchSettings.agentName"),
        toModelSelection(dto.getModel()));
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
        parseUuid(dto.getClientCommandId(), "command.clientCommandId"),
        ThreadCommandPayloadJsonCodec.requestHash(payload));
  }

  private static ThreadCommandPayload toPayload(HarnessCommandCreateDTO dto) {
    String type = requireText(dto.getType(), "command.type");
    return switch (type) {
      case "USER_MESSAGE" -> {
        requireForbidden(dto.hasAgentNameField(), "agentName", "command type " + type);
        requireForbidden(dto.hasModelField(), "model", "command type " + type);
        requireForbidden(dto.hasEnvironmentField(), "environment", "command type " + type);
        yield new UserMessageCommandPayload(
            new AgentMessage(AgentMessageRole.USER, toUserMessageContents(dto)));
      }
      case "SET_AGENT" -> {
        requireForbidden(dto.hasContentsField(), "contents", "command type " + type);
        requireForbidden(dto.hasModelField(), "model", "command type " + type);
        requireForbidden(dto.hasEnvironmentField(), "environment", "command type " + type);
        yield new SetAgentCommandPayload(requireText(dto.getAgentName(), "agentName"));
      }
      case "SET_MODEL" -> {
        requireForbidden(dto.hasContentsField(), "contents", "command type " + type);
        requireForbidden(dto.hasAgentNameField(), "agentName", "command type " + type);
        requireForbidden(dto.hasEnvironmentField(), "environment", "command type " + type);
        yield new SetModelCommandPayload(toModelSelection(requireNonNull(dto.getModel(), "model")));
      }
      case "SET_ENVIRONMENT" -> {
        requireForbidden(dto.hasContentsField(), "contents", "command type " + type);
        requireForbidden(dto.hasAgentNameField(), "agentName", "command type " + type);
        requireForbidden(dto.hasModelField(), "model", "command type " + type);
        if (!dto.hasEnvironmentField()) {
          throw new IllegalArgumentException(
              "SET_ENVIRONMENT must contain environment (a binding object selects, null unbinds)");
        }
        yield new SetEnvironmentCommandPayload(toEnvironmentBinding(dto.getEnvironment()));
      }
      case "CUSTOM_MESSAGE" -> throw new IllegalArgumentException(
          "CUSTOM_MESSAGE is not allowed on the product HTTP surface");
      default -> throw new IllegalArgumentException("unknown command type: " + type);
    };
  }

  private static void validateHttpCommandShape(List<NewThreadCommand> commands) {
    List<ThreadCommandType> prefixOrder =
        List.of(
            ThreadCommandType.SET_ENVIRONMENT,
            ThreadCommandType.SET_AGENT,
            ThreadCommandType.SET_MODEL);
    int lastSetOrder = -1;
    int userMessageCount = 0;
    for (int i = 0; i < commands.size(); i++) {
      ThreadCommandType type = commands.get(i).payload().type();
      if (type == ThreadCommandType.USER_MESSAGE) {
        userMessageCount++;
        if (i != commands.size() - 1) {
          throw new IllegalArgumentException("USER_MESSAGE must be the final HTTP command");
        }
        continue;
      }
      int order = prefixOrder.indexOf(type);
      if (order <= lastSetOrder) {
        throw new IllegalArgumentException(
            "HTTP commands must use SET_ENVIRONMENT, SET_AGENT, SET_MODEL order");
      }
      lastSetOrder = order;
    }
    if (userMessageCount != 1) {
      throw new IllegalArgumentException(
          "HTTP command batch must contain exactly one USER_MESSAGE");
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
        yield new TextMessageContent(requireText(dto.getText(), context + ".text"));
      }
      case "ATTACHMENT" -> {
        requireForbidden(dto.hasTextField(), context + ".text", "content type " + type);
        requireForbidden(dto.hasBlobIdField(), context + ".blobId", "content type " + type);
        requireForbidden(dto.hasNameField(), context + ".name", "content type " + type);
        requireForbidden(dto.hasPreviewField(), context + ".preview", "content type " + type);
        yield new AttachmentMessageContent(parseUuid(dto.getUploadId(), context + ".uploadId"));
      }
      case "RESOURCE" -> {
        requireForbidden(dto.hasTextField(), context + ".text", "content type " + type);
        requireForbidden(dto.hasUploadIdField(), context + ".uploadId", "content type " + type);
        yield new ResourceMessageContent(
            parseUuid(dto.getBlobId(), context + ".blobId"),
            requireText(dto.getName(), context + ".name"),
            dto.getPreview());
      }
      default -> throw new IllegalArgumentException(
          context + ".type must be one of TEXT, ATTACHMENT, RESOURCE: " + type);
    };
  }

  private static void requireForbidden(boolean present, String field, String context) {
    if (present) {
      throw new IllegalArgumentException("field " + field + " is forbidden for " + context);
    }
  }

  private static EnvironmentBinding toEnvironmentBinding(EnvironmentBindingDTO dto) {
    if (dto == null) {
      return null;
    }
    return new EnvironmentBinding(
        new EnvironmentName(requireText(dto.getName(), "environment.name")),
        requireText(dto.getWorkspacePath(), "environment.workspacePath"));
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
