package com.zutils.server.security;

import com.zutils.server.model.entity.Developer;
import com.zutils.server.repository.DeveloperRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DeveloperDetailsService implements UserDetailsService {

    private final DeveloperRepository developerRepository;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        Developer developer = developerRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("Developer not found: " + username));
        return new DeveloperDetails(developer);
    }

    public DeveloperDetails loadUserById(Long id) {
        Developer developer = developerRepository.findById(id)
                .orElseThrow(() -> new UsernameNotFoundException("Developer not found: " + id));
        return new DeveloperDetails(developer);
    }
}
