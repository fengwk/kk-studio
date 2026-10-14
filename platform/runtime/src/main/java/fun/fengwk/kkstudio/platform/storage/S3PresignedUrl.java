package fun.fengwk.kkstudio.platform.storage;

import lombok.Builder;
import lombok.Value;

import java.util.Map;

/**
 * S3 预签名 URL 内部值对象。
 *
 * <p>只保留生产消费所需的传输元数据，不向外暴露 bucket 或物理 key。
 *
 * @author fengwk
 */
@Value
@Builder
public class S3PresignedUrl {

  /** HTTP 方法（PUT 用于上传、GET 用于下载）。 */
  String method;

  /** 已签名的 URL（客户端可直接 PUT/GET）。 */
  String url;

  /** 发起请求时必须显式设置的 signed headers；不包含浏览器自动发送的 Host。 */
  Map<String, String> headers;

  /** 签名过期时刻（UTC，ISO-8601 字符串）。 */
  String expiresAt;
}
