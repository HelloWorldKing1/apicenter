package com.deepx.apicenter.service;

import com.deepx.apicenter.dto.GroupDtos.GroupRequest;
import com.deepx.apicenter.dto.GroupDtos.GroupResponse;
import com.deepx.apicenter.exception.BizException;
import com.deepx.apicenter.model.AppGroupRow;
import com.deepx.apicenter.repository.AppRepository;
import com.deepx.apicenter.repository.GroupRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 分组管理：应用下的组织单元，纯归类/展示，不承载配置（设计 §2.1）。
 * 校验：组必须属于所选应用；应用内组名唯一；组下有接口禁止删除（M1 评审确认点 3）。
 */
@Service
public class GroupService {

    private final GroupRepository groupRepository;
    private final AppRepository appRepository;

    public GroupService(GroupRepository groupRepository, AppRepository appRepository) {
        this.groupRepository = groupRepository;
        this.appRepository = appRepository;
    }

    /** 两级视图：按应用组织的分组列表（可带 appId 过滤） */
    public List<GroupResponse> list(String appId) {
        return groupRepository.findAll(appId).stream().map(GroupResponse::from).toList();
    }

    @Transactional
    public long create(GroupRequest req) {
        if (!appRepository.existsById(req.appId())) {
            throw BizException.appNotFound(req.appId());
        }
        if (groupRepository.existsByName(req.appId(), req.name())) {
            throw BizException.fieldInvalid("该应用下分组名称已存在：" + req.name());
        }
        groupRepository.insert(new AppGroupRow(0, req.appId(), req.name(), req.sortOrder(), null, 0));
        return groupRepository.findAll(req.appId()).stream()
                .filter(g -> g.name().equals(req.name()))
                .findFirst()
                .orElseThrow().id();
    }

    @Transactional
    public void update(long id, GroupRequest req) {
        AppGroupRow row = groupRepository.findById(id).orElseThrow(() -> BizException.fieldInvalid("分组不存在：" + id));
        if (!row.appId().equals(req.appId())) {
            throw BizException.fieldInvalid("不允许变更分组的所属应用");
        }
        if (!row.name().equals(req.name()) && groupRepository.existsByName(req.appId(), req.name())) {
            throw BizException.fieldInvalid("该应用下分组名称已存在：" + req.name());
        }
        groupRepository.update(id, req.name(), req.sortOrder());
    }

    @Transactional
    public void delete(long id) {
        groupRepository.findById(id).orElseThrow(() -> BizException.fieldInvalid("分组不存在：" + id));
        if (groupRepository.countInterfaces(id) > 0) {
            throw BizException.fieldInvalid("分组下存在接口，禁止删除（请先移动或删除组内接口）");
        }
        groupRepository.delete(id);
    }
}
