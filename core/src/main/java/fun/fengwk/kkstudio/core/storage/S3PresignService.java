package fun.fengwk.kkstudio.core.storage;

import fun.fengwk.kkstudio.share.storage.S3PresignedResponseDTO;

/**
 * S3 预签名服务：仅为上传或下载生成预签名 URL，不读写对象字节。
 *
 * <p>bucket 始终来自服务端配置；调用方在请求体中只能指定 key、可选 contentType 与 expiresInSeconds。
 * 有效期为空时取默认；显式值必须为正且不超过服务端上限。
 *
 * @author fengwk
 */
public interface S3PresignService {

  /**
   * 为 PUT 上传生成预签名响应。
   *
   * @param key 对象键（不要求规范化，服务端会统一校验）
   * @param contentType 可选 content type；空白视为未提供，非空时校验并规范化后参与签名
   * @param expiresInSeconds 可选过期秒数；{@code null} 使用服务端默认，显式值必须在允许范围内
   * @return 包含 URL、调用方必须显式设置的 headers 与过期时间的预签名响应
   */
  S3PresignedResponseDTO presignUpload(String key, String contentType, Long expiresInSeconds);

  /**
   * 为仅允许创建新对象的 PUT 上传生成预签名响应。
   *
   * <p>签名包含 {@code If-None-Match: *}，调用方必须原样发送；目标 key 已存在时对象存储拒绝 PUT。
   *
   * @param key 对象键（不要求规范化，服务端会统一校验）
   * @param contentType 可选 content type；空白视为未提供，非空时校验并规范化后参与签名
   * @param expiresInSeconds 可选过期秒数；{@code null} 使用服务端默认，显式值必须在允许范围内
   * @return 包含 URL、调用方必须显式设置的 headers 与过期时间的预签名响应
   */
  S3PresignedResponseDTO presignCreateOnlyUpload(
      String key, String contentType, Long expiresInSeconds);

  /**
   * 为 GET 下载生成预签名响应。
   *
   * @param key 对象键
   * @param expiresInSeconds 可选过期秒数；{@code null} 使用服务端默认，显式值必须在允许范围内
   * @return 包含 URL、调用方必须显式设置的 headers 与过期时间的预签名响应
   */
  S3PresignedResponseDTO presignDownload(String key, Long expiresInSeconds);

  /**
   * 为携带客户端 SHA-256 校验和的 create-only PUT 生成预签名响应（浏览器直传）。
   *
   * <p>签名同时包含 {@code If-None-Match: *}（目标 key 已存在时拒绝 PUT）与 {@code x-amz-checksum-sha256}（浏览器必须原样回传
   * base64 编码的原始 SHA-256 摘要）， 对象存储据此在写入时校验内容并持久化校验和，服务端随后可通过 checksum mode HEAD 复核。
   *
   * @param key 对象键（不要求规范化，服务端会统一校验）
   * @param contentType 可选 content type；空白视为未提供，非空时校验并规范化后参与签名
   * @param checksumSha256Base64 必填：base64 编码的原始 SHA-256 摘要（解码后必须恰好 32 字节）
   * @param expiresInSeconds 可选过期秒数；{@code null} 使用服务端默认，显式值必须在允许范围内
   * @return 包含 URL、调用方必须显式设置的 headers 与过期时间的预签名响应
   */
  default S3PresignedResponseDTO presignChecksummedCreateOnlyUpload(
      String key, String contentType, String checksumSha256Base64, Long expiresInSeconds) {
    throw new UnsupportedOperationException(
        "presignChecksummedCreateOnlyUpload is not supported by this implementation");
  }
}
