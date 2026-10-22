package fun.fengwk.kkstudio.plugin.opencli;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.common.schema.BooleanSchema;
import fun.fengwk.kkstudio.common.schema.InputSchema;
import fun.fengwk.kkstudio.common.schema.IntegerSchema;
import fun.fengwk.kkstudio.common.schema.ObjectSchema;
import fun.fengwk.kkstudio.common.schema.StringSchema;
import fun.fengwk.kkstudio.platform.plugin.PluginAuthHandler;
import fun.fengwk.kkstudio.platform.plugin.PluginAuthSpec;
import fun.fengwk.kkstudio.platform.plugin.PluginConfig;
import fun.fengwk.kkstudio.platform.plugin.PluginConfigSchema;
import fun.fengwk.kkstudio.platform.plugin.PluginContext;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialRefresher;
import fun.fengwk.kkstudio.platform.plugin.PluginDescriptor;
import fun.fengwk.kkstudio.platform.plugin.PluginInstance;
import fun.fengwk.kkstudio.platform.plugin.StudioPlugin;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * OpenCLI 的 Studio Plugin 安装期定义：安装身份、受控配置、静态 apiKey 认证、受保护私有身份与运行期实例装配。
 *
 * <p>启动期静态冻结 descriptor 与受控配置 schema，不访问 Hub 也不解析凭据。 运行期实例由 {@link #configure(PluginConfig,
 * PluginContext)} 装配，归本 Plugin 所有并可关闭。
 */
public final class OpenCliPlugin implements StudioPlugin {

  public static final String PLUGIN_ID = "opencli";
  public static final String PLUGIN_NAME = "OpenCLI";
  public static final String PLUGIN_VERSION = "1.0.0";

  public static final PluginDescriptor DESCRIPTOR =
      new PluginDescriptor(PLUGIN_ID, PLUGIN_NAME, PLUGIN_VERSION);

  private static final PluginAuthSpec AUTHENTICATION_SPEC =
      new PluginAuthSpec.StaticKey("apiKey", Set.of("baseUrl"));

  /** 唯一认证密钥与可选私有身份都以受保护秘密字段存取；instanceId 绝不是普通配置值。 */
  private static final Map<String, String> SECRETS =
      Map.of("apiKey", "OpenCLI API key", "instanceId", "optional OpenCLI instance identifier");

  private static final String DEFAULTS_JSON =
      "{\"enabled\":false,"
          + "\"connectTimeoutMillis\":5000,"
          + "\"requestTimeoutMillis\":120000,"
          + "\"longPollTimeoutMillis\":130000,"
          + "\"streamBufferBytes\":16384,"
          + "\"maxJsonResponseBytes\":524288,"
          + "\"maxErrorResponseBytes\":4096,"
          + "\"maxOutputChars\":65535,"
          + "\"gptImage2\":{\"paidEnabled\":false,\"askTimeoutSeconds\":900,\"hubExecutionTimeoutMillis\":960000,\"maxWaitMillis\":1200000},"
          + "\"seedance\":{\"enabled\":false,\"retry\":0,\"hubExecutionTimeoutMillis\":600000,\"statusPollIntervalMillis\":30000,\"maxWaitMillis\":1800000}}";

  private static final InputSchema VALUES_SCHEMA = createValuesSchema();

  public static final PluginConfigSchema CONFIGURATION_SCHEMA =
      new PluginConfigSchema(VALUES_SCHEMA, DEFAULTS_JSON, SECRETS);

  static {
    CONFIGURATION_SCHEMA.requireDeclaredSecrets(AUTHENTICATION_SPEC);
  }

  private final ObjectMapper mapper;

  public OpenCliPlugin() {
    this(JsonMapper.builder().build());
  }

  public OpenCliPlugin(ObjectMapper mapper) {
    this.mapper = Objects.requireNonNull(mapper, "mapper");
  }

