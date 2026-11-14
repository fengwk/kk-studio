package fun.fengwk.kkstudio.platform.daemon.protocol;

import static fun.fengwk.kkstudio.platform.daemon.protocol.ControlProtocolFields.MAX_CALL_ID_CHARS;
import static fun.fengwk.kkstudio.platform.daemon.protocol.ControlProtocolFields.MAX_FAILURE_MESSAGE_CHARS;
import static fun.fengwk.kkstudio.platform.daemon.protocol.ControlProtocolFields.MEDIA_TYPE_PATTERN;
import static fun.fengwk.kkstudio.platform.daemon.protocol.ControlProtocolFields.SHA256_LOWER_PATTERN;
import static fun.fengwk.kkstudio.platform.daemon.protocol.ControlProtocolFields.requireInt64;
import static fun.fengwk.kkstudio.platform.daemon.protocol.ControlProtocolFields.requireJsonObject;
import static fun.fengwk.kkstudio.platform.daemon.protocol.ControlProtocolFields.requireJsonValue;
import static fun.fengwk.kkstudio.platform.daemon.protocol.ControlProtocolFields.requireMediaType;
import static fun.fengwk.kkstudio.platform.daemon.protocol.ControlProtocolFields.requireNoUnknown;
import static fun.fengwk.kkstudio.platform.daemon.protocol.ControlProtocolFields.requirePattern;
import static fun.fengwk.kkstudio.platform.daemon.protocol.ControlProtocolFields.requirePayload;
import static fun.fengwk.kkstudio.platform.daemon.protocol.ControlProtocolFields.requirePositive;
import static fun.fengwk.kkstudio.platform.daemon.protocol.ControlProtocolFields.requireText;
import static fun.fengwk.kkstudio.platform.daemon.protocol.ControlProtocolFields.requireUtf8Text;
import static fun.fengwk.kkstudio.platform.daemon.protocol.ControlProtocolFields.requireUuid;

import fun.fengwk.kkstudio.common.resource.ResourceRef;
import fun.fengwk.kkstudio.common.result.JsonResultContent;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResult;
import fun.fengwk.kkstudio.platform.daemon.DaemonPresignedGet;
import fun.fengwk.kkstudio.platform.daemon.DaemonPresignedPut;
import fun.fengwk.kkstudio.platform.daemon.DaemonUpdateProgress;

import java.util.Map;

/**
 * 能力结果内容与资源传输票据的语义校验。
 *
 * <p>结果内容按 body 联合类型校验文本/嵌入 JSON/资源三种形态，只有 COMPLETED 允许资源；上传票据 PENDING 携带 uploadId 与预签名 PUT、READY
 * 只带 uploadId、FAILED 只带去敏说明；下载票据没有 PENDING，READY 携带预签名 GET、大小与摘要。
 */
final class ControlProtocolTransferChecks {

  private ControlProtocolTransferChecks() {}

  /** 资源传输类消息的 payload 入口；case 顺序与原校验一致。 */
  static void validatePayload(Envelope envelope, MessageType messageType) {
    switch (messageType) {
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
      case RESOURCE_DOWNLOAD_REQUEST -> {
        requirePayload(
            envelope, Envelope.PayloadCase.RESOURCE_DOWNLOAD_REQUEST, "RESOURCE_DOWNLOAD_REQUEST");
        validateDownloadRequest(envelope.getResourceDownloadRequest());
      }
      case RESOURCE_DOWNLOAD_TICKET -> {
        requirePayload(
            envelope, Envelope.PayloadCase.RESOURCE_DOWNLOAD_TICKET, "RESOURCE_DOWNLOAD_TICKET");
        validateDownloadTicket(envelope.getResourceDownloadTicket());
      }
      case TEMPORARY_RESOURCE_POLICY -> {
        requirePayload(
            envelope, Envelope.PayloadCase.TEMPORARY_RESOURCE_POLICY, "TEMPORARY_RESOURCE_POLICY");
        validatePolicy(envelope.getTemporaryResourcePolicy());
      }
    }
  }

  static void validateResult(
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

  static void validateContent(Content content, String path, boolean allowResources) {
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

  static void validateResource(ResourceContent resource, String path) {
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

  static void validateUploadRequest(ResourceUploadRequest request) {
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

  static void validateUploadTicket(ResourceUploadTicket ticket) {
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

  static void validatePresigned(PresignedPut put) {
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
    requirePositive(
        put.hasExpiresAtEpochMs(), put.getExpiresAtEpochMs(), "presignedPut.expiresAtEpochMs");
  }

  static void validateUploadCommit(ResourceUploadCommit commit) {
    requireUuid(commit.hasTransferId(), commit.getTransferId(), "uploadCommit.transferId");
    requireUuid(commit.hasUploadId(), commit.getUploadId(), "uploadCommit.uploadId");
  }

  static void validateDownloadRequest(ResourceDownloadRequest request) {
    requireUuid(request.hasTransferId(), request.getTransferId(), "downloadRequest.transferId");
  }

  static void validateDownloadTicket(ResourceDownloadTicket ticket) {
    requireUuid(ticket.hasTransferId(), ticket.getTransferId(), "downloadTicket.transferId");
    switch (ticket.getState()) {
      case TICKET_PENDING -> throw new ControlProtocolException(
          "downloadTicket must not use TICKET_PENDING");
      case TICKET_READY -> {
        if (ticket.hasMessage()) {
          throw new ControlProtocolException("READY download ticket must not declare a message");
        }
        validatePresignedGet(ticket.getPresignedGet());
        requireInt64(
            ticket.hasSize(),
            ticket.getSize(),
            "downloadTicket.size",
            0,
            DaemonUpdateProgress.MAX_SAFE_BYTE_COUNT);
        requirePattern(
            ticket.hasSha256(), ticket.getSha256(), "downloadTicket.sha256", SHA256_LOWER_PATTERN);
      }
      case TICKET_FAILED -> {
        if (ticket.hasPresignedGet() || ticket.hasSize() || ticket.hasSha256()) {
          throw new ControlProtocolException("FAILED download ticket requires a message only");
        }
        requireText(
            ticket.hasMessage(),
            ticket.getMessage(),
            "downloadTicket.message",
            MAX_FAILURE_MESSAGE_CHARS,
            true);
      }
      default -> throw new ControlProtocolException("downloadTicket.state is required");
    }
  }

  static void validatePresignedGet(PresignedGet get) {
    String method = requireText(get.hasMethod(), get.getMethod(), "presignedGet.method", 16, true);
    if (!"GET".equals(method)) {
      throw new ControlProtocolException("presignedGet.method must be GET");
    }
    requireText(
        get.hasUrl(), get.getUrl(), "presignedGet.url", DaemonPresignedGet.MAX_URL_CHARS, true);
    requirePositive(
        get.hasExpiresAtEpochMs(), get.getExpiresAtEpochMs(), "presignedGet.expiresAtEpochMs");
  }

  static void validatePolicy(TemporaryResourcePolicy policy) {
    requirePositive(
        policy.hasTemporaryResourceTtlSeconds(),
        policy.getTemporaryResourceTtlSeconds(),
        "temporaryResourcePolicy.temporaryResourceTtlSeconds");
    requirePositive(
        policy.hasTemporaryResourceCleanupIntervalSeconds(),
        policy.getTemporaryResourceCleanupIntervalSeconds(),
        "temporaryResourcePolicy.temporaryResourceCleanupIntervalSeconds");
  }
}
