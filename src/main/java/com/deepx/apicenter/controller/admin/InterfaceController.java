package com.deepx.apicenter.controller.admin;

import com.deepx.apicenter.adapter.auth.HmacSigner;
import com.deepx.apicenter.dto.ApiResult;
import com.deepx.apicenter.dto.InterfaceDtos.InterfaceRequest;
import com.deepx.apicenter.dto.InterfaceDtos.InterfaceResponse;
import com.deepx.apicenter.engine.OutboundEngine;
import com.deepx.apicenter.exception.BizException;
import com.deepx.apicenter.model.CredentialRow;
import com.deepx.apicenter.model.InboundDeliveryRow;
import com.deepx.apicenter.model.InterfaceRow;
import com.deepx.apicenter.repository.CredentialRepository;
import com.deepx.apicenter.repository.InboundDeliveryRepository;
import com.deepx.apicenter.repository.InterfaceRepository;
import com.deepx.apicenter.service.CryptoService;
import com.deepx.apicenter.service.InterfaceService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * 接口管理：完整定义模型（主表 + 参数 / Body / 字段映射 / 响应·ack / 绑定子表）；
 * 更新为全量替换 + version 乐观锁；生命周期 草稿 → 发布 → 下线；
 * 另提供「测试接口」（出站链路调试）与「模拟回调」（入站链路调试，M3）。
 */
@RestController
@RequestMapping("/api/admin/interfaces")
public class InterfaceController {

    private final InterfaceService interfaceService;
    private final InterfaceRepository interfaceRepository;
    private final OutboundEngine outboundEngine;
    private final CredentialRepository credentialRepository;
    private final InboundDeliveryRepository inboundDeliveryRepository;
    private final CryptoService cryptoService;
    private final RestClient restClient;

    public InterfaceController(InterfaceService interfaceService,
                               InterfaceRepository interfaceRepository,
                               OutboundEngine outboundEngine,
                               CredentialRepository credentialRepository,
                               InboundDeliveryRepository inboundDeliveryRepository,
                               CryptoService cryptoService,
                               RestClient restClient) {
        this.interfaceService = interfaceService;
        this.interfaceRepository = interfaceRepository;
        this.outboundEngine = outboundEngine;
        this.credentialRepository = credentialRepository;
        this.inboundDeliveryRepository = inboundDeliveryRepository;
        this.cryptoService = cryptoService;
        this.restClient = restClient;
    }

    @GetMapping
    public ApiResult<List<InterfaceResponse>> list(@RequestParam(required = false) String appId,
                                                   @RequestParam(required = false) Long groupId,
                                                   @RequestParam(required = false) String ifType,
                                                   @RequestParam(required = false) String status,
                                                   @RequestParam(required = false) String keyword) {
        return ApiResult.ok(interfaceService.list(appId, groupId, ifType, status, keyword));
    }

    @GetMapping("/{id}")
    public ApiResult<InterfaceResponse> detail(@PathVariable long id) {
        return ApiResult.ok(interfaceService.detail(id));
    }

    @PostMapping
    public ApiResult<Void> create(@Valid @RequestBody InterfaceRequest req) {
        interfaceService.create(req);
        return ApiResult.ok();
    }

    @PutMapping("/{id}")
    public ApiResult<Void> update(@PathVariable long id, @Valid @RequestBody InterfaceRequest req) {
        interfaceService.update(id, req);
        return ApiResult.ok();
    }

    /**
     * 测试接口（管理面调试）：以给定请求体真实走一遍出站链路（链执行 + 状态机），
     * 与接入层路由的区别：不做 PUBLISHED / 方法校验（草稿态也可测）、不要求经平台路径。
     * 失败分支（死信 / 补偿 / UNKNOWN）由全局异常处理返回对应错误信封（msg 含死信编号等诊断信息）。
     */
    @PostMapping("/{id}/test")
    public ApiResult<?> test(@PathVariable long id, @RequestBody(required = false) byte[] body) {
        InterfaceRow iface = interfaceRepository.findById(id)
                .orElseThrow(() -> BizException.ifaceNotFound(id));
        // OUTBOUND 门控（评审遗漏 4 修复）：INBOUND 接口走「模拟回调」，避免误走出站链路
        if (!"OUTBOUND".equals(iface.ifType())) {
            throw BizException.fieldInvalid("测试接口仅适用于出站中转接口（INBOUND 请用「模拟回调」）");
        }
        // byte[] 原样接收（ByteArrayHttpMessageConverter 对任何 Content-Type 均按原始字节读取），
        // 避免 String + application/json 的字符串字面量解析歧义、也避免 form-urlencoded 的编码污染。
        byte[] raw = body == null || body.length == 0
                ? "{}".getBytes(StandardCharsets.UTF_8)
                : body;
        try {
            return outboundEngine.execute(iface, raw,
                    "TEST-" + UUID.randomUUID().toString().substring(0, 8), null);
        } finally {
            // 调试端点直调引擎不经网关：按 CallLogContext 清理契约自行清理
            // （有意不落 IN 条 call_log——避免调试流量污染成功率口径；OUT 条经 Invoker 切面照常落库）
            com.deepx.apicenter.aspect.CallLogContext.clear();
        }
    }

