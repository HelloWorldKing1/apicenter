package com.deepx.apicenter.engine;

import com.deepx.apicenter.adapter.protocol.XmlProtocolAdapter;
import com.deepx.apicenter.model.AppRow;
import com.deepx.apicenter.model.InterfaceRow;
import com.deepx.apicenter.model.InterfaceRow.FieldDefRow;
import com.deepx.apicenter.repository.AppRepository;
import com.deepx.apicenter.repository.InterfaceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AckRenderer 单测（D-M3-3）：字段名可配值固定 / sort_order 排序（乱序与不连续）/
 * 无声明兜底信封 / 渲染格式按 protocol_in（JSON / XML）。
 */
class AckRendererTest {

    private final InterfaceRepository interfaceRepository = mock(InterfaceRepository.class);
    private final AppRepository appRepository = mock(AppRepository.class);
    private final AckRenderer renderer = new AckRenderer(interfaceRepository, appRepository,
            new XmlProtocolAdapter(), new ObjectMapper());

    private InterfaceRow iface(String protocolIn) {
        return new InterfaceRow(7L, "IF-CB-01", "回调演示", "INBOUND", "POST",
                "/cb", protocolIn, "JSON", "M3DEMO", 1L,
                null, "http://cb", "PUBLISHED", 1,
                3000, 4, null, LocalDateTime.now(), LocalDateTime.now(), null, null);
    }

    @Test
    void 字段名可配值固定JSON() {
        // 乱序 + 不连续 sort_order：按 sort_order 排序后第 1 个 = code、第 2 个 = message
        when(interfaceRepository.findFieldDefs(7L)).thenReturn(List.of(
                new FieldDefRow(3, "ACK", "returnMsg", "string", null, 20),
                new FieldDefRow(1, "ACK", "returnCode", "number", null, 10),
                new FieldDefRow(2, "ACK", "extra", "string", null, 30))); // 第 3 个忽略

        ResponseEntity<byte[]> resp = renderer.render(iface("JSON"), UnifiedModel.emptyObject());

        assertThat(resp.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        String body = new String(resp.getBody(), StandardCharsets.UTF_8);
        assertThat(body).contains("\"returnCode\":0"); // number → 0
        assertThat(body).contains("\"returnMsg\":\"success\"");
        assertThat(body).doesNotContain("extra"); // 其余声明忽略
    }

    @Test
    void 无声明兜底统一信封JSON() {
        when(interfaceRepository.findFieldDefs(7L)).thenReturn(List.of());

        ResponseEntity<byte[]> resp = renderer.render(iface("JSON"), UnifiedModel.emptyObject());

        String body = new String(resp.getBody(), StandardCharsets.UTF_8);
        assertThat(body).contains("\"code\":0");
        assertThat(body).contains("\"msg\":\"ok\"");
        assertThat(body).contains("\"data\":null");
    }

    @Test
    void xml回调渲染xmlAck() {
        when(interfaceRepository.findFieldDefs(7L)).thenReturn(List.of(
                new FieldDefRow(1, "ACK", "returnCode", "number", null, 10),
                new FieldDefRow(2, "ACK", "returnMsg", "string", null, 20)));
        when(appRepository.findById("M3DEMO")).thenReturn(Optional.of(new AppRow(
                "M3DEMO", "演示", null, null, null, null, "http://x", null, null,
                null, null, "ENABLED", null, LocalDateTime.now(), LocalDateTime.now(), 0, 0)));

        ResponseEntity<byte[]> resp = renderer.render(iface("XML"), UnifiedModel.emptyObject());

        assertThat(resp.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_XML);
        String body = new String(resp.getBody(), StandardCharsets.UTF_8);
        assertThat(body).contains("<response>");
        assertThat(body).contains("<returnCode>0</returnCode>");
        assertThat(body).contains("<returnMsg>success</returnMsg>");
    }
}
