package fun.fengwk.kkstudio.platform.daemon.protocol;

import com.google.protobuf.Descriptors;
import com.google.protobuf.Message;

import fun.fengwk.kkstudio.common.id.Uuids;
import fun.fengwk.kkstudio.common.resource.ResourceRef;
import fun.fengwk.kkstudio.common.result.JsonResultContent;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityId;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResult;
import fun.fengwk.kkstudio.platform.daemon.DaemonCapabilities;
import fun.fengwk.kkstudio.platform.daemon.DaemonEnvironmentInfo;
import fun.fengwk.kkstudio.platform.daemon.DaemonIdeLease;
import fun.fengwk.kkstudio.platform.daemon.DaemonPresignedPut;
import fun.fengwk.kkstudio.platform.daemon.DaemonUpdateCommand;
import fun.fengwk.kkstudio.platform.daemon.DaemonUpdateProgress;
import fun.fengwk.kkstudio.platform.daemon.DaemonUpdateResult;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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

  /** callId 的字符上限。 */
  public static final int MAX_CALL_ID_CHARS = 256;

  /** 上传票据失败说明的字符上限。 */
  public static final int MAX_FAILURE_MESSAGE_CHARS = 512;

  /** 展示名的字符上限。 */
  public static final int MAX_DISPLAY_NAME_CHARS = 256;

  /** FAILED / ERROR message 的字符上限。 */
  public static final int MAX_ERROR_MESSAGE_CHARS = 4096;

  /** IDE 路径的字符上限。 */
  public static final int MAX_IDE_PATH_CHARS = 4096;

  /** IDE 列表视图上限。 */
  public static final int MAX_LISTED_IDE_VIEWS = 64;

  private static final Pattern SHA256_LOWER_PATTERN = Pattern.compile("^[0-9a-f]{64}$");
  private static final Pattern MEDIA_TYPE_PATTERN =
      Pattern.compile("^[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+$");
  private static final Pattern IDE_CODE_PATTERN = Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");
  private static final Pattern SKILL_PACKAGE_NAME_PATTERN =
      Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");

  /** 单轮期望集合与状态上报的包数上限。 */
  public static final int MAX_SKILL_PACKAGES = 512;

  /** 技能包下载地址的字符上限。 */
  public static final int MAX_SKILL_DOWNLOAD_URL_CHARS = 4096;

  /** 技能包本地安装根的字符上限。 */
  public static final int MAX_SKILL_LOCAL_PATH_CHARS = 4096;

  /** 技能包安装错误的字符上限。 */
  public static final int MAX_SKILL_ERROR_CHARS = 512;

  /** 技能包名与包版本的字符上限。 */
  public static final int MAX_SKILL_PACKAGE_NAME_CHARS = 64;

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
          MessageType.RESOURCE_UPLOAD_REQUEST,
          MessageType.RESOURCE_UPLOAD_TICKET,
          MessageType.RESOURCE_UPLOAD_COMMIT,
          MessageType.TEMPORARY_RESOURCE_POLICY,
          MessageType.UPDATE,
          MessageType.UPDATE_RESULT,
          MessageType.IDE_COMMAND,
          MessageType.IDE_RESULT,
          MessageType.IDE_LEASE,
          MessageType.SKILL_SYNC,
          MessageType.SKILL_STATE,
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
          MessageType.RESOURCE_UPLOAD_REQUEST,
          MessageType.RESOURCE_UPLOAD_TICKET,
          MessageType.RESOURCE_UPLOAD_COMMIT,
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
          MessageType.SKILL_SYNC,
          MessageType.SKILL_STATE);

  private ControlProtocolValidator() {}

  /** 校验 426 升级拒绝载荷。它不是控制流 envelope。 */
  public static UpgradeRequired validateUpgrade(UpgradeRequired upgrade) {
    if (upgrade == null) {
      throw new ControlProtocolException("upgradeRequired must not be null");
    }
    requireNoUnknown(upgrade, "upgradeRequired");
    if (!upgrade.hasTargetVersion()) {
      throw new ControlProtocolException("upgradeRequired.targetVersion is required");
    }
    try {
      DaemonCapabilities.validateDaemonVersion(upgrade.getTargetVersion());
    } catch (IllegalArgumentException error) {
      throw new ControlProtocolException("upgradeRequired.targetVersion is invalid", error);
    }
    return upgrade;
  }

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
        validateResult(
            envelope.getProgress().getResult(),
            envelope.getProgress().hasResult(),
            "progress",
            false);
      }
      case COMPLETED -> {
        requirePayload(envelope, Envelope.PayloadCase.COMPLETED, "COMPLETED");
        validateResult(
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
      case RECOVER_NOT_FOUND -> requirePayload(
          envelope, Envelope.PayloadCase.RECOVER_NOT_FOUND, "RECOVER_NOT_FOUND");
      case EXECUTION_EXITED -> requirePayload(
          envelope, Envelope.PayloadCase.EXECUTION_EXITED, "EXECUTION_EXITED");
      case RESOURCE_UPLOAD_REQUEST -> {
        requirePayload(
            envelope, Envelope.PayloadCase.RESOURCE_UPLOAD_REQUEST, "RESOURCE_UPLOAD_REQUEST");
        validateUploadRequest(envelope.getResourceUploadRequest());
      }
      case RESOURCE_UPLOAD_TICKET -> {
        requirePayload(
            envelope, Envelope.PayloadCase.RESOURCE_UPLOAD_TICKET, "RESOURCE_UPLOAD_TICKET");
        validateUploadTicket(envelope.getResourceUploadTicket());
      }
      case RESOURCE_UPLOAD_COMMIT -> {
        requirePayload(
            envelope, Envelope.PayloadCase.RESOURCE_UPLOAD_COMMIT, "RESOURCE_UPLOAD_COMMIT");
        validateUploadCommit(envelope.getResourceUploadCommit());
      }
      case TEMPORARY_RESOURCE_POLICY -> {
        requirePayload(
            envelope, Envelope.PayloadCase.TEMPORARY_RESOURCE_POLICY, "TEMPORARY_RESOURCE_POLICY");
        validatePolicy(envelope.getTemporaryResourcePolicy());
      }
      case UPDATE -> {
        requirePayload(envelope, Envelope.PayloadCase.UPDATE, "UPDATE");
        validateUpdate(envelope.getUpdate());
      }
      case UPDATE_RESULT -> {
        requirePayload(envelope, Envelope.PayloadCase.UPDATE_RESULT, "UPDATE_RESULT");
        validateUpdateResult(envelope.getUpdateResult());
      }
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
      case SKILL_SYNC -> {
        requirePayload(envelope, Envelope.PayloadCase.SKILL_SYNC, "SKILL_SYNC");
        validateSkillSync(envelope.getSkillSync());
      }
      case SKILL_STATE -> {
        requirePayload(envelope, Envelope.PayloadCase.SKILL_STATE, "SKILL_STATE");
        validateSkillState(envelope.getSkillState());
      }
      case ERROR -> {
        requirePayload(envelope, Envelope.PayloadCase.ERROR, "ERROR");
        validateError(envelope.getError());
      }
      default -> throw new ControlProtocolException("unsupported messageType: " + messageType);
    }
    return envelope;
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
    requireInt64(
        budgets.hasMaxResourceBytes(),
        budgets.getMaxResourceBytes(),
        "settings.resourceBudgets.maxResourceBytes",
        1,
        DaemonUpdateProgress.MAX_SAFE_BYTE_COUNT);
    requirePositive(
        budgets.hasTemporaryResourceTtlSeconds(),
        budgets.getTemporaryResourceTtlSeconds(),
        "settings.resourceBudgets.temporaryResourceTtlSeconds");
    requirePositive(
        budgets.hasTemporaryResourceCleanupIntervalSeconds(),
        budgets.getTemporaryResourceCleanupIntervalSeconds(),
        "settings.resourceBudgets.temporaryResourceCleanupIntervalSeconds");
    requireText(
        settings.hasNoProxyHosts(),
        settings.getNoProxyHosts(),
        "settings.noProxyHosts",
        4096,
        false);
    if (settings.hasProxyUrl()) {
      requireText(true, settings.getProxyUrl(), "settings.proxyUrl", 2048, true);
    }
    if (settings.hasDisplayName()) {
      requireText(
          true, settings.getDisplayName(), "settings.displayName", MAX_DISPLAY_NAME_CHARS, true);
    }
  }

  private static void validateReady(Ready ready) {
    String daemonVersion =
        requireDaemonVersion(
            ready.hasDaemonVersion(), ready.getDaemonVersion(), "ready.daemonVersion");
    if (!ready.hasEnvironment()) {
      throw new ControlProtocolException("ready.environment is required");
    }
    validateEnvironment(ready.getEnvironment());
    if (!ready.hasRuntimeStatus()) {
      throw new ControlProtocolException("ready.runtimeStatus is required");
    }
    RuntimeSnapshot snapshot = validateSnapshot(ready.getRuntimeStatus(), "ready.runtimeStatus");
    if (!daemonVersion.equals(snapshot.getStatus().getDaemonVersion())) {
      throw new ControlProtocolException(
          "ready.runtimeStatus.status.daemonVersion must match ready.daemonVersion");
    }
  }

  private static void validateEnvironment(EnvironmentInfo info) {
    if (info.getOperatingSystem() == OperatingSystem.OPERATING_SYSTEM_UNSPECIFIED
        || info.getOperatingSystem() == OperatingSystem.UNRECOGNIZED) {
      throw new ControlProtocolException("ready.environment.operatingSystem is required");
    }
    if (info.getArchitecture() == Architecture.ARCHITECTURE_UNSPECIFIED
        || info.getArchitecture() == Architecture.UNRECOGNIZED) {
      throw new ControlProtocolException("ready.environment.architecture is required");
    }
    requireZone(info.hasTimeZone(), info.getTimeZone(), "ready.environment.timeZone");
    requireSingleLine(
        info.hasUserName(), info.getUserName(), "ready.environment.userName", Integer.MAX_VALUE);
    requireSingleLine(
        info.hasHomeDirectory(),
        info.getHomeDirectory(),
        "ready.environment.homeDirectory",
        Integer.MAX_VALUE);
    requireSingleLine(
        info.hasNote(),
        info.getNote(),
        "ready.environment.note",
        DaemonEnvironmentInfo.MAX_NOTE_CHARS);
    requireSingleLine(
        info.hasTempDirectory(),
        info.getTempDirectory(),
        "ready.environment.tempDirectory",
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
    return snapshot;
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
    if (invoke.hasExpectedFile()) {
      validateExpectedFile(capabilityId, invoke.getExpectedFile());
    }
  }

  private static void validateExpectedFile(String capabilityId, ExpectedFile expected) {
    if (!"fs.edit".equals(capabilityId)) {
      throw new ControlProtocolException("invoke.expectedFile is only valid for fs.edit");
    }
    requireText(
        expected.hasPath(),
        expected.getPath(),
        "invoke.expectedFile.path",
        Integer.MAX_VALUE,
        true);
    requirePattern(
        expected.hasSha256(),
        expected.getSha256(),
        "invoke.expectedFile.sha256",
        SHA256_LOWER_PATTERN);
  }

  private static void validateStarted(Started started) {
    if (started.hasReplayed() && !started.getReplayed()) {
      throw new ControlProtocolException("started.replayed must be true when present");
    }
  }

  private static void validateResult(
      CapabilityResult result, boolean present, String path, boolean allowResources) {
    if (!present) {
      throw new ControlProtocolException(path + ".result is required");
    }
    requireText(
        result.hasCallId(), result.getCallId(), path + ".result.callId", MAX_CALL_ID_CHARS, true);
    if (!result.hasError()) {
      throw new ControlProtocolException(path + ".result.error is required");
    }
    requireJsonObject(
        result.hasDetailsJson(),
        result.getDetailsJson(),
        path + ".result.detailsJson",
        EnvironmentCapabilityResult.MAX_DETAILS_JSON_UTF8_BYTES);
    if (result.getContentsCount() > EnvironmentCapabilityResult.MAX_CONTENT_ITEMS) {
      throw new ControlProtocolException(
          path
              + ".result.contents must not exceed "
              + EnvironmentCapabilityResult.MAX_CONTENT_ITEMS
              + " items");
    }
    for (int index = 0; index < result.getContentsCount(); index++) {
      validateContent(
          result.getContents(index), path + ".result.contents[" + index + "]", allowResources);
    }
  }

  private static void validateContent(Content content, String path, boolean allowResources) {
    requireNoUnknown(content, path);
    switch (content.getBodyCase()) {
      case TEXT -> {
        TextContent text = content.getText();
        requireText(text.hasText(), text.getText(), path + ".text", Integer.MAX_VALUE, false);
      }
      case JSON -> {
        JsonContent json = content.getJson();
        requireJsonValue(
            json.hasJson(), json.getJson(), path + ".json", JsonResultContent.MAX_JSON_UTF8_BYTES);
      }
      case RESOURCE -> {
        if (!allowResources) {
          throw new ControlProtocolException(path + " must not declare a resource");
        }
        validateResource(content.getResource(), path);
      }
      default -> throw new ControlProtocolException(
          path + " must declare exactly one content body");
    }
  }

  private static void validateResource(ResourceContent resource, String path) {
    requireUuid(resource.hasUploadId(), resource.getUploadId(), path + ".uploadId");
    String mediaType =
        requireText(
            resource.hasMediaType(),
            resource.getMediaType(),
            path + ".mediaType",
            ResourceRef.MAX_MEDIA_TYPE_ASCII_BYTES,
            true);
    if (!MEDIA_TYPE_PATTERN.matcher(mediaType).matches()) {
      throw new ControlProtocolException(
          path + ".mediaType must be canonical lowercase type/subtype");
    }
    if (resource.hasName()) {
      String name =
          requireUtf8Text(
              true, resource.getName(), path + ".name", ResourceRef.MAX_NAME_UTF8_BYTES, true);
      if (name.codePoints().anyMatch(Character::isISOControl)) {
        throw new ControlProtocolException(path + ".name must not contain control characters");
      }
    }
    requireInt64(
        resource.hasSize(),
        resource.getSize(),
        path + ".size",
        0,
        DaemonUpdateProgress.MAX_SAFE_BYTE_COUNT);
    requirePattern(
        resource.hasSha256(), resource.getSha256(), path + ".sha256", SHA256_LOWER_PATTERN);
    if (resource.hasPreview()) {
      requireUtf8Text(
          true,
          resource.getPreview(),
          path + ".preview",
          ResourceRef.MAX_PREVIEW_UTF8_BYTES,
          false);
    }
  }

  private static void validateUploadRequest(ResourceUploadRequest request) {
    requireUuid(request.hasTransferId(), request.getTransferId(), "uploadRequest.transferId");
    requireMediaType(request.hasMediaType(), request.getMediaType(), "uploadRequest.mediaType");
    if (request.hasName()) {
      requireUtf8Text(
          true, request.getName(), "uploadRequest.name", ResourceRef.MAX_NAME_UTF8_BYTES, true);
    }
    requireInt64(
        request.hasSize(),
        request.getSize(),
        "uploadRequest.size",
        0,
        DaemonUpdateProgress.MAX_SAFE_BYTE_COUNT);
    requirePattern(
        request.hasSha256(), request.getSha256(), "uploadRequest.sha256", SHA256_LOWER_PATTERN);
  }

  private static void validateUploadTicket(ResourceUploadTicket ticket) {
    requireUuid(ticket.hasTransferId(), ticket.getTransferId(), "uploadTicket.transferId");
    switch (ticket.getState()) {
      case TICKET_PENDING -> {
        requireUuid(ticket.hasUploadId(), ticket.getUploadId(), "uploadTicket.uploadId");
        if (!ticket.hasPresignedPut() || ticket.hasMessage()) {
          throw new ControlProtocolException(
              "PENDING ticket requires uploadId and presignedPut only");
        }
        validatePresigned(ticket.getPresignedPut());
      }
      case TICKET_READY -> {
        requireUuid(ticket.hasUploadId(), ticket.getUploadId(), "uploadTicket.uploadId");
        if (ticket.hasPresignedPut() || ticket.hasMessage()) {
          throw new ControlProtocolException("READY ticket requires uploadId only");
        }
      }
      case TICKET_FAILED -> {
        if (ticket.hasUploadId() || ticket.hasPresignedPut()) {
          throw new ControlProtocolException("FAILED ticket requires a message only");
        }
        requireText(
            ticket.hasMessage(),
            ticket.getMessage(),
            "uploadTicket.message",
            MAX_FAILURE_MESSAGE_CHARS,
            true);
      }
      default -> throw new ControlProtocolException("uploadTicket.state is required");
    }
  }

  private static void validatePresigned(PresignedPut put) {
    String method = requireText(put.hasMethod(), put.getMethod(), "presignedPut.method", 16, true);
    if (!"PUT".equals(method)) {
      throw new ControlProtocolException("presignedPut.method must be PUT");
    }
    requireText(
        put.hasUrl(), put.getUrl(), "presignedPut.url", DaemonPresignedPut.MAX_URL_CHARS, true);
    if (put.getHeadersCount() > DaemonPresignedPut.MAX_HEADERS) {
      throw new ControlProtocolException(
          "presignedPut.headers must not exceed " + DaemonPresignedPut.MAX_HEADERS + " entries");
    }
    for (Map.Entry<String, String> header : put.getHeadersMap().entrySet()) {
      requireText(
          true, header.getKey(), "presignedPut.header", DaemonPresignedPut.MAX_HEADER_CHARS, true);
      requireText(
          true,
          header.getValue(),
          "presignedPut.header",
          DaemonPresignedPut.MAX_HEADER_CHARS,
          true);
    }
    requireInstant(put.hasExpiresAt(), put.getExpiresAt(), "presignedPut.expiresAt");
  }

  private static void validateUploadCommit(ResourceUploadCommit commit) {
    requireUuid(commit.hasTransferId(), commit.getTransferId(), "uploadCommit.transferId");
    requireUuid(commit.hasUploadId(), commit.getUploadId(), "uploadCommit.uploadId");
  }

  private static void validatePolicy(TemporaryResourcePolicy policy) {
    requirePositive(
        policy.hasTemporaryResourceTtlSeconds(),
        policy.getTemporaryResourceTtlSeconds(),
        "temporaryResourcePolicy.temporaryResourceTtlSeconds");
    requirePositive(
        policy.hasTemporaryResourceCleanupIntervalSeconds(),
        policy.getTemporaryResourceCleanupIntervalSeconds(),
        "temporaryResourcePolicy.temporaryResourceCleanupIntervalSeconds");
  }

  /** 期望集合：包名唯一，摘要、大小与预签名地址必须完整。 */
  public static void validateSkillSync(SkillSync sync) {
    requireNoUnknown(sync, "skillSync");
    if (sync.getPackagesCount() > MAX_SKILL_PACKAGES) {
      throw new ControlProtocolException("skillSync.packages exceeds the entry limit");
    }
    Set<String> names = new HashSet<>();
    for (int index = 0; index < sync.getPackagesCount(); index++) {
      SkillPackage skillPackage = sync.getPackages(index);
      String path = "skillSync.packages[" + index + "]";
      requireNoUnknown(skillPackage, path);
      String packageName =
          requireSkillPackageName(
              skillPackage.hasPackageName(), skillPackage.getPackageName(), path + ".packageName");
      if (!names.add(packageName)) {
        throw new ControlProtocolException(
            "skillSync contains a duplicate package: " + packageName);
      }
      requireSha256(skillPackage.hasSha256(), skillPackage.getSha256(), path + ".sha256");
      if (!skillPackage.hasSizeBytes() || skillPackage.getSizeBytes() < 0) {
        throw new ControlProtocolException(path + ".sizeBytes must not be negative");
      }
      requireSkillDownloadUrl(
          skillPackage.hasDownloadUrl(), skillPackage.getDownloadUrl(), path + ".downloadUrl");
    }
  }

  /** 状态上报：包名唯一，摘要与本地路径成对，错误有界且去敏。 */
  public static void validateSkillState(SkillState state) {
    requireNoUnknown(state, "skillState");
    if (state.getPackagesCount() > MAX_SKILL_PACKAGES) {
      throw new ControlProtocolException("skillState.packages exceeds the entry limit");
    }
    Set<String> names = new HashSet<>();
    for (int index = 0; index < state.getPackagesCount(); index++) {
      SkillPackageState entry = state.getPackages(index);
      String path = "skillState.packages[" + index + "]";
      requireNoUnknown(entry, path);
      String packageName =
          requireSkillPackageName(
              entry.hasPackageName(), entry.getPackageName(), path + ".packageName");
      if (!names.add(packageName)) {
        throw new ControlProtocolException(
            "skillState contains a duplicate package: " + packageName);
      }
      boolean hasSha = entry.hasInstalledSha256();
      boolean hasPath = entry.hasLocalPath();
      if (hasSha != hasPath) {
        throw new ControlProtocolException(
            path + " must declare installedSha256 and localPath together");
      }
      if (hasSha) {
        requireSha256(true, entry.getInstalledSha256(), path + ".installedSha256");
        requireSkillLocalPath(true, entry.getLocalPath(), path + ".localPath");
      }
      if (entry.hasError()) {
        requireText(true, entry.getError(), path + ".error", MAX_SKILL_ERROR_CHARS, false);
        if (entry.getError().codePoints().anyMatch(Character::isISOControl)) {
          throw new ControlProtocolException(path + ".error must not contain control characters");
        }
      }
    }
  }

  private static String requireSkillPackageName(boolean present, String value, String name) {
    String packageName = requireText(present, value, name, MAX_SKILL_PACKAGE_NAME_CHARS, true);
    if (!SKILL_PACKAGE_NAME_PATTERN.matcher(packageName).matches()) {
      throw new ControlProtocolException(name + " is not a canonical skill package name");
    }
    return packageName;
  }

  private static void requireSha256(boolean present, String value, String name) {
    String sha256 = requireText(present, value, name, 64, true);
    if (!SHA256_LOWER_PATTERN.matcher(sha256).matches()) {
      throw new ControlProtocolException(name + " must be 64 lowercase hex characters");
    }
  }

  private static void requireSkillDownloadUrl(boolean present, String value, String name) {
    String url = requireText(present, value, name, MAX_SKILL_DOWNLOAD_URL_CHARS, true);
    int scheme = url.indexOf("://");
    if (scheme <= 0
        || !(url.startsWith("http://") || url.startsWith("https://"))
        || url.codePoints().anyMatch(Character::isISOControl)) {
      throw new ControlProtocolException(name + " must be an http(s) URL");
    }
  }

  private static void requireSkillLocalPath(boolean present, String value, String name) {
    requireText(present, value, name, MAX_SKILL_LOCAL_PATH_CHARS, false);
    if (value.codePoints().anyMatch(Character::isISOControl)) {
      throw new ControlProtocolException(name + " must not contain control characters");
    }
  }

  public static void validateUpdate(Update update) {
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

  private static void validateIdeCommand(IdeCommand command) {
    switch (command.getAction()) {
      case OPEN_VIEW -> {
        requireUuid(command.hasViewId(), command.getViewId(), "ideCommand.viewId");
        requireUuid(command.hasDocumentId(), command.getDocumentId(), "ideCommand.documentId");
        requireUuid(command.hasEventNodeId(), command.getEventNodeId(), "ideCommand.eventNodeId");
        requireUuid(
            command.hasConnectionId(), command.getConnectionId(), "ideCommand.connectionId");
        requireUuid(command.hasClaimId(), command.getClaimId(), "ideCommand.claimId");
        if (command.hasBindingEpoch() || command.hasDiscard()) {
          throw new ControlProtocolException("OPEN_VIEW must not declare bindingEpoch or discard");
        }
        if (command.getMode() != IdeOpenMode.CREATE && command.getMode() != IdeOpenMode.RESUME) {
          throw new ControlProtocolException("ideCommand.mode is required for OPEN_VIEW");
        }
        if (command.hasDirectory()) {
          requireIdePath(command.getDirectory(), "ideCommand.directory");
        }
      }
      case CHANGE_DIRECTORY -> {
        requireBoundView(command, false);
        if (!command.hasDirectory()) {
          throw new ControlProtocolException("ideCommand.directory is required");
        }
        requireIdePath(command.getDirectory(), "ideCommand.directory");
        rejectOpenFields(command);
      }
      case FOCUS_TERMINAL, COLLAPSE_MOBILE -> {
        requireBoundView(command, false);
        if (command.hasDirectory()) {
          throw new ControlProtocolException("ideCommand.directory is not valid for this action");
        }
        rejectOpenFields(command);
      }
      case CLOSE_VIEW -> {
        requireBoundView(command, true);
        if (command.hasDirectory()) {
          throw new ControlProtocolException("CLOSE_VIEW must not declare directory");
        }
        rejectOpenFields(command);
      }
      case LIST_VIEWS, LIST_DIRECTORIES -> {
        if (command.hasViewId()
            || command.hasDocumentId()
            || command.hasEventNodeId()
            || command.hasConnectionId()
            || command.hasClaimId()
            || command.hasBindingEpoch()
            || command.getMode() != IdeOpenMode.IDE_OPEN_MODE_UNSPECIFIED
            || (command.getAction() == IdeAction.LIST_VIEWS && command.hasDirectory())
            || command.hasDiscard()) {
          throw new ControlProtocolException("IDE list command has invalid fields");
        }
        if (command.hasDirectory()) {
          requireIdePath(command.getDirectory(), "ideCommand.directory");
        }
      }
      default -> throw new ControlProtocolException("ideCommand.action is required");
    }
  }

  private static void requireBoundView(IdeCommand command, boolean discardRequired) {
    requireUuid(command.hasViewId(), command.getViewId(), "ideCommand.viewId");
    requireUuid(command.hasDocumentId(), command.getDocumentId(), "ideCommand.documentId");
    requirePositive(
        command.hasBindingEpoch(), command.getBindingEpoch(), "ideCommand.bindingEpoch");
    if (discardRequired && !command.hasDiscard()) {
      throw new ControlProtocolException("ideCommand.discard is required");
    }
    if (!discardRequired && command.hasDiscard()) {
      throw new ControlProtocolException("ideCommand.discard is not valid for this action");
    }
  }

  private static void rejectOpenFields(IdeCommand command) {
    if (command.hasEventNodeId()
        || command.hasConnectionId()
        || command.hasClaimId()
        || command.getMode() != IdeOpenMode.IDE_OPEN_MODE_UNSPECIFIED) {
      throw new ControlProtocolException(
          "ideCommand declares fields that do not belong to its action");
    }
  }

  private static void validateIdeResult(IdeResult result) {
    if (!result.hasOk()) {
      throw new ControlProtocolException("ideResult.ok is required");
    }
    if (!result.getOk()) {
      if (result.hasViewId()
          || result.hasDocumentId()
          || result.hasEventNodeId()
          || result.hasConnectionId()
          || result.hasClaimId()
          || result.hasBindingEpoch()
          || result.hasDirectory()
          || result.hasWorkspacePath()
          || result.getViewsCount() > 0
          || result.hasDirectoryListing()) {
        throw new ControlProtocolException("failed IDE result must only declare code and message");
      }
      requirePattern(result.hasCode(), result.getCode(), "ideResult.code", IDE_CODE_PATTERN);
      requireText(
          result.hasMessage(),
          result.getMessage(),
          "ideResult.message",
          DaemonUpdateResult.MAX_MESSAGE_CHARS,
          true);
      return;
    }
    if (result.hasCode() || result.hasMessage()) {
      throw new ControlProtocolException("successful IDE result must not declare code or message");
    }
    if (result.hasDirectoryListing()) {
      if (result.hasViewId()
          || result.hasDocumentId()
          || result.hasEventNodeId()
          || result.hasConnectionId()
          || result.hasClaimId()
          || result.hasBindingEpoch()
          || result.hasDirectory()
          || result.hasWorkspacePath()
          || result.getViewsCount() > 0) {
        throw new ControlProtocolException("directory listing result must not contain view fields");
      }
      IdeDirectoryListing listing = result.getDirectoryListing();
      requireIdePath(
          requirePresent(listing.hasPath(), listing.getPath(), "directoryListing.path"),
          "directoryListing.path");
      if (listing.hasParentPath()) {
        requireIdePath(listing.getParentPath(), "directoryListing.parentPath");
      }
      if (!listing.hasTruncated() || listing.getChildrenCount() > 1000) {
        throw new ControlProtocolException(
            "directory listing requires truncated and at most 1000 children");
      }
      for (IdeDirectoryEntry child : listing.getChildrenList()) {
        requireIdePath(
            requirePresent(child.hasName(), child.getName(), "directoryEntry.name"),
            "directoryEntry.name");
        requireIdePath(
            requirePresent(child.hasPath(), child.getPath(), "directoryEntry.path"),
            "directoryEntry.path");
      }
      return;
    }
    if (result.getViewsCount() > 0) {
      if (result.hasViewId()
          || result.hasDocumentId()
          || result.hasDirectory()
          || result.hasWorkspacePath()
          || result.hasEventNodeId()
          || result.hasConnectionId()
          || result.hasClaimId()
          || result.hasBindingEpoch()) {
        throw new ControlProtocolException("IDE list result must only declare views");
      }
      if (result.getViewsCount() > MAX_LISTED_IDE_VIEWS) {
        throw new ControlProtocolException(
            "ideResult.views must not exceed " + MAX_LISTED_IDE_VIEWS + " entries");
      }
      Set<String> viewIds = new HashSet<>();
      for (int index = 0; index < result.getViewsCount(); index++) {
        IdeViewSummary view = result.getViews(index);
        validateView(view, "ideResult.views[" + index + "]");
        if (!viewIds.add(view.getViewId())) {
          throw new ControlProtocolException("ideResult.views must not repeat viewId");
        }
      }
      return;
    }
    boolean binding =
        result.hasDocumentId()
            || result.hasEventNodeId()
            || result.hasConnectionId()
            || result.hasClaimId()
            || result.hasBindingEpoch()
            || result.hasWorkspacePath();
    if (binding) {
      requireUuid(result.hasViewId(), result.getViewId(), "ideResult.viewId");
      requireUuid(result.hasDocumentId(), result.getDocumentId(), "ideResult.documentId");
      requireUuid(result.hasEventNodeId(), result.getEventNodeId(), "ideResult.eventNodeId");
      requireUuid(result.hasConnectionId(), result.getConnectionId(), "ideResult.connectionId");
      requireUuid(result.hasClaimId(), result.getClaimId(), "ideResult.claimId");
      requirePositive(result.hasBindingEpoch(), result.getBindingEpoch(), "ideResult.bindingEpoch");
      requireIdePath(
          requirePresent(result.hasDirectory(), result.getDirectory(), "ideResult.directory"),
          "ideResult.directory");
      requireIdePath(
          requirePresent(
              result.hasWorkspacePath(), result.getWorkspacePath(), "ideResult.workspacePath"),
          "ideResult.workspacePath");
      return;
    }
    if (result.hasDirectory()) {
      requireUuid(result.hasViewId(), result.getViewId(), "ideResult.viewId");
      requireIdePath(result.getDirectory(), "ideResult.directory");
      return;
    }
    if (result.hasViewId()) {
      requireUuid(true, result.getViewId(), "ideResult.viewId");
    }
  }

  private static void validateView(IdeViewSummary view, String path) {
    requireUuid(view.hasViewId(), view.getViewId(), path + ".viewId");
    requireUuid(view.hasDocumentId(), view.getDocumentId(), path + ".documentId");
    requireUuid(view.hasEventNodeId(), view.getEventNodeId(), path + ".eventNodeId");
    requireUuid(view.hasConnectionId(), view.getConnectionId(), path + ".connectionId");
    requireUuid(view.hasClaimId(), view.getClaimId(), path + ".claimId");
    requirePositive(view.hasBindingEpoch(), view.getBindingEpoch(), path + ".bindingEpoch");
    requireIdePath(
        requirePresent(view.hasDirectory(), view.getDirectory(), path + ".directory"),
        path + ".directory");
    requireIdePath(
        requirePresent(view.hasWorkspacePath(), view.getWorkspacePath(), path + ".workspacePath"),
        path + ".workspacePath");
    if (view.getState() != IdeViewState.OPEN && view.getState() != IdeViewState.CLOSING) {
      throw new ControlProtocolException(path + ".state is required");
    }
  }

  private static void validateIdeLease(IdeLease lease) {
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
      requireUuid(binding.hasDocumentId(), binding.getDocumentId(), path + ".documentId");
      requirePositive(binding.hasBindingEpoch(), binding.getBindingEpoch(), path + ".bindingEpoch");
    }
  }

  private static void validateError(Error error) {
    requireBoundedMessage(error.hasMessage(), error.getMessage(), "error.message");
  }

  private static void requirePayload(
      Envelope envelope, Envelope.PayloadCase expected, String messageType) {
    if (envelope.getPayloadCase() != expected) {
      throw new ControlProtocolException(messageType + " envelope must carry the matching payload");
    }
  }

  private static void requireCapabilityId(String capabilityId) {
    try {
      new EnvironmentCapabilityId(capabilityId);
    } catch (IllegalArgumentException error) {
      throw new ControlProtocolException(error.getMessage(), error);
    }
  }

  private static void requireJsonObject(
      boolean present, String value, String name, int maxUtf8Bytes) {
    if (!present) {
      throw new ControlProtocolException(name + " is required");
    }
    ProtocolJson.requireObject(value, name, maxUtf8Bytes);
  }

  private static void requireJsonValue(
      boolean present, String value, String name, int maxUtf8Bytes) {
    if (!present) {
      throw new ControlProtocolException(name + " is required");
    }
    ProtocolJson.requireValue(value, name, maxUtf8Bytes);
  }

  private static String requireText(
      boolean present, String value, String name, int maxChars, boolean nonBlank) {
    if (!present || value == null) {
      throw new ControlProtocolException(name + " is required");
    }
    if (nonBlank && value.isBlank()) {
      throw new ControlProtocolException(name + " must not be blank");
    }
    requireValidUnicode(value, name);
    if (value.length() > maxChars) {
      throw new ControlProtocolException(name + " exceeds " + maxChars + " characters");
    }
    return value;
  }

  private static String requireUtf8Text(
      boolean present, String value, String name, int maxUtf8Bytes, boolean nonBlank) {
    if (!present || value == null) {
      throw new ControlProtocolException(name + " is required");
    }
    if (nonBlank && value.isBlank()) {
      throw new ControlProtocolException(name + " must not be blank");
    }
    int length;
    try {
      length = ResourceRef.utf8LengthUpTo(value, name, maxUtf8Bytes);
    } catch (IllegalArgumentException error) {
      throw new ControlProtocolException(error.getMessage(), error);
    }
    if (length > maxUtf8Bytes) {
      throw new ControlProtocolException(name + " exceeds " + maxUtf8Bytes + " UTF-8 bytes");
    }
    return value;
  }

  private static void requireValidUnicode(String value, String name) {
    try {
      ResourceRef.utf8Length(value, name);
    } catch (IllegalArgumentException error) {
      throw new ControlProtocolException(error.getMessage(), error);
    }
  }

  private static String requirePattern(
      boolean present, String value, String name, Pattern pattern) {
    if (!present || value == null) {
      throw new ControlProtocolException(name + " is required");
    }
    if (!pattern.matcher(value).matches()) {
      throw new ControlProtocolException(name + " has invalid syntax");
    }
    return value;
  }

  private static void requireUuid(boolean present, String value, String name) {
    if (!present || value == null) {
      throw new ControlProtocolException(name + " is required");
    }
    try {
      Uuids.parseCanonical(value, name);
    } catch (IllegalArgumentException error) {
      throw new ControlProtocolException(name + " must be a canonical lowercase UUID", error);
    }
  }

  private static void requireInt64(
      boolean present, long value, String name, long minimum, long maximum) {
    if (!present || value < minimum || value > maximum) {
      throw new ControlProtocolException(
          name + " must be present and in " + minimum + ".." + maximum);
    }
  }

  private static void requirePositive(boolean present, long value, String name) {
    requireInt64(present, value, name, 1, Long.MAX_VALUE);
  }

  private static String requireDaemonVersion(boolean present, String value, String name) {
    if (!present) {
      throw new ControlProtocolException(name + " is required");
    }
    try {
      return DaemonCapabilities.validateDaemonVersion(value);
    } catch (IllegalArgumentException error) {
      throw new ControlProtocolException(name + " is invalid: " + error.getMessage(), error);
    }
  }

  private static void requireZone(boolean present, String value, String name) {
    String zone = requireText(present, value, name, 128, true);
    try {
      ZoneId.of(zone);
    } catch (RuntimeException error) {
      throw new ControlProtocolException(name + " must be a valid ZoneId", error);
    }
  }

  private static void requireSingleLine(boolean present, String value, String name, int maxChars) {
    String text = requireText(present, value, name, maxChars, true);
    if (!text.equals(text.strip())) {
      throw new ControlProtocolException(name + " must not have surrounding whitespace");
    }
    if (text.codePoints().anyMatch(Character::isISOControl)) {
      throw new ControlProtocolException(name + " must not contain control characters");
    }
  }

  private static void requireMediaType(boolean present, String value, String name) {
    String mediaType =
        requireText(present, value, name, ResourceRef.MAX_MEDIA_TYPE_ASCII_BYTES, true);
    if (!MEDIA_TYPE_PATTERN.matcher(mediaType).matches()) {
      throw new ControlProtocolException(name + " must be canonical lowercase type/subtype");
    }
  }

  private static void requireInstant(boolean present, String value, String name) {
    String text = requireText(present, value, name, 64, true);
    try {
      if (!Instant.parse(text).toString().equals(text)) {
        throw new ControlProtocolException(name + " must be a canonical ISO-8601 instant");
      }
    } catch (DateTimeParseException error) {
      throw new ControlProtocolException(name + " must be a canonical ISO-8601 instant", error);
    }
  }

  private static void requireBoundedMessage(boolean present, String value, String name) {
    requireText(present, value, name, MAX_ERROR_MESSAGE_CHARS, true);
  }

  private static String requirePresent(boolean present, String value, String name) {
    if (!present) {
      throw new ControlProtocolException(name + " is required");
    }
    return value;
  }

  private static void requireIdePath(String value, String name) {
    requireText(true, value, name, MAX_IDE_PATH_CHARS, true);
    if (value.codePoints().anyMatch(code -> code <= 0x1f || code == 0x7f)) {
      throw new ControlProtocolException(name + " must not contain control characters");
    }
  }

  private static void requireNoUnknown(Message message, String path) {
    if (!message.getUnknownFields().asMap().isEmpty()) {
      throw new ControlProtocolException(path + " contains unknown protobuf fields");
    }
    for (Map.Entry<Descriptors.FieldDescriptor, Object> entry : message.getAllFields().entrySet()) {
      if (entry.getKey().getJavaType() != Descriptors.FieldDescriptor.JavaType.MESSAGE) {
        continue;
      }
      Object value = entry.getValue();
      String childPath = path + "." + entry.getKey().getJsonName();
      if (value instanceof Message child) {
        requireNoUnknown(child, childPath);
      } else if (value instanceof List<?> children) {
        for (int index = 0; index < children.size(); index++) {
          if (children.get(index) instanceof Message child) {
            requireNoUnknown(child, childPath + "[" + index + "]");
          }
        }
      } else if (value instanceof Map<?, ?> map) {
        for (Object child : map.values()) {
          if (child instanceof Message nested) {
            requireNoUnknown(nested, childPath);
          }
        }
      }
    }
  }
}
