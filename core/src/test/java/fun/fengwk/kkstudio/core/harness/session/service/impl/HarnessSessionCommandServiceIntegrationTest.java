package fun.fengwk.kkstudio.core.harness.session.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fun.fengwk.kkstudio.core.CoreTestApplication;
import fun.fengwk.kkstudio.core.harness.session.service.HarnessSessionCommandService;
import fun.fengwk.kkstudio.share.model.HarnessSessionCreateDTO;
import fun.fengwk.kkstudio.share.model.HarnessSessionDTO;
import fun.fengwk.kkstudio.share.model.HarnessSessionEntryDTO;
import fun.fengwk.kkstudio.share.model.HarnessSessionMessageCreateDTO;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** T15 first-slice core service integration tests. */
@SpringBootTest(classes = CoreTestApplication.class)
class HarnessSessionCommandServiceIntegrationTest {

    @Autowired private HarnessSessionCommandService commandService;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void createRootSessionPersistsSessionAndSnapshotInOneTransaction() {
        HarnessSessionCreateDTO create = new HarnessSessionCreateDTO();
        create.setAgentDefinitionId("1");
        create.setTitle("root-session");

        HarnessSessionDTO dto = commandService.createRootSession(create);

        assertNotNull(dto.getSessionId());
        assertNotNull(dto.getLeafEntryId());
        assertEquals("1", dto.getAgentDefinitionId());
        assertEquals("root-session", dto.getTitle());
        assertNull(dto.getActiveRunId());
        assertEquals(dto.getSessionId(), dto.getRootSessionId());
        assertNull(dto.getParentSessionId());
        assertEquals(Integer.valueOf(0), dto.getDepth());

        Long entryCount = jdbc.queryForObject(
            "select count(*) from harness_session_entry where session_id = ? and entry_type = 'agent_snapshot'",
            Long.class, Long.parseLong(dto.getSessionId()));
        assertEquals(Long.valueOf(1L), entryCount);
    }

    @Test
    void createRootSessionRejectsUnknownAgent() {
        HarnessSessionCreateDTO create = new HarnessSessionCreateDTO();
        create.setAgentDefinitionId("999999999");
        create.setTitle("unknown");
        assertThrows(IllegalArgumentException.class, () -> commandService.createRootSession(create));
    }

    @Test
    void submitUserMessageAppendsEntryAndQueuesRun() {
        HarnessSessionDTO root = commandService.createRootSession(create("submit-ok"));

        HarnessSessionMessageCreateDTO msg = new HarnessSessionMessageCreateDTO();
        msg.setContent("hello");
        msg.setExpectedLeafEntryId(root.getLeafEntryId());

        HarnessSessionEntryDTO entry = commandService.submitUserMessage(root.getSessionId(), msg);
        assertNotNull(entry.getRunId());
        assertEquals("message", entry.getEntryType());
        assertTrue(entry.getPayloadJson().contains("hello"));
    }

    @Test
    void submitUserMessageRejectsBlankContent() {
        HarnessSessionDTO root = commandService.createRootSession(create("submit-blank"));
        HarnessSessionMessageCreateDTO msg = new HarnessSessionMessageCreateDTO();
        msg.setContent("   ");
        msg.setExpectedLeafEntryId(root.getLeafEntryId());
        assertThrows(IllegalArgumentException.class,
            () -> commandService.submitUserMessage(root.getSessionId(), msg));
    }

    @Test
    void submitUserMessageRejectsLeafConflict() {
        HarnessSessionDTO root = commandService.createRootSession(create("leaf-conflict"));
        HarnessSessionMessageCreateDTO first = new HarnessSessionMessageCreateDTO();
        first.setContent("first");
        first.setExpectedLeafEntryId(root.getLeafEntryId());
        commandService.submitUserMessage(root.getSessionId(), first);

        HarnessSessionMessageCreateDTO stale = new HarnessSessionMessageCreateDTO();
        stale.setContent("stale");
        stale.setExpectedLeafEntryId(root.getLeafEntryId());
        assertThrows(RuntimeException.class,
            () -> commandService.submitUserMessage(root.getSessionId(), stale));
    }

    @Test
    void submitUserMessageRejectsActiveRunConflict() {
        HarnessSessionDTO root = commandService.createRootSession(create("active-run"));
        HarnessSessionMessageCreateDTO first = new HarnessSessionMessageCreateDTO();
        first.setContent("first");
        first.setExpectedLeafEntryId(root.getLeafEntryId());
        commandService.submitUserMessage(root.getSessionId(), first);

        HarnessSessionDTO refreshed = commandService.listEntries(root.getSessionId()).stream()
            .findFirst().map(e -> root).orElse(root);
        // second submit fails because active_run_id is now set
        HarnessSessionMessageCreateDTO second = new HarnessSessionMessageCreateDTO();
        second.setContent("second");
        second.setExpectedLeafEntryId(refreshed.getLeafEntryId());
        // The new leafEntryId after first submit is the trigger entry id
        // List entries and use the last as expected leaf.
        List<HarnessSessionEntryDTO> entries = commandService.listEntries(root.getSessionId());
        second.setExpectedLeafEntryId(entries.get(entries.size() - 1).getSessionEntryId());
        assertThrows(RuntimeException.class,
            () -> commandService.submitUserMessage(root.getSessionId(), second));
    }

    @Test
    void strictIdParserRejectsBadInputs() {
        HarnessSessionCreateDTO bad = new HarnessSessionCreateDTO();
        bad.setAgentDefinitionId("abc");
        bad.setTitle("t");
        assertThrows(IllegalArgumentException.class, () -> commandService.createRootSession(bad));

        HarnessSessionCreateDTO zero = new HarnessSessionCreateDTO();
        zero.setAgentDefinitionId("0");
        zero.setTitle("t");
        assertThrows(IllegalArgumentException.class, () -> commandService.createRootSession(zero));

        HarnessSessionCreateDTO blank = new HarnessSessionCreateDTO();
        blank.setAgentDefinitionId("");
        blank.setTitle("t");
        assertThrows(IllegalArgumentException.class, () -> commandService.createRootSession(blank));
    }

    private HarnessSessionCreateDTO create(String title) {
        HarnessSessionCreateDTO dto = new HarnessSessionCreateDTO();
        dto.setAgentDefinitionId("1");
        dto.setTitle(title);
        return dto;
    }
}
