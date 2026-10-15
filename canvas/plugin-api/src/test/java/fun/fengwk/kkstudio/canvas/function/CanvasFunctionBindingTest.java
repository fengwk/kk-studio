package fun.fengwk.kkstudio.canvas.function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.canvas.CanvasJson;
import fun.fengwk.kkstudio.canvas.CanvasResourceKind;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 冻结绑定与插件来源的值语义：epoch、完整定义与来源身份都严格校验，内置来源用 null 明确表达。 */
class CanvasFunctionBindingTest {

  private static final UUID AUTH_GENERATION =
      UUID.fromString("00000000-0000-0000-0000-0000000000aa");
  private static final String CAPABILITY_FINGERPRINT = "a".repeat(64);
  private static final String CONTEXT_FINGERPRINT = "b".repeat(64);

  private static CanvasFunctionDefinition definition() {
    return CanvasFunctionDefinition.of(
        "test.image",
        "Test Image",
        CanvasJson.parseObject(
            "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{}}"),
        CanvasResourceKind.IMAGE,
        new CanvasFunctionReferencePolicy(Set.of(CanvasResourceKind.IMAGE), 1, Map.of()));
  }

  private static CanvasFunctionPluginSource source(long configRevision) {
    return new CanvasFunctionPluginSource(
        "mini-max",
        "1.2.3",
        configRevision,
        CAPABILITY_FINGERPRINT,
        AUTH_GENERATION,
        CONTEXT_FINGERPRINT);
  }

  /** 插件来源冻结身份事实并可原样读回；不携带任何凭据 Key。 */
  @Test
  void pluginSourceFreezesIdentityFacts() {
    CanvasFunctionPluginSource source = source(7L);

    assertEquals("mini-max", source.pluginId());
    assertEquals("1.2.3", source.pluginCodeVersion());
    assertEquals(7L, source.configRevision());
    assertEquals(CAPABILITY_FINGERPRINT, source.capabilityFingerprint());
    assertEquals(AUTH_GENERATION, source.authGeneration());
    assertEquals(CONTEXT_FINGERPRINT, source.contextFingerprint());
  }

  /** pluginId 必须是 canonical 小写标识符：大写、空白、越界与带 padding 都拒绝，不做宽松标准化。 */
  @Test
  void pluginSourceRejectsNonCanonicalPluginId() {
    for (String pluginId :
        new String[] {null, "", "Mini-Max", "mini_max", " mini-max", "a".repeat(65)}) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new CanvasFunctionPluginSource(
                  pluginId,
                  "1.2.3",
                  7L,
                  CAPABILITY_FINGERPRINT,
                  AUTH_GENERATION,
                  CONTEXT_FINGERPRINT));
    }
  }

  /** 版本必须非空、有界且无前后空白；configRevision 必须为正。 */
  @Test
  void pluginSourceRejectsInvalidVersionAndRevision() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CanvasFunctionPluginSource(
                "mini-max", " ", 7L, CAPABILITY_FINGERPRINT, AUTH_GENERATION, CONTEXT_FINGERPRINT));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CanvasFunctionPluginSource(
                "mini-max",
                " v1",
                7L,
                CAPABILITY_FINGERPRINT,
                AUTH_GENERATION,
                CONTEXT_FINGERPRINT));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CanvasFunctionPluginSource(
                "mini-max",
                "1".repeat(65),
                7L,
                CAPABILITY_FINGERPRINT,
                AUTH_GENERATION,
                CONTEXT_FINGERPRINT));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CanvasFunctionPluginSource(
                "mini-max",
                null,
                7L,
                CAPABILITY_FINGERPRINT,
                AUTH_GENERATION,
                CONTEXT_FINGERPRINT));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CanvasFunctionPluginSource(
                "mini-max",
                "1.2.3",
                0L,
                CAPABILITY_FINGERPRINT,
                AUTH_GENERATION,
                CONTEXT_FINGERPRINT));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CanvasFunctionPluginSource(
                "mini-max",
                "1.2.3",
                -1L,
                CAPABILITY_FINGERPRINT,
                AUTH_GENERATION,
                CONTEXT_FINGERPRINT));
  }

  /** 能力/上下文指纹必须是 64 位小写 SHA-256；认证代际必须是真实 UUID。 */
  @Test
  void pluginSourceRejectsInvalidFingerprintsAndAuthGeneration() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CanvasFunctionPluginSource(
                "mini-max", "1.2.3", 7L, "A".repeat(64), AUTH_GENERATION, CONTEXT_FINGERPRINT));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CanvasFunctionPluginSource(
                "mini-max", "1.2.3", 7L, "abc", AUTH_GENERATION, CONTEXT_FINGERPRINT));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CanvasFunctionPluginSource(
                "mini-max", "1.2.3", 7L, null, AUTH_GENERATION, CONTEXT_FINGERPRINT));
    assertThrows(
        NullPointerException.class,
        () ->
            new CanvasFunctionPluginSource(
                "mini-max", "1.2.3", 7L, CAPABILITY_FINGERPRINT, null, CONTEXT_FINGERPRINT));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CanvasFunctionPluginSource(
                "mini-max", "1.2.3", 7L, CAPABILITY_FINGERPRINT, AUTH_GENERATION, "not-a-digest"));

    assertTrue(CanvasFunctionPluginSource.isFingerprint(CAPABILITY_FINGERPRINT));
    assertFalse(CanvasFunctionPluginSource.isFingerprint("A".repeat(64)));
    assertFalse(CanvasFunctionPluginSource.isFingerprint("a".repeat(63)));
    assertFalse(CanvasFunctionPluginSource.isFingerprint(null));
  }

  /** 绑定以正 epoch 与完整定义冻结，并允许内置来源（pluginSource 为 null）。 */
  @Test
  void bindingFreezesEpochDefinitionAndBuiltinSource() {
    CanvasFunctionDefinition definition = definition();
    CanvasFunctionBinding binding = new CanvasFunctionBinding(9L, definition, null);

    assertEquals(9L, binding.catalogEpoch());
    assertEquals(definition, binding.definition());
    assertNull(binding.pluginSource());
  }

  /** 绑定拒绝非正 epoch 与缺失定义。 */
  @Test
  void bindingRejectsNonPositiveEpochAndMissingDefinition() {
    assertThrows(
        IllegalArgumentException.class, () -> new CanvasFunctionBinding(0L, definition(), null));
    assertThrows(
        IllegalArgumentException.class, () -> new CanvasFunctionBinding(-1L, definition(), null));
    assertThrows(NullPointerException.class, () -> new CanvasFunctionBinding(1L, null, null));
  }

  /** 插件来源的 configRevision 不得晚于绑定 epoch：等于允许，更新即拒绝。 */
  @Test
  void bindingBoundsPluginSourceRevisionByEpoch() {
    CanvasFunctionDefinition definition = definition();
    assertEquals(
        7L, new CanvasFunctionBinding(7L, definition, source(7L)).pluginSource().configRevision());
    assertEquals(8L, new CanvasFunctionBinding(8L, definition, source(7L)).catalogEpoch());
    assertThrows(
        IllegalArgumentException.class,
        () -> new CanvasFunctionBinding(6L, definition, source(7L)));
  }
}
