package com.aijiaoxue.action;

import com.aijiaoxue.identity.CurrentUser;
import com.aijiaoxue.identity.CurrentUserProvider;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sprint 2 "帮教师" actions: a teacher picks one AI suggestion as the next attempt
 * (S2-08), adjusts or finishes it later (S2-09, S2-14) and nothing here is ever
 * visible to another user (PRIV3-01).
 */
@Service
public class TeacherActionService {
    static final String SOURCE_SUGGESTION = "SUGGESTION";
    static final String SOURCE_MANUAL = "MANUAL";
    static final String STATUS_PLANNED = "PLANNED";
    private static final Set<String> SOURCE_KINDS = Set.of(SOURCE_SUGGESTION, SOURCE_MANUAL);
    private static final Map<String, Set<String>> ALLOWED_TRANSITIONS = Map.of(
            STATUS_PLANNED, Set.of("ACTIVE", "ABANDONED"),
            "ACTIVE", Set.of("DONE", "ABANDONED"),
            "DONE", Set.of(),
            "ABANDONED", Set.of());
    private static final int TITLE_MAX = 200;
    private static final int CONTENT_MAX = 2000;

    private final TeacherActionRepository repository;
    private final CurrentUserProvider currentUsers;

    TeacherActionService(TeacherActionRepository repository, CurrentUserProvider currentUsers) {
        this.repository = repository;
        this.currentUsers = currentUsers;
    }

    @Transactional(readOnly = true)
    public List<TeacherActionView> listMine() {
        return repository.listForOwner(currentUsers.requireCurrentUser().userId());
    }

    @Transactional
    public TeacherActionView create(Long mediaId, String sourceKind, Integer sourceIndex,
                                    String title, String content) {
        CurrentUser user = currentUsers.requireCurrentUser();
        String kind = sourceKind == null || sourceKind.isBlank() ? SOURCE_MANUAL : sourceKind.trim();
        if (!SOURCE_KINDS.contains(kind))
            throw new TeacherActionException("INVALID_SOURCE_KIND", "行动来源不合法", HttpStatus.BAD_REQUEST);
        if (SOURCE_SUGGESTION.equals(kind) && sourceIndex == null)
            throw new TeacherActionException("SUGGESTION_INDEX_REQUIRED", "来自 AI 建议的行动必须给出建议序号",
                    HttpStatus.BAD_REQUEST);
        if (!SOURCE_SUGGESTION.equals(kind) && sourceIndex != null)
            throw new TeacherActionException("SUGGESTION_INDEX_NOT_ALLOWED", "自拟行动不应带建议序号",
                    HttpStatus.BAD_REQUEST);
        if (sourceIndex != null && sourceIndex < 0)
            throw new TeacherActionException("INVALID_SUGGESTION_INDEX", "建议序号不合法", HttpStatus.BAD_REQUEST);
        if (mediaId != null && mediaId <= 0)
            throw new TeacherActionException("INVALID_MEDIA_ID", "课堂媒体标识不合法", HttpStatus.BAD_REQUEST);
        long actionId = repository.insert(user.userId(), mediaId, kind, sourceIndex,
                required(title, "行动标题", TITLE_MAX), required(content, "行动内容", CONTENT_MAX));
        return owned(actionId, user);
    }

    @Transactional
    public TeacherActionView updateContent(long actionId, String title, String content) {
        CurrentUser user = currentUsers.requireCurrentUser();
        TeacherActionView existing = owned(actionId, user);
        if (repository.updateContent(actionId, user.userId(),
                required(title, "行动标题", TITLE_MAX), required(content, "行动内容", CONTENT_MAX)) != 1)
            throw notFound();
        return owned(existing.actionId(), user);
    }

    @Transactional
    public TeacherActionView changeStatus(long actionId, String status) {
        CurrentUser user = currentUsers.requireCurrentUser();
        TeacherActionView existing = owned(actionId, user);
        String target = status == null ? "" : status.trim();
        Set<String> allowed = ALLOWED_TRANSITIONS.get(existing.status());
        if (allowed == null)
            throw new TeacherActionException("UNKNOWN_ACTION_STATUS", "行动状态不可识别", HttpStatus.CONFLICT);
        if (!allowed.contains(target))
            throw new TeacherActionException("INVALID_ACTION_TRANSITION",
                    "行动状态不能从 " + existing.status() + " 变更为 " + target, HttpStatus.CONFLICT);
        if (repository.updateStatus(actionId, user.userId(), target) != 1) throw notFound();
        return owned(actionId, user);
    }

    /** A row that is missing or owned by somebody else is reported identically, so existence never leaks. */
    private TeacherActionView owned(long actionId, CurrentUser user) {
        TeacherActionView row = repository.findOwned(actionId, user.userId());
        if (row == null) throw notFound();
        return row;
    }

    private static TeacherActionException notFound() {
        return new TeacherActionException("ACTION_NOT_FOUND", "行动不存在", HttpStatus.NOT_FOUND);
    }

    private static String required(String value, String label, int max) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty())
            throw new TeacherActionException("ACTION_FIELD_REQUIRED", label + "不能为空", HttpStatus.BAD_REQUEST);
        if (trimmed.length() > max)
            throw new TeacherActionException("ACTION_FIELD_TOO_LONG", label + "超过 " + max + " 字",
                    HttpStatus.BAD_REQUEST);
        return trimmed;
    }
}
