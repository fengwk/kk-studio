package fun.fengwk.kkstudio.platform.daemon.protocol;

import static fun.fengwk.kkstudio.platform.daemon.protocol.ControlProtocolFields.SHA256_LOWER_PATTERN;
import static fun.fengwk.kkstudio.platform.daemon.protocol.ControlProtocolFields.requireBoundedMessage;
import static fun.fengwk.kkstudio.platform.daemon.protocol.ControlProtocolFields.requireCapabilityId;
import static fun.fengwk.kkstudio.platform.daemon.protocol.ControlProtocolFields.requireDaemonVersion;
import static fun.fengwk.kkstudio.platform.daemon.protocol.ControlProtocolFields.requireHttpOrigin;
import static fun.fengwk.kkstudio.platform.daemon.protocol.ControlProtocolFields.requireInt64;
import static fun.fengwk.kkstudio.platform.daemon.protocol.ControlProtocolFields.requireJsonObject;
import static fun.fengwk.kkstudio.platform.daemon.protocol.ControlProtocolFields.requireNoUnknown;
import static fun.fengwk.kkstudio.platform.daemon.protocol.ControlProtocolFields.requirePattern;
import static fun.fengwk.kkstudio.platform.daemon.protocol.ControlProtocolFields.requirePayload;
import static fun.fengwk.kkstudio.platform.daemon.protocol.ControlProtocolFields.requirePositive;
import static fun.fengwk.kkstudio.platform.daemon.protocol.ControlProtocolFields.requireSingleLine;
import static fun.fengwk.kkstudio.platform.daemon.protocol.ControlProtocolFields.requireText;
import static fun.fengwk.kkstudio.platform.daemon.protocol.ControlProtocolFields.requireUuid;
import static fun.fengwk.kkstudio.platform.daemon.protocol.ControlProtocolFields.requireZone;

import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityId;
import fun.fengwk.kkstudio.platform.daemon.DaemonUpdateCommand;
import fun.fengwk.kkstudio.platform.daemon.DaemonUpdateProgress;
import fun.fengwk.kkstudio.platform.daemon.DaemonUpdateResult;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 控制协议的应用层语义校验。
 *
 * <p>编解码与领域映射见 {@link ControlProtocolCodec}；本类只补 proto3 无法表达的契约：消息类型与 payload
 * 一致性、scope、必填、枚举未指定值、安全整数、UUID/SHA/媒体类型、UTF-8 字节上限、既有 granular limits 以及递归未知字段。嵌入 JSON
 * 只做严格文本校验，绝不重新序列化。
 */
public final class ControlProtocolValidator {

