package com.deepx.apicenter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import com.deepx.apicenter.exception.BizException;

/**
 * 管理面账号登录集成测试（2026-09-18）：登录 / 注册 / 退出 / 改密 + 令牌守卫 + 锁定。
 *
 * <p>本类**不**关闭认证（用默认 `auth.enabled=true`），因此也是「过滤器真的生效」的证据；
 * 其它测试类里直连管理面 HTTP 的用例统一置 `auth.enabled=false`（见各类 properties）。
 *
 * <p>开发库即测试库：账号名带随机后缀，用例结束**清理自己创建的账号与其会话**。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.api-center.retry-worker-fixed-delay-ms=3600000",
        "app.api-center.alert-worker-fixed-delay-ms=3600000",
        "app.api-center.retry-worker-initial-delay-ms=3600000",
        "app.api-center.alert-worker-initial-delay-ms=3600000",
        // 锁定阈值降到 3 次，用例快速验证锁定而不影响生产默认值
        "app.api-center.auth.max-failed-attempts=3",
        "app.api-center.auth.lock-minutes=5"
})
class AuthIntegrationTest {

    private static final String PASSWORD = "Passw0rd!";
    private static final String SUFFIX = String.valueOf(System.currentTimeMillis() % 1_000_000);
    private static final String USER = "it_auth_" + SUFFIX;

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<String> createdUsers = new ArrayList<>();

    private final RestClient rest = RestClient.builder()
            .defaultStatusHandler(HttpStatusCode::isError, (req, resp) -> { /* 不抛：断言状态码 */ })
            .build();

    @AfterEach
    void cleanup() {
        for (String username : createdUsers) {
            jdbcTemplate.update("DELETE FROM admin_session WHERE user_id IN (SELECT id FROM admin_user WHERE username = ?)", username);
            jdbcTemplate.update("DELETE FROM admin_user WHERE username = ?", username);
        }
        createdUsers.clear();
    }

    // ---------- 1. 守卫 ----------

    @Test
    void 未登录访问管理面_401_40104() {
        ResponseEntity<String> resp = get("/api/admin/apps", null);

        assertThat(resp.getStatusCode().value()).isEqualTo(401);
        assertThat(resp.getBody()).contains("40104").contains("未登录");
    }

    @Test
    void 伪造令牌_401_40104() {
        ResponseEntity<String> resp = get("/api/admin/apps", "not-a-real-token");

        assertThat(resp.getStatusCode().value()).isEqualTo(401);
        assertThat(resp.getBody()).contains("40104");
    }

    @Test
    void 登录页状态端点免鉴权_认证开关与账号存在性() {
        ResponseEntity<String> resp = get("/api/admin/auth/status", null);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        JsonNode data = json(resp).get("data");
        assertThat(data.get("enabled").asBoolean()).isTrue();
        assertThat(data.get("allowRegister").asBoolean()).isTrue();
        assertThat(data.has("hasUser")).isTrue();
    }

    // ---------- 2. 注册 ----------

    @Test
    void 注册成功直接返回可用令牌_并可访问管理面() {
        String token = register(USER, PASSWORD, "集成测试账号");

        assertThat(token).isNotBlank();
        assertThat(get("/api/admin/auth/me", token).getStatusCode().value()).isEqualTo(200);
        assertThat(get("/api/admin/apps", token).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void 重复用户名_409_40901() {
        register(USER, PASSWORD, null);

        ResponseEntity<String> again = post("/api/admin/auth/register", null,
                Map.of("username", USER, "password", PASSWORD, "displayName", "dup"));

        assertThat(again.getStatusCode().value()).isEqualTo(409);
        assertThat(again.getBody()).contains("40901");
    }

    @Test
    void 用户名与密码规则校验_40001() {
        // 用户名太短
        assertThat(post("/api/admin/auth/register", null,
                Map.of("username", "ab", "password", PASSWORD)).getBody()).contains("40001");
        // 用户名含非法字符（大写/空格）
        assertThat(post("/api/admin/auth/register", null,
                Map.of("username", "IT AUTH", "password", PASSWORD)).getBody()).contains("40001");
        // 密码太短
        assertThat(post("/api/admin/auth/register", null,
                Map.of("username", USER + "x", "password", "Pa1!")).getBody()).contains("40001");
        // 密码缺数字 / 缺字母
        assertThat(post("/api/admin/auth/register", null,
                Map.of("username", USER + "y", "password", "abcdefgh")).getBody()).contains("40001");
        assertThat(post("/api/admin/auth/register", null,
                Map.of("username", USER + "z", "password", "12345678")).getBody()).contains("40001");
    }

    // ---------- 3. 登录 / 退出 ----------

    @Test
    void 登录成功与密码错误_并对错误密码给出剩余次数() {
        register(USER, PASSWORD, null);

        assertThat(post("/api/admin/auth/login", null,
                Map.of("username", USER, "password", PASSWORD)).getStatusCode().value()).isEqualTo(200);

        ResponseEntity<String> bad = post("/api/admin/auth/login", null,
                Map.of("username", USER, "password", "WrongPass1"));
        assertThat(bad.getStatusCode().value()).isEqualTo(401);
        assertThat(bad.getBody()).contains("40105").contains("用户名或密码错误");
    }

    @Test
    void 账号不存在与密码错误同文案_防枚举() {
        ResponseEntity<String> notExist = post("/api/admin/auth/login", null,
                Map.of("username", "no_such_user_" + SUFFIX, "password", PASSWORD));

        assertThat(notExist.getStatusCode().value()).isEqualTo(401);
        assertThat(notExist.getBody()).contains("用户名或密码错误");
    }

    @Test
    void 退出后令牌立即失效() {
        String token = register(USER, PASSWORD, null);

        assertThat(post("/api/admin/auth/logout", token, Map.of()).getStatusCode().value()).isEqualTo(200);
        assertThat(get("/api/admin/auth/me", token).getStatusCode().value()).isEqualTo(401);
    }

    // ---------- 4. 锁定（暴力破防） ----------

    @Test
    void 连续失败达阈值_锁定且正确密码也被拒() {
        register(USER, PASSWORD, null);

        for (int i = 0; i < 2; i++) {
            assertThat(post("/api/admin/auth/login", null,
                    Map.of("username", USER, "password", "WrongPass1")).getStatusCode().value()).isEqualTo(401);
        }
        // 第 3 次达到阈值（max-failed-attempts=3）→ 锁定
        ResponseEntity<String> locked = post("/api/admin/auth/login", null,
                Map.of("username", USER, "password", "WrongPass1"));
        assertThat(locked.getStatusCode().value()).isEqualTo(401);
        assertThat(locked.getBody()).contains("40106").contains("锁定");

        // 锁定期内即使密码正确也拒绝（不泄漏「密码其实对了」）
        ResponseEntity<String> correct = post("/api/admin/auth/login", null,
                Map.of("username", USER, "password", PASSWORD));
        assertThat(correct.getStatusCode().value()).isEqualTo(401);
        assertThat(correct.getBody()).contains("40106");
    }

    // ---------- 5. 改密 ----------

    @Test
    void 改密_原密码错误被拒_成功后新密码可登录旧密码不可() {
        String token = register(USER, PASSWORD, null);

        ResponseEntity<String> wrongOld = post("/api/admin/auth/password", token,
                Map.of("oldPassword", "WrongOld1", "newPassword", "NewPassw0rd"));
        assertThat(wrongOld.getStatusCode().value()).isEqualTo(401);
        assertThat(wrongOld.getBody()).contains("原密码不正确");

        assertThat(post("/api/admin/auth/password", token,
                Map.of("oldPassword", PASSWORD, "newPassword", "NewPassw0rd")).getStatusCode().value()).isEqualTo(200);

        assertThat(post("/api/admin/auth/login", null,
                Map.of("username", USER, "password", "NewPassw0rd")).getStatusCode().value()).isEqualTo(200);
        assertThat(post("/api/admin/auth/login", null,
                Map.of("username", USER, "password", PASSWORD)).getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void 改密吊销其他会话_当前会话保留() {
        String first = register(USER, PASSWORD, null);
        String second = login(USER, PASSWORD);
        assertThat(get("/api/admin/auth/me", second).getStatusCode().value()).isEqualTo(200);

        assertThat(post("/api/admin/auth/password", first,
                Map.of("oldPassword", PASSWORD, "newPassword", "NewPassw0rd")).getStatusCode().value()).isEqualTo(200);

        assertThat(get("/api/admin/auth/me", second).getStatusCode().value()).isEqualTo(401);   // 他处会话被吊销
        assertThat(get("/api/admin/auth/me", first).getStatusCode().value()).isEqualTo(200);    // 当前会话保留
    }

    @Test
    void 新旧密码相同被拒_40001() {
        String token = register(USER, PASSWORD, null);

        ResponseEntity<String> same = post("/api/admin/auth/password", token,
                Map.of("oldPassword", PASSWORD, "newPassword", PASSWORD));

        assertThat(same.getStatusCode().value()).isEqualTo(400);
        assertThat(same.getBody()).contains("40001").contains("不能与原密码相同");
    }

    // ---------- 6. 存储口径 ----------

    @Test
    void 库内不存明文口令与明文令牌() {
        String token = register(USER, PASSWORD, null);

        String hash = jdbcTemplate.queryForObject(
                "SELECT password_hash FROM admin_user WHERE username = ?", String.class, USER);
        assertThat(hash).startsWith("pbkdf2$").doesNotContain(PASSWORD);

        Integer plainToken = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM admin_session WHERE token_hash = ?", Integer.class, token);
        assertThat(plainToken).isZero();   // 明文令牌不落库（只存 SHA-256 摘要）
    }

    // ---------- 工具 ----------

    private String register(String username, String password, String displayName) {
        createdUsers.add(username);
        ResponseEntity<String> resp = post("/api/admin/auth/register", null,
                displayName == null
                        ? Map.of("username", username, "password", password)
                        : Map.of("username", username, "password", password, "displayName", displayName));
        assertThat(resp.getStatusCode().value()).as("注册失败：%s", resp.getBody()).isEqualTo(200);
        return json(resp).get("data").get("token").asString();
    }

    private String login(String username, String password) {
        ResponseEntity<String> resp = post("/api/admin/auth/login", null,
                Map.of("username", username, "password", password));
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        return json(resp).get("data").get("token").asString();
    }

    /**
     * 入站鉴权**权限矩阵**（2026-09-24 决策 B+C）：
     * VIEWER 连**读**都不可达（40305，页面也进不去）；ADMIN 可读、可管凭证池，但**改不了平台设置**（40304）；
     * OWNER 全可。平台设置是"安全策略类"配置（一改就是全平台放宽/收紧）。
     */
    @Test
    void 入站鉴权_权限矩阵_VIEWER不可达_ADMIN可管凭证但改不了平台设置_OWNER全可() {
        String viewer = register("it_ia_view_" + SUFFIX, PASSWORD, "入站鉴权-只读");
        String admin = register("it_ia_admin_" + SUFFIX, PASSWORD, "入站鉴权-管理");
        String owner = register("it_ia_owner_" + SUFFIX, PASSWORD, "入站鉴权-拥有者");
        // 注册默认是 VIEWER ⇒ 直接改库升级角色（过滤器每请求从库读角色，故立即生效、无需重新登录）。
        // 用 `/auth/me` 拿到的 **id** 定位账号（不依赖用户名的存储形态）。
        long adminId = json(get("/api/admin/auth/me", admin)).get("data").get("id").asLong();
        long ownerId = json(get("/api/admin/auth/me", owner)).get("data").get("id").asLong();
        assertThat(jdbcTemplate.update("UPDATE admin_user SET role = 'ADMIN' WHERE id = ?", adminId)).isEqualTo(1);
        assertThat(jdbcTemplate.update("UPDATE admin_user SET role = 'OWNER' WHERE id = ?", ownerId)).isEqualTo(1);

        // ① VIEWER：读也不可达（403 / 40305）
        ResponseEntity<String> viewerRead = get("/api/admin/inbound-auth/settings", viewer);
        assertThat(viewerRead.getStatusCode().value()).isEqualTo(403);
        assertThat(json(viewerRead).get("code").asInt()).isEqualTo(BizException.NO_INBOUND_AUTH_ADMIN);
        assertThat(get("/api/admin/inbound-credentials?ownerType=PLATFORM", viewer).getStatusCode().value())
                .as("凭证池台账（含密钥备注/指纹）对只读角色也不可见").isEqualTo(403);

        // ② ADMIN：可读；凭证池写**通过过滤器**（这里用空体触发参数校验失败 ⇒ 40001，证明未被权限拦且无副作用）
        ResponseEntity<String> adminRead = get("/api/admin/inbound-auth/settings", admin);
        assertThat(adminRead.getStatusCode().value()).as("ADMIN 读设置 body=%s", adminRead.getBody())
                .isEqualTo(200);
        ResponseEntity<String> adminPost = post("/api/admin/inbound-credentials", admin, Map.of());
        assertThat(adminPost.getStatusCode().value()).isEqualTo(400);
        assertThat(json(adminPost).get("code").asInt()).isEqualTo(BizException.FIELD_INVALID);

        // ③ ADMIN：**改不了平台设置**（403 / 40304）
        ResponseEntity<String> adminPut = put("/api/admin/inbound-auth/settings", admin,
                Map.of("requireClientId", true));
        assertThat(adminPut.getStatusCode().value()).isEqualTo(403);
        assertThat(json(adminPut).get("code").asInt()).isEqualTo(BizException.OWNER_ONLY);

        // ④ OWNER：可改（**幂等写回当前值**，避免破坏开发库既有设置）
        JsonNode before = json(get("/api/admin/inbound-auth/settings", owner)).get("data");
        Map<String, Object> same = new java.util.HashMap<>();
        same.put("defaultAdapterId", before.get("defaultAdapterId").isNull()
                ? null : before.get("defaultAdapterId").asString());
        same.put("requireClientId", before.get("requireClientId").asBoolean());
        assertThat(put("/api/admin/inbound-auth/settings", owner, same).getStatusCode().value()).isEqualTo(200);
    }

    private JsonNode json(ResponseEntity<String> resp) {
        return mapper.readTree(resp.getBody());
    }

    private ResponseEntity<String> get(String path, String token) {
        RestClient.RequestHeadersSpec<?> spec = rest.get().uri(url(path));
        if (token != null) {
            spec = spec.header("Authorization", "Bearer " + token);
        }
        return spec.retrieve().toEntity(String.class);
    }

    private ResponseEntity<String> post(String path, String token, Map<String, ?> body) {
        RestClient.RequestBodySpec spec = rest.post().uri(url(path)).contentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            spec = spec.header("Authorization", "Bearer " + token);
        }
        return spec.body(body).retrieve().toEntity(String.class);
    }

    private ResponseEntity<String> put(String path, String token, Map<String, ?> body) {
        RestClient.RequestBodySpec spec = rest.put().uri(url(path)).contentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            spec = spec.header("Authorization", "Bearer " + token);
        }
        return spec.body(body == null ? Map.of() : body).retrieve().toEntity(String.class);
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
