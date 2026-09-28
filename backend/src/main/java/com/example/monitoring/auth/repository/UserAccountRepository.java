package com.example.monitoring.auth.repository;

import com.example.monitoring.auth.domain.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface UserAccountRepository extends JpaRepository<UserAccount, Long> {

    Optional<UserAccount> findByEmail(String email);

    boolean existsByEmail(String email);

    long countByRoleAndEnabledTrue(com.example.monitoring.auth.domain.UserRole role);

    Page<UserAccount> findAllByOrderByIdAsc(Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from UserAccount u where u.id = :id")
    Optional<UserAccount> findByIdForUpdate(@org.springframework.data.repository.query.Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from UserAccount u where u.role = com.example.monitoring.auth.domain.UserRole.ADMIN and u.enabled = true")
    java.util.List<UserAccount> findActiveAdminsForUpdate();
}
