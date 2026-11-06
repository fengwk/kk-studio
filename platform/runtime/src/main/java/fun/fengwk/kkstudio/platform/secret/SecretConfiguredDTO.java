package fun.fengwk.kkstudio.platform.secret;

/** 秘密字段的配置事实：只陈述是否已配置，绝不回显值。 */
public record SecretConfiguredDTO(boolean configured) {

  /** 已配置与未配置两个事实。 */
  public static SecretConfiguredDTO of(boolean configured) {
    return new SecretConfiguredDTO(configured);
  }
}
