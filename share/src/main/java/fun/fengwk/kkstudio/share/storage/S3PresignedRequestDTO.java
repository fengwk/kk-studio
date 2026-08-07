package fun.fengwk.kkstudio.share.storage;

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

  /** 必填 S3 对象键：统一校验（非空白、无前导 {@code '/'}、无 {@code .}/{@code ..} 段、无控制字符、UTF-8 ≤1024 字节），不做路径重写。 */
  private String key;

  /** 仅直传（PUT）有意义；空白视为未提供；非空时必须为合法 media type（≤255 字符、不含控制字符），将作为 signed header 校验。 */
  private String contentType;

  /** 可空签名有效期（秒）：null 时使用服务端配置默认值；显式值必须为正数且不超过服务端配置上限。 */
  private Long expiresInSeconds;
}
