package fun.fengwk.kkstudio.harness.environment.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** DaemonSkillSourceSnapshotCodec 严格 wire node 解码、编码与快照/descriptor/diagnostic 模型约束测试。 */
class DaemonSkillSourceSnapshotCodecTest {

  private static final UUID SOURCE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final long SOURCE_VERSION = 3L;
  private static final String REVISION = "a".repeat(64);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final DaemonSkillSourceSnapshotCodec codec = new DaemonSkillSourceSnapshotCodec();

  /** 测试意图：完整来源快照在 node codec 下精确往返，校验 wire node 结构与嵌套字段。 */
  @Test
  void roundTripsSnapshotWithSkillsAndDiagnostics() {
    DaemonSkillDescriptor skill =
        new DaemonSkillDescriptor(
            SOURCE_ID,
            SOURCE_VERSION,
            "dev",
            "Developer rules",
            "/home/dev/.agents/skills/dev",
            REVISION);
    DaemonSkillDiagnostic diagnostic =
        new DaemonSkillDiagnostic("/home/dev/.agents/skills/broken", "missing front matter name");
    DaemonSkillSourceSnapshot original =
        new DaemonSkillSourceSnapshot(
            SOURCE_ID, SOURCE_VERSION, REVISION, List.of(skill), List.of(diagnostic));

    ObjectNode node = codec.encodeNode(original);

    assertEquals(SOURCE_ID.toString(), node.get("sourceId").asText());
    assertEquals(SOURCE_VERSION, node.get("sourceVersion").asLong());
    assertEquals(REVISION, node.get("sourceRevision").asText());
    assertTrue(node.get("skills").isArray());
    assertEquals(1, node.get("skills").size());
    ObjectNode skillNode = (ObjectNode) node.get("skills").get(0);
    assertEquals("dev", skillNode.get("name").asText());
    assertEquals("Developer rules", skillNode.get("description").asText());
    assertEquals("/home/dev/.agents/skills/dev", skillNode.get("baseDirectory").asText());
    assertEquals(REVISION, skillNode.get("contentRevision").asText());

    assertTrue(node.get("diagnostics").isArray());
    assertEquals(1, node.get("diagnostics").size());
    ObjectNode diagNode = (ObjectNode) node.get("diagnostics").get(0);
    assertEquals("/home/dev/.agents/skills/broken", diagNode.get("location").asText());
    assertEquals("missing front matter name", diagNode.get("message").asText());

    DaemonSkillSourceSnapshot decoded = codec.decodeNode(node, "testSnapshot");
    assertEquals(original, decoded);
  }

  /** 测试意图：非 ObjectNode 顶层节点在解码时被严格拒绝。 */
  @Test
  void rejectsNonObjectSnapshotNode() {
    DaemonProtocolException textError =
        assertThrows(
            DaemonProtocolException.class,
            () -> codec.decodeNode(new TextNode("not-an-object"), "testSnapshot"));
    assertTrue(textError.getMessage().contains("must be an object"));

    DaemonProtocolException arrayError =
        assertThrows(
            DaemonProtocolException.class,
            () -> codec.decodeNode(MAPPER.createArrayNode(), "testSnapshot"));
    assertTrue(arrayError.getMessage().contains("must be an object"));
  }

  /** 测试意图：来源快照、skill 项与 diagnostic 项中出现未知字段时被严格拒绝。 */
  @Test
  void rejectsUnknownFieldsInSnapshotSkillAndDiagnostic() {
    ObjectNode snapshotNode = baseSnapshotNode();
    snapshotNode.put("extraField", "unexpected");
    DaemonProtocolException snapError =
        assertThrows(
            DaemonProtocolException.class, () -> codec.decodeNode(snapshotNode, "testSnapshot"));
    assertTrue(snapError.getMessage().contains("extraField"));

    ObjectNode skillUnknown = baseSnapshotNode();
    ((ObjectNode) skillUnknown.get("skills").get(0)).put("extraSkillField", 123);
    DaemonProtocolException skillError =
        assertThrows(
            DaemonProtocolException.class, () -> codec.decodeNode(skillUnknown, "testSnapshot"));
    assertTrue(skillError.getMessage().contains("extraSkillField"));

    ObjectNode diagUnknown = baseSnapshotNode();
    ((ObjectNode) diagUnknown.get("diagnostics").get(0)).put("extraDiagField", true);
    DaemonProtocolException diagError =
        assertThrows(
            DaemonProtocolException.class, () -> codec.decodeNode(diagUnknown, "testSnapshot"));
    assertTrue(diagError.getMessage().contains("extraDiagField"));
  }

