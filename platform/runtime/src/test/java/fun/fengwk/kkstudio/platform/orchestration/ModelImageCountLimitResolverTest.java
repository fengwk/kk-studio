package fun.fengwk.kkstudio.platform.orchestration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.api.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.api.entry.ExecutionSelection;
import fun.fengwk.kkstudio.harness.api.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.builtin.driver.BuiltinExecution;
import fun.fengwk.kkstudio.harness.model.provider.ProviderType;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelGroupConfigDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelGroupMemberDTO;
import fun.fengwk.kkstudio.platform.catalog.model.Model;
import fun.fengwk.kkstudio.platform.catalog.model.ModelGroupConfigCodec;
import fun.fengwk.kkstudio.platform.catalog.model.ModelKind;
import fun.fengwk.kkstudio.platform.catalog.model.ModelRepository;
import fun.fengwk.kkstudio.platform.catalog.provider.Provider;
import fun.fengwk.kkstudio.platform.catalog.provider.ProviderRepository;

import java.util.List;

class ModelImageCountLimitResolverTest {

  private final ModelRepository modelRepository = mock(ModelRepository.class);
  private final ProviderRepository providerRepository = mock(ProviderRepository.class);
  private final ModelGroupConfigCodec modelGroupConfigCodec = mock(ModelGroupConfigCodec.class);
  private final ModelImageCountLimitResolver resolver =
      new ModelImageCountLimitResolver(modelRepository, providerRepository, modelGroupConfigCodec);

  @BeforeEach
  void setupModel() {
    when(modelRepository.getByName("vision-model"))
        .thenReturn(
            Model.builder()
                .name("vision-model")
                .kind(ModelKind.MODEL)
                .providerName("openai")
                .build());
  }

  @Test
  void resolvesTheSelectedModelsKnownOfficialProtocolImageCount() {
    when(providerRepository.getByName("openai"))
        .thenReturn(
            Provider.builder()
                .name("openai")
                .providerType(ProviderType.OPENAI)
                .baseUrl("https://api.openai.com/v1")
                .build());

    assertEquals(1500, resolver.resolve(branchSettings()));
  }

  @Test
  void usesTheStrictestKnownLimitAcrossModelGroupMembers() {
    ModelGroupConfigDTO group = new ModelGroupConfigDTO();
    group.setMembers(List.of(member("vision-model"), member("claude-model")));
    when(modelRepository.getByName("vision-group"))
        .thenReturn(
            Model.builder()
                .name("vision-group")
                .kind(ModelKind.GROUP)
                .groupConfigJson("{}")
                .build());
    when(modelGroupConfigCodec.decode("{}")).thenReturn(group);
    when(modelRepository.getByName("claude-model"))
        .thenReturn(
            Model.builder()
                .name("claude-model")
                .kind(ModelKind.MODEL)
                .providerName("anthropic")
                .build());
    when(providerRepository.getByName("openai"))
        .thenReturn(
            Provider.builder()
                .name("openai")
                .providerType(ProviderType.OPENAI)
                .baseUrl("https://api.openai.com/v1")
                .build());
    when(providerRepository.getByName("anthropic"))
        .thenReturn(
            Provider.builder()
                .name("anthropic")
                .providerType(ProviderType.ANTHROPIC)
                .baseUrl("https://api.anthropic.com/v1")
                .build());

    assertEquals(100, resolver.resolve(branchSettings("vision-group")));
  }

  @Test
  void leavesTheProtocolLimitUnknownForNonBuiltinExecutions() {
    BranchSettings settings =
        new BranchSettings("agent", new ExecutionSelection("custom-driver", "{}"), null);

    assertNull(resolver.resolve(settings));
  }

  @Test
  void leavesTheProtocolLimitUnknownForNonOfficialEndpoints() {
    when(providerRepository.getByName("openai"))
        .thenReturn(
            Provider.builder()
                .name("openai")
                .providerType(ProviderType.OPENAI)
                .baseUrl("https://gateway.example/v1")
                .build());

    assertNull(resolver.resolve(branchSettings()));
  }

  @Test
  void leavesTheProtocolLimitUnknownForProvidersWithoutABaseUrl() {
    // 免费/未配置 Provider 的 baseUrl 为 null 或空白：没有可判定的官方端点，必须按未知限制返回 null。
    for (String baseUrl : new String[] {null, "", "   "}) {
      when(providerRepository.getByName("openai"))
          .thenReturn(
              Provider.builder()
                  .name("openai")
                  .providerType(ProviderType.OPENAI)
                  .baseUrl(baseUrl)
                  .build());

      assertNull(resolver.resolve(branchSettings()), "baseUrl=" + baseUrl);
    }
  }

  private static BranchSettings branchSettings() {
    return branchSettings("vision-model");
  }

  private static BranchSettings branchSettings(String modelName) {
    ExecutionSelection execution = BuiltinExecution.of(ModelSelection.model(modelName, null));
    return new BranchSettings("agent", execution, null);
  }

  private static ModelGroupMemberDTO member(String modelName) {
    ModelGroupMemberDTO member = new ModelGroupMemberDTO();
    member.setModelName(modelName);
    member.setVariant("default");
    return member;
  }
}
