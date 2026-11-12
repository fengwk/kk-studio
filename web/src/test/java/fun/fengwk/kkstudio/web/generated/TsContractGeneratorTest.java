package fun.fengwk.kkstudio.web.generated;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.canvas.dto.CanvasConflictDTO;
import fun.fengwk.kkstudio.canvas.dto.CanvasSnapshotDTO;
import fun.fengwk.kkstudio.web.StrictHttpJsonMapperTestSupport;
import fun.fengwk.kkstudio.web.generated.fixture.FixtureDTO;

import java.util.List;

/** 生成器规则的定向覆盖：用真实 HTTP mapper 与 fixture 契约验证 presence、方向、标量、枚举、多态与固定失败的形态。 */
class TsContractGeneratorTest {

  private static final String FIXTURE_PACKAGE = "fun.fengwk.kkstudio.web.generated.fixture";

  static final class ContractController {

    @GetMapping("/view")
    FixtureDTO.View view() {
      throw new UnsupportedOperationException();
    }

    @PostMapping("/create")
    void create(@RequestBody FixtureDTO.Create body) {}

    @PutMapping("/shared")
    FixtureDTO.Shared shared(@RequestBody FixtureDTO.Shared body) {
      return body;
    }

    @GetMapping("/shape")
    FixtureDTO.Shape shape() {
      throw new UnsupportedOperationException();
    }

    @GetMapping("/priced")
    FixtureDTO.Priced priced() {
      throw new UnsupportedOperationException();
    }

    @GetMapping("/canvas")
    CanvasSnapshotDTO canvas() {
      throw new UnsupportedOperationException();
    }
  }

  static final class CustomSerializerController {

    @GetMapping("/custom")
    FixtureDTO.Custom custom() {
      throw new UnsupportedOperationException();
    }
  }

  static final class UnsupportedTypeController {

    @GetMapping("/unsupported")
    FixtureDTO.Unsupported unsupported() {
      throw new UnsupportedOperationException();
    }
  }

  @RequestMapping("/api/widgets")
  static final class RoutesController {
    @PutMapping("/{*name}")
    ResponseEntity<FixtureDTO.View> update(
        @PathVariable("name") long name,
        @RequestParam("version") Long version,
        @RequestParam(value = "limit", defaultValue = "10") int limit,
        @RequestBody FixtureDTO.Create body) {
      throw new UnsupportedOperationException();
    }

    @GetMapping
    FixtureDTO.View list() {
      throw new UnsupportedOperationException();
    }

    @GetMapping(value = "/script", produces = "text/plain")
    ResponseEntity<String> script() {
      throw new UnsupportedOperationException();
    }

    @GetMapping("/download")
    ResponseEntity<byte[]> download() {
      throw new UnsupportedOperationException();
    }

    @GetMapping("/stream")
    ResponseEntity<StreamingResponseBody> stream() {
      throw new UnsupportedOperationException();
    }

    @PostMapping("/optional")
    void optional(@RequestBody(required = false) FixtureDTO.Create body) {}
  }

  /** 路由携带精确参数和解包响应，非 JSON 与可选体可区分。 */
  @Test
  void generatesRouteSignatures() {
    String generated =
        new TsContractGenerator(
                StrictHttpJsonMapperTestSupport.get(),
                List.of(FIXTURE_PACKAGE),
                List.of(RoutesController.class),
                List.of())
            .generate();
    assertContains(
        generated,
        """
          'PUT /api/widgets/{*name}': {
            path: { name: string }
            query: { limit?: number; version: string }
            body: FixtureCreate
            response: FixtureView
          }
        """);
    assertTrue(generated.contains("body?: FixtureCreate"));
    assertContains(
        generated,
        """
          'GET /api/widgets/stream': {
            response: Blob
          }
        """);
    assertTrue(generated.contains("responseType: 'text'"));
    assertTrue(generated.contains("responseType: 'blob'"));
    assertTrue(
        TsContractGenerator.combinePaths("/api", "/items/{id:[0-9]{2}}").equals("/api/items/{id}"));
  }

  private static String generate(Class<?> controller) {
    return new TsContractGenerator(
            StrictHttpJsonMapperTestSupport.get(),
            List.of(FIXTURE_PACKAGE),
            List.of(controller),
            List.of())
        .generate();
  }

