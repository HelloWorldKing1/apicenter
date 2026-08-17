/**
 * apicenter · Vue3 前端调用后端接口
 *
 * 通过 CDN 引入 Vue3（见 index.html），本文件用 Composition API 组织：
 *   - Flow A 出站：调 POST /api/orders（前端扮演 ERP，自动算 HMAC-SHA256 签名头）
 *   - Flow B 入站：调 POST /callback/{channel}/order-status（前端扮演第三方，自动算签名）
 *   - 对账查询：调 POST /api/orders/query
 *
 * 密钥为 demo 级（与 src/main/resources/application.yaml 对齐）；
 * 生产环境应改为后端下发配置，不在前端硬编码。
 */
(function () {
  'use strict';

  const { createApp, ref, reactive } = Vue;

  // ==================== demo 级配置（对齐 application.yaml） ====================
  const CONFIG = {
    appId: 'erp-app',               // ERP 应用标识（X-App-Id）
    erpSecret: 'erp-secret',        // ERP 渠道签名密钥（application.yaml channels.ERP.signature-secret）
    channelSecrets: {               // 第三方渠道签名密钥（channels.PARTNER_A/B.signature-secret）
      PARTNER_A: 'secret-a',
      PARTNER_B: 'secret-b'
    }
  };

  // ==================== 工具函数 ====================

  /** 生成 32 位 hex traceId（演示用；生产由后端 OTel 生成并透传） */
  function genTraceId() {
    const h = '0123456789abcdef';
    let s = '';
    for (let i = 0; i < 32; i++) s += h[Math.floor(Math.random() * 16)];
    return s;
  }

  /** HMAC-SHA256 十六进制签名（Web Crypto API，仅 localhost/https 安全上下文可用） */
  async function hmacSha256Hex(message, secret) {
    const enc = new TextEncoder();
    const key = await crypto.subtle.importKey(
      'raw', enc.encode(secret), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']
    );
    const sig = await crypto.subtle.sign('HMAC', key, enc.encode(message));
    return Array.from(new Uint8Array(sig)).map(b => b.toString(16).padStart(2, '0')).join('');
  }

  /** 当前时间 HH:MM:SS.mmm，用于日志行 */
  function now() {
    const d = new Date();
    const p = n => String(n).padStart(2, '0');
    return `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}.${String(d.getMilliseconds()).padStart(3, '0')}`;
  }

  /** 向日志数组追加一行：cls ∈ blue/green/amber/red/purple/grey/white */
  function pushLog(logs, cls, tag, text) {
    logs.push({ time: now(), cls, tag, text });
  }

  /** JSON 美化输出（模板中调用；null/undefined 返回空串） */
  function pretty(obj) {
    if (obj === null || obj === undefined) return '';
    try { return JSON.stringify(obj, null, 2); } catch { return String(obj); }
  }

  createApp({
    setup() {
      // ==================== 全局状态 ====================
      const currentTab = ref('flowa');          // 当前 Tab
      const traceId = ref(genTraceId());        // 前端 traceId（贯穿日志）

      // ==================== Tab1 · Flow A 出站 ====================
      const flowA = reactive({
        order: {
          orderId: '', orderType: 'PUSH', orderStatus: 'PAID', totalAmount: '199.00',
          currency: 'CNY', buyerName: '', buyerPhone: '', createdTime: '', remark: '',
          items: [{ skuCode: '', qty: 1, unitPrice: '0.00' }],
          address: { province: '', city: '', detail: '' }
        },
        request: null,     // 请求面板（headers + body）
        response: null,    // 后端 ErpOrderResponse
        error: null,       // 异常信息
        logs: [],          // 日志控制台
        submitting: false
      });

      /** 填充示例订单（与 demo 页样例对齐：199.00 元 = 19900 分） */
      function fillSampleOrder() {
        flowA.order.orderId = 'SO202608170001';
        flowA.order.orderType = 'PUSH';
        flowA.order.orderStatus = 'PAID';
        flowA.order.totalAmount = '199.00';
        flowA.order.currency = 'CNY';
        flowA.order.buyerName = '张三';
        flowA.order.buyerPhone = '13812341234';
        flowA.order.createdTime = '2026-08-17T10:30';
        flowA.order.remark = '618 大促订单';
        flowA.order.items = [
          { skuCode: 'SKU-001', qty: 1, unitPrice: '99.00' },
          { skuCode: 'SKU-002', qty: 1, unitPrice: '100.00' }
        ];
        flowA.order.address = { province: '浙江', city: '杭州', detail: '西湖区 xxxx' };
        flowA.request = null; flowA.response = null; flowA.error = null; flowA.logs = [];
      }

      function addItem() { flowA.order.items.push({ skuCode: '', qty: 1, unitPrice: '0.00' }); }
      function removeItem(i) { flowA.order.items.splice(i, 1); }

      /** 表单 → OrderDto JSON（orderId/orderType/... 与后端 record 字段一一对应） */
      function buildOrderPayload() {
        const o = flowA.order;
        return {
          orderId: o.orderId,
          orderType: o.orderType,
          orderStatus: o.orderStatus,
          totalAmount: parseFloat(o.totalAmount) || 0,
          currency: o.currency,
          buyerName: o.buyerName,
          buyerPhone: o.buyerPhone,
          createdTime: o.createdTime || null,   // datetime-local 值即 ISO 本地时间
          items: o.items.map(it => {
            const unit = parseFloat(it.unitPrice) || 0;
            const qty = parseInt(it.qty, 10) || 0;
            return { skuCode: it.skuCode, qty, unitPrice: unit, amount: +(unit * qty).toFixed(2) };
          }),
          shippingAddress: o.address
            ? { province: o.address.province, city: o.address.city, detail: o.address.detail }
            : null,
          remark: o.remark
        };
      }

      /** Flow A：推送订单 → POST /api/orders（带签名头） */
      async function pushOrder() {
        if (flowA.submitting) return;
        flowA.submitting = true;
        flowA.response = null; flowA.error = null; flowA.logs = [];
        try {
          const timestamp = Math.floor(Date.now() / 1000).toString();
          const payload = buildOrderPayload();
          // 签名：HMAC-SHA256(appId + timestamp + orderId, erp-secret) —— 与 OrderController 校验规则一致
          const signature = await hmacSha256Hex(CONFIG.appId + timestamp + payload.orderId, CONFIG.erpSecret);

          flowA.request = {
            headers: { 'X-App-Id': CONFIG.appId, 'X-Timestamp': timestamp, 'X-Signature': signature },
            body: JSON.stringify(payload, null, 2)
          };
          pushLog(flowA.logs, 'blue', 'erp→ac',
            `POST /api/orders  X-App-Id=${CONFIG.appId} X-Timestamp=${timestamp} X-Signature=${signature.slice(0, 8)}…`);

          const resp = await fetch('/api/orders', {
            method: 'POST',
            headers: {
              'Content-Type': 'application/json',
              'X-App-Id': CONFIG.appId,
              'X-Timestamp': timestamp,
              'X-Signature': signature
            },
            body: JSON.stringify(payload)
          });
          const data = await resp.json().catch(() => null);
          if (resp.ok) {
            flowA.response = data;
            pushLog(flowA.logs, 'green', 'ac→erp', `HTTP ${resp.status} → ${JSON.stringify(data)}`);
          } else {
            flowA.error = { status: resp.status, body: data };
            pushLog(flowA.logs, 'red', 'ac→erp', `HTTP ${resp.status} → ${JSON.stringify(data)}`);
          }
        } catch (e) {
          flowA.error = { message: e.message };
          pushLog(flowA.logs, 'red', 'fetch', `请求失败：${e.message}（后端是否已启动？）`);
        } finally {
          flowA.submitting = false;
        }
      }

      // ==================== Tab2 · Flow B 入站回调 ====================
      const flowB = reactive({
        channel: 'PARTNER_A',
        event: 'order.status.changed',
        orderNo: 'SO202608170001',
        status: 'SHIPPED',
        response: null,
        error: null,
        logs: [],
        submitting: false
      });

      /** Flow B：模拟第三方回调 → POST /callback/{channel}/order-status（带签名头） */
      async function sendCallback() {
        if (flowB.submitting) return;
        flowB.submitting = true;
        flowB.response = null; flowB.error = null; flowB.logs = [];
        try {
          const timestamp = Math.floor(Date.now() / 1000).toString();
          const payload = { event: flowB.event, orderNo: flowB.orderNo, status: flowB.status };
          // 签名：HMAC-SHA256(timestamp + orderNo, 渠道 secret) —— 与 CallbackController 校验规则一致
          const signature = await hmacSha256Hex(timestamp + payload.orderNo, CONFIG.channelSecrets[flowB.channel]);
          const url = `/callback/${flowB.channel}/order-status`;

          pushLog(flowB.logs, 'blue', 'pa→ac',
            `POST ${url}  X-Timestamp=${timestamp} X-Partner-Signature=${signature.slice(0, 8)}…`);

          const resp = await fetch(url, {
            method: 'POST',
            headers: {
              'Content-Type': 'application/json',
              'X-Partner-Signature': signature,
              'X-Timestamp': timestamp
            },
            body: JSON.stringify(payload)
          });
          const data = await resp.json().catch(() => null);
          flowB.response = data;
          pushLog(flowB.logs, resp.ok ? 'green' : 'red', 'ac→pa', `HTTP ${resp.status} → ${JSON.stringify(data)}`);
        } catch (e) {
          flowB.error = { message: e.message };
          pushLog(flowB.logs, 'red', 'fetch', `请求失败：${e.message}（后端是否已启动？）`);
        } finally {
          flowB.submitting = false;
        }
      }

      // ==================== Tab4 · 对账查询 ====================
      const query = reactive({
        orderId: 'SO202608170001',
        response: null,
        error: null,
        logs: [],
        submitting: false
      });

      /** 对账查询 → POST /api/orders/query（示例未鉴权） */
      async function queryOrder() {
        if (query.submitting) return;
        query.submitting = true;
        query.response = null; query.error = null; query.logs = [];
        try {
          const payload = {
            orderId: query.orderId, orderType: 'PULL', orderStatus: 'NEW', totalAmount: 0,
            currency: 'CNY', buyerName: '', buyerPhone: '', createdTime: null,
            items: [], shippingAddress: null, remark: ''
          };
          pushLog(query.logs, 'blue', 'erp→ac', `POST /api/orders/query  {orderId: ${query.orderId}}`);
          const resp = await fetch('/api/orders/query', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload)
          });
          const data = await resp.json().catch(() => null);
          query.response = data;
          pushLog(query.logs, resp.ok ? 'green' : 'red', 'ac→erp', `HTTP ${resp.status} → ${JSON.stringify(data)}`);
        } catch (e) {
          query.error = { message: e.message };
          pushLog(query.logs, 'red', 'fetch', `请求失败：${e.message}（后端是否已启动？）`);
        } finally {
          query.submitting = false;
        }
      }

      // ==================== 顶部栏 ====================
      function resetTrace() { traceId.value = genTraceId(); }
      function copyTrace() {
        if (navigator.clipboard) navigator.clipboard.writeText(traceId.value);
      }

      /** 渠道签名密钥（模板中展示用） */
      function secretOf(channel) { return CONFIG.channelSecrets[channel]; }

      // 默认填充一次示例订单，方便直接演示
      fillSampleOrder();

      return {
        currentTab, traceId,
        flowA, flowB, query,
        // 工具
        pretty, now,
        // 顶部栏 / 工具
        resetTrace, copyTrace, secretOf,
        // Tab 切换
        goto(tab) { currentTab.value = tab; },
        // Flow A
        fillSampleOrder, addItem, removeItem, pushOrder,
        // Flow B
        sendCallback,
        // 对账
        queryOrder
      };
    }
  }).mount('#app');
})();