  private static InputSchema createValuesSchema() {
    return new InputSchema(
        "OpenCLI configuration",
        Map.ofEntries(
            Map.entry("enabled", new BooleanSchema("whether OpenCLI integration is enabled")),
            Map.entry("baseUrl", new StringSchema("OpenCLI Hub HTTP(S) origin base URL")),
            Map.entry(
                "connectTimeoutMillis", new IntegerSchema("HTTP connect timeout in milliseconds")),
            Map.entry(
                "requestTimeoutMillis", new IntegerSchema("HTTP request timeout in milliseconds")),
            Map.entry(
                "longPollTimeoutMillis", new IntegerSchema("long polling timeout in milliseconds")),
            Map.entry(
                "streamBufferBytes", new IntegerSchema("streaming response buffer size in bytes")),
            Map.entry("maxJsonResponseBytes", new IntegerSchema("maximum JSON response bytes")),
            Map.entry("maxErrorResponseBytes", new IntegerSchema("maximum error response bytes")),
            Map.entry("maxOutputChars", new IntegerSchema("maximum execution output characters")),
            Map.entry("gptImage2", gptImage2Schema()),
            Map.entry("seedance", seedanceSchema())),
        Set.of(
            "enabled",
            "connectTimeoutMillis",
            "requestTimeoutMillis",
            "longPollTimeoutMillis",
            "streamBufferBytes",
            "maxJsonResponseBytes",
            "maxErrorResponseBytes",
            "maxOutputChars",
            "gptImage2",
            "seedance"),
        false);
  }

  private static ObjectSchema gptImage2Schema() {
    return new ObjectSchema(
        "GPT Image 2 configuration",
        Map.of(
            "paidEnabled",
            new BooleanSchema("whether paid GPT Image 2 execution is enabled"),
            "askTimeoutSeconds",
            new IntegerSchema("timeout for ask operations in seconds"),
            "hubExecutionTimeoutMillis",
            new IntegerSchema("Hub execution timeout in milliseconds"),
            "maxWaitMillis",
            new IntegerSchema("maximum wait duration in milliseconds")),
        Set.of("paidEnabled", "askTimeoutSeconds", "hubExecutionTimeoutMillis", "maxWaitMillis"),
        false);
  }

  private static ObjectSchema seedanceSchema() {
    return new ObjectSchema(
        "Seedance configuration",
        Map.of(
            "enabled",
            new BooleanSchema("whether Seedance execution is enabled"),
            "workspaceId",
            new StringSchema("optional Seedance workspace identifier"),
            "retry",
            new IntegerSchema("retry attempt count"),
            "hubExecutionTimeoutMillis",
            new IntegerSchema("Hub execution timeout in milliseconds"),
            "statusPollIntervalMillis",
            new IntegerSchema("status polling interval in milliseconds"),
            "maxWaitMillis",
            new IntegerSchema("maximum wait duration in milliseconds")),
        Set.of(
            "enabled",
            "retry",
            "hubExecutionTimeoutMillis",
            "statusPollIntervalMillis",
            "maxWaitMillis"),
        false);
  }

  @Override
  public PluginDescriptor descriptor() {
    return DESCRIPTOR;
  }

  @Override
  public PluginConfigSchema configurationSchema() {
    return CONFIGURATION_SCHEMA;
  }

  @Override
  public PluginAuthSpec authenticationSpec() {
    return AUTHENTICATION_SPEC;
  }

  @Override
  public PluginInstance configure(PluginConfig config, PluginContext context) {
    Objects.requireNonNull(config, "config");
    Objects.requireNonNull(context, "context");
    String resolved = CONFIGURATION_SCHEMA.resolveValues(config.valuesJson());
    OpenCliSettings settings = OpenCliSettings.parse(resolved);
    return OpenCliInstance.create(settings, mapper, context);
  }

  @Override
  public Optional<PluginAuthHandler> authHandler() {
    return Optional.empty();
  }

  @Override
  public Optional<PluginCredentialRefresher> refresher() {
    return Optional.empty();
  }
}
