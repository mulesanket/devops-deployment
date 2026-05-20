package com.shopease.auth.service;

import com.shopease.auth.dto.AuthResponse;
import com.shopease.auth.dto.LoginRequest;
import com.shopease.auth.dto.SignupRequest;
import com.shopease.auth.model.User;
import com.shopease.auth.repository.UserRepository;
import com.shopease.auth.security.JwtTokenProvider;
import com.shopease.common.exception.ResourceAlreadyExistsException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtTokenProvider tokenProvider;

    @Mock
    private AuthenticationManager authenticationManager;

    @Mock
    private SnsService snsService;

    @InjectMocks
    private AuthService authService;

    private SignupRequest signupRequest;
    private LoginRequest loginRequest;

    @BeforeEach
    void setUp() {
        signupRequest = new SignupRequest();
        signupRequest.setName("Alice");
        signupRequest.setEmail("alice@example.com");
        signupRequest.setPassword("secret123");

        loginRequest = new LoginRequest();
        loginRequest.setEmail("alice@example.com");
        loginRequest.setPassword("secret123");
    }

    @Test
    @DisplayName("signup with a new email persists the user and returns a token")
    void signup_newEmail_persistsUserAndReturnsToken() {
        when(userRepository.existsByEmail("alice@example.com")).thenReturn(false);
        when(passwordEncoder.encode("secret123")).thenReturn("ENCODED");
        when(tokenProvider.generateToken("alice@example.com")).thenReturn("jwt.token.value");

        AuthResponse response = authService.signup(signupRequest);

        assertThat(response.getToken()).isEqualTo("jwt.token.value");
        assertThat(response.getEmail()).isEqualTo("alice@example.com");
        assertThat(response.getName()).isEqualTo("Alice");

        verify(userRepository).save(any(User.class));
        verify(snsService).publishSignupEvent("Alice", "alice@example.com");
    }

    @Test
    @DisplayName("signup with an existing email throws ResourceAlreadyExistsException")
    void signup_existingEmail_throwsResourceAlreadyExists() {
        when(userRepository.existsByEmail("alice@example.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.signup(signupRequest))
                .isInstanceOf(ResourceAlreadyExistsException.class)
                .hasMessageContaining("Email already exists");

        verify(userRepository, never()).save(any());
        verify(tokenProvider, never()).generateToken(anyString());
        verify(snsService, never()).publishSignupEvent(anyString(), anyString());
    }

    @Test
    @DisplayName("signup still succeeds when SNS publish fails (best-effort notification)")
    void signup_snsPublishFails_signupStillSucceeds() {
        when(userRepository.existsByEmail("alice@example.com")).thenReturn(false);
        when(passwordEncoder.encode("secret123")).thenReturn("ENCODED");
        when(tokenProvider.generateToken("alice@example.com")).thenReturn("jwt.token.value");
        doThrow(new RuntimeException("SNS down"))
                .when(snsService).publishSignupEvent(anyString(), anyString());

        AuthResponse response = authService.signup(signupRequest);

        assertThat(response.getToken()).isEqualTo("jwt.token.value");
        verify(userRepository).save(any(User.class));
    }

    @Test
    @DisplayName("login with valid credentials returns a token for the user")
    void login_validCredentials_returnsToken() {
        User user = new User();
        user.setName("Alice");
        user.setEmail("alice@example.com");

        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(user));
        when(tokenProvider.generateToken("alice@example.com")).thenReturn("jwt.token.value");

        AuthResponse response = authService.login(loginRequest);

        assertThat(response.getToken()).isEqualTo("jwt.token.value");
        assertThat(response.getEmail()).isEqualTo("alice@example.com");
        verify(authenticationManager).authenticate(any(UsernamePasswordAuthenticationToken.class));
    }

    @Test
    @DisplayName("login propagates BadCredentialsException from AuthenticationManager")
    void login_invalidCredentials_propagatesException() {
        doThrow(new BadCredentialsException("Bad credentials"))
                .when(authenticationManager).authenticate(any(UsernamePasswordAuthenticationToken.class));

        assertThatThrownBy(() -> authService.login(loginRequest))
                .isInstanceOf(BadCredentialsException.class);

        verify(tokenProvider, never()).generateToken(anyString());
        verify(userRepository, never()).findByEmail(anyString());
    }

    @Test
    @DisplayName("login throws when authentication passes but user is missing from repository")
    void login_authPassesButUserMissing_throws() {
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(loginRequest))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("User not found");
    }
}
