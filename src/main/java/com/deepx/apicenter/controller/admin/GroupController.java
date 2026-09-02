package com.deepx.apicenter.controller.admin;

import com.deepx.apicenter.dto.ApiResult;
import com.deepx.apicenter.dto.GroupDtos.GroupRequest;
import com.deepx.apicenter.dto.GroupDtos.GroupResponse;
import com.deepx.apicenter.service.GroupService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 分组管理：应用 → 分组两级视图；纯归类/展示，不承载配置。
 */
@RestController
@RequestMapping("/api/admin/groups")
public class GroupController {

    private final GroupService groupService;

    public GroupController(GroupService groupService) {
        this.groupService = groupService;
    }

    @GetMapping
    public ApiResult<List<GroupResponse>> list(@RequestParam(required = false) String appId) {
        return ApiResult.ok(groupService.list(appId));
    }

    @PostMapping
    public ApiResult<Void> create(@Valid @RequestBody GroupRequest req) {
        groupService.create(req);
        return ApiResult.ok();
    }

    @PutMapping("/{id}")
    public ApiResult<Void> update(@PathVariable long id, @Valid @RequestBody GroupRequest req) {
        groupService.update(id, req);
        return ApiResult.ok();
    }

    @DeleteMapping("/{id}")
    public ApiResult<Void> delete(@PathVariable long id) {
        groupService.delete(id);
        return ApiResult.ok();
    }
}
