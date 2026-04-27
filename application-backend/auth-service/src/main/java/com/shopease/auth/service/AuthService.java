package com.shopease.auth.service;

import com.shopease.auth.dto.AuthResponse;
import com.shopease.auth.dto.LoginRequest;
import com.shopease.auth.dto.SignupRequest;
import com.shopease.auth.model.User;
import com.shopease.auth.repository.UserRepository;
import com.shopease.auth.security.JwtTokenProvider;
import com.shopease.common.exception.ResourceAlreadyExistsException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider tokenProvider;
    private final AuthenticationManager authenticationManager;
    private final SnsService snsService;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                       JwtTokenProvider tokenProvider, AuthenticationManager authenticationManager,
                       SnsService snsService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenProvider = tokenProvider;
        this.authenticationManager = authenticationManager;
        this.snsService = snsService;
    }

    public AuthResponse signup(SignupRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new ResourceAlreadyExistsException("Email already exists");
        }

        User user = new User();
        user.setName(request.getName());
        user.setEmail(request.getEmail());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        userRepository.save(user);

        // Publish signup event to SNS (async email)
        try {
            snsService.publishSignupEvent(user.getName(), user.getEmail());
        } catch (Exception e) {
            System.err.println("Failed to publish SNS event: " + e.getMessage());
        }

        String token = tokenProvider.generateToken(user.getEmail());
        return new AuthResponse(token, user.getName(), user.getEmail(), "Signup successful");
    }

    public AuthResponse login(LoginRequest request) {
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.getEmail(), request.getPassword())
        );

        String token = tokenProvider.generateToken(request.getEmail());
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new RuntimeException("User not found"));

        return new AuthResponse(token, user.getName(), user.getEmail(), "Login successful");
    }
}
