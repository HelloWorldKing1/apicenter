package com.deepx.apicenter;

import com.deepx.apicenter.dto.AppDtos.AppRequest;
import com.deepx.apicenter.dto.CredentialDtos.UpdateRequest;
import com.deepx.apicenter.dto.GroupDtos.GroupRequest;
import com.deepx.apicenter.dto.InterfaceDtos.BindingDto;
import com.deepx.apicenter.dto.InterfaceDtos.InterfaceRequest;
import com.deepx.apicenter.engine.AdapterContext;
import com.deepx.apicenter.engine.ChainEngine;
import com.deepx.apicenter.engine.UnifiedModel;
import com.deepx.apicenter.model.AdapterRow;
import com.deepx.apicenter.repository.AdapterRepository;
import com.deepx.apicenter.repository.AppRepository;
import com.deepx.apicenter.repository.InterfaceRepository;
import com.deepx.apicenter.repository.OutboundRequestRepository;
import com.deepx.apicenter.service.AppService;
import com.deepx.apicenter.service.CredentialService;
import com.deepx.apicenter.service.GroupService;
import com.deepx.apicenter.service.InterfaceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 链内阶段前置条件的**不占端口**回归（2026-09-24 真机事故的第二道防线）。
 *
 * ## 为什么需要它（而不是靠 M2 的链路用例）
 * 真机联调时 `CloudSignatureAdapter` 报 500（后为 `40001`）：它要在 `OUTBOUND_AUTH` 阶段按「真实要发的请求」
 * 算 host / canonicalRequest，而**出站 URL 当时还没被设置**（`OutboundEngine` 是"链跑完再补 URL"）。
 * 既有单测全绿是因为 ctx 里的 URL 是**手工塞进去的**（盲区）；M2 的链路级用例虽然能拦（`g9`），但它要
 * WireMock 占 18080 ⇒ 手动验收期间跑不了。本类**只调 `ChainEngine.execute`**（不发任何 HTTP、不绑端口），
 * 因此任何时刻都能跑，把不变量钉死：
 *
 * > **`OUTBOUND_AUTH` 阶段开始前，`ctx.outbound().url()` 必须已经等于 `app.base_url + interface.upstream_path`。**
 *
 * 用**真实的** `CloudSignatureAdapter` 做探针（不引测试替身）：URL 缺失时它会抛 `40001`（天然断言），
 * URL 就绪时它会把 `Authorization` / `X-TC-Timestamp` 写进 `ctx.outbound().headers()` ⇒ 可直接断言签名结果。
 */
@SpringBootTest(properties = {
        // 认证与本类无关；同时避免后台 worker 扫到开发库残留（隔离纪律）
        "app.api-center.auth.enabled=false",
        "app.api-center.retry-worker-fixed-delay-ms=3600000",
        "app.api-center.retry-worker-initial-delay-ms=3600000",
        "app.api-center.alert-worker-fixed-delay-ms=3600000",
        "app.api-center.alert-worker-initial-delay-ms=3600000"
})
class ChainAuthStageIntegrationTest {

    private static final String APP = "PROBE-CLOUD-APP";
    private static final String IFACE_CODE = "PROBE-CLD-IFACE";
    private static final String IFACE_PATH = "/probe/cloud/out";
    private static final String UPSTREAM_PATH = "/shop/v1/creatorList";
    private static final String BASE_URL = "http://probe-upstream.local";
    private static final String SECRET_ID = "AKID-PROBE";
    private static final String AUTH_ADAPTER_ID = "PROBE-901";
    private static final String MESSAGE_ADAPTER_ID = "ADP-201";

    @Autowired
    private ChainEngine chainEngine;
    @Autowired
    private AppService appService;
    @Autowired
    private GroupService groupService;
    @Autowired
    private InterfaceService interfaceService;
    @Autowired
    private CredentialService credentialService;
    @Autowired
    private AdapterRepository adapterRepository;
    @Autowired
    private AppRepository appRepository;
    @Autowired
    private InterfaceRepository interfaceRepository;
    @Autowired
    private OutboundRequestRepository outboundRequestRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanup() {
        outboundRequestRepository.deleteByApp(APP);
        if (appRepository.existsById(APP)) {
            jdbcTemplate.queryForList("SELECT id FROM interface WHERE app_id = ?", Long.class, APP)
                    .forEach(interfaceRepository::deleteCascade);
            appRepository.deleteCascade(APP);
        }
        // 探针适配器行留着无妨（幂等插入、无副作用）
    }

