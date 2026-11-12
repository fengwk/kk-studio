package fun.fengwk.kkstudio.common.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

class ResourceUriTest {

  @Test
  void encodesAndParsesSkillPathSegments() {
    String uri = ResourceUri.skills("团队 skill", "v1.0", "写作", "指南/快速 开始.md");
    assertEquals(
        "resource://skills/%E5%9B%A2%E9%98%9F%20skill/v1.0/%E5%86%99%E4%BD%9C/%E6%8C%87%E5%8D%97/%E5%BF%AB%E9%80%9F%20%E5%BC%80%E5%A7%8B.md",
        uri);
    ResourceUri.Parsed parsed = ResourceUri.parse(uri);
    assertEquals(ResourceUri.Namespace.SKILLS, parsed.namespace());
    assertEquals("团队 skill", parsed.packageName());
    assertEquals("指南/快速 开始.md", parsed.path());
  }

  @Test
  void acceptsUnencodedSegments() {
    ResourceUri.Parsed parsed = ResourceUri.parse("resource://skills/pkg/v1/skill/中文 文件.md");
    assertEquals("中文 文件.md", parsed.path());
  }

  @Test
  void findsResourceReferencesInModelVisibleText() {
    UUID first = UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e");
    assertEquals(
        List.of(first),
        ResourceUri.referencedFiles("See [image](resource://files/" + first + ")."));
  }

  @Test
  void encodesFileIdsAndRejectsMalformedUris() {
    UUID id = UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e");
    assertEquals(id, ResourceUri.parse(ResourceUri.files(id)).fileId());
    for (String uri :
        new String[] {
          "resource://files/not-a-uuid",
          "resource://skills/pkg/v1/skill/%C0%AF",
          "resource://skills/pkg/v1/skill/../secret",
          "resource://skills/pkg/v1/skill/file?query"
        }) {
      assertThrows(IllegalArgumentException.class, () -> ResourceUri.parse(uri), uri);
    }
  }
}
