package com.zutils.server.repository;

import com.zutils.server.model.entity.Developer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DeveloperRepository extends JpaRepository<Developer, Long> {
    Optional<Developer> findByUsername(String username);
    Optional<Developer> findByEmail(String email);
    Optional<Developer> findByMemberUid(String memberUid);
    boolean existsByUsername(String username);
    boolean existsByEmail(String email);
    boolean existsByMemberUid(String memberUid);
    List<Developer> findAllByDeletedFalse();
}
