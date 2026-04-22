package com.shopease.service;

import com.shopease.dto.AuthResponse;
import com.shopease.dto.LoginRequest;
import com.shopease.dto.SignupRequest;
import com.shopease.model.User;
import com.shopease.repository.UserRepository;
import com.shopease.security.JwtTokenProvider;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
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
            throw new RuntimeException("Email already exists");
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
            // Don't fail signup if SNS fails
        }

        String token = tokenProvider.generateToken(user.getEmail());
        return new AuthResponse(token, user.getName(), user.getEmail(), "Signup successful");
    }

    public AuthResponse login(LoginRequest request) {
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.getEmail(), request.getPassword())
        );

        String token = tokenProvider.generateToken(request.getEmail());
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new RuntimeException("User not found"));

        return new AuthResponse(token, user.getName(), user.getEmail(), "Login successful");
    }
}
