package fun.fengwk.kkstudio.project.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** {@link ProjectWorkflowJsonCodec} 的严格 grammar、规范输出，以及状态/边/额度拒绝测试。 */
class ProjectWorkflowJsonTest {

  private static final String INIT_STATE =
      """
      {"state":"INIT","name":"待开始","next":["WORK"]}""";
  private static final String WORK_STATE =
      """
      {"state":"WORK","name":"工作","agent":"worker","instructions":"做事","maxRuns":3,"next":["DONE"]}""";
  private static final String BLOCKED_STATE = """
      {"state":"BLOCKED","name":"业务阻塞"}""";
  private static final String DONE_STATE = """
      {"state":"DONE","name":"完成"}""";

  private final ProjectWorkflowJsonCodec codec = new ProjectWorkflowJsonCodec();

  /** 设计文档示例必须可解析，规范输出只省略默认值与空边，字段顺序与示例一致，且可无损往返。 */
  @Test
  void decodesDocumentedWorkflowAndEncodesCanonicalForm() throws IOException {
    ProjectWorkflow workflow = codec.decode(readDocumentedWorkflow());

    assertEquals(
        List.of("INIT", "DESIGN", "REVIEW", "BLOCKED", "DONE"),
        workflow.states().stream().map(state -> state.state().value()).toList());
    ProjectWorkflowState design = workflow.require(ProjectStateCode.of("DESIGN"));
    assertEquals("设计", design.name());
    assertEquals("designer", design.agent());
    assertEquals("完成可交付方案", design.instructions());
    assertEquals(3, design.maxRuns());
    assertTrue(design.enabled());
    assertNull(design.environment());
    assertEquals(ProjectDomainFixtures.codes("REVIEW"), design.next());
    assertEquals(List.of(), workflow.require(ProjectStateCode.of("BLOCKED")).next());
    assertEquals(List.of(), workflow.require(ProjectStateCode.of("DONE")).next());

    String expected =
        """
        {"states":[{"state":"INIT","name":"待开始","next":["DESIGN"]},\
        {"state":"DESIGN","name":"设计","agent":"designer","instructions":"完成可交付方案","maxRuns":3,"next":["REVIEW"]},\
        {"state":"REVIEW","name":"检查","agent":"reviewer","instructions":"按 Issue 验收要求检查","maxRuns":3,"next":["DESIGN","DONE"]},\
        {"state":"BLOCKED","name":"业务阻塞"},\
        {"state":"DONE","name":"完成"}]}""";
    assertEquals(expected, codec.encode(workflow));
    assertEquals(workflow, codec.decode(codec.encode(workflow)));
  }

  /** enabled、environment 只在偏离默认值或确实配置时写出，往返后值不丢。 */
  @Test
  void encodesOptionalFieldsOnlyWhenPresent() {
    ProjectWorkflow workflow =
        new ProjectWorkflow(
            List.of(
                ProjectDomainFixtures.reservedState("INIT", "待开始", "WORK", "DONE"),
                new ProjectWorkflowState(
                    ProjectStateCode.of("WORK"),
                    "工作",
                    "worker",
                    "linux",
                    "做事",
                    2,
                    false,
                    ProjectDomainFixtures.codes("DONE")),
                ProjectDomainFixtures.reservedState("BLOCKED", "业务阻塞"),
                ProjectDomainFixtures.reservedState("DONE", "完成")));

    String encoded = codec.encode(workflow);
    assertTrue(encoded.contains("\"environment\":\"linux\""));
    assertTrue(encoded.contains("\"enabled\":false"));
    assertEquals(workflow, codec.decode(encoded));
  }

