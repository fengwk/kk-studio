package fun.fengwk.kkstudio.platform.harness.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.builtin.skill.SelectedSkill;
import fun.fengwk.kkstudio.harness.builtin.skill.ThreadSelectedSkillLookup;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.runtime.invocation.codec.ModelRequestSpecJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelRequestSpec;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.SkillBinding;
import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.runtime.model.ModelPricing;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;
import fun.fengwk.kkstudio.platform.testing.TestEnvironments;

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

  private static final EnvironmentId ENV_ID = TestEnvironments.environmentId("env-1");
  private static final ModelRequestSpecJsonCodec REQUEST_CODEC = new ModelRequestSpecJsonCodec();

  private static final UUID INVOCATION_ID = new UUID(0L, 42L);
  private static final UUID THREAD_ID = new UUID(0L, 7L);
  private static final UUID SOURCE_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
  private static final String CONTENT_REVISION =
      "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

  @Test
  void resolvesFrozenSkillBindingsThroughTheCodec() {
    SelectedSkillBindingMapper mapper = mock(SelectedSkillBindingMapper.class);
    when(mapper.findModelRequest(INVOCATION_ID, THREAD_ID)).thenReturn(encodedRequest(ENV_ID));

    ThreadSelectedSkillLookup lookup = new DatabaseThreadSelectedSkillLookup(mapper);
    Optional<SelectedSkill> result = lookup.findSelected(INVOCATION_ID, THREAD_ID, "review");

    assertTrue(result.isPresent());
    SelectedSkill skill = result.get();
    // 全部六个冻结字段都必须来自持久请求，load_skill 才能按精确版本取回正文。
    assertEquals(ENV_ID, skill.sourceEnvironmentId());
    assertEquals(SOURCE_ID, skill.sourceId());
    assertEquals("review", skill.name());
    assertEquals("Review code", skill.description());
    assertEquals("/host/skills/review", skill.baseDirectory());
    assertEquals(CONTENT_REVISION, skill.contentRevision());

    assertTrue(lookup.findSelected(INVOCATION_ID, THREAD_ID, "unknown").isEmpty());
  }

  @Test
  void missingRequestRowReturnsEmptySkills() {
    SelectedSkillBindingMapper mapper = mock(SelectedSkillBindingMapper.class);
    when(mapper.findModelRequest(INVOCATION_ID, THREAD_ID)).thenReturn(null);

    ThreadSelectedSkillLookup lookup = new DatabaseThreadSelectedSkillLookup(mapper);
    assertTrue(lookup.findSelected(INVOCATION_ID, THREAD_ID, "review").isEmpty());
  }

  /** 冻结字段在持久化边界上原样保留：不同来源的同一名称互不覆盖。 */
  @Test
  void preservesEveryFrozenIdentityFieldFromThePersistedRequest() {
    SelectedSkillBindingMapper mapper = mock(SelectedSkillBindingMapper.class);
    when(mapper.findModelRequest(INVOCATION_ID, THREAD_ID)).thenReturn(encodedRequest(ENV_ID));

    ThreadSelectedSkillLookup lookup = new DatabaseThreadSelectedSkillLookup(mapper);
    SelectedSkill skill = lookup.findSelected(INVOCATION_ID, THREAD_ID, "review").orElseThrow();

    assertEquals("/host/skills/review", skill.baseDirectory());
    assertEquals(CONTENT_REVISION, skill.contentRevision());
    assertNotNull(skill.sourceId());
  }

  private static String encodedRequest(EnvironmentId sourceEnvironmentId) {
    ModelRequestSpec requestSpec =
        new ModelRequestSpec(
            ProviderType.OPENAI,
            new UUID(0L, 1L),
            modelDescriptor(),
            new ModelVariant("v1"),
            1024,
            List.of(),
            List.of(),
            List.of(
                new SkillBinding(
                    sourceEnvironmentId,
                    SOURCE_ID,
                    "review",
                    "Review code",
                    "/host/skills/review",
                    CONTENT_REVISION)),
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
