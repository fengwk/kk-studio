package fun.fengwk.kkstudio.harness.runtime.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelPricing;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheBreakpoint;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheCapability;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCachePolicy;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

class RuntimeConfigJsonCodecTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final RuntimeConfigJsonCodec codec = new RuntimeConfigJsonCodec();

  @Test
  void roundTripsCanonicalSnapshot() {
    RuntimeConfigSnapshot snapshot =
        new RuntimeConfigSnapshot(
            agent(),
            model(),
            "sandbox",
            List.of("write", "read"),
            List.of("research", "code-review"),
            false);

    RuntimeConfigSnapshot decoded = codec.decode(codec.encode(snapshot));

    assertEquals(snapshot, decoded);
    assertEquals(List.of("read", "write"), decoded.toolNames());
    assertEquals(List.of("code-review", "research"), decoded.skillNames());
  }

  @Test
  void acceptsNullableEnvironmentAndYoloState() {
    RuntimeConfigSnapshot snapshot =
        new RuntimeConfigSnapshot(agent(), model(), null, List.of(), List.of(), true);

    assertEquals(snapshot, codec.decode(codec.encode(snapshot)));
    assertEquals(true, snapshot.yoloEnabled());
  }

  @Test
  void rejectsUnknownAndMissingFields() throws Exception {
    ObjectNode node = (ObjectNode) MAPPER.readTree(codec.encode(emptySnapshot()));
    node.put("extra", true);
    assertThrows(IllegalArgumentException.class, () -> codec.decode(node.toString()));

    node.remove("skillNames");
    assertThrows(IllegalArgumentException.class, () -> codec.decode(node.toString()));
  }

  @Test
  void rejectsDuplicateAndTrailingJson() {
    String encoded = codec.encode(emptySnapshot());
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(encoded.replaceFirst("\\}$", ",\"yoloEnabled\":false}")));
    assertThrows(IllegalArgumentException.class, () -> codec.decode(encoded + encoded));
  }

  @Test
  void rejectsInvalidCanonicalNames() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RuntimeConfigSnapshot(
                agent(), model(), null, List.of("read", "read"), List.of(), false));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RuntimeConfigSnapshot(
                agent(), model(), null, List.of("namespace:read"), List.of(), false));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RuntimeConfigSnapshot(agent(), model(), null, List.of(" read"), List.of(), false));
    assertThrows(
        IllegalArgumentException.class,
        () -> new RuntimeConfigSnapshot(agent(), model(), null, List.of(), List.of(""), false));
    assertThrows(
        IllegalArgumentException.class,
        () -> new RuntimeConfigSnapshot(agent(), model(), " ", List.of(), List.of(), false));
  }

  @Test
  void rejectsToolsOrSkillsWhenModelDoesNotSupportTools() {
    ModelDescriptor descriptor = model().descriptor();
    ModelDescriptor noTools =
        new ModelDescriptor(
            descriptor.providerResourceId(),
            descriptor.modelResourceId(),
            descriptor.providerType(),
            descriptor.modelId(),
            false,
            descriptor.reasoning(),
            descriptor.pricing(),
            descriptor.promptCachePolicy());

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RuntimeConfigSnapshot(
                agent(),
                new ModelSnapshot(noTools, model().variant()),
                null,
                List.of("read"),
                List.of(),
                false));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RuntimeConfigSnapshot(
                agent(),
                new ModelSnapshot(noTools, model().variant()),
                null,
                List.of(),
                List.of("research"),
                false));
  }

  private static RuntimeConfigSnapshot emptySnapshot() {
    return new RuntimeConfigSnapshot(agent(), model(), null, List.of(), List.of(), false);
  }

  private static AgentSnapshot agent() {
    return new AgentSnapshot(1001L, "primary-agent", "You are a careful assistant.");
  }

  private static ModelSnapshot model() {
    return new ModelSnapshot(
        new ModelDescriptor(
            9001L,
            8001L,
            ProviderType.OPENAI,
            "gpt-5-mini",
            true,
            true,
            new ModelPricing(
                "USD",
                "tier-1",
                "default",
                new BigDecimal("1.5"),
                "v1",
                new BigDecimal("3"),
                new BigDecimal("6"),
                new BigDecimal("0.3"),
                new BigDecimal("3.75"),
                BigDecimal.ZERO,
                BigDecimal.ZERO),
            PromptCachePolicy.breakpointsShort(
                PromptCacheCapability.breakpoints(
                    Set.of(PromptCacheRetention.SHORT, PromptCacheRetention.LONG),
                    Set.of(PromptCacheBreakpoint.SYSTEM, PromptCacheBreakpoint.TOOLS)))),
        new ModelVariant("balanced", null, 0.2, 0.9, null, null, null, List.of("STOP"), null));
  }
}
