package com.team3.backend.behavior.retention.admin;

import com.team3.backend.behavior.retention.UserBehaviorEventRetention;
import com.team3.backend.behavior.retention.UserBehaviorEventRetention.Plan;
import com.team3.backend.behavior.retention.UserBehaviorEventRetention.Result;
import com.team3.backend.behavior.retention.admin.RetentionAdminDtos.CreateItem;
import com.team3.backend.behavior.retention.admin.RetentionAdminDtos.DropItem;
import com.team3.backend.behavior.retention.admin.RetentionAdminDtos.ExecuteResponse;
import com.team3.backend.behavior.retention.admin.RetentionAdminDtos.IgnoredItem;
import com.team3.backend.behavior.retention.admin.RetentionAdminDtos.Policy;
import com.team3.backend.behavior.retention.admin.RetentionAdminDtos.PreviewResponse;
import com.team3.backend.global.exception.BusinessException;
import com.team3.backend.user.domain.entity.Role;
import com.team3.backend.user.domain.entity.User;
import com.team3.backend.user.domain.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;

/**
 * 관리자 페이지의 보관 정리 미리보기·실행. 판정·실행은 자동·수동 실행과 같은 {@link UserBehaviorEventRetention}을 쓴다.
 * <ul>
 *   <li>미리보기는 읽기 전용 계획만 만든다 (잠금·DDL 없음).</li>
 *   <li>실행은 yon.behavior.retention.admin-execute-enabled=true(기본 false)일 때만 하고, 미리보기의 계획 토큰이
 *       잠금 아래 다시 만든 계획과 같을 때만 생성·삭제한다. dry-run 설정과 무관하게 실제 실행이다.</li>
 *   <li>실행 직전 DB의 사용자 역할이 ADMIN이고 현재 관리자 목록(app.admin-kakao-ids)에 있는지 다시 확인한다.
 *       역할은 로그인 시에만 승격되고 목록에서 빠져도 강등되지 않으므로 삭제 작업에는 현재 목록을 함께 본다.</li>
 *   <li>모든 실행 시도·결과를 action=BEHAVIOR_RETENTION_* 감사 로그로 남긴다 (관리자 내부 ID만, 토큰·개인정보 없음).</li>
 * </ul>
 */
