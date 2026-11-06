package fun.fengwk.kkstudio.platform.harness.dto;

import lombok.Data;
import org.jspecify.annotations.Nullable;

import java.time.Instant;

/**
 * 历史 Provider 请求重建：把已记录模型输出之前的请求前缀按当前 catalog 与 Provider 配置重建为协议请求体。
 *
 * <p>{@link #kind} 恒为 {@link Kind#HISTORICAL_REQUEST_PREVIEW}；{@link #notice} 是固定能力声明，说明结果由当前定义重建，
 * 绝不是当时实际发送的原始字节。{@link #bodyJson} 是最终请求体的原样 UTF-8 解码，绝不截断；endpoint、credential、 对象存储地址与任何认证 Header
 * 一律不进入本 DTO。
 */
@Data
public class HarnessProviderRequestPreviewDTO {

  /** 历史重建的固定提示：请求按当前 catalog 与 Provider 配置重建，不是当时实际发送的原始字节。 */
  public static final String HISTORICAL_NOTICE =
      "Preview is reconstructed from the current catalog, provider configuration and model "
          + "selection against the recorded history before this output. It is not the original "
          + "request that was sent, and it does not consume uploads or write anything.";

  /** 视图判别符，恒为 {@link Kind#HISTORICAL_REQUEST_PREVIEW}。 */
  private Kind kind;

  /** 预览生成时间。 */
  private Instant generatedAt;

  /** 本次请求实际使用的 Provider 协议类型名。 */
  private String providerType;

  /** 本次请求的模型名（最终 wire 请求使用的模型标识）。 */
  private String modelName;

  /** 最终请求体的 UTF-8 字节数。 */
  private int bodyByteSize;

  /** 最终请求体 JSON 原文，未经截断。 */
  private String bodyJson;

  /** 被查看模型输出的请求前缀 head Entry id（canonical UUID 文本）。 */
  private @Nullable String sourceHeadEntryId;

  /** 固定提示，恒为 {@link #HISTORICAL_NOTICE}。 */
  private String notice;

  /** 历史 Provider 请求视图判别符。 */
  public enum Kind {
    HISTORICAL_REQUEST_PREVIEW
  }
}
