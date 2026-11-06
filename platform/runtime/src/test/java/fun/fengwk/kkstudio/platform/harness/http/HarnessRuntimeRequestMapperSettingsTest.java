package fun.fengwk.kkstudio.platform.harness.http;

import static fun.fengwk.kkstudio.platform.harness.http.HarnessRuntimeRequestMapperTestSupport.modelSelection;
import static fun.fengwk.kkstudio.platform.harness.http.HarnessRuntimeRequestMapperTestSupport.newSessionTarget;
import static fun.fengwk.kkstudio.platform.harness.http.HarnessRuntimeRequestMapperTestSupport.request;
import static fun.fengwk.kkstudio.platform.harness.http.HarnessRuntimeRequestMapperTestSupport.userCommand;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.api.AcceptCommandsCommand;
import fun.fengwk.kkstudio.harness.api.AcceptCommandsTarget;
import fun.fengwk.kkstudio.harness.api.entry.ModelSelection;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessCommandTargetDTO;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessModelRequestDebugRequestDTO;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessModelSelectionDTO;

class HarnessRuntimeRequestMapperSettingsTest {

  /** rootSettings 的 environmentName 是 nullable 字段：null 与 canonical 文本都精确保留，非法值 fail closed。 */
  @Test
  void mapsNullableEnvironmentNameFromRootSettings() {
    HarnessCommandTargetDTO.NewSessionDTO target =
        (HarnessCommandTargetDTO.NewSessionDTO) newSessionTarget();
    target.rootSettings().setEnvironmentName("local");
    AcceptCommandsCommand withEnvironment =
        HarnessRuntimeRequestMapper.toAcceptCommandsCommand(request(target, userCommand("env")));
    AcceptCommandsTarget.NewRootSession newSession =
        assertInstanceOf(AcceptCommandsTarget.NewRootSession.class, withEnvironment.target());
    assertEquals("local", newSession.rootSettings().environmentName());

    AcceptCommandsCommand withoutEnvironment =
        HarnessRuntimeRequestMapper.toAcceptCommandsCommand(
            request(newSessionTarget(), userCommand("no-env")));
    AcceptCommandsTarget.NewRootSession cleared =
        assertInstanceOf(AcceptCommandsTarget.NewRootSession.class, withoutEnvironment.target());
    assertNull(cleared.rootSettings().environmentName());

    HarnessCommandTargetDTO missingSettings =
        new HarnessCommandTargetDTO.NewSessionDTO(
            target.sessionId(), target.threadId(), null, target.yoloEnabled());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            HarnessRuntimeRequestMapper.toAcceptCommandsCommand(
                request(missingSettings, userCommand("missing"))));

    HarnessCommandTargetDTO.NewSessionDTO invalid =
        (HarnessCommandTargetDTO.NewSessionDTO) newSessionTarget();
    invalid.rootSettings().setEnvironmentName("a/b");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            HarnessRuntimeRequestMapper.toAcceptCommandsCommand(
                request(invalid, userCommand("bad"))));
  }

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
        IllegalArgumentException.class,
        () ->
            HarnessRuntimeRequestMapper.toModelRequestDebugSelection(
                new HarnessModelRequestDebugRequestDTO()));

    // canonical model 校验复用 ModelSelection。
    HarnessModelRequestDebugRequestDTO blankVariant = new HarnessModelRequestDebugRequestDTO();
    HarnessModelSelectionDTO blankModel = modelSelection();
    blankModel.setVariant(" ");
    blankVariant.setModel(blankModel);
    assertThrows(
        IllegalArgumentException.class,
        () -> HarnessRuntimeRequestMapper.toModelRequestDebugSelection(blankVariant));

    // Environment name 复用 branch canonical 规则。
    HarnessModelRequestDebugRequestDTO badEnvironment = new HarnessModelRequestDebugRequestDTO();
    badEnvironment.setModel(modelSelection());
    badEnvironment.setEnvironmentName("a/b");
    assertThrows(
        IllegalArgumentException.class,
        () -> HarnessRuntimeRequestMapper.toModelRequestDebugSelection(badEnvironment));

    // 请求本身为 null 也必须 fail closed。
    assertThrows(
        IllegalArgumentException.class,
        () -> HarnessRuntimeRequestMapper.toModelRequestDebugSelection(null));
  }
}
