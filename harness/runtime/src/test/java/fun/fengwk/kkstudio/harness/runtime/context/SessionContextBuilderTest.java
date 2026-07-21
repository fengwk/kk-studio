package fun.fengwk.kkstudio.harness.runtime.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.model.ModelCost;
import fun.fengwk.kkstudio.harness.model.ModelUsage;
import fun.fengwk.kkstudio.harness.model.provider.ProviderStopReason;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AssistantErrorEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.AssistantMessageMetadata;
import fun.fengwk.kkstudio.harness.runtime.session.MessageEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntry;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntryStore;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntryType;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;

import java.math.BigDecimal;
import java.time.Instant;
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

  /** AssistantErrorEntryPayload 永远不进入 Provider Context；下一次重试不会回放 error 文本。 */
  @Test
  void skipsAssistantErrorPayloadWhenProjectingContext() {
    AgentMessage user =
        new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent("ping")));
    AgentMessage assistant =
        new AgentMessage(AgentMessageRole.ASSISTANT, List.of(new TextMessageContent("pong")));
    AssistantMessageMetadata assistantMetadata =
        new AssistantMessageMetadata(
            ProviderStopReason.COMPLETED,
            new ModelUsage(0, 0, 0, 0, 0, 0, 0),
            new ModelCost(
                "USD",
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO));
    SessionEntry userEntry =
        new SessionEntry(
            1L,
            1L,
            null,
            SessionEntryType.MESSAGE,
            new MessageEntryPayload(user),
            Instant.parse("2026-07-20T00:00:00Z"));
    SessionEntry errorEntry =
        new SessionEntry(
            2L,
            1L,
            1L,
            SessionEntryType.ASSISTANT_ERROR,
            new AssistantErrorEntryPayload("TRANSIENT", "model call timed out", 1, 2, true),
            Instant.parse("2026-07-20T00:00:01Z"));
    SessionEntry assistantEntry =
        new SessionEntry(
            3L,
            1L,
            2L,
            SessionEntryType.MESSAGE,
            new MessageEntryPayload(assistant, assistantMetadata),
            Instant.parse("2026-07-20T00:00:02Z"));
    SessionEntryStore store =
        new SessionEntryStore() {
          @Override
          public Optional<SessionEntry> find(long sessionId, long entryId) {
            return Optional.empty();
          }

          @Override
          public List<SessionEntry> loadPath(long sessionId, long leafEntryId) {
            return List.of(userEntry, errorEntry, assistantEntry);
          }

          @Override
          public List<SessionEntry> listChildren(long sessionId, Long parentEntryId) {
            return List.of();
          }
        };
    SessionContextBuilder builder =
        new SessionContextBuilder(store, new DefaultContextTransform(), List.of());

    SessionContext context = builder.build(1L, 3L, config("base", List.of()));
    List<AgentMessage> projected = context.messages();
    // system + USER + ASSISTANT; no error text injected.
    assertEquals(3, projected.size());
    assertEquals(AgentMessageRole.SYSTEM, projected.get(0).role());
    assertEquals(AgentMessageRole.USER, projected.get(1).role());
    assertEquals(AgentMessageRole.ASSISTANT, projected.get(2).role());
    String userText = ((TextMessageContent) projected.get(1).contents().get(0)).text();
    String assistantText = ((TextMessageContent) projected.get(2).contents().get(0)).text();
    assertEquals("ping", userText);
    assertEquals("pong", assistantText);
    assertFalse(
        projected.stream()
            .flatMap(message -> message.contents().stream())
            .anyMatch(
                content ->
                    content instanceof TextMessageContent
                        && ((TextMessageContent) content).text().contains("model call timed out")),
        "error message must never appear in projected context");
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
