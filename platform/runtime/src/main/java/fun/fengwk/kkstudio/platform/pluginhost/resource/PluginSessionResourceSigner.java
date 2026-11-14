package fun.fengwk.kkstudio.platform.pluginhost.resource;

import fun.fengwk.kkstudio.common.error.DomainErrorCode;
import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.platform.plugin.resource.PluginResourceUnavailableException;
import fun.fengwk.kkstudio.platform.plugin.resource.SessionResourceUri;
import fun.fengwk.kkstudio.platform.storage.dto.StoragePresignedUrlDTO;
import fun.fengwk.kkstudio.platform.storage.service.SessionBlobRefManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;

import java.net.URI;
import java.util.UUID;

/**
 * Session Resource 的授权下载地址签发。
 *
 * <p>URI 必须是规范的 {@code resource://files/<blobId>}，且调用方显式传入执行上下文冻结的 sessionId：只有该 Session 的 {@code
 * session_blob_ref} 确实引用该 blob 时才签发短期预签名 GET。没有 sessionId 时确定性失败，绝不退化成「未鉴权下载」， 也不查询 Harness 存储。
 */
final class PluginSessionResourceSigner {

  private final SessionBlobRefManager sessionBlobRefManager;
  private final StorageBlobManager storageBlobManager;

  PluginSessionResourceSigner(
      SessionBlobRefManager sessionBlobRefManager, StorageBlobManager storageBlobManager) {
    this.sessionBlobRefManager = sessionBlobRefManager;
    this.storageBlobManager = storageBlobManager;
  }

  URI resolve(UUID sessionId, String resourceUri) {
    UUID blobId =
        SessionResourceUri.parse(resourceUri)
            .orElseThrow(
                () ->
                    new PluginResourceUnavailableException(
                        "session resource uri must be resource://files/<blobId>"));
    if (sessionId == null) {
      throw new PluginResourceUnavailableException(
          "session resource access requires an invocation session id");
    }
    boolean authorized;
    try {
      authorized = sessionBlobRefManager.contains(sessionId, blobId);
    } catch (RuntimeException error) {
      throw new PluginResourceUnavailableException(
          "cannot verify session resource ownership", error);
    }
    if (!authorized) {
      throw new PluginResourceUnavailableException(
          "the invocation session does not reference this resource");
    }
    StoragePresignedUrlDTO presigned;
    try {
      presigned = storageBlobManager.presignOriginalUrl(blobId);
    } catch (DomainException error) {
      if (error.code() != DomainErrorCode.NOT_FOUND || !"blob".equals(error.resource())) {
        throw error;
      }
      throw new PluginResourceUnavailableException(
          "session resource is no longer available", error);
    } catch (RuntimeException error) {
      throw new PluginResourceUnavailableException("cannot sign this session resource", error);
    }
    return PluginResourceUris.toHttpsUri(presigned == null ? null : presigned.getUrl());
  }
}
