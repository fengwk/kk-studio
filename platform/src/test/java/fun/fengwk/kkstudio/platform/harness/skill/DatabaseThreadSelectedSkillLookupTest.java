package fun.fengwk.kkstudio.platform.harness.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.builtin.skill.ThreadSelectedSkillLookup;
import fun.fengwk.kkstudio.harness.runtime.invocation.codec.ModelRequestSpecJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelRequestSpec;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.SkillBinding;
import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.runtime.model.ModelPricing;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.tool.EnvironmentBinding;
import fun.fengwk.kkstudio.platform.testing.TestEnvironmentBindings;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * {@link DatabaseThreadSelectedSkillLookup}：用 invocation request codec 解码并直接返回冻结的 canonical skill
 * bindings。
 */
class DatabaseThreadSelectedSkillLookupTest {

  private static final EnvironmentBinding ENV_ID = TestEnvironmentBindings.binding("env-1");
  private static final ModelRequestSpecJsonCodec REQUEST_CODEC = new ModelRequestSpecJsonCodec();

  private static final UUID INVOCATION_ID = new UUID(0L, 42L);
  private static final UUID THREAD_ID = new UUID(0L, 7L);

  @Test
  void resolvesFrozenSkillBindingsThroughTheCodec() {
    SelectedSkillBindingMapper mapper = mock(SelectedSkillBindingMapper.class);
    when(mapper.findModelRequest(INVOCATION_ID, THREAD_ID)).thenReturn(encodedRequest(ENV_ID));

    ThreadSelectedSkillLookup lookup = new DatabaseThreadSelectedSkillLookup(mapper);
    var skills = lookup.selectedSkills(INVOCATION_ID, THREAD_ID);

    assertEquals(1, skills.size());
    var skill = skills.get(0);
    assertEquals("review", skill.name());
    assertEquals("Review code", skill.description());
    assertEquals(ENV_ID, skill.sourceEnvironment());
  }

  @Test
  void missingRequestRowReturnsEmptySkills() {
    SelectedSkillBindingMapper mapper = mock(SelectedSkillBindingMapper.class);
    when(mapper.findModelRequest(INVOCATION_ID, THREAD_ID)).thenReturn(null);

    ThreadSelectedSkillLookup lookup = new DatabaseThreadSelectedSkillLookup(mapper);
    assertTrue(lookup.selectedSkills(INVOCATION_ID, THREAD_ID).isEmpty());
  }

  @Test
  void preservesPlatformSkillWithoutSourceEnvironment() {
    SelectedSkillBindingMapper mapper = mock(SelectedSkillBindingMapper.class);
    when(mapper.findModelRequest(INVOCATION_ID, THREAD_ID)).thenReturn(encodedRequest(null));

    ThreadSelectedSkillLookup lookup = new DatabaseThreadSelectedSkillLookup(mapper);
    var skills = lookup.selectedSkills(INVOCATION_ID, THREAD_ID);
    assertEquals(1, skills.size());
    assertNull(skills.getFirst().sourceEnvironment());
  }

  private static String encodedRequest(EnvironmentBinding sourceEnvironment) {
    ModelRequestSpec request =
        new ModelRequestSpec(
            ProviderType.OPENAI,
            modelDescriptor(),
            new ModelVariant("v1", null, null, null, null, null, null, List.of(), null),
            List.of(),
            List.of(),
            List.of(new SkillBinding("review", "Review code", sourceEnvironment)),
            List.of(),
            ProviderCacheControl.none());
    return REQUEST_CODEC.encode(request);
  }

  private static ModelDescriptor modelDescriptor() {
    return new ModelDescriptor(
        "provider",
        "model",
        Set.of(ModelInputModality.TEXT),
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
