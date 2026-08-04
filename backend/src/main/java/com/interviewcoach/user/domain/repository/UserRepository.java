package com.interviewcoach.user.domain.repository;

import com.interviewcoach.user.domain.entity.User;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * 用户数据访问接口。
 */
@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    /**
     * 锁定用户行，串行化个人岗位数量、等待数量和提交间隔检查。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") Long id);

    /**
     * 根据用户名查询用户。
     */
    Optional<User> findByUsername(String username);

    /**
     * 根据手机号查询用户。
     */
    Optional<User> findByPhone(String phone);

    /**
     * 根据 OpenID 查询用户。
     */
    Optional<User> findByOpenid(String openid);

    /**
     * 判断用户名是否已存在。
     */
    boolean existsByUsername(String username);

    /**
     * 判断手机号是否已存在。
     */
    boolean existsByPhone(String phone);
}