    /**
     * 模拟回调（M3 手动验收工具，仅 INBOUND）：按接口 CALLBACK 凭证（ACTIVE）正确签名后
     * 自调平台回调端点（真实网关路径，需已发布），返回供应商视角 ack + 送达状态（按 trace_id 回查）。
     * 签名算法默认 HMAC-SHA256（D-M3-2 约定），报文预填由前端按接口入站参数生成、可编辑。
     */
    @PostMapping("/{id}/test-callback")
    public ApiResult<TestCallbackResult> testCallback(@PathVariable long id,
                                                      @RequestBody(required = false) byte[] body,
                                                      HttpServletRequest request) {
        InterfaceRow iface = interfaceRepository.findById(id)
                .orElseThrow(() -> BizException.ifaceNotFound(id));
        if (!"INBOUND".equals(iface.ifType())) {
            throw BizException.fieldInvalid("模拟回调仅适用于入站回调接口");
        }
        if (!"PUBLISHED".equals(iface.status())) {
            throw BizException.fieldInvalid("接口未发布：模拟回调走真实网关路径需已发布");
        }
        String trace = UUID.randomUUID().toString().replace("-", "");
        byte[] raw = body == null || body.length == 0 ? "{}".getBytes(StandardCharsets.UTF_8) : body;
        String secret = credentialRepository.findActive(iface.appId(), "CALLBACK")
                .map(CredentialRow::credential)
                .map(cryptoService::decrypt)
                .orElseThrow(() -> BizException.fieldInvalid("应用未配置回调验签凭证（CALLBACK）"));
        String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
        String signature = HmacSigner.sign("HMAC-SHA256", secret, timestamp, raw);

        // 自调本服务网关：端口取本次请求的本地监听端口（RANDOM_PORT 测试下 server.port=0，不可用作 URL）
        ResponseEntity<byte[]> resp = restClient.post()
                .uri("http://localhost:" + request.getLocalPort() + iface.path())
                .header("Content-Type", "XML".equals(iface.protocolIn()) ? "application/xml" : "application/json")
                .header("X-Timestamp", timestamp)
                .header("X-Partner-Signature", signature)
                .header("X-Trace-Id", trace)
                .body(raw)
                .retrieve()
                .toEntity(byte[].class);

        String deliveryStatus = inboundDeliveryRepository.findByTrace(trace).stream()
                .findFirst()
                .map(InboundDeliveryRow::deliveryStatus)
                .orElse(null);
        return ApiResult.ok(new TestCallbackResult(trace, resp.getStatusCode().value(),
                resp.getHeaders().getFirst("Content-Type"),
                resp.getBody() == null ? null : new String(resp.getBody(), StandardCharsets.UTF_8),
                deliveryStatus));
    }

    /** 模拟回调结果：供应商视角 ack + 送达状态（管理面展示） */
    public record TestCallbackResult(String traceId, int ackStatus, String ackContentType,
                                     String ackBody, String deliveryStatus) {
    }

    @PostMapping("/{id}/publish")
    public ApiResult<Void> publish(@PathVariable long id) {
        interfaceService.publish(id);
        return ApiResult.ok();
    }

    @PostMapping("/{id}/offline")
    public ApiResult<Void> offline(@PathVariable long id) {
        interfaceService.offline(id);
        return ApiResult.ok();
    }

    @DeleteMapping("/{id}")
    public ApiResult<Void> delete(@PathVariable long id) {
        interfaceService.delete(id);
        return ApiResult.ok();
    }
}
