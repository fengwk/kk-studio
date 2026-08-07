package fun.fengwk.kkstudio.core.ai.runtime.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.invocation.codec.ModelInvocationRequestJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationRequest;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.SkillBinding;
import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelPricing;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.skill.ThreadSelectedSkillLookup;
import fun.fengwk.kkstudio.harness.tool.EnvironmentId;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * {@link DatabaseThreadSelectedSkillLookup}：用 invocation request codec 解码冻结 skill bindings，并把
 * canonical {@code EnvironmentId} 映射回 legacy {@code SkillBinding} 表面（LoadSkillTool 契约）。
 */
class DatabaseThreadSelectedSkillLookupTest {

  private static final EnvironmentId ENV_ID = new EnvironmentId(UUID.randomUUID().toString());
  private static final ModelInvocationRequestJsonCodec REQUEST_CODEC =
      new ModelInvocationRequestJsonCodec();

  @Test
  void resolvesFrozenSkillBindingsThroughTheCodec() {
    SelectedSkillBindingMapper mapper = mock(SelectedSkillBindingMapper.class);
    when(mapper.findModelRequest(42L, 7L)).thenReturn(encodedRequest(ENV_ID));

    ThreadSelectedSkillLookup lookup = new DatabaseThreadSelectedSkillLookup(mapper);
    var skills = lookup.selectedSkills(42L, 7L);

    assertEquals(1, skills.size());
    var skill = skills.get(0);
    assertEquals("review", skill.name());
    assertEquals("Review code", skill.description());
    // legacy 表面携带 canonical EnvironmentId 文本（EnvironmentSkillBodyLoader 严格解析）。
    assertEquals(ENV_ID.value(), skill.sourceEnvironment());
  }

  @Test
  void missingRequestRowReturnsEmptySkills() {
    SelectedSkillBindingMapper mapper = mock(SelectedSkillBindingMapper.class);
    when(mapper.findModelRequest(42L, 7L)).thenReturn(null);

    ThreadSelectedSkillLookup lookup = new DatabaseThreadSelectedSkillLookup(mapper);
    assertTrue(lookup.selectedSkills(42L, 7L).isEmpty());
  }

  @Test
  void platformSkillWithoutSourceEnvironmentFailsClosed() {
    SelectedSkillBindingMapper mapper = mock(SelectedSkillBindingMapper.class);
    when(mapper.findModelRequest(42L, 7L)).thenReturn(encodedRequest(null));

    ThreadSelectedSkillLookup lookup = new DatabaseThreadSelectedSkillLookup(mapper);
    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> lookup.selectedSkills(42L, 7L));
    assertTrue(error.getMessage().contains("review"), error.getMessage());
  }

  private static String encodedRequest(EnvironmentId sourceEnvironmentId) {
    ModelInvocationRequest request =
        new ModelInvocationRequest(
            ENV_ID,
            new ProviderRequest(
                modelDescriptor(),
                new ModelVariant("v1", null, null, null, null, null, null, List.of(), null),
                List.of(),
                List.of(),
                ProviderCacheControl.none()),
            List.of(),
            List.of(new SkillBinding("review", "Review code", sourceEnvironmentId)),
            false);
    return REQUEST_CODEC.encode(request);
  }

  private static ModelDescriptor modelDescriptor() {
    return new ModelDescriptor(
        "provider",
        "model",
        true,
        true,
        new ModelPricing(
            "USD",
            "standard",
            "standard",
            BigDecimal.ONE,
            "1",
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO));
  }
}
