package com.interviewcoach.user.domain.repository;

import com.interviewcoach.user.domain.entity.UserProfile;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * 用户资料数据访问接口。
 */
@Repository
public interface UserProfileRepository extends JpaRepository<UserProfile, Long> {

    /**
     * 根据用户ID查询用户资料。
     */
    Optional<UserProfile> findByUserId(Long userId);
}
