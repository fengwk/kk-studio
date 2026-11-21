package fun.fengwk.kkstudio.platform.daemon.protocol;

import static fun.fengwk.kkstudio.platform.daemon.protocol.ControlProtocolFields.requireIdePath;
import static fun.fengwk.kkstudio.platform.daemon.protocol.ControlProtocolFields.requireInt64;
import static fun.fengwk.kkstudio.platform.daemon.protocol.ControlProtocolFields.requirePattern;
import static fun.fengwk.kkstudio.platform.daemon.protocol.ControlProtocolFields.requirePayload;
import static fun.fengwk.kkstudio.platform.daemon.protocol.ControlProtocolFields.requirePositive;
import static fun.fengwk.kkstudio.platform.daemon.protocol.ControlProtocolFields.requirePresent;
import static fun.fengwk.kkstudio.platform.daemon.protocol.ControlProtocolFields.requireText;
import static fun.fengwk.kkstudio.platform.daemon.protocol.ControlProtocolFields.requireUtf8Text;
import static fun.fengwk.kkstudio.platform.daemon.protocol.ControlProtocolFields.requireUuid;

import fun.fengwk.kkstudio.platform.daemon.DaemonIdeLease;
import fun.fengwk.kkstudio.platform.daemon.DaemonUpdateResult;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * IDE 命令、结果快照与租约的语义校验。
 *
 * <p>按 action 校验字段归属：OPEN_VIEW 要求完整 open
 * 字段集合，CHANGE_DIRECTORY/FOCUS_TERMINAL/COLLAPSE_MOBILE/CLOSE_VIEW 要求已绑定 view，LIST_VIEWS 禁止任何 view
 * 字段。IDE 结果区分失败回执、快照与绑定回执三类形态，并保证快照内 top-level 绑定与对应 summary 一致。绑定事实以 {@code workspacePath} 为正
 * bindingEpoch 为必备字段；零根视图的 {@code directory} 可缺省。
 */
final class ControlProtocolIdeChecks {

  /** IDE 列表视图上限。 */
  private static final int MAX_LISTED_IDE_VIEWS = 64;

  private static final int MAX_ATTACHMENT_BYTES = 64 * 1024;

  private static final Pattern IDE_CODE_PATTERN = Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");

  private ControlProtocolIdeChecks() {}

  /** IDE 类消息的 payload 入口；case 顺序与原校验一致。 */
  static void validatePayload(Envelope envelope, MessageType messageType) {
    switch (messageType) {
      case IDE_COMMAND -> {
        requirePayload(envelope, Envelope.PayloadCase.IDE_COMMAND, "IDE_COMMAND");
        validateIdeCommand(envelope.getIdeCommand());
      }
      case IDE_RESULT -> {
        requirePayload(envelope, Envelope.PayloadCase.IDE_RESULT, "IDE_RESULT");
        validateIdeResult(envelope.getIdeResult());
      }
      case IDE_LEASE -> {
        requirePayload(envelope, Envelope.PayloadCase.IDE_LEASE, "IDE_LEASE");
        validateIdeLease(envelope.getIdeLease());
      }
    }
  }

