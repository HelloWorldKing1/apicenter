package com.deepx.apicenter;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HTTP 错误语义回归（2026-09-18 补，代码评审 P2）：
 * 原先全局异常处理只认 BizException / 参数校验 / 兜底 Exception，
 * 导致「请求体畸形 JSON、未知路径、方法不对」全部回 **500 平台内部错误**（误导调用方与前端）。
 * 本用例把三类语义钉住：400（40001）/ 404 / 405（40500）。
 *
 * <p>注 1：接入层通配路由 `/{*path}` 会先捕获未匹配路径，因此「未知路径」通常由引擎回 40401
 * （接口不存在）；仅当未被通配捕获时才走 `NoResourceFoundException` → 40404。两者都是 404。
 * <p>注 2：Spring Boot 4 已移除 `TestRestTemplate`（见《技术踩坑记录.md》），本用例用
 * `RestClient + @LocalServerPort` 与其它集成测试保持一致（并禁用默认 4xx/5xx 抛异常以便断言状态码）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.api-center.retry-worker-fixed-delay-ms=3600000",
        "app.api-center.alert-worker-fixed-delay-ms=3600000",
        "app.api-center.retry-worker-initial-delay-ms=3600000",
        "app.api-center.alert-worker-initial-delay-ms=3600000"
})
class HttpErrorSemanticsTest {

    @LocalServerPort
    private int port;

    private final RestClient rest = RestClient.builder()
            .defaultStatusHandler(HttpStatusCode::isError, (req, resp) -> { /* 不抛：断言状态码 */ })
            .build();

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @Test
    void 畸形JSON请求体_返回400_40001() {
        ResponseEntity<String> resp = rest.post().uri(url("/api/admin/apps"))
                .contentType(MediaType.APPLICATION_JSON)
                .body("{ this is not json")
                .retrieve().toEntity(String.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(resp.getBody()).contains("40001").contains("请求体格式非法");
    }

    @Test
    void 未知管理面路径_返回404而非500() {
        ResponseEntity<String> resp = rest.get().uri(url("/api/admin/nope/not-exist"))
                .retrieve().toEntity(String.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void 方法不支持_返回405_40500() {
        // /api/admin/apps 仅支持 GET/POST（接入层通配路由也不含 PATCH）
        ResponseEntity<String> resp = rest.method(org.springframework.http.HttpMethod.PATCH)
                .uri(url("/api/admin/apps"))
                .retrieve().toEntity(String.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(405);
        assertThat(resp.getBody()).contains("40500");
    }

    @Test
    void 路径参数类型不符_返回400_40001() {
        ResponseEntity<String> resp = rest.get().uri(url("/api/admin/interfaces/abc"))
                .retrieve().toEntity(String.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(resp.getBody()).contains("40001");
    }
}
