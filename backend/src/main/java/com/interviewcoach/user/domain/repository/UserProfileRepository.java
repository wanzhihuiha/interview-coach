package com.interviewcoach.user.domain.repository;

import com.interviewcoach.user.domain.entity.UserProfile;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * 用户一对一扩展资料的 JPA 数据访问接口。
 *
 * <p>认证服务用它为新账号保存空白资料，资料服务按用户主键查询和保存资料；查询为空时由调用方分别决定
 * 只构造临时响应对象，还是创建并持久化新资料。</p>
 */
@Repository
public interface UserProfileRepository extends JpaRepository<UserProfile, Long> {

    /**
     * 根据资料所属用户主键查询一对一资料记录。
     *
     * @param userId 资料所属用户主键
     * @return 已保存资料；不存在时为空，由调用服务决定是否创建
     */
    Optional<UserProfile> findByUserId(Long userId);
}
