package fun.fengwk.kkstudio.harness.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.history.CustomEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.history.CustomMessagePayload;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;

import java.util.List;

class PluginIntentTest {

  @Test
  void appendCustomEntryWrapsTheFinalPayload() {
    CustomEntryPayload payload =
        new CustomEntryPayload("com.example.goal", "goal", 1, "{\"state\":\"open\"}");
    AppendCustomEntry intent = new AppendCustomEntry(payload);
    assertEquals(payload, intent.payload());
    // 校验完全委托 payload 构造：非法 dataJson / schemaVersion / 标识符由 payload 拒绝。
    assertThrows(
        IllegalArgumentException.class,
        () -> new AppendCustomEntry(new CustomEntryPayload("com.example.goal", "goal", 0, "{}")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AppendCustomEntry(
                new CustomEntryPayload("com.example.goal", "goal", 1, "{\"a\": 1}")));
    assertThrows(NullPointerException.class, () -> new AppendCustomEntry(null));
  }

  @Test
  void appendCustomMessageWrapsTheFinalPayload() {
    AgentMessage system =
        new AgentMessage(AgentMessageRole.SYSTEM, List.of(new TextMessageContent("s")));
    CustomMessagePayload payload =
        new CustomMessagePayload(
            CustomMessagePayload.CORE_PLUGIN_ID,
            CustomMessagePayload.CORE_CUSTOM_TYPE,
            CustomMessagePayload.CORE_RENDERER_KEY,
            system,
            CustomMessagePayload.CORE_DETAILS_JSON);
    assertEquals(payload, new AppendCustomMessage(payload).payload());
    // 校验完全委托 payload 构造：非 SYSTEM/USER role 由 payload 拒绝。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AppendCustomMessage(
                new CustomMessagePayload(
                    "core",
                    "message",
                    "message",
                    new AgentMessage(
                        AgentMessageRole.ASSISTANT, List.of(new TextMessageContent("a"))),
                    "{}")));
    assertThrows(NullPointerException.class, () -> new AppendCustomMessage(null));
  }

  @Test
  void continueModelIsSingletonShaped() {
    assertEquals(new ContinueModel(), new ContinueModel());
  }
}
