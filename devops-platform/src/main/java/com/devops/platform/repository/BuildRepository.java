package com.devops.platform.repository;

import com.devops.platform.entity.Build;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface BuildRepository extends JpaRepository<Build, Long> {

    List<Build> findByProjectIdOrderByStartTimeDesc(Long projectId);

    List<Build> findByPipelineIdOrderByStartTimeDesc(Long pipelineId);

    Page<Build> findByProjectId(Long projectId, Pageable pageable);

    List<Build> findByStatus(String status);

    List<Build> findByStartTimeBetween(LocalDateTime start, LocalDateTime end);

    @Query("SELECT b FROM Build b WHERE b.projectId = :projectId AND b.status = :status ORDER BY b.startTime DESC")
    List<Build> findByProjectIdAndStatus(@Param("projectId") Long projectId, @Param("status") String status);

    /** 查找使用指定数据库名的所有构建记录 */
    List<Build> findByDbName(String dbName);

    /** 查找使用指定数据库名的构建次数 */
    Long countByDbName(String dbName);

    /** 查找指定数据库名、非指定项目的构建（冲突检测用） */
    @Query("SELECT b FROM Build b WHERE b.dbName = :dbName AND b.projectId <> :projectId")
    List<Build> findByDbNameAndProjectIdNot(@Param("dbName") String dbName, @Param("projectId") Long projectId);

    Long countByProjectIdAndStatus(Long projectId, String status);

    Long countByStatus(String status);
}
