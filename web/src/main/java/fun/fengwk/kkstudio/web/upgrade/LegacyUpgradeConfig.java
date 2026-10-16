package fun.fengwk.kkstudio.web.upgrade;

import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialKeyLoader;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 受保护升级输入：严格解析操作员提供的绝对路径 JSON 与 raw32 主密钥文件，得到不可变连接信息、主密钥与显式静态密钥材料。
 *
 * <p>只接受固定字段名与绝对路径，绝不接受 URL/密码/密钥的 inline 参数或环境兜底。JSON 有 1 MiB 上限且不先无界 {@code
 * readAllBytes}；文件必须是绝对路径、非 symlink 的普通文件、POSIX owner-readable 且无 group/other 权限，主密钥恰为 32
 * bytes，任何形态失败都 fail closed，读取临时 byte[] 后立即擦除。
 *
 * <p>对象不覆写 {@code toString}（沿用 identity），因此任何默认打印都不会泄露连接或密钥。
 */
final class LegacyUpgradeConfig {

  /** 受保护配置 JSON 的硬上限；有界读取，绝不先无界物化再判断。 */
  static final int MAX_CONFIG_BYTES = 1024 * 1024;

  private static final String URL = "url";

  private static final String USERNAME = "username";

  private static final String PASSWORD = "password";

  private static final String SOURCE = "source";

  private static final String TARGET = "target";

  private static final String SOURCE_KEY_FILE = "sourceKeyFile";

  private static final String TARGET_KEY_FILE = "targetKeyFile";

  private static final String STATIC_SECRETS = "staticSecrets";

  private static final String PG_URL_PREFIX = "jdbc:postgresql://";

