package com.deepx.apicenter.seed;

import com.deepx.apicenter.dto.AppDtos.AppRequest;
import com.deepx.apicenter.dto.CredentialDtos.UpdateRequest;
import com.deepx.apicenter.dto.GroupDtos.GroupRequest;
import com.deepx.apicenter.dto.InterfaceDtos.BindingDto;
import com.deepx.apicenter.dto.InterfaceDtos.BodyDto;
import com.deepx.apicenter.dto.InterfaceDtos.FieldDefDto;
import com.deepx.apicenter.dto.InterfaceDtos.InterfaceRequest;
import com.deepx.apicenter.dto.InterfaceDtos.ParamDto;
import com.deepx.apicenter.model.AdapterRow;
import com.deepx.apicenter.repository.AdapterRepository;
import com.deepx.apicenter.repository.AppRepository;
import com.deepx.apicenter.repository.InterfaceRepository;
import com.deepx.apicenter.service.AppService;
import com.deepx.apicenter.service.CredentialService;
import com.deepx.apicenter.service.GroupService;
import com.deepx.apicenter.service.InterfaceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 种子数据初始化（开发计划 M1 任务四）：fastmoss 黄金用例（开发计划 §2.2）为首个种子。
 * 幂等：检测 fastmoss 应用已存在则跳过。凭证经 CryptoService 运行时加密落库
 * （种子用 Java Runner 而非纯 SQL 的原因——密文依赖运行时密钥，M1 评审确认点 4）。
 * 测试 token 为占位值，接真实联调时通过管理面「凭证管理」更新。
 */
