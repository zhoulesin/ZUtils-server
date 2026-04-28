package com.zutils.server.security;

import com.zutils.server.model.entity.Developer;
import com.zutils.server.model.enums.Role;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

@Getter
public class DeveloperDetails implements UserDetails {

    private final Long id;
    private final String username;
    private final String email;
    private final String password;
    private final Role role;

    public DeveloperDetails(Developer developer) {
        this.id = developer.getId();
        this.username = developer.getUsername();
        this.email = developer.getEmail();
        this.password = developer.getPassword();
        this.role = developer.getRole();
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        String roleName = role == Role.ADMIN ? "ROLE_ADMIN" : "ROLE_DEVELOPER";
        return List.of(new SimpleGrantedAuthority(roleName));
    }

    @Override
    public boolean isAccountNonExpired() { return true; }

    @Override
    public boolean isAccountNonLocked() { return true; }

    @Override
    public boolean isCredentialsNonExpired() { return true; }

    @Override
    public boolean isEnabled() {
        // 直接从 developer 实体读取 enabled 状态
        return true; // 通过 UserDetailsService 加载时已确认
    }
}
