package com.interviewcoach.user.domain.repository;

import com.interviewcoach.user.domain.entity.ConsentType;
import com.interviewcoach.user.domain.entity.UserConsentRecord;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * 用户同意记录数据访问接口。
 */
@Repository
public interface UserConsentRecordRepository extends JpaRepository<UserConsentRecord, Long> {

    /**
     * 根据用户ID和同意类型查询最新同意记录。
     */
    Optional<UserConsentRecord> findTopByUserIdAndConsentTypeOrderByConsentTimeDesc(Long userId, ConsentType consentType);

    /**
     * 判断用户是否已同意某类型。
     */
    default boolean hasConsented(Long userId, ConsentType consentType) {
        return findTopByUserIdAndConsentTypeOrderByConsentTimeDesc(userId, consentType).isPresent();
    }
}