  /** 测试意图：非规范大写 UUID 或非法 UUID 文本在解码时被严格拒绝。 */
  @Test
  void rejectsInvalidOrNonCanonicalSourceId() {
    UUID letterUuid = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    ObjectNode uppercaseNode = baseSnapshotNode();
    uppercaseNode.put("sourceId", letterUuid.toString().toUpperCase());
    DaemonProtocolException upperError =
        assertThrows(
            DaemonProtocolException.class, () -> codec.decodeNode(uppercaseNode, "testSnapshot"));
    assertTrue(upperError.getMessage().contains("canonical UUID string"));

    ObjectNode malformedNode = baseSnapshotNode();
    malformedNode.put("sourceId", "not-a-uuid");
    DaemonProtocolException malformedError =
        assertThrows(
            DaemonProtocolException.class, () -> codec.decodeNode(malformedNode, "testSnapshot"));
    assertTrue(malformedError.getMessage().contains("canonical UUID string"));

    ObjectNode nonTextNode = baseSnapshotNode();
    nonTextNode.put("sourceId", 12345);
    DaemonProtocolException nonTextError =
        assertThrows(
            DaemonProtocolException.class, () -> codec.decodeNode(nonTextNode, "testSnapshot"));
    assertTrue(nonTextError.getMessage().contains("sourceId must be non-blank text"));

    ObjectNode blankNode = baseSnapshotNode();
    blankNode.put("sourceId", "   ");
    DaemonProtocolException blankError =
        assertThrows(
            DaemonProtocolException.class, () -> codec.decodeNode(blankNode, "testSnapshot"));
    assertTrue(blankError.getMessage().contains("sourceId must be non-blank text"));
  }

  /** 测试意图：非法 sourceVersion（负数、缺失、浮点数、字符串）在解码时被严格拒绝。 */
  @Test
  void rejectsInvalidSourceVersion() {
    ObjectNode negativeNode = baseSnapshotNode();
    negativeNode.put("sourceVersion", -1);
    DaemonProtocolException negError =
        assertThrows(
            DaemonProtocolException.class, () -> codec.decodeNode(negativeNode, "testSnapshot"));
    assertTrue(negError.getMessage().contains("sourceVersion"));

    ObjectNode missingNode = baseSnapshotNode();
    missingNode.remove("sourceVersion");
    DaemonProtocolException missError =
        assertThrows(
            DaemonProtocolException.class, () -> codec.decodeNode(missingNode, "testSnapshot"));
    assertTrue(missError.getMessage().contains("sourceVersion"));

    ObjectNode stringNode = baseSnapshotNode();
    stringNode.put("sourceVersion", "3");
    DaemonProtocolException strError =
        assertThrows(
            DaemonProtocolException.class, () -> codec.decodeNode(stringNode, "testSnapshot"));
    assertTrue(strError.getMessage().contains("sourceVersion"));

    ObjectNode floatNode = baseSnapshotNode();
    floatNode.put("sourceVersion", 3.14);
    DaemonProtocolException floatError =
        assertThrows(
            DaemonProtocolException.class, () -> codec.decodeNode(floatNode, "testSnapshot"));
    assertTrue(floatError.getMessage().contains("sourceVersion"));
  }

  /** 测试意图：空白、缺失或非合法 commit SHA 的 sourceRevision 在解码时被严格拒绝。 */
  @Test
  void rejectsBlankOrNonTextSourceRevision() {
    ObjectNode blankNode = baseSnapshotNode();
    blankNode.put("sourceRevision", "   ");
    DaemonProtocolException blankError =
        assertThrows(
            DaemonProtocolException.class, () -> codec.decodeNode(blankNode, "testSnapshot"));
    assertTrue(blankError.getMessage().contains("sourceRevision must be non-blank text"));

    ObjectNode missingNode = baseSnapshotNode();
    missingNode.remove("sourceRevision");
    DaemonProtocolException missError =
        assertThrows(
            DaemonProtocolException.class, () -> codec.decodeNode(missingNode, "testSnapshot"));
    assertTrue(missError.getMessage().contains("sourceRevision must be non-blank text"));

    ObjectNode nonCommitNode = baseSnapshotNode();
    nonCommitNode.put("sourceRevision", "not-a-commit-hex");
    DaemonProtocolException nonCommitError =
        assertThrows(
            DaemonProtocolException.class, () -> codec.decodeNode(nonCommitNode, "testSnapshot"));
    assertTrue(nonCommitError.getMessage().contains("sourceRevision must be a lowercase"));
  }

