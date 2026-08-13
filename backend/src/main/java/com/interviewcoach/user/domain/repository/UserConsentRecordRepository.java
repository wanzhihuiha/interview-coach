package com.interviewcoach.user.domain.repository;

import com.interviewcoach.user.domain.entity.ConsentType;
import com.interviewcoach.user.domain.entity.UserConsentRecord;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * 用户协议同意历史记录的 JPA 数据访问接口。
 *
 * <p>同意服务通过本仓储追加记录并查询指定用户、指定协议类型的最新记录，查询结果用于前端状态响应和
 * AI 处理前置校验；仓储不负责比较协议版本或决定是否允许后续业务。</p>
 */
@Repository
public interface UserConsentRecordRepository extends JpaRepository<UserConsentRecord, Long> {

    /**
     * 按同意时间倒序查询指定用户和类型的最新一条记录。
     *
     * @param userId 作出同意的用户主键
     * @param consentType 待查询的协议类型
     * @return 最新记录；没有历史记录时为空
     */
    Optional<UserConsentRecord> findTopByUserIdAndConsentTypeOrderByConsentTimeDesc(Long userId, ConsentType consentType);

    /**
     * 判断指定用户是否存在该协议类型的任意历史记录。
     *
     * <p>方法通过最新记录查询做存在性判断，不比较 {@code consentVersion}，也不判断记录是否属于当前协议版本。</p>
     *
     * @param userId 作出同意的用户主键
     * @param consentType 待判断的协议类型
     * @return 至少存在一条记录时为 {@code true}
     */
    default boolean hasConsented(Long userId, ConsentType consentType) {
        // 查询最新历史记录并只取存在性，调用方据此决定状态展示或 AI 处理准入。
        return findTopByUserIdAndConsentTypeOrderByConsentTimeDesc(userId, consentType).isPresent();
    }
}
