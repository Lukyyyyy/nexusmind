package com.luky.nexusmind.repository;

import com.luky.nexusmind.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);
    Optional<User> findByEmail(String email);
    Optional<User> findByEmailAndEmailVerifiedAtIsNotNull(String email);
    java.util.List<User> findByRoleIn(java.util.Collection<User.Role> roles);
}
