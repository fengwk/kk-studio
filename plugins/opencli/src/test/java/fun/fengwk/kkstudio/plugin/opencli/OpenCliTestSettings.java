package fun.fengwk.kkstudio.plugin.opencli;

/** 测试专用配置助手：默认配置统一从真实 {@link OpenCliPlugin#CONFIGURATION_SCHEMA} 解析，测试里不复制任何默认值。 */
final class OpenCliTestSettings {

  private OpenCliTestSettings() {}

  /** schema 默认值解析出的完整配置。 */
  static OpenCliSettings defaults() {
    return parse("{}");
  }

  static OpenCliSettings parse(String valuesJson) {
    return OpenCliSettings.parse(OpenCliPlugin.CONFIGURATION_SCHEMA.resolveValues(valuesJson));
  }
}
