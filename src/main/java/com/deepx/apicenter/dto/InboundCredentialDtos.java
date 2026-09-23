package com.deepx.apicenter.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 入站鉴权**凭证池** DTO（2026-09-24 v1.2 / D-CA-18）。
 *
 * <p>与 `CredentialDtos`（应用凭证 / 调用方档案凭证）的区别：这里显式带**属主**（`ownerType` + `ownerId`），
 * 因为凭证池一张表装三级属主：`PLATFORM`（平台共享，`ownerId` 必须为空）/
 * `INTERFACE`（接口专属，`ownerId` = 接口数字 id）/ `CLIENT`（档案专属，`ownerId` = client_id）。
 *
 * <p>`label` = 「发给谁 / 何时」的人工备注 —— 共享凭证模型下唯一可归因、可吊销的抓手
 * （落入审计 `credential_label`）。
 */
public final class InboundCredentialDtos {

    private InboundCredentialDtos() {
    }

    /** 新增 / 轮换（**平台生成随机值**；明文仅在响应回显一次） */
    public record PoolPrepareRequest(@NotBlank(message = "属主类型不能为空") String ownerType,
                                     String ownerId,
                                     @NotBlank(message = "凭证类型不能为空") String kind,
                                     String label) {
    }

    /** 录入（**第三方给的密钥**：一步入 ACTIVE，旧 ACTIVE 转入 ROTATING 并存 24h） */
    public record PoolUpdateRequest(@NotBlank(message = "属主类型不能为空") String ownerType,
                                    String ownerId,
                                    @NotBlank(message = "凭证类型不能为空") String kind,
                                    @NotBlank(message = "凭证内容不能为空") String credential,
                                    String label) {
    }

    /** 仅修改备注（不改凭证值，不触发轮换） */
    public record PoolLabelRequest(String label) {
    }
}
