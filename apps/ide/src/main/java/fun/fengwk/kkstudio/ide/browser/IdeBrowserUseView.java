package fun.fengwk.kkstudio.ide.browser;

import fun.fengwk.kkstudio.common.id.Uuids;
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
      throw new IllegalArgumentException("bindingEpoch must be positive");
    }
  }

  /** 解析 wire 身份字段；{@code bindingEpoch} 已由入口按正整数校验。 */
  public static IdeBrowserUseView parse(
      String environmentId, String viewId, String claimId, Long bindingEpoch) {
    return new IdeBrowserUseView(
        EnvironmentId.parse(environmentId),
        Uuids.parseCanonical(viewId, "viewId"),
        Uuids.parseCanonical(claimId, "claimId"),
        bindingEpoch);
  }

  /** 该持有项是否已带 claim 成功后的 binding epoch。 */
  public boolean bound() {
    return bindingEpoch != null;
  }
}
