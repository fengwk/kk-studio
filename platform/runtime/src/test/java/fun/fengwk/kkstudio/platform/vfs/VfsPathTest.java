package fun.fengwk.kkstudio.platform.vfs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.platform.vfs.VfsPath.Kind;

import java.util.UUID;

/** {@link VfsPath} 的纯词法解析与规范化契约。 */
class VfsPathTest {

  private static final String SESSION_ID = "11111111-2222-3333-4444-555555555555";
  private static final String BLOB_ID = "0f8fad5b-d9cb-469f-a165-70867728950e";

  @Test
  void parsesRootAndNamespaceRoots() {
    assertEquals(Kind.ROOT, VfsPath.parse("vfs:/").kind());
    assertEquals(Kind.ROOT, VfsPath.parse("vfs:").kind());
    assertEquals("vfs:/", VfsPath.parse("vfs:").normalizedPath());

    VfsPath sessions = VfsPath.parse("vfs:/sessions/");
    assertEquals(Kind.SESSIONS_ROOT, sessions.kind());
    assertEquals("vfs:/sessions/", sessions.normalizedPath());

    assertEquals(Kind.RESOURCES_ROOT, VfsPath.parse("vfs:/resources").kind());
    assertEquals(Kind.SKILLS, VfsPath.parse("vfs:/skills/").kind());
  }

  @Test
  void parsesSessionDirectoryAndFile() {
    VfsPath directory = VfsPath.parse("vfs:/sessions/" + SESSION_ID);
    assertEquals(Kind.SESSION_DIR, directory.kind());
    assertEquals(UUID.fromString(SESSION_ID), directory.sessionId());
    assertEquals("", directory.sessionPath());

    VfsPath file = VfsPath.parse("vfs:/sessions/" + SESSION_ID + "/tmp/notes.txt");
    assertEquals(Kind.SESSION_FILE, file.kind());
    assertEquals("tmp/notes.txt", file.sessionPath());
    assertFalse(file.isReadOnly());
    assertTrue(file.isThreadScoped() == false);
  }

  @Test
  void parsesResourceAndSkillPaths() {
    VfsPath resource = VfsPath.parse("vfs:/resources/" + BLOB_ID);
    assertEquals(Kind.RESOURCE, resource.kind());
    assertEquals(UUID.fromString(BLOB_ID), resource.blobId());
    assertTrue(resource.isReadOnly());
    assertTrue(resource.isUnderResourcesRoot());

    VfsPath skill = VfsPath.parse("vfs:/skills/dev-tools/review/SKILL.md");
    assertEquals(Kind.SKILLS_PATH, skill.kind());
    assertEquals("dev-tools", skill.skillPackage());
    assertEquals("review", skill.skillName());
    assertEquals("SKILL.md", skill.skillRelativePath());
    assertTrue(skill.isReadOnly());
    assertTrue(skill.isUnderSkillsRoot());
  }

  @Test
  void extractsThreadScopeForThreadDirectories() {
    String threadId = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
    VfsPath threadFile =
        VfsPath.parse("vfs:/sessions/" + SESSION_ID + "/threads/" + threadId + "/MEMORY.md");
    assertEquals(UUID.fromString(threadId), threadFile.threadId());
    assertTrue(threadFile.isThreadScoped());
  }

  /** 空段、相对段、反斜杠、非规范 UUID 与未知命名空间都在解析期被拒绝，不产生可执行路径。 */
  @Test
  void rejectsMalformedPaths() {
    assertThrows(IllegalArgumentException.class, () -> VfsPath.parse(null));
    assertThrows(IllegalArgumentException.class, () -> VfsPath.parse(" "));
    assertThrows(IllegalArgumentException.class, () -> VfsPath.parse("/sessions/" + SESSION_ID));
    assertThrows(IllegalArgumentException.class, () -> VfsPath.parse("vfs://sessions"));
    assertThrows(IllegalArgumentException.class, () -> VfsPath.parse("vfs:/sessions//x"));
    assertThrows(
        IllegalArgumentException.class,
        () -> VfsPath.parse("vfs:/sessions/" + SESSION_ID + "/../escape"));
    assertThrows(
        IllegalArgumentException.class,
        () -> VfsPath.parse("vfs:/sessions/" + SESSION_ID + "/./notes.txt"));
    assertThrows(
        IllegalArgumentException.class,
        () -> VfsPath.parse("vfs:/sessions/" + SESSION_ID + "\\notes.txt"));
    String hexSessionId = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
    assertThrows(
        IllegalArgumentException.class,
        () -> VfsPath.parse("vfs:/sessions/" + hexSessionId.toUpperCase()));
    assertThrows(IllegalArgumentException.class, () -> VfsPath.parse("vfs:/unknown/x"));
    assertThrows(IllegalArgumentException.class, () -> VfsPath.parse("vfs:/resources/not-a-uuid"));
  }
}
