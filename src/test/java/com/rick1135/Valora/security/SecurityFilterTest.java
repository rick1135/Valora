package com.rick1135.Valora.security;

import com.rick1135.Valora.entity.User;
import com.rick1135.Valora.entity.UserRole;
import com.rick1135.Valora.exception.InvalidTokenException;
import com.rick1135.Valora.repository.UserRepository;
import com.rick1135.Valora.service.TokenService;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SecurityFilterTest {

    @Mock
    private TokenService tokenService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private HandlerExceptionResolver handlerExceptionResolver;

    @Mock
    private FilterChain filterChain;

    private SecurityFilter securityFilter;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        securityFilter = new SecurityFilter(tokenService, userRepository, handlerExceptionResolver);
        response = new MockHttpServletResponse();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void missingAuthorizationHeaderShouldContinueWithoutAuthentication() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/assets");

        securityFilter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verify(tokenService, never()).validateToken(any());
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void malformedAuthorizationHeaderShouldResolveGenericInvalidToken() throws Exception {
        MockHttpServletRequest request = requestWithAuthorization("Basic abc");

        securityFilter.doFilter(request, response, filterChain);

        assertInvalidTokenResolved(request, "Token invalido ou expirado.", "Basic abc");
        verify(filterChain, never()).doFilter(any(), any());
    }

    @Test
    void blankBearerTokenShouldResolveGenericInvalidToken() throws Exception {
        MockHttpServletRequest request = requestWithAuthorization("Bearer   ");

        securityFilter.doFilter(request, response, filterChain);

        assertInvalidTokenResolved(request, "Token invalido ou expirado.", "Bearer");
        verify(filterChain, never()).doFilter(any(), any());
    }

    @Test
    void invalidTokenFromTokenServiceShouldResolveGenericInvalidToken() throws Exception {
        MockHttpServletRequest request = requestWithAuthorization("Bearer invalid-token");
        when(tokenService.validateToken("invalid-token"))
                .thenThrow(new InvalidTokenException("Token invalido ou expirado."));

        securityFilter.doFilter(request, response, filterChain);

        assertInvalidTokenResolved(request, "Token invalido ou expirado.", "invalid-token");
        verify(filterChain, never()).doFilter(any(), any());
    }

    @Test
    void validTokenWithMissingUserShouldResolveGenericInvalidToken() throws Exception {
        MockHttpServletRequest request = requestWithAuthorization("Bearer valid-token");
        when(tokenService.validateToken("valid-token")).thenReturn("missing@valora.dev");
        when(userRepository.findByEmail("missing@valora.dev")).thenReturn(Optional.empty());

        securityFilter.doFilter(request, response, filterChain);

        assertInvalidTokenResolved(request, "Token invalido ou expirado.", "valid-token");
        verify(filterChain, never()).doFilter(any(), any());
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void validTokenWithExistingUserShouldAuthenticateAndContinue() throws Exception {
        MockHttpServletRequest request = requestWithAuthorization("Bearer valid-token");
        User user = user("user@valora.dev", UserRole.USER);
        when(tokenService.validateToken("valid-token")).thenReturn("user@valora.dev");
        when(userRepository.findByEmail("user@valora.dev")).thenReturn(Optional.of(user));

        securityFilter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verify(handlerExceptionResolver, never()).resolveException(any(), any(), any(), any());
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertThat(authentication).isNotNull();
        assertThat(authentication.getPrincipal()).isSameAs(user);
        assertThat(authentication.getAuthorities())
                .extracting("authority")
                .containsExactly("ROLE_USER");
    }

    private MockHttpServletRequest requestWithAuthorization(String authorization) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/assets");
        request.addHeader("Authorization", authorization);
        return request;
    }

    private void assertInvalidTokenResolved(
            MockHttpServletRequest request,
            String expectedMessage,
            String sensitiveValue
    ) {
        ArgumentCaptor<InvalidTokenException> exceptionCaptor = ArgumentCaptor.forClass(InvalidTokenException.class);
        verify(handlerExceptionResolver).resolveException(
                org.mockito.Mockito.eq(request),
                org.mockito.Mockito.eq(response),
                isNull(),
                exceptionCaptor.capture()
        );
        assertThat(exceptionCaptor.getValue())
                .hasMessage(expectedMessage);
        assertThat(exceptionCaptor.getValue().getMessage())
                .doesNotContain(sensitiveValue)
                .doesNotContain("JWT")
                .doesNotContain("Exception");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    private User user(String email, UserRole role) {
        User user = new User();
        user.setEmail(email);
        user.setPasswordHash("hash");
        user.setName("User");
        user.setRole(role);
        return user;
    }
}