  /** 测试意图：skills 字段非数组或元素非对象时被严格拒绝。 */
  @Test
  void rejectsInvalidSkillsArray() {
    ObjectNode missingSkills = baseSnapshotNode();
    missingSkills.remove("skills");
    DaemonProtocolException missError =
        assertThrows(
            DaemonProtocolException.class, () -> codec.decodeNode(missingSkills, "testSnapshot"));
    assertTrue(missError.getMessage().contains("skills must be an array"));

    ObjectNode objectSkills = baseSnapshotNode();
    objectSkills.put("skills", "not-array");
    DaemonProtocolException objError =
        assertThrows(
            DaemonProtocolException.class, () -> codec.decodeNode(objectSkills, "testSnapshot"));
    assertTrue(objError.getMessage().contains("skills must be an array"));

    ObjectNode elementNotObject = baseSnapshotNode();
    ArrayNode array = MAPPER.createArrayNode();
    array.add("string-element");
    elementNotObject.set("skills", array);
    DaemonProtocolException elemError =
        assertThrows(
            DaemonProtocolException.class,
            () -> codec.decodeNode(elementNotObject, "testSnapshot"));
    assertTrue(elemError.getMessage().contains("must be an object"));
  }

  /** 测试意图：skill 的 sourceId 或 sourceVersion 与所属来源不一致时被拒绝。 */
  @Test
  void rejectsSkillMismatchedSourceOwnership() {
    UUID otherSourceId = UUID.fromString("22222222-2222-2222-2222-222222222222");

    ObjectNode mismatchedId = baseSnapshotNode();
    ((ObjectNode) mismatchedId.get("skills").get(0)).put("sourceId", otherSourceId.toString());
    DaemonProtocolException idError =
        assertThrows(
            DaemonProtocolException.class, () -> codec.decodeNode(mismatchedId, "testSnapshot"));
    assertTrue(idError.getMessage().contains("must belong to the enclosing skill source"));

    ObjectNode mismatchedVersion = baseSnapshotNode();
    ((ObjectNode) mismatchedVersion.get("skills").get(0)).put("sourceVersion", SOURCE_VERSION + 1);
    DaemonProtocolException verError =
        assertThrows(
            DaemonProtocolException.class,
            () -> codec.decodeNode(mismatchedVersion, "testSnapshot"));
    assertTrue(verError.getMessage().contains("must belong to the enclosing skill source"));
  }

  /** 测试意图：同一来源内存在重名 skill 时被严格拒绝。 */
  @Test
  void rejectsDuplicateSkillNameWithinSource() {
    ObjectNode duplicateSkill = baseSnapshotNode();
    ArrayNode skills = (ArrayNode) duplicateSkill.get("skills");
    skills.add(skills.get(0).deepCopy());
    DaemonProtocolException error =
        assertThrows(
            DaemonProtocolException.class, () -> codec.decodeNode(duplicateSkill, "testSnapshot"));
    assertTrue(error.getMessage().contains("duplicate skill name within source: dev"));
  }

  /** 测试意图：skill descriptor 字段校验失败（如相对路径、非法 revision）时被转译为 DaemonProtocolException。 */
  @Test
  void rejectsInvalidSkillDescriptorFieldsDuringDecode() {
    ObjectNode relativeBaseDir = baseSnapshotNode();
    ((ObjectNode) relativeBaseDir.get("skills").get(0))
        .put("baseDirectory", "relative/not/absolute");
    DaemonProtocolException dirError =
        assertThrows(
            DaemonProtocolException.class, () -> codec.decodeNode(relativeBaseDir, "testSnapshot"));
    assertTrue(dirError.getMessage().contains("baseDirectory must be an absolute path"));

    ObjectNode invalidRevision = baseSnapshotNode();
    ((ObjectNode) invalidRevision.get("skills").get(0))
        .put("contentRevision", "invalid-revision-chars");
    DaemonProtocolException revError =
        assertThrows(
            DaemonProtocolException.class, () -> codec.decodeNode(invalidRevision, "testSnapshot"));
    assertTrue(revError.getMessage().contains("contentRevision"));
  }