  static void validateIdeCommand(IdeCommand command) {
    if (command.getAction() != IdeAction.OPEN_ATTACHMENT
        && (command.hasAttachmentId() || command.hasAttachmentText())) {
      throw new ControlProtocolException("attachment fields are only valid for OPEN_ATTACHMENT");
    }
    switch (command.getAction()) {
      case OPEN_VIEW -> {
        requireUuid(command.hasViewId(), command.getViewId(), "ideCommand.viewId");
        requireUuid(
            command.hasBrowserSessionId(),
            command.getBrowserSessionId(),
            "ideCommand.browserSessionId");
        requireUuid(command.hasEventNodeId(), command.getEventNodeId(), "ideCommand.eventNodeId");
        requireUuid(
            command.hasConnectionId(), command.getConnectionId(), "ideCommand.connectionId");
        requireUuid(command.hasClaimId(), command.getClaimId(), "ideCommand.claimId");
        requireExpectedDaemonInstance(command);
        requireInt64(
            command.hasExpectedBindingEpoch(),
            command.getExpectedBindingEpoch(),
            "ideCommand.expectedBindingEpoch",
            0,
            Long.MAX_VALUE);
        if (command.hasBindingEpoch() || command.hasDiscard()) {
          throw new ControlProtocolException("OPEN_VIEW must not declare bindingEpoch or discard");
        }
        if (command.getMode() != IdeOpenMode.CREATE && command.getMode() != IdeOpenMode.RESUME) {
          throw new ControlProtocolException("ideCommand.mode is required for OPEN_VIEW");
        }
        if (command.getMode() == IdeOpenMode.CREATE && command.getExpectedBindingEpoch() != 0) {
          throw new ControlProtocolException("OPEN_VIEW create requires expectedBindingEpoch 0");
        }
        if (command.getMode() == IdeOpenMode.RESUME && command.getExpectedBindingEpoch() <= 0) {
          throw new ControlProtocolException(
              "OPEN_VIEW resume requires a positive expectedBindingEpoch");
        }
        if (command.hasDirectory()) {
          requireIdePath(command.getDirectory(), "ideCommand.directory");
        }
      }
      case CHANGE_DIRECTORY -> {
        requireBoundView(command, false);
        requireExpectedDaemonInstance(command);
        if (!command.hasDirectory()) {
          throw new ControlProtocolException("ideCommand.directory is required");
        }
        requireIdePath(command.getDirectory(), "ideCommand.directory");
        rejectOpenFields(command);
      }
      case OPEN_ATTACHMENT -> {
        requireBoundView(command, false);
        requireExpectedDaemonInstance(command);
        if (command.hasDirectory()) {
          throw new ControlProtocolException("OPEN_ATTACHMENT must not declare directory");
        }
        rejectOpenFields(command);
        requireUuid(
            command.hasAttachmentId(), command.getAttachmentId(), "ideCommand.attachmentId");
        String text =
            requireUtf8Text(
                command.hasAttachmentText(),
                command.getAttachmentText(),
                "ideCommand.attachmentText",
                MAX_ATTACHMENT_BYTES,
                false);
        if (text.codePoints()
            .anyMatch(
                codePoint ->
                    Character.isISOControl(codePoint)
                        && codePoint != '\t'
                        && codePoint != '\n'
                        && codePoint != '\r')) {
          throw new ControlProtocolException(
              "ideCommand.attachmentText must be text, not binary data");
        }
      }
      case FOCUS_TERMINAL, COLLAPSE_MOBILE -> {
        requireBoundView(command, false);
        requireExpectedDaemonInstance(command);
        if (command.hasDirectory()) {
          throw new ControlProtocolException("ideCommand.directory is not valid for this action");
        }
        rejectOpenFields(command);
      }
      case CLOSE_VIEW -> {
        requireBoundView(command, true);
        requireExpectedDaemonInstance(command);
        if (command.hasDirectory()) {
          throw new ControlProtocolException("CLOSE_VIEW must not declare directory");
        }
        rejectOpenFields(command);
      }
      case LIST_VIEWS -> {
        if (command.hasViewId()
            || command.hasBrowserSessionId()
            || command.hasEventNodeId()
            || command.hasConnectionId()
            || command.hasClaimId()
            || command.hasBindingEpoch()
            || command.hasExpectedBindingEpoch()
            || command.hasExpectedDaemonInstanceId()
            || command.getMode() != IdeOpenMode.IDE_OPEN_MODE_UNSPECIFIED
            || command.hasDirectory()
            || command.hasDiscard()) {
          throw new ControlProtocolException("IDE list command has invalid fields");
        }
      }
      default -> throw new ControlProtocolException("ideCommand.action is required");
    }
  }

  static void requireExpectedDaemonInstance(IdeCommand command) {
    requireUuid(
        command.hasExpectedDaemonInstanceId(),
        command.getExpectedDaemonInstanceId(),
        "ideCommand.expectedDaemonInstanceId");
  }

  static void requireBoundView(IdeCommand command, boolean discardRequired) {
    requireUuid(command.hasViewId(), command.getViewId(), "ideCommand.viewId");
    requireUuid(
        command.hasBrowserSessionId(),
        command.getBrowserSessionId(),
        "ideCommand.browserSessionId");
    requirePositive(
        command.hasBindingEpoch(), command.getBindingEpoch(), "ideCommand.bindingEpoch");
    if (discardRequired && !command.hasDiscard()) {
      throw new ControlProtocolException("ideCommand.discard is required");
    }
    if (!discardRequired && command.hasDiscard()) {
      throw new ControlProtocolException("ideCommand.discard is not valid for this action");
    }
  }

  static void rejectOpenFields(IdeCommand command) {
    if (command.hasEventNodeId()
        || command.hasConnectionId()
        || command.hasClaimId()
        || command.hasExpectedBindingEpoch()
        || command.getMode() != IdeOpenMode.IDE_OPEN_MODE_UNSPECIFIED) {
      throw new ControlProtocolException(
          "ideCommand declares fields that do not belong to its action");
    }
  }

