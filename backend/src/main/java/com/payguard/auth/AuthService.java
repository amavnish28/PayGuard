package com.payguard.auth;

import com.payguard.auth.dto.LoginRequest;
import com.payguard.auth.dto.LoginResponse;
import com.payguard.security.JwtService;
import com.payguard.user.User;
import com.payguard.user.UserRepository;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest request) {
        if (request == null || request.getUsername() == null || request.getPassword() == null) {
            throw new BadCredentialsException("Invalid username or password");
        }

        User user = userRepository.findByUsername(request.getUsername().trim())
                .orElseThrow(() -> new BadCredentialsException("Invalid username or password"));

        if (!Boolean.TRUE.equals(user.getIsActive())) {
            throw new BadCredentialsException("Account is disabled");
        }

        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new BadCredentialsException("Invalid username or password");
        }

        String token = jwtService.generateToken(user);
        String roleName = user.getRole() != null ? user.getRole().name() : "ANALYST";

        return new LoginResponse(token, user.getUsername(), roleName);
    }
}
