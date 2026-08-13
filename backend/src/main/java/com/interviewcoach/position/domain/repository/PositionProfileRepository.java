package com.interviewcoach.position.domain.repository;

import com.interviewcoach.position.domain.entity.PositionProfile;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * 已确认岗位正式画像的 JPA 仓储，由确认事务写入，并由列表、详情和面试流程读取。
 * 当前任务中的候选画像不通过本仓储保存，不能与正式画像混用。
 */
@Repository
public interface PositionProfileRepository extends JpaRepository<PositionProfile, Long> {

    /**
     * 根据岗位 ID 查询当前正式画像。
     *
     * @return 岗位从未确认正式画像时为空
     */
    Optional<PositionProfile> findByPositionId(Long positionId);

    /** 批量读取岗位集合中的正式画像，供列表判断可用性并避免 N+1；未命中岗位不会产生占位记录。 */
    List<PositionProfile> findByPositionIdIn(Collection<Long> positionIds);

    /** 判断岗位是否已有正式画像，供状态和列表生成 {@code profileUsable}。 */
    boolean existsByPositionId(Long positionId);

    /**
     * 悲观写锁读取岗位正式画像，供候选确认事务更新同一岗位的当前正式版本。
     *
     * @return 首次确认尚无记录时为空，调用方会在同一事务创建画像
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PositionProfile p where p.positionId = :positionId")
    Optional<PositionProfile> findByPositionIdForUpdate(@Param("positionId") Long positionId);
}
