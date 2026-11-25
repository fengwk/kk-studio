package fun.fengwk.kkstudio.platform.harness.http;

import static fun.fengwk.kkstudio.platform.harness.http.HarnessRuntimeRequestMapperTestSupport.modelSelection;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.harness.api.entry.ModelSelection;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessModelRequestDebugRequestDTO;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessModelSelectionDTO;

class HarnessRuntimeRequestMapperSettingsTest {

  /**
   * 测试意图：Debug 请求的草稿选择复用 canonical model 与 branch Environment name 校验——model 必填、environmentName 可空
   * canonical，非法值一律 fail closed。
   */
  @Test
  void mapsModelRequestDebugDraftSelectionStrictly() {
    HarnessModelRequestDebugRequestDTO request = new HarnessModelRequestDebugRequestDTO();
    request.setModel(modelSelection());
    request.setEnvironmentName("env-a");
    HarnessRuntimeRequestMapper.ModelRequestDebugSelection selection =
        HarnessRuntimeRequestMapper.toModelRequestDebugSelection(request);
    assertEquals(new ModelSelection("gpt-5", "default"), selection.model());
    assertEquals("env-a", selection.environmentName());

    // 缺省与显式 null 都表示未选择 Environment。
    HarnessModelRequestDebugRequestDTO unbound = new HarnessModelRequestDebugRequestDTO();
    unbound.setModel(modelSelection());
    assertNull(HarnessRuntimeRequestMapper.toModelRequestDebugSelection(unbound).environmentName());

    // model 必填。
    assertThrows(
        DomainException.class,
        () ->
            HarnessRuntimeRequestMapper.toModelRequestDebugSelection(
                new HarnessModelRequestDebugRequestDTO()));

    // canonical model 校验复用 ModelSelection。
    HarnessModelRequestDebugRequestDTO blankVariant = new HarnessModelRequestDebugRequestDTO();
    HarnessModelSelectionDTO blankModel = modelSelection();
    blankModel.setVariant(" ");
    blankVariant.setModel(blankModel);
    assertThrows(
        DomainException.class,
        () -> HarnessRuntimeRequestMapper.toModelRequestDebugSelection(blankVariant));

    // Environment name 复用 branch canonical 规则。
    HarnessModelRequestDebugRequestDTO badEnvironment = new HarnessModelRequestDebugRequestDTO();
    badEnvironment.setModel(modelSelection());
    badEnvironment.setEnvironmentName("a/b");
    assertThrows(
        DomainException.class,
        () -> HarnessRuntimeRequestMapper.toModelRequestDebugSelection(badEnvironment));

    // 请求本身为 null 也必须 fail closed。
    assertThrows(
        DomainException.class,
        () -> HarnessRuntimeRequestMapper.toModelRequestDebugSelection(null));
  }
}
