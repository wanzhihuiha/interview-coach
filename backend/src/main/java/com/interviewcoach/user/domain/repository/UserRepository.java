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
 * 用户账号实体的 JPA 数据访问接口。
 *
 * <p>认证、资料和管理端服务使用普通查询及存在性判断；岗位提交服务在事务内使用悲观锁查询，
 * 把同一用户的岗位数量、等待数量和提交间隔检查串行化。本仓储只访问账号数据，不决定 HTTP 身份。</p>
 */
@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    /**
     * 在调用方事务内以悲观写锁查询用户行，串行化该用户的个人岗位数量、等待数量和提交间隔检查。
     *
     * <p>锁在当前事务结束时释放；用户不存在时返回空结果，也没有可锁定的用户行。</p>
     *
     * @param id 需要锁定的用户主键
     * @return 已锁定的用户实体；用户不存在时为空
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") Long id);

    /**
     * 按唯一用户名查询账号，供登录和管理员初始化判断账号归属。
     *
     * @param username 登录名或待初始化管理员用户名
     * @return 匹配账号；不存在时为空
     */
    Optional<User> findByUsername(String username);

    /**
     * 按唯一手机号查询账号，供手机号登录决定直接登录还是自动创建账号。
     *
     * @param phone 登录手机号
     * @return 已绑定该手机号的账号；不存在时为空
     */
    Optional<User> findByPhone(String phone);

    /**
     * 按唯一 OpenID 查询账号，供当前模拟微信登录决定直接登录还是自动创建账号。
     *
     * @param openid 当前流程派生的模拟 OpenID
     * @return 已关联该标识的账号；不存在时为空
     */
    Optional<User> findByOpenid(String openid);

    /**
     * 判断用户名是否已被账号占用，供注册流程在写入前拒绝重复用户名。
     *
     * @param username 待注册用户名
     * @return 已存在时为 {@code true}
     */
    boolean existsByUsername(String username);

    /**
     * 判断手机号是否已被账号占用，供注册和绑定流程在写入前拒绝重复手机号。
     *
     * @param phone 待注册或绑定的手机号
     * @return 已存在时为 {@code true}
     */
    boolean existsByPhone(String phone);
}
