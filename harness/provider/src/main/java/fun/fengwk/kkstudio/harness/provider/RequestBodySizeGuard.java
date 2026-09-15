package fun.fengwk.kkstudio.harness.provider;

import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderException;

import java.util.Objects;

/**
 * 应用层「最终 UTF-8 序列化请求体」字节上限守卫，供各协议编码器在序列化完成后统一收口。
 *
 * <p>该上限是适配器自身的内存与请求安全策略，<strong>不是</strong>对厂商能力的断言：厂商允许的请求体大小由其自身策略决定， 本上限只在本地超限时以 {@link
 * ProviderErrorKind#INVALID_REQUEST} 明确拒绝，不静默截断、不降级、不改写请求内容。
 *
 * <p>各协议可在应用上限之下叠加更严格的协议级限制（Anthropic 保留自己的 32 MiB），此时由协议自身守卫负责。
 *
 * <p>依赖方通过 {@link #DEFAULT} 使用应用上限；测试可注入更小的 {@code limitBytes}，从而在无昂贵大内存分配的前提下覆盖边界行为。
 */
public final class RequestBodySizeGuard {

  /** 应用层最终 UTF-8 请求体字节上限：192 MiB。 */
  public static final long MAX_REQUEST_BODY_BYTES = 192L * 1024 * 1024;

  /** 使用应用上限的共享守卫实例。 */
  public static final RequestBodySizeGuard DEFAULT =
      new RequestBodySizeGuard(MAX_REQUEST_BODY_BYTES);

  private final long limitBytes;

  public RequestBodySizeGuard(long limitBytes) {
    if (limitBytes <= 0) {
      throw new IllegalArgumentException("limitBytes must be positive");
    }
    this.limitBytes = limitBytes;
  }

  /** 当前生效的字节上限。 */
  public long limitBytes() {
    return limitBytes;
  }

  /**
   * 校验最终 UTF-8 请求体长度；超限即抛出脱敏的 {@link ProviderException}。
   *
   * @param bodyUtf8Bytes 已序列化的 UTF-8 请求体字节
   */
  public void enforce(byte[] bodyUtf8Bytes) {
    Objects.requireNonNull(bodyUtf8Bytes, "bodyUtf8Bytes");
    if (bodyUtf8Bytes.length > limitBytes) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST, "request body exceeds " + limitBytes + " bytes limit");
    }
  }
}