  static void validateIdeResult(IdeResult result) {
    if (!result.hasOk()) {
      throw new ControlProtocolException("ideResult.ok is required");
    }
    if (!result.getOk()) {
      validateFailedIdeResult(result);
      return;
    }
    if (result.hasCode() || result.hasMessage()) {
      throw new ControlProtocolException("successful IDE result must not declare code or message");
    }
    if (result.hasRevision()) {
      validateIdeSnapshot(result);
    } else if (result.getViewsCount() > 0) {
      throw new ControlProtocolException("ideResult.revision is required with views");
    }
    if (declaresBinding(result)) {
      validateBoundIdeResult(result);
      return;
    }
    if (result.hasViewId()) {
      requireUuid(true, result.getViewId(), "ideResult.viewId");
      return;
    }
    if (!result.hasRevision()) {
      throw new ControlProtocolException("successful IDE result must declare an outcome");
    }
  }

  /** 失败回执：只能带 code/message，可附只读快照；带 views 时必须同时带 revision。 */
  private static void validateFailedIdeResult(IdeResult result) {
    if (result.hasRevision()) {
      if (declaresViewState(result)) {
        throw new ControlProtocolException(
            "failed IDE result with a snapshot must only declare code, message, revision and views");
      }
      validateIdeSnapshot(result);
    } else if (result.getViewsCount() > 0) {
      throw new ControlProtocolException("ideResult.revision is required with views");
    } else if (declaresViewState(result)) {
      throw new ControlProtocolException("failed IDE result must only declare code and message");
    }
    requirePattern(result.hasCode(), result.getCode(), "ideResult.code", IDE_CODE_PATTERN);
    requireText(
        result.hasMessage(),
        result.getMessage(),
        "ideResult.message",
        DaemonUpdateResult.MAX_MESSAGE_CHARS,
        true);
  }

  /** 完整绑定回执：零根视图可缺省 directory，workspacePath 与 epoch 永远必填；带 revision 时还必须与快照中的同名 summary 一致。 */
  private static void validateBoundIdeResult(IdeResult result) {
    requireUuid(result.hasViewId(), result.getViewId(), "ideResult.viewId");
    requireUuid(
        result.hasBrowserSessionId(), result.getBrowserSessionId(), "ideResult.browserSessionId");
    requireUuid(result.hasEventNodeId(), result.getEventNodeId(), "ideResult.eventNodeId");
    requireUuid(result.hasConnectionId(), result.getConnectionId(), "ideResult.connectionId");
    requireUuid(result.hasClaimId(), result.getClaimId(), "ideResult.claimId");
    requirePositive(result.hasBindingEpoch(), result.getBindingEpoch(), "ideResult.bindingEpoch");
    if (result.hasDirectory()) {
      requireIdePath(result.getDirectory(), "ideResult.directory");
    }
    requireIdePath(
        requirePresent(
            result.hasWorkspacePath(), result.getWorkspacePath(), "ideResult.workspacePath"),
        "ideResult.workspacePath");
    if (result.hasRevision()) {
      validateSnapshotBinding(result);
    }
  }

  /** view 自身字段（不含 directoryListing）：这些字段只能与 view 形态的回执同时出现。 */
  private static boolean declaresViewState(IdeResult result) {
    return result.hasViewId()
        || result.hasBrowserSessionId()
        || result.hasEventNodeId()
        || result.hasConnectionId()
        || result.hasClaimId()
        || result.hasBindingEpoch()
        || result.hasDirectory()
        || result.hasWorkspacePath();
  }

  /** 任一绑定字段出现即按完整绑定回执校验；directory 只在绑定回执里合法。 */
  private static boolean declaresBinding(IdeResult result) {
    return result.hasBrowserSessionId()
        || result.hasEventNodeId()
        || result.hasConnectionId()
        || result.hasClaimId()
        || result.hasBindingEpoch()
        || result.hasDirectory()
        || result.hasWorkspacePath();
  }

