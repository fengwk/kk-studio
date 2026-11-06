package fun.fengwk.kkstudio.platform.catalog.model;

import static fun.fengwk.kkstudio.platform.catalog.model.ModelTestData.executableConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.platform.catalog.dto.ModelConfigDTO;

/** Model Variant 覆盖必须引用当前全局逻辑 Model 名称。 */
class ModelDefaultVariantResolverTest {

  @Test
  void resolvesDefaultAndRejectsUnknownModelOrVariant() {
    ModelRepository store = mock(ModelRepository.class);
    ModelRuntimeConfigParser parser = new ModelRuntimeConfigParser(JsonMapper.builder().build());
    Model model =
        Model.builder()
            .kind(ModelKind.MODEL)
            .name("model")
            .configJson(parser.encode(executableConfig()))
            .build();
    when(store.getByName("model")).thenReturn(model);

    ModelDefaultVariantResolver resolver = new ModelDefaultVariantResolver(store, parser);

    assertEquals("default", resolver.resolve("model", null));
    assertEquals("default", resolver.resolve("model", " default "));
    assertEquals(
        "unknown agent model variant: model=model, variant=missing",
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve("model", "missing"))
            .getMessage());
    assertEquals(
        "unknown agent model: missing",
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve("missing", "default"))
            .getMessage());
  }

  /** 纯入口只依赖传入 config：override 归一化、default 回退、未声明 variant 与缺失 default 的错误语义保持。 */
  @Test
  void resolvesConfiguredVariantAgainstProvidedConfig() {
    ModelConfigDTO config = executableConfig();

    assertEquals(
        "default", ModelDefaultVariantResolver.resolveConfiguredVariant("model", null, config));
    assertEquals(
        "default",
        ModelDefaultVariantResolver.resolveConfiguredVariant("model", "  default  ", config));
    assertEquals(
        "unknown agent model variant: model=model, variant=missing",
        assertThrows(
                IllegalArgumentException.class,
                () ->
                    ModelDefaultVariantResolver.resolveConfiguredVariant(
                        "model", "missing", config))
            .getMessage());
    ModelConfigDTO noDefault = executableConfig();
    noDefault.setDefaultVariant(null);
    assertEquals(
        "model defaultVariant missing: model",
        assertThrows(
                IllegalStateException.class,
                () -> ModelDefaultVariantResolver.resolveConfiguredVariant("model", " ", noDefault))
            .getMessage());
  }
}
