package fun.fengwk.kkstudio.share.model;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * S3 预签名响应。
 *
 * <p>{@code bucket} 始终来自服务端配置；{@code headers} 仅列出调用方必须显式设置的已签名头（典型如直传场景下的 {@code
 * Content-Type}）。浏览器根据 URL 自动发送且禁止脚本设置的 {@code Host} 不会返回。
 *
 * @author fengwk
 */
@Data
@Builder
public class S3PresignedResponseDTO {

  private String bucket;
  private String key;

  /** HTTP 方法（PUT 用于上传、GET 用于下载）。 */
  private String method;

  /** 已签名的 URL（浏览器可直接 PUT/GET）。 */
  private String url;

  /** 调用方发起请求时必须显式设置的 signed headers；不包含浏览器自动发送的 Host。 */
  private Map<String, String> headers;

  /** 签名过期时刻（UTC，ISO-8601 字符串）。 */
  private String expiresAt;
}
