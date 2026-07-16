package fun.fengwk.kkstudio.share.model;

import lombok.Data;

/**
 * S3 预签名请求体（同时用于直传与直下载）。
 *
 * <p>{@code key} 必填；{@code contentType} 仅直传有意义，空白视为未提供，非空时必须是长度合理且不含控制字符的合法 media type；{@code
 * expiresInSeconds} 可选，为空时由服务端使用默认有效期；显式值必须为正数且不超过服务端上限。
 *
 * @author fengwk
 */
@Data
public class S3PresignedRequestDTO {

  private String key;
  private String contentType;
  private Long expiresInSeconds;
}
