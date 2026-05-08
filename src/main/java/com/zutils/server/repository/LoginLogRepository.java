package com.zutils.server.repository;

import com.zutils.server.model.entity.LoginLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LoginLogRepository extends JpaRepository<LoginLog, Long> {
    Page<LoginLog> findByDeveloperIdOrderByCreatedAtDesc(Long developerId, Pageable pageable);
}
