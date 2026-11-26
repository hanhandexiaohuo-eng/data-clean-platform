package com.dataclean.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataclean.config.AppMode;
import com.dataclean.entity.CleanLog;
import com.dataclean.entity.CleanRule;
import com.dataclean.entity.Dataset;
import com.dataclean.exception.BusinessException;
import com.dataclean.mapper.CleanLogMapper;
import com.dataclean.mapper.CleanRuleMapper;
import com.dataclean.mapper.DatasetMapper;
import com.dataclean.util.RequestContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 统一资源归属校验。
 *
 * <p>公开模式的可见条件必须从这里复用，禁止各控制器自行拼装：
 * 官方演示数据 + 当前匿名会话数据；其他会话/私有数据统一 404，不泄露是否存在。
 * 私有模式保持第二阶段行为（全部可见）。
 */
@Service
@RequiredArgsConstructor
public class OwnershipService {

    public static final String OWNER_PRIVATE = "private";
    public static final String OWNER_DEMO = "demo";
    public static final String OWNER_ANON = "anon";

    private final AppMode appMode;
    private final DatasetMapper datasetMapper;
    private final CleanRuleMapper cleanRuleMapper;
    private final CleanLogMapper cleanLogMapper;

    public boolean isPublic() {
        return appMode.isPublic();
    }

    public String currentOwnerType() {
        return appMode.isPublic() ? OWNER_ANON : OWNER_PRIVATE;
    }

    public Long currentSessionId() {
        return appMode.isPublic() ? RequestContext.requireSessionId() : null;
    }

    /** 数据集列表可见条件（public：demo 或本会话 anon；private：无限制）。 */
    public LambdaQueryWrapper<Dataset> visibleDatasetWrapper() {
        LambdaQueryWrapper<Dataset> wrapper = new LambdaQueryWrapper<>();
        if (appMode.isPublic()) {
            Long sessionId = RequestContext.requireSessionId();
            wrapper.and(w -> w.eq(Dataset::getOwnerType, OWNER_DEMO)
                    .or(x -> x.eq(Dataset::getOwnerType, OWNER_ANON)
                            .eq(Dataset::getAnonSessionId, sessionId)));
        }
        return wrapper;
    }

    /** 读取数据集：不存在或不可见统一 404。 */
    public Dataset requireDataset(Long id) {
        Dataset dataset = findVisibleDataset(id);
        if (dataset == null) {
            throw new BusinessException(404, "数据集不存在");
        }
        return dataset;
    }

    /**
     * 非抛异常的可见性查询：供看板装配等“允许降级”的场景使用。
     * 不存在或当前会话不可见时返回 null。
     */
    public Dataset findVisibleDataset(Long id) {
        if (id == null) {
            return null;
        }
        Dataset dataset = datasetMapper.selectById(id);
        if (dataset == null) {
            return null;
        }
        if (appMode.isPublic() && !isDatasetVisible(dataset)) {
            return null;
        }
        return dataset;
    }

    /** 演示数据只读：写操作统一 403。 */
    public void requireWritable(Dataset dataset) {
        if (appMode.isPublic() && OWNER_DEMO.equals(dataset.getOwnerType())) {
            throw new BusinessException(403, "官方演示数据为只读，不支持修改、清洗、删除或导出");
        }
    }

    /** 清洗规则归属：public 下只允许本会话创建的规则。 */
    public CleanRule requireRule(Long ruleId) {
        if (ruleId == null) {
            throw new BusinessException(404, "清洗规则不存在");
        }
        CleanRule rule = cleanRuleMapper.selectById(ruleId);
        if (rule == null) {
            throw new BusinessException(404, "清洗规则不存在");
        }
        if (appMode.isPublic()) {
            Long sessionId = RequestContext.requireSessionId();
            if (!sessionId.equals(rule.getAnonSessionId())) {
                throw new BusinessException(404, "清洗规则不存在");
            }
        }
        // 规则所属数据集也必须可见
        requireDataset(rule.getDatasetId());
        return rule;
    }

    /** 清洗日志归属：public 下只允许本会话产生的日志。 */
    public CleanLog requireCleanLog(Long logId) {
        if (logId == null) {
            throw new BusinessException(404, "清洗记录不存在");
        }
        CleanLog cleanLog = cleanLogMapper.selectById(logId);
        if (cleanLog == null) {
            throw new BusinessException(404, "清洗记录不存在");
        }
        if (appMode.isPublic()) {
            Long sessionId = RequestContext.requireSessionId();
            if (!sessionId.equals(cleanLog.getAnonSessionId())) {
                throw new BusinessException(404, "清洗记录不存在");
            }
        }
        requireDataset(cleanLog.getDatasetId());
        return cleanLog;
    }

    /** 供看板批量装配等场景复用：判断数据集对当前会话是否可见。 */
    public boolean isDatasetVisible(Dataset dataset) {
        Long sessionId = RequestContext.requireSessionId();
        if (OWNER_DEMO.equals(dataset.getOwnerType())) {
            return true;
        }
        return OWNER_ANON.equals(dataset.getOwnerType())
                && sessionId.equals(dataset.getAnonSessionId());
    }
}
