package com.deepx.apicenter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 账号管理集成测试（v1，2026-09-18）：列表 / 新建 / 编辑（启停用）/ 重置口令 / 解锁 / 删除 + 两条安全守卫。
 *
 * <p>与 {@link AuthIntegrationTest} 的分工：前者测「认证」，本类测「账号管理」。
 * 两类都用默认 `auth.enabled=true`（即过滤器真实生效），因此每个用例都要先登录拿令牌。
 *
 * <p>守卫语义（v1 无角色，只有「不把系统锁死」的底线）：
 * ① 不能停用/删除**最后一个可用账号**；② 不能停用/删除自己、不能用重置口令改自己的口令。判定顺序：先①后②。
 *
 * <p>开发库即测试库：账号名统一随机后缀，用例自清理（含会话）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.api-center.retry-worker-fixed-delay-ms=3600000",
        "app.api-center.alert-worker-fixed-delay-ms=3600000",
        "app.api-center.retry-worker-initial-delay-ms=3600000",
        "app.api-center.alert-worker-initial-delay-ms=3600000",
        // 阈值降到 3 次，便于快速触发锁定后验证「解除锁定」
        "app.api-center.auth.max-failed-attempts=3"
})
class AdminUserIntegrationTest {

    private static final String PASSWORD = "Passw0rd!";
    private static final String SUFFIX = String.valueOf(System.currentTimeMillis() % 1_000_000);

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<String> createdUsers = new ArrayList<>();

    private final RestClient rest = RestClient.builder()
            .defaultStatusHandler(HttpStatusCode::isError, (req, resp) -> { /* 不抛：断言状态码 */ })
            .build();

    /** 操作者（每个用例自己注册登录，避免共享令牌带来的串扰）：register() 返回的是**令牌**，用户名另存 */
    private String operatorToken;
    private String operatorUsername;
    private long operatorId;

    @BeforeEach
    void loginOperator() {
        operatorUsername = "it_usr_op_" + SUFFIX;
        operatorToken = register(operatorUsername, "操作者");
        operatorId = idOf(operatorUsername);
        // 共享开发库上「开放注册」通常拿不到首个账号身份 → 角色为 VIEWER；
        // 本类测「账号管理」需要 OWNER，故直接改库提升（角色每请求实时读，无需重新登录）。
        setRole(operatorId, "OWNER");
    }

    /** 测试脚手架：直接改库设角色 */
    private void setRole(long id, String role) {
        jdbcTemplate.update("UPDATE admin_user SET role = ? WHERE id = ?", role, id);
    }

    private String roleOf(long id) {
        return jdbcTemplate.queryForObject("SELECT role FROM admin_user WHERE id = ?", String.class, id);
    }

    @AfterEach
    void cleanup() {
        for (String username : createdUsers) {
            jdbcTemplate.update("DELETE FROM admin_session WHERE user_id IN (SELECT id FROM admin_user WHERE username = ?)", username);
            jdbcTemplate.update("DELETE FROM admin_user WHERE username = ?", username);
        }
        createdUsers.clear();
    }

    // ---------- 1. 列表 ----------

    @Test
    void 列表需要登录_且返回无口令摘要的账号行() {
        assertThat(get("/api/admin/users", null).getStatusCode().value()).isEqualTo(401);

        JsonNode rows = json(get("/api/admin/users", operatorToken)).get("data");
        assertThat(rows.isArray()).isTrue();
        assertThat(rows.size()).isGreaterThanOrEqualTo(1);
        JsonNode mine = find(rows, operatorUsername);
        assertThat(mine).as("列表中应含当前账号 %s，实际返回：%s", operatorUsername, rows).isNotNull();
        assertThat(mine.get("username").asString()).isEqualTo(operatorUsername);
        assertThat(mine.get("status").asString()).isEqualTo("ENABLED");
        assertThat(mine.get("role").asString()).isEqualTo("OWNER");   // 夹具已提升操作者为 OWNER
        assertThat(mine.get("sessionCount").asInt()).isGreaterThanOrEqualTo(1);
        // 列表行不得含口令摘要（注意 passwordUpdatedAt 内含 "password" 子串，故按摘要特征断言）
        assertThat(mine.toString()).doesNotContain("passwordHash").doesNotContain("pbkdf2");
    }

    @Test
    void 列表按关键字过滤() {
        String otherName = "it_usr_kw_" + SUFFIX;
        register(otherName, "关键字账号");   // 注意：register 返回的是**令牌**，用户名要自己留存

        JsonNode rows = json(get("/api/admin/users?keyword=" + otherName, operatorToken)).get("data");

        assertThat(rows.size()).isEqualTo(1);
        assertThat(rows.get(0).get("username").asString()).isEqualTo(otherName);
    }