@Slf4j
@Service
public class RetentionAdminService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final String SCHEDULE = "매월 1일 03:00 (Asia/Seoul)";

    private final UserBehaviorEventRetention retention;
    private final UserRepository userRepository;
    private final Clock clock;
    private final int retainedMonths;
    private final Duration lockTimeout;
    private final boolean adminExecuteEnabled;
    private final boolean schedulerEnabled;
    private final boolean schedulerDryRun;
    private final String adminKakaoIds;

    public RetentionAdminService(UserBehaviorEventRetention retention, UserRepository userRepository, Clock clock,
                                 @Value("${yon.behavior.retention.retained-months:4}") int retainedMonths,
                                 @Value("${yon.behavior.retention.lock-timeout:3s}") Duration lockTimeout,
                                 @Value("${yon.behavior.retention.admin-execute-enabled:false}") boolean adminExecuteEnabled,
                                 @Value("#{${app.scheduling.enabled:true} and ${yon.behavior.retention.enabled:false}}") boolean schedulerEnabled,
                                 @Value("${yon.behavior.retention.dry-run:true}") boolean schedulerDryRun,
                                 @Value("${app.admin-kakao-ids:}") String adminKakaoIds) {
        if (retainedMonths < 1) {
            throw new IllegalArgumentException("yon.behavior.retention.retained-months must be at least 1: " + retainedMonths);
        }
        this.retention = retention;
        this.userRepository = userRepository;
        this.clock = clock;
        this.retainedMonths = retainedMonths;
        this.lockTimeout = lockTimeout;
        this.adminExecuteEnabled = adminExecuteEnabled;
        this.schedulerEnabled = schedulerEnabled;
        this.schedulerDryRun = schedulerDryRun;
        this.adminKakaoIds = adminKakaoIds;
    }

    public PreviewResponse preview(Long adminUserId) {
        Plan plan = retention.preview(clock, retainedMonths);
        String status = plan.skipped() == null ? "READY" : plan.skipped().name();
        log.info("action=BEHAVIOR_RETENTION_PREVIEW adminUserId={} status={} keepFrom={} toDrop={} toCreate={}",
                adminUserId, status, plan.keepFrom(), plan.toDrop().stream().map(UserBehaviorEventRetention.DropTarget::name).toList(),
                plan.toCreate().size());
        return new PreviewResponse(status, policy(), plan.today(), plan.keepFrom(),
                plan.toDrop().stream().map(t -> new DropItem(t.name(), t.from(), t.to(), t.estimatedRows(), t.totalBytes())).toList(),
                plan.toCreate().stream().map(t -> new CreateItem(t.name(), t.from(), t.to(), t.conflict())).toList(),
                plan.ignored().stream().map(t -> new IgnoredItem(t.name(), t.bound())).toList(),
                plan.defaultHasRows(), plan.skipped() == null ? plan.token() : null);
    }

    public ExecuteResponse execute(Long adminUserId, String planToken) {
        LocalDateTime requestedAt = LocalDateTime.now(clock.withZone(KST));
        if (!adminExecuteEnabled) {
            log.warn("action=BEHAVIOR_RETENTION_EXECUTE adminUserId={} at={} result=DENIED reason=ADMIN_EXECUTE_DISABLED",
                    adminUserId, requestedAt);
            throw new BusinessException(RetentionAdminErrorCode.EXECUTE_DISABLED);
        }
        if (!isCurrentAdmin(adminUserId)) {
            log.warn("action=BEHAVIOR_RETENTION_EXECUTE adminUserId={} at={} result=DENIED reason=NOT_CURRENT_ADMIN",
                    adminUserId, requestedAt);
            throw new BusinessException(RetentionAdminErrorCode.ACCESS_DENIED);
        }
        log.info("action=BEHAVIOR_RETENTION_EXECUTE adminUserId={} at={} result=STARTED retainedMonths={}",
                adminUserId, requestedAt, retainedMonths);
        Result result;
        try {
            result = retention.runNow(clock, "admin:" + adminUserId, retainedMonths, false, lockTimeout, planToken);
        } catch (RuntimeException exception) {
            log.error("action=BEHAVIOR_RETENTION_EXECUTE adminUserId={} at={} result=ERROR error={}",
                    adminUserId, requestedAt, exception.getClass().getSimpleName(), exception);
            throw exception;
        }
        log.info("action=BEHAVIOR_RETENTION_EXECUTE adminUserId={} at={} result={} keepFrom={} created={} dropped={} failed={} ignored={}",
                adminUserId, requestedAt, result.status(), result.keepFrom(), result.created(), result.dropped(), result.failed(),
                result.ignored());
        return switch (result.status()) {
            case COMPLETED, COMPLETED_WITH_FAILURES -> new ExecuteResponse(result.status().name(), result.keepFrom(), result.created(),
                    result.dropped(), result.failed(), result.ignored(), requestedAt);
            case PLAN_CHANGED -> throw new BusinessException(RetentionAdminErrorCode.PLAN_CHANGED);
            case SKIPPED_LOCKED -> throw new BusinessException(RetentionAdminErrorCode.RUN_IN_PROGRESS);
            case SKIPPED_NOT_PARTITIONED, SKIPPED_ROLLBACK_WINDOW, SKIPPED_CLOCK_MISMATCH ->
                    throw new BusinessException(RetentionAdminErrorCode.NOT_RUNNABLE);
        };
    }

    private Policy policy() {
        return new Policy(retainedMonths, 12, SCHEDULE, schedulerEnabled, schedulerDryRun, adminExecuteEnabled);
    }

    /** 실행 직전 DB 역할과 현재 관리자 목록을 함께 확인한다. 목록 형식이 잘못되면 허용하지 않는다 */
    private boolean isCurrentAdmin(Long userId) {
        User user = userId == null ? null : userRepository.findById(userId).orElse(null);
        if (user == null || user.getRole() != Role.ADMIN || user.getKakaoId() == null || adminKakaoIds == null) {
            return false;
        }
        try {
            return Arrays.stream(adminKakaoIds.split(",")).map(String::trim).filter(id -> !id.isBlank())
                    .map(Long::valueOf).anyMatch(user.getKakaoId()::equals);
        } catch (NumberFormatException exception) {
            log.error("action=BEHAVIOR_RETENTION_EXECUTE result=DENIED reason=INVALID_ADMIN_KAKAO_IDS");
            return false;
        }
    }
}