    @Test
    void 出站鉴权阶段开始前_ctx里必须已有出站URL_且签名据此产出() {
        long ifaceId = prepareCloudInterface();

        AdapterContext ctx = chainEngine.execute(ifaceId, UnifiedModel.emptyObject(), "trace-probe",
                "{\"page\":1}".getBytes(StandardCharsets.UTF_8), Map.of("attempt", 1, "preCallDepth", 1));

        // ① 不变量：整块出站规格在 AUTH 之前已就绪
        //   修复前：这里的 url 为 null ⇒ 适配器抛 40001 ⇒ 后面所有断言根本走不到；
        //   修 url 之后 method 仍为 null（链后才设）⇒ 又一处 NPE —— 故断言覆盖**整块规格**，防止只修一半。
        assertThat(ctx.outbound().url()).isEqualTo(BASE_URL + UPSTREAM_PATH);
        assertThat(ctx.outbound().method()).isEqualTo("POST");
        assertThat(ctx.outbound().readTimeoutMs()).isEqualTo(3000);
        assertThat(ctx.outbound().interfaceId()).isEqualTo(ifaceId);
        assertThat(ctx.outbound().appId()).isEqualTo(APP);
        assertThat(ctx.outbound().traceId()).isEqualTo("trace-probe");
        assertThat(ctx.outbound().headers().getFirst("X-Trace-Id")).isEqualTo("trace-probe");
        // ② 签名确实按"真实要发的请求"产出：TC3 规范三件套
        assertThat(ctx.outbound().headers().getFirst("Authorization"))
                .startsWith("TC3-HMAC-SHA256 Credential=" + SECRET_ID + "/")
                .contains("/cvm/tc3_request")
                .contains("Signature=");
        assertThat(ctx.outbound().headers().getFirst("X-TC-Timestamp")).isNotBlank();
        // host 只进**签名**（canonical Request 的第 2 段 / SignedHeaders），不作发送头（由 HTTP 客户端自己加）
        assertThat(ctx.outbound().headers().getFirst("Authorization")).contains("SignedHeaders=").contains("host");
    }

    /** 造一套最小可跑夹具：云签名鉴权适配器 + 应用（复合凭证）+ 分组 + 出站接口（显式绑 AUTH/MESSAGE） */
    private long prepareCloudInterface() {
        // 注意：直接写库（不经 AdapterService）——本用例不校验目录元数据，只为把适配器行喂给链装配
        if (!adapterRepository.existsById(AUTH_ADAPTER_ID)) {
            adapterRepository.insert(new AdapterRow(AUTH_ADAPTER_ID, "探针·云厂商签名", "auth",
                    "CloudSignatureAdapter", true, "1.0",
                    "{\"scheme\":\"TC3-HMAC-SHA256\",\"service\":\"cvm\",\"region\":\"ap-guangzhou\"}", null, null));
        }
        if (!adapterRepository.existsById(MESSAGE_ADAPTER_ID)) {
            adapterRepository.insert(new AdapterRow(MESSAGE_ADAPTER_ID, "探针·信封报文适配", "message",
                    "EnvelopeMessageAdapter", true, "1.0",
                    "{\"envelope\":\"data\",\"codeField\":\"code\",\"successValue\":\"0\"}", null, null));
        }
        if (!appRepository.existsById(APP)) {
            appService.create(new AppRequest(APP, "探针 云签名供应商", null,
                    AUTH_ADAPTER_ID, null, MESSAGE_ADAPTER_ID, BASE_URL, null, null, null, null, "链内 AUTH 前置条件回归"));
            appService.enable(APP);
            credentialService.update(APP, new UpdateRequest("OUTBOUND",
                    "{\"secretId\":\"" + SECRET_ID + "\",\"secretKey\":\"SK-PROBE\"}"));
        }
        long groupId = groupService.create(new GroupRequest(APP, "探针分组", 0));
        long ifaceId = interfaceService.create(new InterfaceRequest(
                IFACE_CODE, "探针 云签名接口", "OUTBOUND", "POST", IFACE_PATH,
                "JSON", "JSON", APP, groupId,
                UPSTREAM_PATH, null, null, 3000, 0, "链内 AUTH 前置条件回归", 1,
                List.of(), List.of(), List.of(), List.of(),
                List.of(new BindingDto("AUTH", AUTH_ADAPTER_ID, null),
                        new BindingDto("MESSAGE", MESSAGE_ADAPTER_ID, null))));
        interfaceService.publish(ifaceId);
        return ifaceId;
    }
}