    // ---------- 2. 新建 ----------

    @Test
    void 新建账号_新账号可登录且不自动建会话() {
        String username = "it_usr_new_" + SUFFIX;

        assertThat(post("/api/admin/users", operatorToken,
                Map.of("username", username, "password", PASSWORD, "displayName", "新同事")).getStatusCode().value())
                .isEqualTo(200);
        createdUsers.add(username);

        JsonNode row = find(json(get("/api/admin/users", operatorToken)).get("data"), username);
        assertThat(row.get("displayName").asString()).isEqualTo("新同事");
        assertThat(row.get("sessionCount").asInt()).isZero();        // 新建不自动登录

        assertThat(post("/api/admin/auth/login", null,
                Map.of("username", username, "password", PASSWORD)).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void 新建账号_重名40901_规则不符40001() {
        String username = "it_usr_dup_" + SUFFIX;
        createdUsers.add(username);
        post("/api/admin/users", operatorToken, Map.of("username", username, "password", PASSWORD));

        ResponseEntity<String> dup = post("/api/admin/users", operatorToken,
                Map.of("username", username.toUpperCase(), "password", PASSWORD));   // 大写归一化后同名
        assertThat(dup.getStatusCode().value()).isEqualTo(409);
        assertThat(dup.getBody()).contains("40901");

        ResponseEntity<String> badName = post("/api/admin/users", operatorToken,
                Map.of("username", "ab", "password", PASSWORD));
        assertThat(badName.getStatusCode().value()).isEqualTo(400);
        assertThat(badName.getBody()).contains("40001");

        ResponseEntity<String> weak = post("/api/admin/users", operatorToken,
                Map.of("username", "it_usr_weak_" + SUFFIX, "password", "abcdefgh"));
        assertThat(weak.getStatusCode().value()).isEqualTo(400);
        assertThat(weak.getBody()).contains("40001");
    }

    // ---------- 3. 编辑（显示名 / 启停用） ----------

    @Test
    void 编辑显示名与启停用_停用后该账号无法登录且会话立即失效() {
        String username = "it_usr_edit_" + SUFFIX;
        long id = create(username);
        String victimToken = login(username, PASSWORD);
        assertThat(get("/api/admin/auth/me", victimToken).getStatusCode().value()).isEqualTo(200);

        // 改显示名（状态不变）
        assertThat(put("/api/admin/users/" + id, operatorToken, Map.of("displayName", "改名后")).getStatusCode().value())
                .isEqualTo(200);
        assertThat(find(json(get("/api/admin/users", operatorToken)).get("data"), username).get("displayName").asString())
                .isEqualTo("改名后");
        assertThat(get("/api/admin/auth/me", victimToken).getStatusCode().value()).isEqualTo(200);

        // 停用 → 既有会话立即失效 + 无法再登录
        assertThat(put("/api/admin/users/" + id, operatorToken, Map.of("status", "DISABLED")).getStatusCode().value())
                .isEqualTo(200);
        assertThat(get("/api/admin/auth/me", victimToken).getStatusCode().value()).isEqualTo(401);
        ResponseEntity<String> relogin = post("/api/admin/auth/login", null,
                Map.of("username", username, "password", PASSWORD));
        assertThat(relogin.getStatusCode().value()).isEqualTo(401);
        assertThat(relogin.getBody()).contains("账号已停用");

        // 重新启用 → 可以登录
        assertThat(put("/api/admin/users/" + id, operatorToken, Map.of("status", "ENABLED")).getStatusCode().value())
                .isEqualTo(200);
        assertThat(post("/api/admin/auth/login", null,
                Map.of("username", username, "password", PASSWORD)).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void 编辑_非法状态与不存在的账号() {
        long id = create("it_usr_bad_" + SUFFIX);

        ResponseEntity<String> badStatus = put("/api/admin/users/" + id, operatorToken, Map.of("status", "CANCELLED"));
        assertThat(badStatus.getStatusCode().value()).isEqualTo(400);
        assertThat(badStatus.getBody()).contains("ENABLED 或 DISABLED");

        ResponseEntity<String> notFound = put("/api/admin/users/999999999", operatorToken, Map.of("status", "DISABLED"));
        assertThat(notFound.getStatusCode().value()).isEqualTo(404);
        assertThat(notFound.getBody()).contains("40405");
    }

    // ---------- 4. 守卫（不把系统锁死） ----------

    @Test
    void 守卫_不能对自己执行停用与删除() {
        // 提示文案取决于环境（库里是否还有别的可用账号）：单账号 → 「最后一个可用账号」；否则 → 「不能动自己」。
        // 两条精确语义由 AdminUserServiceTest 用 Mockito 在确定性环境下钉死；此处只断言「一律被拒」。
        ResponseEntity<String> disableSelf = put("/api/admin/users/" + operatorId, operatorToken, Map.of("status", "DISABLED"));
        assertThat(disableSelf.getStatusCode().value()).isEqualTo(400);
        assertThat(disableSelf.getBody()).contains("40001").contains("不能");

        ResponseEntity<String> deleteSelf = delete("/api/admin/users/" + operatorId, operatorToken);
        assertThat(deleteSelf.getStatusCode().value()).isEqualTo(400);
        assertThat(deleteSelf.getBody()).contains("不能");

        // 守卫不会误伤他人：停用他人正常成功（前提：其不是最后一个可用账号——本环境至少有 operatorToken 可用）
        String other = "it_usr_other_" + SUFFIX;
        long otherId = create(other);
        assertThat(put("/api/admin/users/" + otherId, operatorToken, Map.of("status", "DISABLED")).getStatusCode().value())
                .isEqualTo(200);
    }

    @Test
    void 守卫_重置自己的口令被拒_重置他人会吊销其全部会话() {
        String other = "it_usr_reset_" + SUFFIX;
        long otherId = create(other);
        String otherToken = login(other, PASSWORD);

        ResponseEntity<String> self = post("/api/admin/users/" + operatorId + "/password", operatorToken,
                Map.of("newPassword", "NewPassw0rd"));
        assertThat(self.getStatusCode().value()).isEqualTo(400);
        assertThat(self.getBody()).contains("重置自己的口令请用「修改密码」");

        assertThat(post("/api/admin/users/" + otherId + "/password", operatorToken,
                Map.of("newPassword", "NewPassw0rd")).getStatusCode().value()).isEqualTo(200);
        assertThat(get("/api/admin/auth/me", otherToken).getStatusCode().value()).isEqualTo(401);   // 会话被吊销
        assertThat(post("/api/admin/auth/login", null,
                Map.of("username", other, "password", "NewPassw0rd")).getStatusCode().value()).isEqualTo(200);
        assertThat(post("/api/admin/auth/login", null,
                Map.of("username", other, "password", PASSWORD)).getStatusCode().value()).isEqualTo(401);
    }

    // ---------- 5. 解锁 ----------

    @Test
    void 解除锁定_清空失败计数与锁定时间() {
        String username = "it_usr_lock_" + SUFFIX;
        long id = create(username);
        for (int i = 0; i < 3; i++) {   // max-failed-attempts=3 → 第 3 次锁定
            post("/api/admin/auth/login", null, Map.of("username", username, "password", "WrongPass1"));
        }
        ResponseEntity<String> locked = post("/api/admin/auth/login", null,
                Map.of("username", username, "password", PASSWORD));
        assertThat(locked.getBody()).contains("40106");

        JsonNode before = find(json(get("/api/admin/users", operatorToken)).get("data"), username);
        assertThat(before.get("lockedUntil").isNull()).isFalse();

        assertThat(post("/api/admin/users/" + id + "/unlock", operatorToken, Map.of()).getStatusCode().value()).isEqualTo(200);

        JsonNode after = find(json(get("/api/admin/users", operatorToken)).get("data"), username);
        assertThat(after.get("lockedUntil").isNull()).isTrue();
        assertThat(after.get("failedAttempts").asInt()).isZero();
        assertThat(post("/api/admin/auth/login", null,
                Map.of("username", username, "password", PASSWORD)).getStatusCode().value()).isEqualTo(200);
    }

    // ---------- 6. 删除 ----------

    @Test
    void 删除账号_列表消失且其令牌立即失效() {
        String username = "it_usr_del_" + SUFFIX;
        long id = create(username);
        String victimToken = login(username, PASSWORD);

        assertThat(delete("/api/admin/users/" + id, operatorToken).getStatusCode().value()).isEqualTo(200);

        assertThat(find(json(get("/api/admin/users", operatorToken)).get("data"), username)).isNull();
        assertThat(get("/api/admin/auth/me", victimToken).getStatusCode().value()).isEqualTo(401);
        Integer sessions = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM admin_session WHERE user_id = ?", Integer.class, id);
        assertThat(sessions).isZero();
    }

    @Test
    void 删除不存在的账号_40405() {
        ResponseEntity<String> resp = delete("/api/admin/users/999999999", operatorToken);

        assertThat(resp.getStatusCode().value()).isEqualTo(404);
        assertThat(resp.getBody()).contains("40405");
    }

    // ---------- 7. 角色（RBAC 第一层） ----------

    @Test
    void VIEWER只读_写操作40302_账号管理40303_但可读且能改自己口令与退出() {
        String viewerName = "it_usr_view_" + SUFFIX;
        long viewerId = createWithRole(viewerName, "VIEWER");
        String viewerToken = login(viewerName, PASSWORD);

        // 读：允许
        assertThat(get("/api/admin/apps", viewerToken).getStatusCode().value()).isEqualTo(200);
        // 写：403 + 40302
        ResponseEntity<String> write = post("/api/admin/groups", viewerToken,
                Map.of("appId", "fastmoss", "name", "只读试写" + SUFFIX, "sortOrder", 99));
        assertThat(write.getStatusCode().value()).isEqualTo(403);
        assertThat(write.getBody()).contains("40302").contains("只读");
        // 账号管理：403 + 40303（连入口都不可达）
        ResponseEntity<String> users = get("/api/admin/users", viewerToken);
        assertThat(users.getStatusCode().value()).isEqualTo(403);
        assertThat(users.getBody()).contains("40303");
        // 个人账号操作（改自己口令 / 退出）任何角色都允许
        assertThat(post("/api/admin/auth/password", viewerToken,
                Map.of("oldPassword", PASSWORD, "newPassword", "NewPassw0rd")).getStatusCode().value()).isEqualTo(200);
        assertThat(post("/api/admin/auth/logout", viewerToken, Map.of()).getStatusCode().value()).isEqualTo(200);
        assertThat(roleOf(viewerId)).isEqualTo("VIEWER");
    }

    @Test
    void ADMIN可管理低等级账号_但不能删账号或改角色或动OWNER() {
        String adminName = "it_usr_adm_" + SUFFIX;
        long adminId = createWithRole(adminName, "ADMIN");
        String adminToken = login(adminName, PASSWORD);

        // 可列表、可建 VIEWER、可停用/重置/启用
        assertThat(get("/api/admin/users", adminToken).getStatusCode().value()).isEqualTo(200);
        String victim = "it_usr_v_" + SUFFIX;
        long victimId = createWithRole(victim, "VIEWER");
        assertThat(put("/api/admin/users/" + victimId, adminToken, Map.of("status", "DISABLED")).getStatusCode().value())
                .isEqualTo(200);
        assertThat(post("/api/admin/users/" + victimId + "/password", adminToken,
                Map.of("newPassword", "NewPassw0rd")).getStatusCode().value()).isEqualTo(200);
        assertThat(put("/api/admin/users/" + victimId, adminToken, Map.of("status", "ENABLED")).getStatusCode().value())
                .isEqualTo(200);

        // 不能提权建号（只能建 VIEWER）
        ResponseEntity<String> escalate = post("/api/admin/users", adminToken,
                Map.of("username", "it_usr_esc_" + SUFFIX, "password", PASSWORD, "role", "ADMIN"));
        assertThat(escalate.getStatusCode().value()).isEqualTo(403);
        assertThat(escalate.getBody()).contains("40303");

        // 不能删除账号
        ResponseEntity<String> del = delete("/api/admin/users/" + victimId, adminToken);
        assertThat(del.getStatusCode().value()).isEqualTo(403);
        assertThat(del.getBody()).contains("删除账号需要 OWNER");

        // 不能变更角色
        assertThat(put("/api/admin/users/" + victimId, adminToken, Map.of("role", "ADMIN")).getStatusCode().value())
                .isEqualTo(403);

        // 不能操作 OWNER（操作者 operator 是 OWNER）
        ResponseEntity<String> touchOwner = put("/api/admin/users/" + operatorId, adminToken, Map.of("status", "DISABLED"));
        assertThat(touchOwner.getStatusCode().value()).isEqualTo(403);
        assertThat(touchOwner.getBody()).contains("40303");
        assertThat(roleOf(adminId)).isEqualTo("ADMIN");
    }

    @Test
    void OWNER可变更角色_变更后旧会话立即失效_但不能改自己角色() {
        String target = "it_usr_promo_" + SUFFIX;
        long targetId = createWithRole(target, "VIEWER");
        String targetToken = login(target, PASSWORD);
        assertThat(get("/api/admin/apps", targetToken).getStatusCode().value()).isEqualTo(200);

        assertThat(put("/api/admin/users/" + targetId, operatorToken, Map.of("role", "ADMIN")).getStatusCode().value())
                .isEqualTo(200);
        assertThat(roleOf(targetId)).isEqualTo("ADMIN");
        // 角色变更 → 吊销既有会话（避免旧权限残留），重新登录即拿到新权限
        assertThat(get("/api/admin/auth/me", targetToken).getStatusCode().value()).isEqualTo(401);
        String newToken = login(target, PASSWORD);
        assertThat(get("/api/admin/users", newToken).getStatusCode().value()).isEqualTo(200);

        // 不能改自己的角色
        ResponseEntity<String> self = put("/api/admin/users/" + operatorId, operatorToken, Map.of("role", "ADMIN"));
        assertThat(self.getStatusCode().value()).isEqualTo(400);
        assertThat(self.getBody()).contains("不能修改自己的角色");
    }

    @Test
    void 非法角色被拒40001() {
        long targetId = createWithRole("it_usr_badrole_" + SUFFIX, "VIEWER");

        ResponseEntity<String> resp = put("/api/admin/users/" + targetId, operatorToken, Map.of("role", "SUPER"));

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(resp.getBody()).contains("角色只能是");
    }

    // ---------- 工具 ----------

    private String register(String username, String displayName) {
        createdUsers.add(username);
        ResponseEntity<String> resp = post("/api/admin/auth/register", null,
                Map.of("username", username, "password", PASSWORD, "displayName", displayName));
        assertThat(resp.getStatusCode().value()).as("注册失败：%s", resp.getBody()).isEqualTo(200);
        return json(resp).get("data").get("token").asString();
    }

    private long create(String username) {
        return createWithRole(username, "VIEWER");
    }

    /** 建账号并指定角色（role=null → 服务端默认 VIEWER 最小权限） */
    private long createWithRole(String username, String role) {
        createdUsers.add(username);
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("username", username);
        body.put("password", PASSWORD);
        body.put("displayName", "被管理账号");
        if (role != null) {
            body.put("role", role);
        }
        ResponseEntity<String> resp = post("/api/admin/users", operatorToken, body);
        assertThat(resp.getStatusCode().value()).as("建账号失败：%s", resp.getBody()).isEqualTo(200);
        return json(resp).get("data").asLong();
    }

    private String login(String username, String password) {
        ResponseEntity<String> resp = post("/api/admin/auth/login", null,
                Map.of("username", username, "password", password));
        assertThat(resp.getStatusCode().value()).as("登录失败：%s", resp.getBody()).isEqualTo(200);
        return json(resp).get("data").get("token").asString();
    }

    private long idOf(String username) {
        Long id = jdbcTemplate.queryForObject("SELECT id FROM admin_user WHERE username = ?", Long.class, username);
        return id == null ? -1 : id;
    }

    /** 在列表 data 中按用户名找行（找不到返回 null） */
    private JsonNode find(JsonNode rows, String username) {
        for (JsonNode row : rows) {
            if (username.equals(row.get("username").asString())) {
                return row;
            }
        }
        return null;
    }

    private JsonNode json(ResponseEntity<String> resp) {
        return mapper.readTree(resp.getBody());
    }

    private ResponseEntity<String> get(String path, String token) {
        RestClient.RequestHeadersSpec<?> spec = rest.get().uri(url(path));
        return (token == null ? spec : spec.header("Authorization", "Bearer " + token)).retrieve().toEntity(String.class);
    }

    private ResponseEntity<String> post(String path, String token, Map<String, ?> body) {
        RestClient.RequestBodySpec spec = rest.post().uri(url(path)).contentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            spec = spec.header("Authorization", "Bearer " + token);
        }
        return spec.body(new HashMap<>(body)).retrieve().toEntity(String.class);
    }

    private ResponseEntity<String> put(String path, String token, Map<String, ?> body) {
        RestClient.RequestBodySpec spec = rest.put().uri(url(path)).contentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            spec = spec.header("Authorization", "Bearer " + token);
        }
        return spec.body(new HashMap<>(body)).retrieve().toEntity(String.class);
    }

    private ResponseEntity<String> delete(String path, String token) {
        RestClient.RequestHeadersSpec<?> spec = rest.delete().uri(url(path));
        return (token == null ? spec : spec.header("Authorization", "Bearer " + token)).retrieve().toEntity(String.class);
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
