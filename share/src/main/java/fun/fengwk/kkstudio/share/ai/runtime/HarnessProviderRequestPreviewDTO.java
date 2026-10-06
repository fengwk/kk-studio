package fun.fengwk.kkstudio.share.ai.runtime;

import lombok.Data;

import java.time.Instant;

/**
 * 发送前请求预览：把一次尚未提交的输入按正式发送路径物化为 Provider 协议将发送的最终请求体。
 *
 * <p>{@code kind} 区分两种来源：{@link #DRAFT_REQUEST_PREVIEW} 是「点击时」的草稿（branch 草稿或既有 Thread 的下一轮输入），
 * {@link #HISTORICAL_REQUEST_PREVIEW} 是对已记录模型输出之前的历史前缀按当前定义重建。两种预览都不消费任何 upload、不写任何 durable 状态、不触发
 * transport，因此随后真正发送时的历史、附件与 Provider 配置都可能已经变化。
 *
 * <p>{@link #notice} 是固定能力声明而不是噪声：草稿预览声明它是点击时快照；历史预览声明它由当前 catalog 与 Provider 配置重建，
 * 绝不声称自己就是当时的原始请求字节。
 *
 * <p>{@link #bodyJson} 是最终请求体的原样 UTF-8 解码，绝不截断；endpoint、credential、对象存储地址与任何认证 Header 一律不进入本 DTO。
 */
@Data
public class HarnessProviderRequestPreviewDTO {

  /** 请求尚未落库的草稿时使用的视图判别符。 */
  public static final String DRAFT_REQUEST_PREVIEW = "DRAFT_REQUEST_PREVIEW";

  /** 重建已记录模型输出之前的请求时使用的视图判别符。 */
  public static final String HISTORICAL_REQUEST_PREVIEW = "HISTORICAL_REQUEST_PREVIEW";

  /** 草稿预览的固定提示：预览只在点击时成立，绝不保证随后发送得到逐字节相同的请求。 */
  public static final String DRAFT_NOTICE =
      "Preview is a click-time snapshot of the request that would be sent now. It does not "
          + "consume uploads or write anything, so a later send may observe different history, "
          + "attachments or provider configuration.";

  /** 历史预览的固定提示：请求按当前 catalog 与 Provider 配置重建，不是当时实际发送的原始字节。 */
  public static final String HISTORICAL_NOTICE =
      "Preview is reconstructed from the current catalog, provider configuration and model "
          + "selection against the recorded history before this output. It is not the original "
          + "request that was sent, and it does not consume uploads or write anything.";

  /** 视图判别符，取 {@link #DRAFT_REQUEST_PREVIEW} 或 {@link #HISTORICAL_REQUEST_PREVIEW}。 */
  private String kind;

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

  /**
   * 预览所基于的 source head Entry id（canonical UUID 文本）。
   *
   * <p>草稿预览是草稿分支起点；历史预览是该模型输出的请求前缀 head。两者都不包含被预览输出自身及其之后的历史。
   */
  private String sourceHeadEntryId;

  /** 固定提示，取 {@link #DRAFT_NOTICE} 或 {@link #HISTORICAL_NOTICE}。 */
  private String notice;
}
