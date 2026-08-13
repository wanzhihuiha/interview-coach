package com.interviewcoach.interview.domain.repository;

import com.interviewcoach.interview.domain.entity.Interview;
import com.interviewcoach.interview.domain.entity.InterviewStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * 面试会话的 Spring Data JPA 仓储。
 *
 * <p>用户接口使用带 userId 的查询守住资源归属；创建、回答和结束状态写入使用悲观锁查询；
 * 启动清理及岗位删除检查使用状态派生查询。</p>
 */
@Repository
public interface InterviewRepository extends JpaRepository<Interview, Long> {

    /** 按面试 ID 和当前用户读取会话；不存在或不属于用户时都返回空。 */
    Optional<Interview> findByIdAndUserId(Long id, Long userId);

    /**
     * 以数据库悲观写锁读取本人面试，供首题写回、轮次 reservation/完成、失败补偿和结束流程
     * 串行化状态变更；空结果仍表示不存在或不属于用户。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Interview i where i.id = :id and i.userId = :userId")
    Optional<Interview> findByIdAndUserIdForUpdate(
            @Param("id") Long id, @Param("userId") Long userId);

    /** 判断指定面试是否处于给定状态，当前用于识别资源锁是否仍指向进行中会话。 */
    boolean existsByIdAndStatus(Long id, InterviewStatus status);

    /** 返回当前用户全部面试，并按创建时间倒序供历史列表展示。 */
    List<Interview> findByUserIdOrderByCreatedAtDesc(Long userId);

    /** 按当前用户分页读取面试；最终排序由调用方传入的 Pageable 决定。 */
    Page<Interview> findByUserId(Long userId, Pageable pageable);

    /** 返回指定状态的全部面试，当前启动回调用它全量读取进行中记录。 */
    List<Interview> findByStatus(InterviewStatus status);

    /**
     * 永久删除岗位前检查是否仍有指定状态的面试引用它；只返回存在性，不锁定引用记录。
     */
    boolean existsByPositionIdAndStatus(Long positionId, InterviewStatus status);
}