  /** 测试意图：控制器返回类型及嵌套字段可分别来自不同的契约包。 */
  @Test
  void discoversTypesFromMultipleContractPackages() {
    String generated =
        new TsContractGenerator(
                StrictHttpJsonMapperTestSupport.get(),
                List.of(FIXTURE_PACKAGE, "fun.fengwk.kkstudio.canvas"),
                List.of(ContractController.class),
                List.of(CanvasConflictDTO.class))
            .generate();

    assertTrue(generated.contains("export interface FixtureView"));
    assertTrue(generated.contains("export interface CanvasSnapshotDTO"));
    assertTrue(generated.contains("export interface CanvasDocumentDTO"));
    assertTrue(generated.contains("export type CanvasConflictDTO"));
    // 读模型里的固定取值来自 canvas 根的领域枚举，跨包嵌套字段同样要生成。
    assertTrue(generated.contains("export type CanvasConflictTarget"));
    assertTrue(generated.contains("export type CanvasFunctionRunStatus"));
  }

  /** 测试意图：未标 Nullable 必填非空；ALWAYS 可空必出现；其余可空可缺省；Long 写成字符串，Instant 写成 epoch 毫秒。 */
  @Test
  void responsePresenceAndScalarsFollowTheRealMapper() {
    assertContains(
        generate(ContractController.class),
        """
        export interface FixtureView {
          name: string
          note?: string | null
          always: string | null
          count: string
          at: number
          id: string
          kinds: FixtureKind[]
          scores: Record<string, number>
        }
        """);
  }

  /** 测试意图：白名单字符串序列化器把 BigDecimal 属性与 Map 元素写作 string；未声明序列化器的 BigDecimal 仍是 number。 */
  @Test
  void allowlistedStringSerializersRewriteTheWireType() {
    assertContains(
        generate(ContractController.class),
        """
        export interface FixturePriced {
          price: string
          tiers: Record<string, string>
          plain: number
        }
        """);
  }

  /** 测试意图：请求及双向类型的 Long 均为 string；可空属性可缺省。 */
  @Test
  void longFollowsTheDirectionItIsUsedIn() {
    String generated = generate(ContractController.class);

    assertContains(
        generated,
        """
        export interface FixtureCreate {
          limit: string
          note?: string | null
        }
        """);
    assertContains(
        generated,
        """
        export interface FixtureShared {
          version: string
        }
        """);
  }

  /** 测试意图：枚举 wire 值由 mapper 序列化真实常量得到；sealed 多态生成带判别字面量的子类型与联合。 */
  @Test
  void enumsAndSealedTypesUseTheirWireNames() {
    String generated = generate(ContractController.class);

    assertContains(
        generated,
        """
        export type FixtureKind =
          | 'alpha'
          | 'beta'

        export const FIXTURE_KIND_VALUES: readonly FixtureKind[] = [
          'alpha',
          'beta',
        ]
        """);
    assertContains(
        generated,
        """
        export interface FixtureShapeCircle {
          kind: 'CIRCLE'
          radius: number
        }
        """);
    assertContains(
        generated,
        """
        export type FixtureShape =
          | FixtureShapeCircle
          | FixtureShapeSquare
        """);
  }

  /** 测试意图：改变写出形态的定制序列化与没有 TS 映射的类型固定失败，绝不回退成 unknown。 */
  @Test
  void rejectsShapesItCannotDescribe() {
    IllegalStateException custom =
        assertThrows(IllegalStateException.class, () -> generate(CustomSerializerController.class));
    assertTrue(custom.getMessage().contains("custom @JsonSerialize"), custom.getMessage());

    IllegalArgumentException unsupported =
        assertThrows(
            IllegalArgumentException.class, () -> generate(UnsupportedTypeController.class));
    assertTrue(
        unsupported.getMessage().contains("unsupported wire type"), unsupported.getMessage());
  }

  /** 测试意图：mapper 的标量 wire 形态与生成器预设不一致时构造即失败，避免生成与生产不符的类型。 */
  @Test
  void rejectsAMapperWhoseWireProfileDrifted() {
    IllegalStateException drifted =
        assertThrows(
            IllegalStateException.class,
            () ->
                new TsContractGenerator(
                    JsonMapper.builder().build(),
                    List.of(FIXTURE_PACKAGE),
                    List.of(ContractController.class),
                    List.of()));
    assertTrue(drifted.getMessage().contains("wire profile drifted"), drifted.getMessage());
  }

  private static void assertContains(String generated, String block) {
    assertTrue(generated.contains(block), () -> "missing block:\n" + block + "\nin:\n" + generated);
  }
}
