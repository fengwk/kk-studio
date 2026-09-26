package fun.fengwk.kkstudio.project.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.List;

/** {@link IssueAgentThread} 稳定身份契约：身份是 (issueId, agentName)，Agent 名是规范名。 */
class IssueAgentThreadTest {

  /** 绑定只保存 Issue、Agent 与 Thread 三个身份，不重复保存 Session。 */
  @Test
  void bindsIssueAndAgentToThread() {
    IssueAgentThread binding =
        new IssueAgentThread(
            ProjectDomainFixtures.id(1L), "designer", ProjectDomainFixtures.id(2L));

    assertEquals(ProjectDomainFixtures.id(1L), binding.issueId());
    assertEquals("designer", binding.agentName());
    assertEquals(ProjectDomainFixtures.id(2L), binding.threadId());
  }

  /** Agent 自然名沿用名称列约束：非空白、无首尾空白、不含 '/'、不超过 64 字符。 */
  @Test
  void rejectsInvalidIdentity() {
    List<String> invalid = List.of("", " ", " designer", "designer ", "a/b", "a".repeat(65));
    for (String agentName : invalid) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new IssueAgentThread(
                  ProjectDomainFixtures.id(1L), agentName, ProjectDomainFixtures.id(2L)));
    }
    assertThrows(
        NullPointerException.class,
        () -> new IssueAgentThread(null, "designer", ProjectDomainFixtures.id(2L)));
    assertThrows(
        NullPointerException.class,
        () ->
            new IssueAgentThread(ProjectDomainFixtures.id(1L), null, ProjectDomainFixtures.id(2L)));
    assertThrows(
        NullPointerException.class,
        () -> new IssueAgentThread(ProjectDomainFixtures.id(1L), "designer", null));
  }

  /** 同一 (issue, agent) 指向不同 Thread 是不同的绑定事实：重绑必须由上层拒绝，而不是静默替换。 */
  @Test
  void identityIsIssueAndAgentPair() {
    IssueAgentThread first =
        new IssueAgentThread(
            ProjectDomainFixtures.id(1L), "designer", ProjectDomainFixtures.id(2L));
    IssueAgentThread rebound =
        new IssueAgentThread(
            ProjectDomainFixtures.id(1L), "designer", ProjectDomainFixtures.id(3L));

    assertNotEquals(first, rebound);
    assertTrue(
        first.equals(
            new IssueAgentThread(
                ProjectDomainFixtures.id(1L), "designer", ProjectDomainFixtures.id(2L))));
  }
}
