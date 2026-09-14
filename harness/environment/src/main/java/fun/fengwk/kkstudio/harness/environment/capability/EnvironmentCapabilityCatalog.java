package fun.fengwk.kkstudio.harness.environment.capability;

import fun.fengwk.kkstudio.harness.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.common.schema.SchemaJsonCodec;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Atomic Environment Capability execution descriptors.
 *
 * <p>This catalog is independent of model tool names, prompts, renderers and side-effect labels.
 * Its order is the stable order used by the shared capability contract. Management-only
 * capabilities ({@link EnvironmentCapabilityIds#MANAGEMENT_ONLY}) are listed after model-visible
 * capabilities and are never registered as model Tool contributions.
 */
public final class EnvironmentCapabilityCatalog {

  /** 基础 capability 与 catalog 版本。 */
  public static final String VERSION = "1";

  private static final String RESOURCE_PREFIX =
      "/fun/fengwk/kkstudio/harness/environment/capability/schemas/";

  /** 三个管理能力共享的 arguments schema 资源名。 */
  private static final String SHARED_SOURCE_SCHEMA = "skill.source.schema.json";

  private static final SchemaJsonCodec CODEC = new SchemaJsonCodec();
  private static final Set<EnvironmentCapabilityId> WORKDIR_CAPABILITY_IDS =
      Set.of(
          EnvironmentCapabilityIds.FS_READ,
          EnvironmentCapabilityIds.FS_WRITE,
          EnvironmentCapabilityIds.FS_EDIT,
          EnvironmentCapabilityIds.PROCESS_EXEC,
          EnvironmentCapabilityIds.FS_GREP,
          EnvironmentCapabilityIds.FS_FIND,
          EnvironmentCapabilityIds.LSP_GOTO_DEFINITION,
          EnvironmentCapabilityIds.LSP_WORKSPACE_SYMBOLS,
          EnvironmentCapabilityIds.LSP_JAVA_DECOMPILE);
  private static final List<EnvironmentCapabilityDescriptor> DESCRIPTORS = createDescriptors();
  private static final List<EnvironmentCapabilityDescriptor> MANAGEMENT_DESCRIPTORS =
      createManagementDescriptors();
  private static final Map<EnvironmentCapabilityId, EnvironmentCapabilityDescriptor> BY_ID =
      indexById(DESCRIPTORS);

  private EnvironmentCapabilityCatalog() {}

  /** 返回当前 capability catalog 版本，用于 HELLO 与 Platform 对齐判定。 */
  public static String version() {
    return VERSION;
  }

  /** 返回按固定 canonical 顺序排列的不可变 capability descriptor 列表。 */
  public static List<EnvironmentCapabilityDescriptor> descriptors() {
    return DESCRIPTORS;
  }

  /** 返回仅供管理执行器使用、绝不成为模型 Tool 的 capability descriptor 列表。 */
  public static List<EnvironmentCapabilityDescriptor> managementDescriptors() {
    return MANAGEMENT_DESCRIPTORS;
  }

  public static Optional<EnvironmentCapabilityDescriptor> find(EnvironmentCapabilityId id) {
    return Optional.ofNullable(BY_ID.get(Objects.requireNonNull(id, "id")));
  }

  public static EnvironmentCapabilityDescriptor require(EnvironmentCapabilityId id) {
    return find(id)
        .orElseThrow(() -> new IllegalArgumentException("unknown Environment capability: " + id));
  }

  private static List<EnvironmentCapabilityDescriptor> createDescriptors() {
    return List.of(
        descriptor(EnvironmentCapabilityIds.FS_READ, Duration.ofMinutes(1)),
        descriptor(EnvironmentCapabilityIds.FS_WRITE, Duration.ofMinutes(1)),
        descriptor(EnvironmentCapabilityIds.FS_EDIT, Duration.ofMinutes(1)),
        descriptor(EnvironmentCapabilityIds.PROCESS_EXEC, Duration.ofHours(1)),
        descriptor(EnvironmentCapabilityIds.FS_GREP, Duration.ofHours(1)),
        descriptor(EnvironmentCapabilityIds.FS_FIND, Duration.ofHours(1)),
        descriptor(EnvironmentCapabilityIds.LSP_GOTO_DEFINITION, Duration.ofMinutes(2)),
        descriptor(EnvironmentCapabilityIds.LSP_WORKSPACE_SYMBOLS, Duration.ofMinutes(2)),
        descriptor(EnvironmentCapabilityIds.LSP_JAVA_DECOMPILE, Duration.ofMinutes(2)),
        descriptor(EnvironmentCapabilityIds.SKILL_LOAD, Duration.ofMinutes(1)),
        descriptor(EnvironmentCapabilityIds.MCP_LOCAL_CALL, Duration.ofHours(1)));
  }

  /**
   * 管理专用能力：只复用 INVOKE/CANCEL/终态通道，不注册为模型 Tool，也不接受 workdir。
   *
   * <p>refresh 只扫描本地已发布 revision；install/update 允许一次普通 Git 获取，执行时间预算高于普通读取。
   */
  private static List<EnvironmentCapabilityDescriptor> createManagementDescriptors() {
    return List.of(
        descriptor(EnvironmentCapabilityIds.SKILL_SOURCE_REFRESH, Duration.ofMinutes(5)),
        descriptor(EnvironmentCapabilityIds.SKILL_SOURCE_INSTALL, Duration.ofMinutes(30)),
        descriptor(EnvironmentCapabilityIds.SKILL_SOURCE_UPDATE, Duration.ofMinutes(30)),
        descriptor(EnvironmentCapabilityIds.MCP_LOCAL_DISCOVER, Duration.ofMinutes(5)));
  }

  /** 全部已注册 descriptor：模型可见能力在前，管理专用能力在后。 */
  private static Map<EnvironmentCapabilityId, EnvironmentCapabilityDescriptor> indexById(
      List<EnvironmentCapabilityDescriptor> descriptors) {
    Objects.requireNonNull(descriptors, "descriptors");
    Map<EnvironmentCapabilityId, EnvironmentCapabilityDescriptor> result = new LinkedHashMap<>();
    Set<EnvironmentCapabilityDescriptor> uniqueDescriptors = new HashSet<>();
    for (EnvironmentCapabilityDescriptor descriptor : descriptors) {
      Objects.requireNonNull(descriptor, "descriptors[]");
      if (!uniqueDescriptors.add(descriptor)) {
        throw new IllegalStateException(
            "duplicate Environment capability descriptor: " + descriptor.id());
      }
      if (result.putIfAbsent(descriptor.id(), descriptor) != null) {
        throw new IllegalStateException("duplicate Environment capability id: " + descriptor.id());
      }
    }
    for (EnvironmentCapabilityDescriptor descriptor : MANAGEMENT_DESCRIPTORS) {
      if (result.putIfAbsent(descriptor.id(), descriptor) != null) {
        throw new IllegalStateException("duplicate Environment capability id: " + descriptor.id());
      }
      if (!EnvironmentCapabilityIds.MANAGEMENT_ONLY.contains(descriptor.id())) {
        throw new IllegalStateException(
            "management descriptor must be management-only: " + descriptor.id());
      }
    }
    return Map.copyOf(result);
  }

  private static EnvironmentCapabilityDescriptor descriptor(
      EnvironmentCapabilityId id, Duration timeout) {
    return new EnvironmentCapabilityDescriptor(id, VERSION, loadSchema(id), timeout);
  }

  /** 该能力是否要求具体 arguments 携带目标 Daemon 上的显式绝对 workdir。 */
  public static boolean requiresWorkdir(EnvironmentCapabilityId id) {
    require(id);
    return WORKDIR_CAPABILITY_IDS.contains(id);
  }

  private static InputSchema loadSchema(EnvironmentCapabilityId id) {
    // 三个 skill.source 管理能力共用同一份冻结来源配置 arguments schema：它们只差执行语义，不差 wire 形状。
    String fileName =
        (id.equals(EnvironmentCapabilityIds.SKILL_SOURCE_REFRESH)
                || id.equals(EnvironmentCapabilityIds.SKILL_SOURCE_INSTALL)
                || id.equals(EnvironmentCapabilityIds.SKILL_SOURCE_UPDATE))
            ? SHARED_SOURCE_SCHEMA
            : id.value() + ".schema.json";
    return CODEC.decode(loadText(fileName));
  }

  private static String loadText(String fileName) {
    Objects.requireNonNull(fileName, "fileName");
    String resource = RESOURCE_PREFIX + fileName;
    try (InputStream input = EnvironmentCapabilityCatalog.class.getResourceAsStream(resource)) {
      if (input == null) {
        throw new IllegalStateException(
            "missing Environment capability schema resource: " + resource);
      }
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException error) {
      throw new UncheckedIOException(
          "failed to load Environment capability schema: " + resource, error);
    }
  }
}
