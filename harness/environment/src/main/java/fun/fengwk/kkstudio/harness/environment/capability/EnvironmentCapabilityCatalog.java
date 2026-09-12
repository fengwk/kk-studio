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
 * Its order is the stable order used by the shared capability contract.
 */
public final class EnvironmentCapabilityCatalog {

  /** 未改变形态的能力版本（{@code skill.load} 等）。 */
  public static final String VERSION = "1";

  /** 引入必填绝对 workdir 的 coding/process/LSP 能力版本。 */
  public static final String WORKDIR_VERSION = "2";

  private static final String RESOURCE_PREFIX =
      "/fun/fengwk/kkstudio/harness/environment/capability/schemas/";
  private static final SchemaJsonCodec CODEC = new SchemaJsonCodec();
  private static final Set<EnvironmentCapabilityId> WORKDIR_CAPABILITY_IDS =
      Set.of(
          EnvironmentCapabilityIds.FS_READ,
          EnvironmentCapabilityIds.FS_WRITE,
          EnvironmentCapabilityIds.FS_APPLY_EDIT,
          EnvironmentCapabilityIds.PROCESS_EXEC,
          EnvironmentCapabilityIds.FS_SEARCH,
          EnvironmentCapabilityIds.FS_FIND,
          EnvironmentCapabilityIds.LSP_GOTO_DEFINITION,
          EnvironmentCapabilityIds.LSP_WORKSPACE_SYMBOLS,
          EnvironmentCapabilityIds.LSP_JAVA_DECOMPILE);
  private static final List<EnvironmentCapabilityDescriptor> DESCRIPTORS = createDescriptors();
  private static final Map<EnvironmentCapabilityId, EnvironmentCapabilityDescriptor> BY_ID =
      indexById(DESCRIPTORS);

  private EnvironmentCapabilityCatalog() {}

  public static String version() {
    return VERSION;
  }

  /** 返回按固定 canonical 顺序排列的不可变 capability descriptor 列表。 */
  public static List<EnvironmentCapabilityDescriptor> descriptors() {
    return DESCRIPTORS;
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
        workdirDescriptor(EnvironmentCapabilityIds.FS_READ, Duration.ofMinutes(1)),
        workdirDescriptor(EnvironmentCapabilityIds.FS_WRITE, Duration.ofMinutes(1)),
        workdirDescriptor(EnvironmentCapabilityIds.FS_APPLY_EDIT, Duration.ofMinutes(1)),
        workdirDescriptor(EnvironmentCapabilityIds.PROCESS_EXEC, Duration.ofHours(1)),
        workdirDescriptor(EnvironmentCapabilityIds.FS_SEARCH, Duration.ofHours(1)),
        workdirDescriptor(EnvironmentCapabilityIds.FS_FIND, Duration.ofHours(1)),
        workdirDescriptor(EnvironmentCapabilityIds.LSP_GOTO_DEFINITION, Duration.ofMinutes(2)),
        workdirDescriptor(EnvironmentCapabilityIds.LSP_WORKSPACE_SYMBOLS, Duration.ofMinutes(2)),
        workdirDescriptor(EnvironmentCapabilityIds.LSP_JAVA_DECOMPILE, Duration.ofMinutes(2)),
        descriptor(EnvironmentCapabilityIds.SKILL_LOAD, Duration.ofMinutes(1)));
  }

  private static EnvironmentCapabilityDescriptor descriptor(
      EnvironmentCapabilityId id, Duration timeout) {
    return new EnvironmentCapabilityDescriptor(id, VERSION, loadSchema(id), timeout);
  }

  /** 使用引入必填 workdir 的能力版本构造 descriptor。 */
  private static EnvironmentCapabilityDescriptor workdirDescriptor(
      EnvironmentCapabilityId id, Duration timeout) {
    return new EnvironmentCapabilityDescriptor(id, WORKDIR_VERSION, loadSchema(id), timeout);
  }

  /** 该能力是否要求具体 arguments 携带目标 Daemon 上的显式绝对 workdir。 */
  public static boolean requiresWorkdir(EnvironmentCapabilityId id) {
    require(id);
    return WORKDIR_CAPABILITY_IDS.contains(id);
  }

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
    return Map.copyOf(result);
  }

  private static InputSchema loadSchema(EnvironmentCapabilityId id) {
    return CODEC.decode(loadText(id.value() + ".schema.json"));
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