  /** 字符串边界必须拒绝 Java null、空文档、非对象根、missing states、trailing token 与 duplicate field。 */
  @Test
  void rejectsMalformedDocuments() {
    assertThrows(NullPointerException.class, () -> codec.decode(null));
    assertThrows(NullPointerException.class, () -> codec.encode(null));
    assertThrows(IllegalArgumentException.class, () -> codec.decode(""));
    assertThrows(IllegalArgumentException.class, () -> codec.decode("null"));
    assertThrows(IllegalArgumentException.class, () -> codec.decode("{"));
    assertThrows(IllegalArgumentException.class, () -> codec.decode("[]"));
    assertThrows(IllegalArgumentException.class, () -> codec.decode("{}"));
    assertThrows(IllegalArgumentException.class, () -> codec.decode("{\"states\":null}"));
    assertThrows(IllegalArgumentException.class, () -> codec.decode("{\"states\":{}}"));
    assertThrows(IllegalArgumentException.class, () -> codec.decode("{\"states\":[]} {}"));
    assertThrows(
        IllegalArgumentException.class, () -> codec.decode("{\"states\":[],\"states\":[]}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode("{\"states\":[{\"state\":\"INIT\",\"state\":\"DONE\"}]}"));
  }

  /** 未知、缺失、null 与错误类型字段都必须失败：workflow 是严格配置，不做宽容解析或兼容别名。 */
  @Test
  void rejectsUnknownMissingAndWrongTypedFields() {
    assertThrows(
        IllegalArgumentException.class, () -> codec.decode("{\"states\":[],\"version\":1}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                workflow(INIT_STATE.replace("\"name\"", "\"title\""), BLOCKED_STATE, DONE_STATE)));
    assertThrows(IllegalArgumentException.class, () -> codec.decode(workflow("[1]")));
    assertThrows(IllegalArgumentException.class, () -> codec.decode(workflow("{}")));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(workflow("{\"name\":\"待开始\"}", BLOCKED_STATE, DONE_STATE)));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(workflow("{\"state\":\"INIT\"}", BLOCKED_STATE, DONE_STATE)));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(workflow("{\"state\":5,\"name\":\"待开始\"}")));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(workflow("{\"state\":\"INIT\",\"name\":5}")));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(workflow("{\"state\":\"init\",\"name\":\"待开始\"}")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                workflow(
                    "{\"state\":\"INIT\",\"name\":\"待开始\",\"agent\":null,\"next\":[\"DONE\"]}",
                    BLOCKED_STATE,
                    DONE_STATE)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                workflow("{\"state\":\"WORK\",\"name\":\"工作\",\"agent\":5,\"maxRuns\":3}")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                workflow(
                    "{\"state\":\"WORK\",\"name\":\"工作\",\"agent\":\"worker\",\"agent\":\"other\"}")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                workflow(
                    "{\"state\":\"WORK\",\"name\":\"工作\",\"agent\":\"worker\",\"maxRuns\":1.5}")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                workflow(
                    "{\"state\":\"WORK\",\"name\":\"工作\",\"agent\":\"worker\",\"maxRuns\":3000000000}")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                workflow(
                    "{\"state\":\"WORK\",\"name\":\"工作\",\"agent\":\"worker\",\"maxRuns\":null}")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                workflow(
                    "{\"state\":\"WORK\",\"name\":\"工作\",\"agent\":\"worker\",\"maxRuns\":3,\"enabled\":\"yes\"}")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                workflow(
                    "{\"state\":\"WORK\",\"name\":\"工作\",\"agent\":\"worker\",\"maxRuns\":3,\"next\":\"DONE\"}")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                workflow(
                    "{\"state\":\"WORK\",\"name\":\"工作\",\"agent\":\"worker\",\"maxRuns\":3,\"next\":[1]}")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                workflow(
                    "{\"state\":\"WORK\",\"name\":\"工作\",\"agent\":\"worker\",\"maxRuns\":3,\"next\":[\"done\"]}")));
  }

  /** 保留状态、Agent/额度与 instructions 的组合规则在解析路径同样生效（由领域对象判定）。 */
  @Test
  void rejectsInvalidStageContracts() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                workflow(
                    "{\"state\":\"INIT\",\"name\":\"待开始\",\"agent\":\"worker\",\"maxRuns\":1,\"next\":[\"DONE\"]}",
                    BLOCKED_STATE,
                    DONE_STATE)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                workflow(
                    INIT_STATE,
                    BLOCKED_STATE,
                    "{\"state\":\"DONE\",\"name\":\"完成\",\"enabled\":false}")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                workflow(
                    INIT_STATE,
                    BLOCKED_STATE,
                    "{\"state\":\"DONE\",\"name\":\"完成\",\"next\":[\"INIT\"]}")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                workflow(
                    INIT_STATE,
                    "{\"state\":\"BLOCKED\",\"name\":\"业务阻塞\",\"next\":[\"DONE\"]}",
                    DONE_STATE)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                workflow(
                    INIT_STATE,
                    "{\"state\":\"WORK\",\"name\":\"工作\",\"agent\":\"worker\"}",
                    BLOCKED_STATE,
                    DONE_STATE)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                workflow(
                    INIT_STATE,
                    "{\"state\":\"WORK\",\"name\":\"工作\",\"agent\":\"worker\",\"maxRuns\":0}",
                    BLOCKED_STATE,
                    DONE_STATE)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                workflow(
                    INIT_STATE,
                    "{\"state\":\"WORK\",\"name\":\"工作\",\"environment\":\"linux\"}",
                    BLOCKED_STATE,
                    DONE_STATE)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                workflow(
                    INIT_STATE,
                    "{\"state\":\"WORK\",\"name\":\"工作\",\"maxRuns\":1}",
                    BLOCKED_STATE,
                    DONE_STATE)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                workflow(
                    INIT_STATE,
                    "{\"state\":\"WORK\",\"name\":\"工作\",\"agent\":\" \",\"maxRuns\":1}",
                    BLOCKED_STATE,
                    DONE_STATE)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                workflow(
                    INIT_STATE,
                    "{\"state\":\"WORK\",\"name\":\" 工作 \",\"agent\":\"worker\",\"maxRuns\":1}",
                    BLOCKED_STATE,
                    DONE_STATE)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                workflow(
                    INIT_STATE,
                    "{\"state\":\"WORK\",\"name\":\"工作\",\"agent\":\"worker\",\"maxRuns\":1,\"instructions\":\" \"}",
                    BLOCKED_STATE,
                    DONE_STATE)));
  }

  /** 状态唯一、边合法与启用图可达性同样在解析路径生效，错误不会推迟到运行期。 */
  @Test
  void rejectsInvalidGraphs() {
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(workflow(INIT_STATE, INIT_STATE, BLOCKED_STATE, DONE_STATE)));
    assertThrows(
        IllegalArgumentException.class, () -> codec.decode(workflow(INIT_STATE, BLOCKED_STATE)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                workflow(
                    INIT_STATE.replace("[\"WORK\"]", "[\"GHOST\"]"), BLOCKED_STATE, DONE_STATE)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                workflow(
                    "{\"state\":\"INIT\",\"name\":\"待开始\",\"next\":[\"INIT\"]}",
                    BLOCKED_STATE,
                    DONE_STATE)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                workflow(
                    "{\"state\":\"INIT\",\"name\":\"待开始\",\"next\":[\"BLOCKED\"]}",
                    BLOCKED_STATE,
                    DONE_STATE)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                workflow(
                    "{\"state\":\"INIT\",\"name\":\"待开始\",\"next\":[\"DONE\",\"DONE\"]}",
                    BLOCKED_STATE,
                    DONE_STATE)));
  }

  private static String workflow(String... states) {
    return "{\"states\":[" + String.join(",", states) + "]}";
  }

  private static String readDocumentedWorkflow() throws IOException {
    try (InputStream in =
        ProjectWorkflowJsonTest.class.getResourceAsStream("documentation-workflow.json")) {
      assertNotNull(in, "documentation workflow resource must exist");
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