  /** 测试意图：diagnostics 字段非数组、元素非对象或超出配额时被严格拒绝。 */
  @Test
  void rejectsInvalidDiagnosticsArray() {
    ObjectNode missingDiags = baseSnapshotNode();
    missingDiags.remove("diagnostics");
    DaemonProtocolException missError =
        assertThrows(
            DaemonProtocolException.class, () -> codec.decodeNode(missingDiags, "testSnapshot"));
    assertTrue(missError.getMessage().contains("diagnostics must be an array"));

    ObjectNode nonArrayDiags = baseSnapshotNode();
    nonArrayDiags.put("diagnostics", 123);
    DaemonProtocolException nonArrError =
        assertThrows(
            DaemonProtocolException.class, () -> codec.decodeNode(nonArrayDiags, "testSnapshot"));
    assertTrue(nonArrError.getMessage().contains("diagnostics must be an array"));

    ObjectNode elementNotObject = baseSnapshotNode();
    ArrayNode array = MAPPER.createArrayNode();
    array.add("string-diagnostic");
    elementNotObject.set("diagnostics", array);
    DaemonProtocolException elemError =
        assertThrows(
            DaemonProtocolException.class,
            () -> codec.decodeNode(elementNotObject, "testSnapshot"));
    assertTrue(elemError.getMessage().contains("must be an object"));

    ObjectNode exceededDiags = baseSnapshotNode();
    ArrayNode largeDiags = MAPPER.createArrayNode();
    for (int i = 0; i < DaemonSkillSourceSnapshot.MAX_DIAGNOSTICS + 1; i++) {
      ObjectNode diag = MAPPER.createObjectNode();
      diag.put("location", "/loc/" + i);
      diag.put("message", "msg");
      largeDiags.add(diag);
    }
    exceededDiags.set("diagnostics", largeDiags);
    DaemonProtocolException exceedError =
        assertThrows(
            DaemonProtocolException.class, () -> codec.decodeNode(exceededDiags, "testSnapshot"));
    assertTrue(
        exceedError
            .getMessage()
            .contains("diagnostics must not exceed " + DaemonSkillSourceSnapshot.MAX_DIAGNOSTICS));
  }

  /** 测试意图：diagnostic 字段值非法（如空白 location 或 message）时被拒绝。 */
  @Test
  void rejectsInvalidDiagnosticFieldsDuringDecode() {
    ObjectNode blankLocation = baseSnapshotNode();
    ((ObjectNode) blankLocation.get("diagnostics").get(0)).put("location", "   ");
    DaemonProtocolException locError =
        assertThrows(
            DaemonProtocolException.class, () -> codec.decodeNode(blankLocation, "testSnapshot"));
    assertTrue(locError.getMessage().contains("location must be non-blank text"));

    ObjectNode blankMessage = baseSnapshotNode();
    ((ObjectNode) blankMessage.get("diagnostics").get(0)).put("message", "   ");
    DaemonProtocolException msgError =
        assertThrows(
            DaemonProtocolException.class, () -> codec.decodeNode(blankMessage, "testSnapshot"));
    assertTrue(msgError.getMessage().contains("message must be non-blank text"));
  }

  /** 测试意图：DaemonSkillSourceSnapshot 模型直接构造时的防御校验（负版本、配额、归属一致、重名与合法 revision）。 */
  @Test
  void validatesSnapshotModelDirectly() {
    DaemonSkillDescriptor skill =
        new DaemonSkillDescriptor(
            SOURCE_ID,
            SOURCE_VERSION,
            "dev",
            "Developer rules",
            "/home/dev/.agents/skills/dev",
            REVISION);
    DaemonSkillDiagnostic diagnostic = new DaemonSkillDiagnostic("/loc", "msg");

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DaemonSkillSourceSnapshot(
                SOURCE_ID, -1, REVISION, List.of(skill), List.of(diagnostic)));

