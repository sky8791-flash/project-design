package com.collabdoc.repository;

import com.collabdoc.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);
    Optional<User> findByUserCode(String userCode);
    Optional<User> findByEmail(String email);
    boolean existsByUsername(String username);
    boolean existsByUserCode(String userCode);
    boolean existsByEmail(String email);
    Page<User> findByUsernameContainingOrEmailContaining(String username, String email, Pageable pageable);
}
