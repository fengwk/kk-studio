package fun.fengwk.kkstudio.platform.plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Plugin 安装期的认证交互声明：封闭的 {@code None} / {@code StaticKey} / {@code DeepLink} 三态。
 *
 * <p>region 候选只属于 {@link DeepLink}：只有它会发起拨出式登录跳转，因此也只有它需要固定 region 集合。{@link StaticKey}
 * 的密钥由平台秘密存取提供，其字段必须由 {@link PluginConfigSchema#secrets()} 声明。三态与 {@link
 * StudioPlugin#authHandler()} 强一致：只有 {@link DeepLink} 允许存在交互式登录处理器。
 */
public sealed interface PluginAuthSpec
    permits PluginAuthSpec.None, PluginAuthSpec.StaticKey, PluginAuthSpec.DeepLink {

  /** 安装期一致性校验：只有 {@link DeepLink} 才允许存在交互式 {@code authHandler}。 */
  default void requireConsistentWith(Optional<PluginAuthHandler> authHandler) {
    Objects.requireNonNull(authHandler, "authHandler");
    boolean interactive = this instanceof DeepLink;
    if (interactive != authHandler.isPresent()) {
      throw new IllegalStateException(
          "authenticationSpec must match the interactive authHandler presence");
    }
  }

  /** 无需认证：该 Plugin 不消费凭据。 */
  record None() implements PluginAuthSpec {}

  /**
   * 静态密钥认证：密钥由平台秘密存取提供，没有交互式登录步骤。
   *
   * @param secretField 必须与 {@link PluginConfigSchema#secrets()} 声明的秘密字段同名
   */
  record StaticKey(String secretField) implements PluginAuthSpec {

    private static final Pattern SECRET_FIELD = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    public StaticKey {
      if (secretField == null || !SECRET_FIELD.matcher(secretField).matches()) {
        throw new IllegalArgumentException("secretField must be a schema secret name");
      }
    }
  }

  /**
   * 固定 deep-link 登录：用户在浏览器完成登录后把回调 deep link 交回 Platform。
   *
   * @param regions 固定 region 候选，非空、去重；管理面只能选择其中之一
   */
  record DeepLink(List<String> regions) implements PluginAuthSpec {

    public static final int MAX_REGION_CHARS = 64;

    public static final int MAX_REGIONS = 8;

    private static final Pattern REGION_SYNTAX = Pattern.compile("[A-Za-z0-9_-]+");

    public DeepLink {
      Objects.requireNonNull(regions, "regions");
      if (regions.isEmpty()) {
        throw new IllegalArgumentException("regions must declare at least one candidate");
      }
      if (regions.size() > MAX_REGIONS) {
        throw new IllegalArgumentException(
            "regions must not exceed " + MAX_REGIONS + " candidates");
      }
      List<String> validated = new ArrayList<>(regions.size());
      for (String region : regions) {
        String candidate = requireRegion(region);
        if (validated.contains(candidate)) {
          throw new IllegalArgumentException("regions must not repeat: " + candidate);
        }
        validated.add(candidate);
      }
      regions = List.copyOf(validated);
    }

    /** 给定 region 是否为声明的候选之一；大小写敏感，不做别名或默认回退。 */
    public boolean acceptsRegion(String region) {
      return region != null && regions.contains(region);
    }

    private static String requireRegion(String region) {
      if (region == null) {
        throw new IllegalArgumentException("regions[] must not be null");
      }
      if (region.isBlank() || !region.equals(region.strip())) {
        throw new IllegalArgumentException("regions[] must not be blank or padded");
      }
      if (region.length() > MAX_REGION_CHARS) {
        throw new IllegalArgumentException(
            "regions[] must not exceed " + MAX_REGION_CHARS + " characters");
      }
      if (!REGION_SYNTAX.matcher(region).matches()) {
        throw new IllegalArgumentException("regions[] must match [A-Za-z0-9_-]+");
      }
      return region;
    }
  }
}
