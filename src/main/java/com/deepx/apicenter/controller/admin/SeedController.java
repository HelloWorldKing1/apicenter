package com.deepx.apicenter.controller.admin;

import com.deepx.apicenter.dto.ApiResult;
import com.deepx.apicenter.seed.SeedDataInitializer;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 种子数据手动导入端点（2026-09-08）：seed 启动自动导入默认关闭（application.yaml seed.enabled=false），
 * 需要 fastmoss 黄金用例演示基线时显式调用本端点（幂等 / 增量补齐 / 残缺重建语义与启动自动导入一致）。
 * ⚠ 仅用于开发 / 测试库维护演示数据，生产不应暴露。
 */
@RestController
@RequestMapping("/api/admin/seed")
public class SeedController {

    private final SeedDataInitializer seedDataInitializer;

    public SeedController(SeedDataInitializer seedDataInitializer) {
        this.seedDataInitializer = seedDataInitializer;
    }

    /** 手动导入/补齐 fastmoss 种子（幂等；已完整则跳过） */
    @PostMapping("/import")
    public ApiResult<Void> importSeed() {
        seedDataInitializer.importSeed();
        return ApiResult.ok();
    }
}
