package fun.fengwk.kkstudio.core.ai.runtime.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import fun.fengwk.kkstudio.harness.tool.EnvironmentName;

import java.math.BigDecimal;
import java.util.List;

/**
 * {@link DatabaseThreadSelectedSkillLookup}：用 invocation request codec 解码并直接返回冻结的 canonical skill
 * bindings。
 */
class DatabaseThreadSelectedSkillLookupTest {

  private static final EnvironmentName ENV_ID = new EnvironmentName("env-1");
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
    assertEquals(ENV_ID, skill.sourceEnvironmentName());
  }

  @Test
  void missingRequestRowReturnsEmptySkills() {
    SelectedSkillBindingMapper mapper = mock(SelectedSkillBindingMapper.class);
    when(mapper.findModelRequest(42L, 7L)).thenReturn(null);

    ThreadSelectedSkillLookup lookup = new DatabaseThreadSelectedSkillLookup(mapper);
    assertTrue(lookup.selectedSkills(42L, 7L).isEmpty());
  }

  @Test
  void preservesPlatformSkillWithoutSourceEnvironment() {
    SelectedSkillBindingMapper mapper = mock(SelectedSkillBindingMapper.class);
    when(mapper.findModelRequest(42L, 7L)).thenReturn(encodedRequest(null));

    ThreadSelectedSkillLookup lookup = new DatabaseThreadSelectedSkillLookup(mapper);
    var skills = lookup.selectedSkills(42L, 7L);
    assertEquals(1, skills.size());
    assertNull(skills.getFirst().sourceEnvironmentName());
  }

  private static String encodedRequest(EnvironmentName sourceEnvironmentName) {
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
            List.of(new SkillBinding("review", "Review code", sourceEnvironmentName)),
            false,
            100_000,
            null);
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
