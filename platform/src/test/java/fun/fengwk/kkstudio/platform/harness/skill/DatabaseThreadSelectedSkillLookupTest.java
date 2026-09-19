package fun.fengwk.kkstudio.platform.harness.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.builtin.skill.SelectedSkill;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * {@link DatabaseThreadSelectedSkillLookup}：用 invocation request codec 解码并按名称返回选中的 {@link
 * SelectedSkill}。
 */
class DatabaseThreadSelectedSkillLookupTest {

  private static final ModelRequestSpecJsonCodec REQUEST_CODEC = new ModelRequestSpecJsonCodec();

  private static final UUID INVOCATION_ID = new UUID(0L, 42L);
  private static final UUID THREAD_ID = new UUID(0L, 7L);

  @Test
  void resolvesFrozenSkillBindingsThroughTheCodec() {
    SelectedSkillBindingMapper mapper = mock(SelectedSkillBindingMapper.class);
    when(mapper.findModelRequest(INVOCATION_ID, THREAD_ID)).thenReturn(encodedRequest());

    ThreadSelectedSkillLookup lookup = new DatabaseThreadSelectedSkillLookup(mapper);
    Optional<SelectedSkill> result = lookup.findSelected(INVOCATION_ID, THREAD_ID, "review");

    assertTrue(result.isPresent());
    SelectedSkill skill = result.get();
    // 包身份必须来自持久请求，load_skill 才能按精确的三元组取回正文。
    assertEquals("review", skill.name());
    assertEquals("review-package", skill.packageName());
    assertEquals("1.0.0", skill.packageVersion());
    assertEquals("Review code", skill.description());

    assertTrue(lookup.findSelected(INVOCATION_ID, THREAD_ID, "unknown").isEmpty());
  }

  @Test
  void missingRequestRowReturnsEmptySkills() {
    SelectedSkillBindingMapper mapper = mock(SelectedSkillBindingMapper.class);
    when(mapper.findModelRequest(INVOCATION_ID, THREAD_ID)).thenReturn(null);

    ThreadSelectedSkillLookup lookup = new DatabaseThreadSelectedSkillLookup(mapper);
    assertTrue(lookup.findSelected(INVOCATION_ID, THREAD_ID, "review").isEmpty());
  }

  /** 冻结字段在持久化边界上原样保留。 */
  @Test
  void preservesEveryFrozenIdentityFieldFromThePersistedRequest() {
    SelectedSkillBindingMapper mapper = mock(SelectedSkillBindingMapper.class);
    when(mapper.findModelRequest(INVOCATION_ID, THREAD_ID)).thenReturn(encodedRequest());

    ThreadSelectedSkillLookup lookup = new DatabaseThreadSelectedSkillLookup(mapper);
    SelectedSkill skill = lookup.findSelected(INVOCATION_ID, THREAD_ID, "review").orElseThrow();

    assertEquals("review-package", skill.packageName());
    assertEquals("1.0.0", skill.packageVersion());
  }

  private static String encodedRequest() {
    ModelRequestSpec requestSpec =
        new ModelRequestSpec(
            ProviderType.OPENAI,
            new UUID(0L, 1L),
            modelDescriptor(),
            new ModelVariant("v1"),
            1024,
            "Test system instruction.",
            List.of(),
            List.of(new SkillBinding("review", "review-package", "1.0.0", "Review code")),
            List.of(),
            ProviderCacheControl.none());
    return REQUEST_CODEC.encode(requestSpec);
  }

  private static ModelDescriptor modelDescriptor() {
    return new ModelDescriptor(
        "provider",
        "model",
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
