package fun.fengwk.kkstudio.share.storage;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * 预签名 URL 响应（浏览器直传 PUT 或直下 GET）。
 *
 * <p>刻意不暴露 bucket 与对象物理 key：目标始终由服务端从 upload/blob id 确定性推导， 调用方只需原样回传 {@code headers} 并请求 {@code
 * url}。
 *
 * @author fengwk
 */
@Data
@Builder
public class StoragePresignedUrlDTO {

  /** HTTP 方法（PUT 用于直传、GET 用于直下）。 */
  private String method;

  /** 已签名的 URL（浏览器可直接 PUT/GET）。 */
  private String url;

  /** 调用方发起请求时必须显式设置的 signed headers；不包含浏览器自动发送的 Host。 */
  private Map<String, String> headers;

  /** 签名过期时刻（UTC，ISO-8601 字符串）。 */
  private String expiresAt;

  /** 原件 URL 对应 Blob 的权威 media type；上传签名与 preview URL 可为 null。 */
  private String mediaType;

  /** 原件 URL 对应 Blob 的权威字节数；上传签名与 preview URL 可为 null。 */
  private Long sizeBytes;
}