  static void validateIdeSnapshot(IdeResult result) {
    requireInt64(
        result.hasRevision(), result.getRevision(), "ideResult.revision", 0, Long.MAX_VALUE);
    if (result.getViewsCount() > MAX_LISTED_IDE_VIEWS) {
      throw new ControlProtocolException(
          "ideResult.views must not exceed " + MAX_LISTED_IDE_VIEWS + " entries");
    }
    Set<String> viewIds = new HashSet<>();
    String previousViewId = null;
    for (int index = 0; index < result.getViewsCount(); index++) {
      IdeViewSummary view = result.getViews(index);
      validateView(view, "ideResult.views[" + index + "]");
      if (view.getBindingEpoch() > result.getRevision()) {
        throw new ControlProtocolException(
            "ideResult.views[" + index + "].bindingEpoch must not exceed ideResult.revision");
      }
      if (!viewIds.add(view.getViewId())) {
        throw new ControlProtocolException("ideResult.views must not repeat viewId");
      }
      if (previousViewId != null && previousViewId.compareTo(view.getViewId()) > 0) {
        throw new ControlProtocolException("ideResult.views must be sorted by viewId");
      }
      previousViewId = view.getViewId();
    }
  }

  /**
   * top-level 完整绑定与同一快照中的 summary 必须一致，防止旧绑定套在新 revision 快照上。
   *
   * <p>没有快照的简单回执（例如 FOCUS_TERMINAL/COLLAPSE_MOBILE 只带 viewId）不经过本校验。
   */
  static void validateSnapshotBinding(IdeResult result) {
    for (IdeViewSummary view : result.getViewsList()) {
      if (!view.getViewId().equals(result.getViewId())) {
        continue;
      }
      if (!view.getBrowserSessionId().equals(result.getBrowserSessionId())
          || !view.getEventNodeId().equals(result.getEventNodeId())
          || !view.getConnectionId().equals(result.getConnectionId())
          || !view.getClaimId().equals(result.getClaimId())
          || view.getBindingEpoch() != result.getBindingEpoch()
          || !sameOptionalPath(view, result)
          || !view.getWorkspacePath().equals(result.getWorkspacePath())) {
        throw new ControlProtocolException(
            "ideResult snapshot view must match the top-level binding");
      }
      return;
    }
    throw new ControlProtocolException(
        "ideResult snapshot must contain the top-level binding view");
  }

  /** 零根绑定两侧都没有 directory；仅一侧缺省与带值都属于不一致。 */
  private static boolean sameOptionalPath(IdeViewSummary view, IdeResult result) {
    if (!view.hasDirectory() || !result.hasDirectory()) {
      return view.hasDirectory() == result.hasDirectory();
    }
    return view.getDirectory().equals(result.getDirectory());
  }

  static void validateView(IdeViewSummary view, String path) {
    requireUuid(view.hasViewId(), view.getViewId(), path + ".viewId");
    requireUuid(view.hasBrowserSessionId(), view.getBrowserSessionId(), path + ".browserSessionId");
    requireUuid(view.hasEventNodeId(), view.getEventNodeId(), path + ".eventNodeId");
    requireUuid(view.hasConnectionId(), view.getConnectionId(), path + ".connectionId");
    requireUuid(view.hasClaimId(), view.getClaimId(), path + ".claimId");
    requirePositive(view.hasBindingEpoch(), view.getBindingEpoch(), path + ".bindingEpoch");
    if (view.hasDirectory()) {
      requireIdePath(view.getDirectory(), path + ".directory");
    }
    requireIdePath(
        requirePresent(view.hasWorkspacePath(), view.getWorkspacePath(), path + ".workspacePath"),
        path + ".workspacePath");
    if (view.getState() != IdeViewState.OPEN && view.getState() != IdeViewState.CLOSING) {
      throw new ControlProtocolException(path + ".state is required");
    }
  }

  static void validateIdeLease(IdeLease lease) {
    requirePositive(lease.hasRound(), lease.getRound(), "ideLease.round");
    if (lease.getBindingsCount() > DaemonIdeLease.MAX_BINDINGS) {
      throw new ControlProtocolException(
          "ideLease.bindings must not exceed " + DaemonIdeLease.MAX_BINDINGS);
    }
    Set<String> viewIds = new HashSet<>();
    for (int index = 0; index < lease.getBindingsCount(); index++) {
      IdeLeaseBinding binding = lease.getBindings(index);
      String path = "ideLease.bindings[" + index + "]";
      requireUuid(binding.hasViewId(), binding.getViewId(), path + ".viewId");
      if (!viewIds.add(binding.getViewId())) {
        throw new ControlProtocolException("ideLease.bindings must not repeat viewId");
      }
      requireUuid(
          binding.hasBrowserSessionId(), binding.getBrowserSessionId(), path + ".browserSessionId");
      requirePositive(binding.hasBindingEpoch(), binding.getBindingEpoch(), path + ".bindingEpoch");
    }
  }
}
