package fun.fengwk.kkstudio.harness.runtime.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntry;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntryStore;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;

import java.util.List;
import java.util.Optional;

/** available_skills rendering uses only selected name/description and omits body/path. */
class SessionContextBuilderTest {

  @Test
  void appendsAvailableSkillsWithoutBodyOrPath() {
    SessionContextBuilder builder =
        new SessionContextBuilder(emptyStore(), new DefaultContextTransform(), List.of());

    AgentRuntimeConfig config =
        new AgentRuntimeConfig(
            1L,
            "You are helpful.",
            "11",
            "default",
            "local-dev",
            List.of(),
            List.of("dev"),
            List.of(new SelectedSkillMetadata("dev", "Developer rules", "platform")),
            List.of(),
            "{}",
            false);

    SessionContext context = builder.build(1L, 2L, config);
    assertEquals(1, context.messages().size());
    assertEquals(AgentMessageRole.SYSTEM, context.messages().get(0).role());
    String prompt = ((TextMessageContent) context.messages().get(0).contents().get(0)).text();
    assertTrue(prompt.startsWith("You are helpful."));
    assertTrue(prompt.contains("<available_skills>"));
    assertTrue(prompt.contains("<name>dev</name>"));
    assertTrue(prompt.contains("<description>Developer rules</description>"));
    assertFalse(prompt.contains("<location>"));
    assertFalse(prompt.contains("SKILL.md"));
    assertFalse(prompt.contains("/home/"));
    assertFalse(prompt.contains("sourceEnvironment"));
  }

  @Test
  void omitsAvailableSkillsSectionWhenNoneSelected() {
    assertEquals("base", SessionContextBuilder.composeSystemPrompt(config("base", List.of())));
    assertEquals("", SessionContextBuilder.composeSystemPrompt(config(null, List.of())));
    assertEquals("  ", SessionContextBuilder.composeSystemPrompt(config("  ", List.of())));
    assertFalse(
        SessionContextBuilder.composeSystemPrompt(config("base", List.of()))
            .contains("available_skills"));
  }

  @Test
  void escapesSkillXmlAndOmitsPaths() {
    String prompt =
        SessionContextBuilder.composeSystemPrompt(
            config(null, List.of(new SelectedSkillMetadata("a<&", "desc>'\"", "platform"))));
    assertTrue(prompt.contains("<name>a&lt;&amp;</name>"));
    assertTrue(prompt.contains("<description>desc&gt;&apos;&quot;</description>"));
    assertFalse(prompt.contains("<location>"));
  }

  private static AgentRuntimeConfig config(
      String systemPrompt, List<SelectedSkillMetadata> skills) {
    return new AgentRuntimeConfig(
        1L,
        systemPrompt,
        "11",
        "default",
        null,
        List.of(),
        skills.stream().map(SelectedSkillMetadata::name).toList(),
        skills,
        List.of(),
        "{}",
        false);
  }

  private static SessionEntryStore emptyStore() {
    return new SessionEntryStore() {
      @Override
      public Optional<SessionEntry> find(long sessionId, long entryId) {
        return Optional.empty();
      }

      @Override
      public List<SessionEntry> loadPath(long sessionId, long leafEntryId) {
        return List.of();
      }

      @Override
      public List<SessionEntry> listChildren(long sessionId, Long parentEntryId) {
        return List.of();
      }
    };
  }
}