  private static final Pattern LSP_SERVER_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_.-]+$");
  private static final Pattern CONFIG_ERROR_CODE_PATTERN =
      Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");

  private static final Set<MessageType> BOUND =
      Set.of(
          MessageType.SETTINGS,
          MessageType.READY,
          MessageType.HEARTBEAT,
          MessageType.RUNTIME_STATUS,
          MessageType.INVOKE,
          MessageType.STARTED,
          MessageType.PROGRESS,
          MessageType.COMPLETED,
          MessageType.FAILED,
          MessageType.CANCEL,
          MessageType.CANCELLED,
          MessageType.RECOVER,
          MessageType.RECOVER_NOT_FOUND,
          MessageType.EXECUTION_EXITED,
          MessageType.EXECUTION_EXIT_ACK,
          MessageType.RESOURCE_UPLOAD_REQUEST,
          MessageType.RESOURCE_UPLOAD_TICKET,
          MessageType.RESOURCE_UPLOAD_COMMIT,
          MessageType.RESOURCE_DOWNLOAD_REQUEST,
          MessageType.RESOURCE_DOWNLOAD_TICKET,
          MessageType.TEMPORARY_RESOURCE_POLICY,
          MessageType.UPDATE,
          MessageType.UPDATE_RESULT,
          MessageType.IDE_COMMAND,
          MessageType.IDE_RESULT,
          MessageType.IDE_LEASE,
          MessageType.ENVIRONMENT_CONFIG,
          MessageType.ERROR);

  private static final Set<MessageType> INVOCATION =
      Set.of(
          MessageType.INVOKE,
          MessageType.STARTED,
          MessageType.PROGRESS,
          MessageType.COMPLETED,
          MessageType.FAILED,
          MessageType.CANCEL,
          MessageType.CANCELLED,
          MessageType.RECOVER,
          MessageType.RECOVER_NOT_FOUND,
          MessageType.EXECUTION_EXITED,
          MessageType.EXECUTION_EXIT_ACK,
          MessageType.RESOURCE_UPLOAD_REQUEST,
          MessageType.RESOURCE_UPLOAD_TICKET,
          MessageType.RESOURCE_UPLOAD_COMMIT,
          MessageType.RESOURCE_DOWNLOAD_REQUEST,
          MessageType.RESOURCE_DOWNLOAD_TICKET,
          MessageType.IDE_COMMAND,
          MessageType.IDE_RESULT);

  private static final Set<MessageType> NO_INVOCATION =
      Set.of(
          MessageType.SETTINGS,
          MessageType.READY,
          MessageType.HEARTBEAT,
          MessageType.RUNTIME_STATUS,
          MessageType.TEMPORARY_RESOURCE_POLICY,
          MessageType.UPDATE,
          MessageType.UPDATE_RESULT,
          MessageType.IDE_LEASE,
          MessageType.ENVIRONMENT_CONFIG);

  private ControlProtocolValidator() {}

  /** 校验 envelope 的类型、scope、payload 一致性与全部业务必填/限额。 */
  public static Envelope validate(Envelope envelope) {
    if (envelope == null) {
      throw new ControlProtocolException("envelope must not be null");
    }
    requireNoUnknown(envelope, "envelope");
    MessageType messageType = envelope.getMessageType();
    if (messageType == MessageType.MESSAGE_TYPE_UNSPECIFIED
        || messageType == MessageType.UNRECOGNIZED) {
      throw new ControlProtocolException("messageType must be a known non-unspecified value");
    }
    validateScope(envelope, messageType);
    switch (messageType) {
      case SETTINGS,
          READY,
          HEARTBEAT,
          RUNTIME_STATUS,
          ENVIRONMENT_CONFIG,
          ERROR -> validateHostPayload(envelope, messageType);
      case INVOKE,
          STARTED,
          PROGRESS,
          COMPLETED,
          FAILED,
          CANCEL,
          CANCELLED,
          RECOVER,
          RECOVER_NOT_FOUND,
          EXECUTION_EXITED,
          EXECUTION_EXIT_ACK -> validateInvocationPayload(envelope, messageType);
      case RESOURCE_UPLOAD_REQUEST,
          RESOURCE_UPLOAD_TICKET,
          RESOURCE_UPLOAD_COMMIT,
          RESOURCE_DOWNLOAD_REQUEST,
          RESOURCE_DOWNLOAD_TICKET,
          TEMPORARY_RESOURCE_POLICY -> ControlProtocolTransferChecks.validatePayload(
          envelope, messageType);
      case UPDATE, UPDATE_RESULT -> validateUpdatePayload(envelope, messageType);
      case IDE_COMMAND, IDE_RESULT, IDE_LEASE -> ControlProtocolIdeChecks.validatePayload(
          envelope, messageType);
      default -> throw new ControlProtocolException("unsupported messageType: " + messageType);
    }
    return envelope;
  }

  /** Daemon 自述类消息：settings、ready、heartbeat、runtimeStatus 与环境配置。 */
  private static void validateHostPayload(Envelope envelope, MessageType messageType) {
    switch (messageType) {
      case SETTINGS -> {
        requirePayload(envelope, Envelope.PayloadCase.SETTINGS, "SETTINGS");
        validateSettings(envelope.getSettings());
      }
      case READY -> {
        requirePayload(envelope, Envelope.PayloadCase.READY, "READY");
        validateReady(envelope.getReady());
      }
      case HEARTBEAT -> {
        requirePayload(envelope, Envelope.PayloadCase.HEARTBEAT, "HEARTBEAT");
        validateHeartbeat(envelope.getHeartbeat());
      }
      case RUNTIME_STATUS -> {
        requirePayload(envelope, Envelope.PayloadCase.RUNTIME_STATUS, "RUNTIME_STATUS");
        validateRuntimeStatus(envelope.getRuntimeStatus());
      }
      case ENVIRONMENT_CONFIG -> {
        requirePayload(envelope, Envelope.PayloadCase.ENVIRONMENT_CONFIG, "ENVIRONMENT_CONFIG");
        validateEnvironmentConfig(envelope.getEnvironmentConfig());
      }
      case ERROR -> {
        requirePayload(envelope, Envelope.PayloadCase.ERROR, "ERROR");
        validateError(envelope.getError());
      }
    }
  }

  /** invocation 生命周期消息：从 invoke 到 executionExited 的每一步都绑定同一 invocationId。 */
  private static void validateInvocationPayload(Envelope envelope, MessageType messageType) {
    switch (messageType) {
      case INVOKE -> {
        requirePayload(envelope, Envelope.PayloadCase.INVOKE, "INVOKE");
        validateInvoke(envelope.getInvoke());
      }
      case STARTED -> {
        requirePayload(envelope, Envelope.PayloadCase.STARTED, "STARTED");
        validateStarted(envelope.getStarted());
      }
      case PROGRESS -> {
        requirePayload(envelope, Envelope.PayloadCase.PROGRESS, "PROGRESS");
        ControlProtocolTransferChecks.validateResult(
            envelope.getProgress().getResult(),
            envelope.getProgress().hasResult(),
            "progress",
            false);
      }
      case COMPLETED -> {
        requirePayload(envelope, Envelope.PayloadCase.COMPLETED, "COMPLETED");
        ControlProtocolTransferChecks.validateResult(
            envelope.getCompleted().getResult(),
            envelope.getCompleted().hasResult(),
            "completed",
            true);
      }
      case FAILED -> {
        requirePayload(envelope, Envelope.PayloadCase.FAILED, "FAILED");
        requireBoundedMessage(
            envelope.getFailed().hasMessage(), envelope.getFailed().getMessage(), "failed.message");
      }
      case CANCEL -> requirePayload(envelope, Envelope.PayloadCase.CANCEL, "CANCEL");
      case CANCELLED -> {
        requirePayload(envelope, Envelope.PayloadCase.CANCELLED, "CANCELLED");
        requireBoundedMessage(
            envelope.getCancelled().hasReason(),
            envelope.getCancelled().getReason(),
            "cancelled.reason");
      }
      case RECOVER -> requirePayload(envelope, Envelope.PayloadCase.RECOVER, "RECOVER");
      case RECOVER_NOT_FOUND -> {
        requirePayload(envelope, Envelope.PayloadCase.RECOVER_NOT_FOUND, "RECOVER_NOT_FOUND");
      }
      case EXECUTION_EXITED -> {
        requirePayload(envelope, Envelope.PayloadCase.EXECUTION_EXITED, "EXECUTION_EXITED");
      }
      case EXECUTION_EXIT_ACK -> {
        requirePayload(envelope, Envelope.PayloadCase.EXECUTION_EXIT_ACK, "EXECUTION_EXIT_ACK");
      }
    }
  }

  /** 更新指令与更新回执。 */
  private static void validateUpdatePayload(Envelope envelope, MessageType messageType) {
    switch (messageType) {
      case UPDATE -> {
        requirePayload(envelope, Envelope.PayloadCase.UPDATE, "UPDATE");
        validateUpdate(envelope.getUpdate());
      }
      case UPDATE_RESULT -> {
        requirePayload(envelope, Envelope.PayloadCase.UPDATE_RESULT, "UPDATE_RESULT");
        validateUpdateResult(envelope.getUpdateResult());
      }
    }
  }

  private static void validateScope(Envelope envelope, MessageType messageType) {
    if (BOUND.contains(messageType)) {
      requireUuid(envelope.hasEnvironmentId(), envelope.getEnvironmentId(), "environmentId");
    } else if (envelope.hasEnvironmentId()) {
      requireUuid(true, envelope.getEnvironmentId(), "environmentId");
    }
    if (INVOCATION.contains(messageType)) {
      requireUuid(envelope.hasInvocationId(), envelope.getInvocationId(), "invocationId");
    } else if (NO_INVOCATION.contains(messageType) && envelope.hasInvocationId()) {
      throw new ControlProtocolException(messageType + " must not declare invocationId");
    } else if (envelope.hasInvocationId()) {
      requireUuid(true, envelope.getInvocationId(), "invocationId");
    }
  }

  private static void validateSettings(Settings settings) {
    if (!settings.hasResourceBudgets()) {
      throw new ControlProtocolException("settings.resourceBudgets is required");
    }
    ResourceBudgets budgets = settings.getResourceBudgets();
    requirePositive(
        budgets.hasTemporaryResourceTtlSeconds(),
        budgets.getTemporaryResourceTtlSeconds(),
        "settings.resourceBudgets.temporaryResourceTtlSeconds");
    requirePositive(
        budgets.hasTemporaryResourceCleanupIntervalSeconds(),
        budgets.getTemporaryResourceCleanupIntervalSeconds(),
        "settings.resourceBudgets.temporaryResourceCleanupIntervalSeconds");
  }

  private static void validateEnvironmentConfig(EnvironmentConfig environmentConfig) {
    requireInt64(
        environmentConfig.hasVersion(),
        environmentConfig.getVersion(),
        "environmentConfig.version",
        0,
        Long.MAX_VALUE);
    if (!environmentConfig.hasConfig()) {
      throw new ControlProtocolException("environmentConfig.config is required");
    }
    validateDaemonConfiguration(environmentConfig.getConfig(), "environmentConfig.config");
  }

  private static void validateDaemonConfiguration(DaemonConfiguration config, String path) {
    requireHttpOrigin(
        requireText(true, config.getStudioUrl(), path + ".studioUrl", 2048, true),
        path + ".studioUrl",
        false);
    if (config.hasBashExecutable()) {
      requireSingleLine(true, config.getBashExecutable(), path + ".bashExecutable", 4096);
    }
    if (config.hasLsp()) {
      validateLspConfiguration(config.getLsp(), path + ".lsp");
    }
    if (config.hasProxyUrl()) {
      requireHttpOrigin(
          requireText(true, config.getProxyUrl(), path + ".proxyUrl", 2048, true),
          path + ".proxyUrl",
          true);
    }
  }

  private static void validateLspConfiguration(LspConfiguration lsp, String path) {
    if (lsp.getServersCount() == 0) {
      throw new ControlProtocolException(path + ".servers must declare at least one server");
    }
    Set<String> ids = new HashSet<>();
    for (int index = 0; index < lsp.getServersCount(); index++) {
      LspServer server = lsp.getServers(index);
      String serverPath = path + ".servers[" + index + "]";
      String id = requireText(true, server.getId(), serverPath + ".id", 256, true);
      if (!LSP_SERVER_ID_PATTERN.matcher(id).matches()) {
        throw new ControlProtocolException(serverPath + ".id must match [A-Za-z0-9_.-]+");
      }
      if (!ids.add(id)) {
        throw new ControlProtocolException(path + ".servers must not repeat id");
      }
      validateStringList(server.getCommandList(), serverPath + ".command", true, false);
      validateStringList(server.getExtensionsList(), serverPath + ".extensions", true, true);
      validateStringList(server.getRootMarkersList(), serverPath + ".rootMarkers", false, false);
      validateStringList(
          server.getFirstMatchMarkersList(), serverPath + ".firstMatchMarkers", false, false);
    }
  }

  private static void validateStringList(
      List<String> values, String path, boolean required, boolean extension) {
    if (values.isEmpty()) {
      if (required) {
        throw new ControlProtocolException(path + " must be a nonempty array of strings");
      }
      return;
    }
    for (int index = 0; index < values.size(); index++) {
      String value = requireText(true, values.get(index), path + "[" + index + "]", 4096, true);
      if (extension
          && (value.length() < 2
              || !value.startsWith(".")
              || value.contains("/")
              || value.contains("\\"))) {
        throw new ControlProtocolException(
            path + "[" + index + "] must be a file extension with a leading dot");
      }
    }
  }

  private static void validateReady(Ready ready) {
    String daemonVersion =
        requireDaemonVersion(
            ready.hasDaemonVersion(), ready.getDaemonVersion(), "ready.daemonVersion");
    if (!ready.hasHostInfo()) {
      throw new ControlProtocolException("ready.hostInfo is required");
    }
    validateHostInfo(ready.getHostInfo());
    if (!ready.hasRuntimeStatus()) {
      throw new ControlProtocolException("ready.runtimeStatus is required");
    }
    RuntimeSnapshot snapshot = validateSnapshot(ready.getRuntimeStatus(), "ready.runtimeStatus");
    if (!daemonVersion.equals(snapshot.getStatus().getDaemonVersion())) {
      throw new ControlProtocolException(
          "ready.runtimeStatus.status.daemonVersion must match ready.daemonVersion");
    }
  }

  private static void validateHostInfo(HostInfo info) {
    if (info.getOperatingSystem() == OperatingSystem.OPERATING_SYSTEM_UNSPECIFIED
        || info.getOperatingSystem() == OperatingSystem.UNRECOGNIZED) {
      throw new ControlProtocolException("ready.hostInfo.operatingSystem is required");
    }
    if (info.getArchitecture() == Architecture.ARCHITECTURE_UNSPECIFIED
        || info.getArchitecture() == Architecture.UNRECOGNIZED) {
      throw new ControlProtocolException("ready.hostInfo.architecture is required");
    }
    requireZone(info.hasTimeZone(), info.getTimeZone(), "ready.hostInfo.timeZone");
    requireSingleLine(
        info.hasUserName(), info.getUserName(), "ready.hostInfo.userName", Integer.MAX_VALUE);
    requireSingleLine(
        info.hasHomeDirectory(),
        info.getHomeDirectory(),
        "ready.hostInfo.homeDirectory",
        Integer.MAX_VALUE);
    requireSingleLine(
        info.hasTempDirectory(),
        info.getTempDirectory(),
        "ready.hostInfo.tempDirectory",
        Integer.MAX_VALUE);
  }

  private static void validateHeartbeat(Heartbeat heartbeat) {
    if (!heartbeat.hasRuntimeStatus()) {
      throw new ControlProtocolException("heartbeat.runtimeStatus is required");
    }
    validateSnapshot(heartbeat.getRuntimeStatus(), "heartbeat.runtimeStatus");
    if (heartbeat.hasIdeLeaseRound()) {
      requirePositive(true, heartbeat.getIdeLeaseRound(), "heartbeat.ideLeaseRound");
    }
  }

  private static void validateRuntimeStatus(RuntimeStatus status) {
    if (!status.hasRuntimeStatus()) {
      throw new ControlProtocolException("runtimeStatus.runtimeStatus is required");
    }
    validateSnapshot(status.getRuntimeStatus(), "runtimeStatus");
  }

  private static RuntimeSnapshot validateSnapshot(RuntimeSnapshot snapshot, String path) {
    requirePositive(snapshot.hasRevision(), snapshot.getRevision(), path + ".revision");
    if (!snapshot.hasStatus()) {
      throw new ControlProtocolException(path + ".status is required");
    }
    RuntimeBody body = snapshot.getStatus();
    requireDaemonVersion(
        body.hasDaemonVersion(), body.getDaemonVersion(), path + ".status.daemonVersion");
    if (body.hasUpdate()) {
      validateUpdateProgress(body.getUpdate(), path + ".status.update");
    }
    if (body.hasConfiguration()) {
      validateConfigurationStatus(body.getConfiguration(), path + ".status.configuration");
    }
    return snapshot;
  }

  private static void validateConfigurationStatus(ConfigurationStatus configuration, String path) {
    if (configuration.hasAppliedVersion()) {
      requireInt64(
          true, configuration.getAppliedVersion(), path + ".appliedVersion", 0, Long.MAX_VALUE);
    }
    if (configuration.hasProxyUrl()) {
      if (!configuration.hasAppliedVersion()) {
        throw new ControlProtocolException(path + ".proxyUrl requires appliedVersion");
      }
      requireHttpOrigin(
          requireText(true, configuration.getProxyUrl(), path + ".proxyUrl", 2048, true),
          path + ".proxyUrl",
          true);
    }
    if (configuration.hasError()) {
      ConfigurationError error = configuration.getError();
      requireInt64(
          error.hasVersion(), error.getVersion(), path + ".error.version", 0, Long.MAX_VALUE);
      String code = requireText(true, error.getCode(), path + ".error.code", 64, true);
      if (!CONFIG_ERROR_CODE_PATTERN.matcher(code).matches()) {
        throw new ControlProtocolException(
            path + ".error.code must be a stable upper-snake-case identifier");
      }
      if (error.hasField()) {
        requireSingleLine(true, error.getField(), path + ".error.field", 256);
      }
    }
  }

  private static void validateUpdateProgress(UpdateProgress update, String path) {
    requireDaemonVersion(
        update.hasTargetVersion(), update.getTargetVersion(), path + ".targetVersion");
    requireInt64(
        update.hasDownloadedBytes(),
        update.getDownloadedBytes(),
        path + ".downloadedBytes",
        0,
        DaemonUpdateProgress.MAX_SAFE_BYTE_COUNT);
    if (update.hasTotalBytes()) {
      requireInt64(
          true,
          update.getTotalBytes(),
          path + ".totalBytes",
          update.getDownloadedBytes(),
          DaemonUpdateProgress.MAX_SAFE_BYTE_COUNT);
    }
  }

  private static void validateInvoke(Invoke invoke) {
    String capabilityId =
        requireText(
            invoke.hasCapabilityId(),
            invoke.getCapabilityId(),
            "invoke.capabilityId",
            EnvironmentCapabilityId.MAX_LENGTH,
            true);
    requireCapabilityId(capabilityId);
    requireJsonObject(
        invoke.hasArgumentsJson(),
        invoke.getArgumentsJson(),
        "invoke.argumentsJson",
        ControlFraming.MAX_MESSAGE_BYTES);
    requireInt64(
        invoke.hasTimeoutMillis(),
        invoke.getTimeoutMillis(),
        "invoke.timeoutMillis",
        0,
        DaemonUpdateProgress.MAX_SAFE_BYTE_COUNT);
    requireInt64(
        invoke.hasMaxOutputBytes(),
        invoke.getMaxOutputBytes(),
        "invoke.maxOutputBytes",
        1,
        DaemonUpdateProgress.MAX_SAFE_BYTE_COUNT);
    if (!invoke.hasImageInputTier()) {
      throw new ControlProtocolException("invoke.imageInputTier is required");
    }
    if (invoke.getImageInputTier() == ImageInputTier.IMAGE_INPUT_TIER_UNSPECIFIED
        || invoke.getImageInputTier() == ImageInputTier.UNRECOGNIZED) {
      throw new ControlProtocolException("invoke.imageInputTier must be a supported image tier");
    }
    if (invoke.hasExpectedFile()) {
      validateExpectedFile(capabilityId, invoke.getExpectedFile());
    }
  }

  private static void validateExpectedFile(String capabilityId, ExpectedFile expected) {
    boolean edit = "fs.edit".equals(capabilityId);
    boolean write = "fs.write".equals(capabilityId);
    if (!edit && !write) {
      throw new ControlProtocolException(
          "invoke.expectedFile is only valid for fs.edit or fs.write");
    }
    requireText(
        expected.hasPath(),
        expected.getPath(),
        "invoke.expectedFile.path",
        Integer.MAX_VALUE,
        true);
    if (expected.hasSha256()) {
      requirePattern(
          true, expected.getSha256(), "invoke.expectedFile.sha256", SHA256_LOWER_PATTERN);
    } else if (edit) {
      throw new ControlProtocolException("invoke.expectedFile.sha256 is required for fs.edit");
    }
  }

  private static void validateStarted(Started started) {
    if (started.hasReplayed() && !started.getReplayed()) {
      throw new ControlProtocolException("started.replayed must be true when present");
    }
  }

  /** 更新指令：operationId 必须是规范 UUID，targetVersion 为合法的 daemon 版本。 */
  static void validateUpdate(Update update) {
    requireNoUnknown(update, "update");
    String operationId =
        requireText(
            update.hasOperationId(), update.getOperationId(), "update.operationId", 36, true);
    try {
      DaemonUpdateCommand.validateOperationId(operationId);
    } catch (IllegalArgumentException error) {
      throw new ControlProtocolException(error.getMessage(), error);
    }
    requireDaemonVersion(
        update.hasTargetVersion(), update.getTargetVersion(), "update.targetVersion");
  }

  private static void validateUpdateResult(UpdateResult result) {
    requireText(
        result.hasOperationId(), result.getOperationId(), "updateResult.operationId", 256, true);
    if (result.getPhase() == UpdatePhase.UPDATE_PHASE_UNSPECIFIED
        || result.getPhase() == UpdatePhase.UNRECOGNIZED) {
      throw new ControlProtocolException("updateResult.phase is required");
    }
    if (result.hasMessage()) {
      requireText(
          true,
          result.getMessage(),
          "updateResult.message",
          DaemonUpdateResult.MAX_MESSAGE_CHARS,
          true);
      if (result.getMessage().codePoints().anyMatch(Character::isISOControl)) {
        throw new ControlProtocolException(
            "updateResult.message must not contain control characters");
      }
    }
  }

  private static void validateError(Error error) {
    requireBoundedMessage(error.hasMessage(), error.getMessage(), "error.message");
  }
}
