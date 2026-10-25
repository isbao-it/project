package com.pg.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.pg.domain.Pwork;
import com.pg.domain.WorkExif;
import com.pg.common.BizException;
import com.pg.common.ErrorCode;
import com.pg.mapper.PworkMapper;
import com.pg.mapper.WorkExifMapper;
import com.pg.service.MsgSecCheck;
import com.pg.service.PworkService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class PworkServiceImpl extends ServiceImpl<PworkMapper, Pwork> implements PworkService {
    private static final int TITLE_MAX = 100; // 冻结§1.3：Unicode 字符数。
    private static final int TEXT_MAX = 255;

    private final PworkMapper pworkMapper;
    private final WorkExifMapper workExifMapper;
    private final MsgSecCheck msgSecCheck;

    /**
     * 创建组件并固定依赖，由 Spring 完成构造器注入。
     * @param pworkMapper 作品数据访问组件
     * @param workExifMapper 六项参数数据访问
     * @param msgSecCheck 文本安全检查，拒绝及故障不能降级放行
     */
    public PworkServiceImpl(PworkMapper pworkMapper, WorkExifMapper workExifMapper, MsgSecCheck msgSecCheck) {
        this.pworkMapper = pworkMapper;
        this.workExifMapper = workExifMapper;
        this.msgSecCheck = msgSecCheck;
    }

    @Override
    public List<Pwork> queryPwork(Map<String, Object> param) {
        return pworkMapper.queryPwork(param);
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public Map<String, Object> publish(String uid, Map<String, Object> body) {
        Map<String, Object> work = validated(body);
        Map<String, Object> exif = body.containsKey("exif") ? WorkExif.validated(body.get("exif")) : null;
        checkText(uid, work);
        String pid = UUID.randomUUID().toString();
        // 入口仅取四项内容及六项参数；uid、状态、计数始终由服务端决定。
        work.put("pid", pid);
        work.put("uid", uid);
        pworkMapper.insertPublished(work);
        if (exif != null) {
            workExifMapper.insert(pid, exif);
        }
        // 与主表共用事务，编码占位失败时连同作品、EXIF 一并回滚。
        pworkMapper.insertPendingEmbedding(pid);
        return pending(pid);
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public Map<String, Object> update(String uid, String pid, Map<String, Object> body) {
        Map<String, Object> owner = owner(pid);
        if (!uid.equals(owner.get("uid"))) {
            throw new BizException(ErrorCode.FORBIDDEN, "仅作者可以编辑作品");
        }
        Map<String, Object> work = validated(body);
        checkText(uid, work);
        work.put("pid", pid);
        pworkMapper.updatePublished(work);
        return pending(pid);
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public Map<String, Object> updateVisibility(String uid, String pid, Map<String, Object> body) {
        Object value = body == null ? null : body.get("status");
        if (!(value instanceof String) || (!"展示".equals(value) && !"隐藏".equals(value))
                || pid == null || !pid.matches("[A-Za-z0-9_-]{1,36}")) {
            throw invalid();
        }
        String status = (String) value;
        // 条件 UPDATE 同时约束作者和原状态，避免覆盖并发审核结果或绕过审核。
        if (pworkMapper.updateVisibility(pid, uid, status) == 0) {
            Map<String, Object> owner = pworkMapper.findOwnerStatus(pid);
            if (owner == null || !uid.equals(owner.get("uid"))) {
                throw new BizException(ErrorCode.NOT_FOUND, "作品不存在");
            }
            // 驱动可能对相同值更新返回 0；仅当前已是目标状态时按幂等成功处理。
            if (!status.equals(owner.get("wstatus"))) {
                throw new BizException(ErrorCode.CONFLICT, "当前作品状态不允许切换");
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pid", pid);
        result.put("status", status);
        return result;
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public void delete(String uid, String role, String pid) {
        Map<String, Object> owner = owner(pid);
        if (!uid.equals(owner.get("uid")) && !"管理员".equals(role)) {
            throw new BizException(ErrorCode.FORBIDDEN, "无权删除作品");
        }
        // targetid 是多态编号，没有 pid 外键；必须显式删帖子类型，保留同编号的其它类型。
        pworkMapper.deletePostLikes(pid);
        pworkMapper.deletePublished(pid);
    }

    private Map<String, Object> owner(String pid) {
        if (pid == null || !pid.matches("[A-Za-z0-9_-]{1,36}")) {
            throw invalid();
        }
        Map<String, Object> owner = pworkMapper.lockOwner(pid);
        if (owner == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "作品不存在");
        }
        return owner;
    }

    private Map<String, Object> validated(Map<String, Object> body) {
        if (body == null) {
            throw invalid();
        }
        Map<String, Object> work = new LinkedHashMap<>();
        work.put("title", text(body.get("title"), TITLE_MAX, false));
        work.put("desc", text(body.get("desc"), TEXT_MAX, true));
        work.put("imageUrl", text(body.get("imageUrl"), TEXT_MAX, false));
        String category = text(body.get("categoryId"), TEXT_MAX, false);
        if (!category.matches("[A-Za-z0-9_-]{1,36}") || pworkMapper.categoryCount(category) == 0L) {
            throw invalid();
        }
        work.put("categoryId", category);
        return work;
    }

    private String text(Object value, int max, boolean allowBlank) {
        if (!(value instanceof String)) {
            throw invalid();
        }
        String text = (String) value;
        if (text.codePointCount(0, text.length()) > max
                || (!allowBlank && text.codePoints().allMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c)))) {
            throw invalid();
        }
        return text;
    }

    private void checkText(String uid, Map<String, Object> work) {
        msgSecCheck.check(uid, (String) work.get("title"));
        msgSecCheck.check(uid, (String) work.get("desc"));
    }

    private Map<String, Object> pending(String pid) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pid", pid);
        result.put("status", "待审核");
        return result;
    }

    private BizException invalid() {
        return new BizException(ErrorCode.INVALID_REQUEST, "作品参数不合法");
    }
}