@Component
public class SeedDataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedDataInitializer.class);

    /** 黄金用例测试 token（占位值；真实 token 通过管理面凭证管理更新） */
    private static final String FASTBOSS_TEST_TOKEN = "fastmoss-test-token";

    /** 黄金用例请求体模板（开发计划 §2.4） */
    private static final String BODY_TEMPLATE = """
            {
              "filter": { "seller_id": "7494312521977267257" },
              "orderby": [ { "field": "units_sold", "order": "desc" } ],
              "page": 1,
              "pagesize": 1
            }
            """;

    private final boolean seedEnabled;
    private final AppRepository appRepository;
    private final AdapterRepository adapterRepository;
    private final AppService appService;
    private final CredentialService credentialService;
    private final GroupService groupService;
    private final InterfaceService interfaceService;
    private final InterfaceRepository interfaceRepository;
    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    public SeedDataInitializer(@Value("${app.api-center.seed.enabled:true}") boolean seedEnabled,
                               AppRepository appRepository,
                               AdapterRepository adapterRepository,
                               AppService appService,
                               CredentialService credentialService,
                               GroupService groupService,
                               InterfaceService interfaceService,
                               InterfaceRepository interfaceRepository,
                               org.springframework.jdbc.core.JdbcTemplate jdbcTemplate) {
        this.seedEnabled = seedEnabled;
        this.appRepository = appRepository;
        this.adapterRepository = adapterRepository;
        this.appService = appService;
        this.credentialService = credentialService;
        this.groupService = groupService;
        this.interfaceService = interfaceService;
        this.interfaceRepository = interfaceRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!seedEnabled) {
            log.info("种子导入已关闭（app.api-center.seed.enabled=false）");
            return;
        }
        if (seedIntact()) {
            log.info("fastmoss 种子完整，跳过导入（幂等）");
            return;
        }
        if (appRepository.existsById("fastmoss")) {
            // 存在但不完整（如外部导入只含主表、参数子表缺失）→ 清理后重建
            log.warn("fastmoss 种子数据不完整，清理后重建");
            cleanFastmossSeed();
        }
        log.info("开始导入 fastmoss 黄金用例种子…");
        seedAdapters();
        seedApp();
        seedCredential();
        long groupId = seedGroup();
        seedInterface(groupId);
        log.info("fastmoss 黄金用例种子导入完成");
    }

    /**
     * 种子完整性校验：应用 + 凭证 + 接口主表 + 参数子表（黄金用例 8 条参数）。
     * 存在但缺参数/接口 → 视为不完整，走清理重建（seed 只管理 fastmoss 自身数据）。
     */
    private boolean seedIntact() {
        if (!appRepository.existsById("fastmoss")) {
            return false;
        }
        if (credentialService.listViews("fastmoss").stream()
                .noneMatch(v -> "OUTBOUND".equals(v.kind()) && "ACTIVE".equals(v.status()))) {
            return false;
        }
        return interfaceService.list("fastmoss", null).stream()
                .filter(i -> "IF-FM-001".equals(i.code()))
                .anyMatch(i -> i.params().size() == 8);
    }

    /** 仅清理 fastmoss 种子数据（接口及 6 子表、分组、凭证、应用），不触碰其他配置 */
    private void cleanFastmossSeed() {
        jdbcTemplate.queryForList("SELECT id FROM interface WHERE app_id = ?", Long.class, "fastmoss")
                .forEach(interfaceRepository::deleteCascade);
        appRepository.deleteCascade("fastmoss");
    }

    // ---------- 各实体种子（走业务 service 保证校验一致） ----------

    private void seedAdapters() {
        insertAdapter("ADP-000", "无鉴权", "auth", "NoopAuthAdapter", "{}");
        insertAdapter("ADP-100", "直通报文", "message", "NoopMessageAdapter", "{}");
        insertAdapter("ADP-101", "Bearer Token", "auth", "BearerTokenAuthAdapter",
                "{\"headerName\":\"Authorization\",\"prefix\":\"Bearer\"}");
        insertAdapter("ADP-201", "信封报文适配", "message", "EnvelopeMessageAdapter",
                "{\"envelope\":\"data\",\"codeField\":\"code\",\"successValue\":\"0\",\"messageField\":\"message\"}");
    }

    private void insertAdapter(String id, String name, String type, String impl, String params) {
        if (adapterRepository.existsById(id)) {
            return;
        }
        adapterRepository.insert(new AdapterRow(id, name, type, impl, true, "1.0", params, null, null));
    }

    private void seedApp() {
        appService.create(new AppRequest(
                "fastmoss", "FastMoss", null,
                "ADP-101", // 出站供应商签名 = Bearer Token
                null,      // 回调验签（黄金用例无入站回调）
                "ADP-201", // 默认报文适配器 = 信封适配
                "https://openapi.fastmoss.com",
                null, null, null, null, "fastmoss 黄金用例供应商（开发计划 §2.2）"));
        appService.enable("fastmoss");
    }

    private void seedCredential() {
        // 黄金用例 OUTBOUND 凭证 = Bearer token（测试占位值，运行时加密落库）
        credentialService.update("fastmoss", new UpdateRequest("OUTBOUND", FASTBOSS_TEST_TOKEN));
    }

    private long seedGroup() {
        return groupService.create(new GroupRequest("fastmoss", "默认分组", 0));
    }

    private void seedInterface(long groupId) {
        List<ParamDto> inParams = List.of(
                new ParamDto("IN", "filter.seller_id", "string", true, "7494312521977267257", 1),
                new ParamDto("IN", "orderby", "array", false, null, 2),
                new ParamDto("IN", "page", "number", false, "1", 3),
                new ParamDto("IN", "pagesize", "number", false, "1", 4));
        // 出站侧与入站侧一致（黄金用例 = 纯透传，字段映射为空）
        List<ParamDto> outParams = inParams.stream()
                .map(p -> new ParamDto("OUT", p.name(), p.type(), p.required(), p.sample(), p.sortOrder()))
                .toList();
        long interfaceId = interfaceService.create(new InterfaceRequest(
                "IF-FM-001", "FastMoss 达人列表", "OUTBOUND", "POST", "/fastmoss/creatorList",
                "JSON", "JSON", "fastmoss", groupId,
                "/shop/v1/creatorList", null,
                null, 3000, 4, "fastmoss 黄金用例出站中转接口（开发计划 §2.2）",
                1,
                java.util.stream.Stream.concat(inParams.stream(), outParams.stream()).toList(),
                List.of(new BodyDto("IN", "json", BODY_TEMPLATE, null)),
                List.of(), // 字段映射：空 = 整体透传（M0-02 D3）
                List.of(
                        new FieldDefDto("RESP", "total", "number", "结果总数", 1),
                        new FieldDefDto("RESP", "list", "array", "达人列表", 2)),
                List.of(
                        new BindingDto("AUTH", "ADP-101", null),
                        new BindingDto("MESSAGE", "ADP-201", null))));
        interfaceService.publish(interfaceId);
    }
}