    List<DaemonSkillDiagnostic> oversizedDiags = new ArrayList<>();
    for (int i = 0; i < DaemonSkillSourceSnapshot.MAX_DIAGNOSTICS + 1; i++) {
      oversizedDiags.add(new DaemonSkillDiagnostic("/loc/" + i, "msg"));
    }
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DaemonSkillSourceSnapshot(
                SOURCE_ID, SOURCE_VERSION, REVISION, List.of(skill), oversizedDiags));

    DaemonSkillDescriptor mismatchedVersionSkill =
        new DaemonSkillDescriptor(
            SOURCE_ID,
            SOURCE_VERSION + 1,
            "dev",
            "Developer rules",
            "/home/dev/.agents/skills/dev",
            REVISION);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DaemonSkillSourceSnapshot(
                SOURCE_ID,
                SOURCE_VERSION,
                REVISION,
                List.of(mismatchedVersionSkill),
                List.of(diagnostic)));

    UUID otherId = UUID.fromString("22222222-2222-2222-2222-222222222222");
    DaemonSkillDescriptor mismatchedIdSkill =
        new DaemonSkillDescriptor(
            otherId,
            SOURCE_VERSION,
            "dev",
            "Developer rules",
            "/home/dev/.agents/skills/dev",
            REVISION);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DaemonSkillSourceSnapshot(
                SOURCE_ID,
                SOURCE_VERSION,
                REVISION,
                List.of(mismatchedIdSkill),
                List.of(diagnostic)));

    DaemonSkillDescriptor duplicateSkill =
        new DaemonSkillDescriptor(
            SOURCE_ID,
            SOURCE_VERSION,
            "dev",
            "Other rules",
            "/home/dev/.agents/skills/dev2",
            REVISION);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DaemonSkillSourceSnapshot(
                SOURCE_ID,
                SOURCE_VERSION,
                REVISION,
                List.of(skill, duplicateSkill),
                List.of(diagnostic)));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DaemonSkillSourceSnapshot(
                SOURCE_ID, SOURCE_VERSION, "bad-rev", List.of(skill), List.of(diagnostic)));
  }

  /** 测试意图：DaemonSkillDescriptor 名称与描述的规范约束（非空、无首尾空白、长度限制、换行与控制字符规则）。 */
  @Test
  void validatesSkillDescriptorCanonicalNamesAndDescriptions() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DaemonSkillDescriptor(SOURCE_ID, -1, "name", "desc", "/home/dev/skills", REVISION));

    for (String invalidName :
        new String[] {"", "   ", " name", "name ", "name\nline", "name\u0000"}) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new DaemonSkillDescriptor(
                  SOURCE_ID, SOURCE_VERSION, invalidName, "desc", "/home/dev/skills", REVISION));
    }
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DaemonSkillDescriptor(
                SOURCE_ID,
                SOURCE_VERSION,
                "a".repeat(DaemonSkillDescriptor.MAX_NAME_CHARS + 1),
                "desc",
                "/home/dev/skills",
                REVISION));

    for (String invalidDesc :
        new String[] {"", "   ", " desc", "desc ", "desc\rline", "desc\u0000"}) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new DaemonSkillDescriptor(
                  SOURCE_ID, SOURCE_VERSION, "name", invalidDesc, "/home/dev/skills", REVISION));
    }
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DaemonSkillDescriptor(
                SOURCE_ID,
                SOURCE_VERSION,
                "name",
                "a".repeat(DaemonSkillDescriptor.MAX_DESCRIPTION_CHARS + 1),
                "/home/dev/skills",
                REVISION));

    DaemonSkillDescriptor multiline =
        new DaemonSkillDescriptor(
            SOURCE_ID, SOURCE_VERSION, "name", "line1\nline2", "/home/dev/skills", REVISION);
    assertEquals("line1\nline2", multiline.description());
  }

  /** 测试意图：DaemonSkillDescriptor contentRevision 必须为 64 位小写十六进制 SHA-256。 */
  @Test
  void validatesSkillDescriptorContentRevision() {
    for (String invalidRev :
        new String[] {
          null,
          "",
          "abc",
          "a".repeat(63),
          "a".repeat(65),
          "g".repeat(64),
          "A".repeat(64),
          REVISION.toUpperCase()
        }) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new DaemonSkillDescriptor(
                  SOURCE_ID, SOURCE_VERSION, "name", "desc", "/home/dev/skills", invalidRev));
    }
    assertEquals(REVISION, DaemonSkillDescriptor.contentRevision(REVISION));
  }

  /** 测试意图：DaemonSkillDescriptor baseDirectory 绝对路径校验，覆盖 Unix 路径、Windows 盘符与 UNC 路径。 */
  @Test
  void validatesSkillDescriptorBaseDirectoryPaths() {
    for (String invalidPath :
        new String[] {
          null,
          "",
          "   ",
          " /abs",
          "/abs ",
          "/" + "a".repeat(DaemonSkillDescriptor.MAX_BASE_DIRECTORY_CHARS),
          "/abs\u0000dir",
          "relative/path",
          "C:",
          "1:/skills",
          "C:skills",
          "\\\\",
          "\\\\server",
          "\\\\server\\",
          "\\\\\\share"
        }) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new DaemonSkillDescriptor(
                  SOURCE_ID, SOURCE_VERSION, "name", "desc", invalidPath, REVISION));
    }

    DaemonSkillDescriptor unix =
        new DaemonSkillDescriptor(
            SOURCE_ID, SOURCE_VERSION, "name", "desc", "/home/dev/skills", REVISION);
    assertEquals("/home/dev/skills", unix.baseDirectory());

    DaemonSkillDescriptor winDriveSlash =
        new DaemonSkillDescriptor(SOURCE_ID, SOURCE_VERSION, "name", "desc", "C:/skills", REVISION);
    assertEquals("C:/skills", winDriveSlash.baseDirectory());

    DaemonSkillDescriptor winDriveBackslash =
        new DaemonSkillDescriptor(
            SOURCE_ID, SOURCE_VERSION, "name", "desc", "D:\\skills", REVISION);
    assertEquals("D:\\skills", winDriveBackslash.baseDirectory());

    DaemonSkillDescriptor winUncBackslash =
        new DaemonSkillDescriptor(
            SOURCE_ID, SOURCE_VERSION, "name", "desc", "\\\\server\\share\\skills", REVISION);
    assertEquals("\\\\server\\share\\skills", winUncBackslash.baseDirectory());

    DaemonSkillDescriptor winUncSlash =
        new DaemonSkillDescriptor(
            SOURCE_ID, SOURCE_VERSION, "name", "desc", "//server/share/skills", REVISION);
    assertEquals("//server/share/skills", winUncSlash.baseDirectory());
  }

  /** 测试意图：DaemonSkillDiagnostic 模型的非空约束以及超出上限时的安全截断行为。 */
  @Test
  void validatesSkillDiagnosticModel() {
    assertThrows(NullPointerException.class, () -> new DaemonSkillDiagnostic(null, "msg"));
    assertThrows(NullPointerException.class, () -> new DaemonSkillDiagnostic("/loc", null));
    assertThrows(IllegalArgumentException.class, () -> new DaemonSkillDiagnostic("   ", "msg"));
    assertThrows(IllegalArgumentException.class, () -> new DaemonSkillDiagnostic("/loc", "   "));

    String longLoc = "a".repeat(DaemonSkillDiagnostic.MAX_LOCATION_CHARS + 10);
    String longMsg = "b".repeat(DaemonSkillDiagnostic.MAX_MESSAGE_CHARS + 10);
    DaemonSkillDiagnostic truncated = new DaemonSkillDiagnostic(longLoc, longMsg);
    assertEquals(
        longLoc.substring(0, DaemonSkillDiagnostic.MAX_LOCATION_CHARS), truncated.location());
    assertEquals(
        longMsg.substring(0, DaemonSkillDiagnostic.MAX_MESSAGE_CHARS), truncated.message());
  }

  private static ObjectNode baseSnapshotNode() {
    ObjectNode node = MAPPER.createObjectNode();
    node.put("sourceId", SOURCE_ID.toString());
    node.put("sourceVersion", SOURCE_VERSION);
    node.put("sourceRevision", REVISION);

    ArrayNode skills = node.putArray("skills");
    ObjectNode skill = skills.addObject();
    skill.put("sourceId", SOURCE_ID.toString());
    skill.put("sourceVersion", SOURCE_VERSION);
    skill.put("name", "dev");
    skill.put("description", "Developer rules");
    skill.put("baseDirectory", "/home/dev/.agents/skills/dev");
    skill.put("contentRevision", REVISION);

    ArrayNode diags = node.putArray("diagnostics");
    ObjectNode diag = diags.addObject();
    diag.put("location", "/home/dev/.agents/skills/broken");
    diag.put("message", "missing front matter name");

    return node;
  }
}
