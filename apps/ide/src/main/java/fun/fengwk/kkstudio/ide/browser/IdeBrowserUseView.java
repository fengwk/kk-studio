package fun.fengwk.kkstudio.ide.browser;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;

import java.util.Objects;
import java.util.UUID;

/**
 * 浏览器声明的一项 IDE 视图使用意图或已绑定持有项。
 *
 * <p>{@code bindingEpoch} 缺失表示尚未完成 claim 的打开/恢复意图，不能用于续租；只有带正 epoch 的项才构成使用证明。
 */
public record IdeBrowserUseView(
    EnvironmentId environmentId, UUID viewId, UUID claimId, Long bindingEpoch) {

  public IdeBrowserUseView {
    environmentId = Objects.requireNonNull(environmentId, "environmentId");
    viewId = Objects.requireNonNull(viewId, "viewId");
    claimId = Objects.requireNonNull(claimId, "claimId");
    if (bindingEpoch != null && bindingEpoch <= 0) {
      throw new IllegalArgumentException("bindingEpoch must be a positive decimal");
    }
  }

  /** 解析 wire 字段；{@code bindingEpoch} 只接受 canonical 正十进制字符串且不超过 long 上限。 */
  public static IdeBrowserUseView parse(
      String environmentId, String viewId, String claimId, String bindingEpoch) {
    return new IdeBrowserUseView(
        EnvironmentId.parse(environmentId),
        ConnectionIdentity.parseCanonicalUuid(viewId, "viewId"),
        ConnectionIdentity.parseCanonicalUuid(claimId, "claimId"),
        bindingEpoch == null ? null : parsePositiveLong(bindingEpoch, "bindingEpoch"));
  }

  /** 该持有项是否已带 claim 成功后的 binding epoch。 */
  public boolean bound() {
    return bindingEpoch != null;
  }

  /** 解析 canonical 正十进制字符串，上限为 {@link Long#MAX_VALUE}。 */
  public static long parsePositiveLong(String value, String field) {
    Objects.requireNonNull(field, "field");
    if (value == null || value.isEmpty()) {
      throw new IllegalArgumentException(field + " must be a canonical positive decimal");
    }
    if (value.charAt(0) < '1' || value.charAt(0) > '9') {
      throw new IllegalArgumentException(field + " must be a canonical positive decimal");
    }
    for (int i = 1; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c < '0' || c > '9') {
        throw new IllegalArgumentException(field + " must be a canonical positive decimal");
      }
    }
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException error) {
      throw new IllegalArgumentException(field + " must fit in a signed 64-bit decimal");
    }
  }
}