  private static final ObjectMapper STRICT_MAPPER =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS)
          .build();

  private final Endpoint source;

  private final Endpoint target;

  private final SecretKey sourceKey;

  private final SecretKey targetKey;

  private final Map<String, Map<String, String>> staticSecrets;

  private LegacyUpgradeConfig(
      Endpoint source,
      Endpoint target,
      SecretKey sourceKey,
      SecretKey targetKey,
      Map<String, Map<String, String>> staticSecrets) {
    this.source = source;
    this.target = target;
    this.sourceKey = sourceKey;
    this.targetKey = targetKey;
    this.staticSecrets = staticSecrets;
  }

  /**
   * 读取并严格校验一份受保护配置。
   *
   * @param prepareMode prepare 只接受恰为 {@code target} 的配置；dry-run/apply 额外接受 {@code source} 与可选材料
   * @throws LegacyUpgradeCliException 任何输入形态问题（固定安全原因）
   */
  static LegacyUpgradeConfig read(boolean prepareMode, Path configFile) {
    String text = readConfigText(configFile);
    JsonNode root = parseStrictJson(text);
    if (!root.isObject()) {
      throw input("the configuration must be a JSON object");
    }
    ObjectNode object = (ObjectNode) root;
    requireKnownFields(object, prepareMode);
    Endpoint target = readEndpoint(object.get(TARGET), TARGET);
    if (prepareMode) {
      return new LegacyUpgradeConfig(null, target, null, null, Map.of());
    }
    Endpoint source = readEndpoint(object.get(SOURCE), SOURCE);
    SecretKey sourceKey = readOptionalKey(object.get(SOURCE_KEY_FILE), SOURCE_KEY_FILE);
    SecretKey targetKey = readOptionalKey(object.get(TARGET_KEY_FILE), TARGET_KEY_FILE);
    Map<String, Map<String, String>> staticSecrets = readStaticSecrets(object.get(STATIC_SECRETS));
    return new LegacyUpgradeConfig(source, target, sourceKey, targetKey, staticSecrets);
  }

  /** prepare 的专属目标连接。 */
  Endpoint target() {
    return target;
  }

  /** dry-run/apply 的旧源连接；prepare 没有源。 */
  Endpoint source() {
    return source;
  }

  /** 旧 format 1 凭据与 Skill 令牌的主密钥；未提供时为 null。 */
  SecretKey sourceKey() {
    return sourceKey;
  }

  /** 当前 format 2 凭据与 Skill 令牌的主密钥；未提供时为 null。 */
  SecretKey targetKey() {
    return targetKey;
  }

  /** 显式静态密钥材料（pluginId → 字段名到真实值）；省略时为空 map，绝不伪造凭据。 */
  Map<String, Map<String, String>> staticSecrets() {
    return staticSecrets;
  }

  /** 一条 PostgreSQL 连接描述；不覆写 {@code toString}，默认打印不含密码。 */
  static final class Endpoint {

    private final String url;

    private final String username;

    private final String password;

    private Endpoint(String url, String username, String password) {
      this.url = url;
      this.username = username;
      this.password = password;
    }

    String url() {
      return url;
    }

    String username() {
      return username;
    }

    String password() {
      return password;
    }
  }

  private static String readConfigText(Path configFile) {
    requireProtectedFile(configFile);
    byte[] raw = readBounded(configFile, MAX_CONFIG_BYTES);
    try {
      return strictUtf8(raw);
    } finally {
      Arrays.fill(raw, (byte) 0);
    }
  }

  /** 严格 Jackson3 解析：拒绝重复字段与尾随 token；绝不回显原文。 */
  private static JsonNode parseStrictJson(String text) {
    try {
      JsonNode node = STRICT_MAPPER.readTree(text);
      if (node == null || node.isMissingNode()) {
        throw input("the configuration is not valid strict JSON");
      }
      return node;
    } catch (JacksonException error) {
      throw input("the configuration is not valid strict JSON");
    }
  }

  private static String strictUtf8(byte[] raw) {
    CharsetDecoder decoder =
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
    try {
      return decoder.decode(ByteBuffer.wrap(raw)).toString();
    } catch (CharacterCodingException error) {
      throw input("the configuration file must be valid UTF-8");
    }
  }

  private static void requireKnownFields(ObjectNode object, boolean prepareMode) {
    Set<String> allowed =
        prepareMode
            ? Set.of(TARGET)
            : Set.of(SOURCE, TARGET, SOURCE_KEY_FILE, TARGET_KEY_FILE, STATIC_SECRETS);
    for (Map.Entry<String, JsonNode> property : object.properties()) {
      if (!allowed.contains(property.getKey())) {
        throw input("the configuration contains an unsupported field");
      }
    }
  }

  private static Endpoint readEndpoint(JsonNode node, String name) {
    if (node == null || !node.isObject()) {
      throw input(name + " must be a JSON object");
    }
    ObjectNode object = (ObjectNode) node;
    for (Map.Entry<String, JsonNode> property : object.properties()) {
      String field = property.getKey();
      if (!URL.equals(field) && !USERNAME.equals(field) && !PASSWORD.equals(field)) {
        throw input(name + " contains an unsupported field");
      }
    }
    String url = requireText(object.get(URL), name + "." + URL + " must be a non-empty string");
    if (!url.startsWith(PG_URL_PREFIX)) {
      throw input(name + "." + URL + " must be a PostgreSQL JDBC URL");
    }
    String username =
        requireText(object.get(USERNAME), name + "." + USERNAME + " must be a non-empty string");
    JsonNode password = object.get(PASSWORD);
    if (password == null || !password.isTextual()) {
      throw input(name + "." + PASSWORD + " must be a string");
    }
    return new Endpoint(url, username, password.textValue());
  }

  private static String requireText(JsonNode node, String reason) {
    if (node == null || !node.isTextual() || node.textValue().isBlank()) {
      throw input(reason);
    }
    return node.textValue();
  }

  private static SecretKey readOptionalKey(JsonNode node, String field) {
    if (node == null) {
      return null;
    }
    if (!node.isTextual()) {
      throw input(field + " must be an absolute path string");
    }
    Path path;
    try {
      path = Path.of(node.textValue());
    } catch (InvalidPathException error) {
      throw input(field + " must be an absolute path string");
    }
    return readKey(path, field);
  }

  /** 读取恰好 32 bytes 的 raw AES-256 主密钥；读取临时 byte[] 后擦除。 */
  private static SecretKey readKey(Path path, String field) {
    requireProtectedFile(path);
    byte[] raw;
    try (InputStream stream = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
      raw = stream.readNBytes(PluginCredentialKeyLoader.KEY_BYTES + 1);
    } catch (IOException error) {
      throw input(field + " could not be read");
    }
    try {
      if (raw.length != PluginCredentialKeyLoader.KEY_BYTES) {
        throw input(
            field
                + " must contain exactly "
                + PluginCredentialKeyLoader.KEY_BYTES
                + " bytes of raw AES-256 key material");
      }
      return new SecretKeySpec(raw, "AES");
    } finally {
      Arrays.fill(raw, (byte) 0);
    }
  }

  private static Map<String, Map<String, String>> readStaticSecrets(JsonNode node) {
    if (node == null) {
      return Map.of();
    }
    if (!node.isObject()) {
      throw input(STATIC_SECRETS + " must be a JSON object");
    }
    Map<String, Map<String, String>> secrets = new LinkedHashMap<>();
    for (Map.Entry<String, JsonNode> plugin : node.properties()) {
      JsonNode fields = plugin.getValue();
      if (!fields.isObject()) {
        throw input(STATIC_SECRETS + " must map plugin identifiers to secret objects");
      }
      Map<String, String> values = new LinkedHashMap<>();
      for (Map.Entry<String, JsonNode> field : fields.properties()) {
        if (!field.getValue().isTextual()) {
          throw input(STATIC_SECRETS + " values must be strings");
        }
        values.put(field.getKey(), field.getValue().textValue());
      }
      if (values.isEmpty()) {
        throw input(STATIC_SECRETS + " entries must declare at least one secret field");
      }
      secrets.put(plugin.getKey(), Collections.unmodifiableMap(values));
    }
    return Collections.unmodifiableMap(secrets);
  }

  /** 受保护文件：绝对路径、非 symlink 的普通文件、POSIX owner-readable 且无 group/other 权限；不支持 POSIX 时 fail closed。 */
  private static void requireProtectedFile(Path path) {
    if (path == null || !path.isAbsolute()) {
      throw input("a protected input file must be given as an absolute path");
    }
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
      throw input("a protected input file must be a regular file that is not a symlink");
    }
    Set<PosixFilePermission> permissions;
    try {
      permissions = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
    } catch (UnsupportedOperationException error) {
      throw input("the file system does not support the required POSIX permissions");
    } catch (IOException error) {
      throw input("a protected input file must be a readable regular file");
    }
    for (PosixFilePermission permission : permissions) {
      String name = permission.name();
      if (name.startsWith("GROUP_") || name.startsWith("OTHERS_")) {
        throw input("a protected input file must not be readable by group or others");
      }
    }
    if (!permissions.contains(PosixFilePermission.OWNER_READ)) {
      throw input("a protected input file must be readable by its owner");
    }
  }

  /** 有界读取：最多读 {@code limit} 字节后立即拒绝，绝不先无界 {@code readAllBytes}。 */
  private static byte[] readBounded(Path path, int limit) {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream(8192);
    byte[] chunk = new byte[8192];
    int total = 0;
    try (InputStream stream = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
      int read;
      while ((read = stream.read(chunk)) != -1) {
        total += read;
        if (total > limit) {
          throw input("the configuration file exceeds the size limit");
        }
        buffer.write(chunk, 0, read);
      }
    } catch (IOException error) {
      throw input("a protected input file could not be read");
    }
    return buffer.toByteArray();
  }

  private static LegacyUpgradeCliException input(String reason) {
    return new LegacyUpgradeCliException(LegacyUpgradeCliException.Stage.INPUT, reason);
  }
}
