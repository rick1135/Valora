package com.rick1135.Valora.controller;

import com.rick1135.Valora.entity.User;
import com.rick1135.Valora.exception.EmailAlreadyRegisteredException;
import com.rick1135.Valora.exception.GlobalExceptionHandler;
import com.rick1135.Valora.exception.InvalidTokenException;
import com.rick1135.Valora.service.TokenService;
import com.rick1135.Valora.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

    MockMvc mockMvc;

    @Mock
    TokenService tokenService;

    @Mock
    UserService userService;

    @Mock
    AuthenticationManager authenticationManager;

    @BeforeEach
    void setUp() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        AuthController authController = new AuthController(
                tokenService,
                userService,
                authenticationManager
        );

        mockMvc = MockMvcBuilders.standaloneSetup(authController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @Test
    void registerReturnsTokensWhenPayloadIsValid() throws Exception {
        User registeredUser = new User();
        registeredUser.setEmail("new@valora.dev");

        when(userService.registerUser(any())).thenReturn(registeredUser);
        when(tokenService.generateToken(registeredUser)).thenReturn("jwt-token");
        when(tokenService.generateRefreshToken(registeredUser)).thenReturn("refresh-token");

        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Novo Usuario",
                                  "email": "new@valora.dev",
                                  "password": "12345678"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").value("jwt-token"))
                .andExpect(jsonPath("$.refreshToken").value("refresh-token"));
    }

    @Test
    void registerReturnsConflictWhenEmailAlreadyExists() throws Exception {
        when(userService.registerUser(any()))
                .thenThrow(new EmailAlreadyRegisteredException("Email ja cadastrado."));

        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Usuario",
                                  "email": "existing@valora.dev",
                                  "password": "12345678"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Email ja cadastrado."));
    }

    @Test
    void loginReturnsTokensWhenCredentialsAreValid() throws Exception {
        User principal = new User();
        principal.setEmail("user@valora.dev");
        var authentication = new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());

        when(authenticationManager.authenticate(any())).thenReturn(authentication);
        when(tokenService.generateToken(principal)).thenReturn("jwt-login");
        when(tokenService.generateRefreshToken(principal)).thenReturn("refresh-login");

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "user@valora.dev",
                                  "password": "12345678"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("jwt-login"))
                .andExpect(jsonPath("$.refreshToken").value("refresh-login"));
    }

    @Test
    void loginReturnsUnauthorizedWhenCredentialsAreInvalid() throws Exception {
        when(authenticationManager.authenticate(any()))
                .thenThrow(new BadCredentialsException("invalid credentials"));

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "user@valora.dev",
                                  "password": "12345678"
                                }
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Credenciais invalidas."));
    }

    @Test
    void refreshReturnsNewTokensWhenRefreshTokenIsValid() throws Exception {
        User user = new User();
        user.setEmail("refresh@valora.dev");

        when(tokenService.consumeRefreshToken("refresh-token")).thenReturn("refresh@valora.dev");
        when(userService.getByEmail("refresh@valora.dev")).thenReturn(user);
        when(tokenService.generateToken(user)).thenReturn("jwt-refreshed");
        when(tokenService.generateRefreshToken(user)).thenReturn("refresh-rotated");

        mockMvc.perform(post("/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "refreshToken": "refresh-token"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("jwt-refreshed"))
                .andExpect(jsonPath("$.refreshToken").value("refresh-rotated"));
    }

    @Test
    void refreshReturnsUnauthorizedWhenRefreshTokenIsInvalid() throws Exception {
        when(tokenService.consumeRefreshToken("refresh-token"))
                .thenThrow(new InvalidTokenException("Refresh token invalido ou expirado."));

        mockMvc.perform(post("/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "refreshToken": "refresh-token"
                                }
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Refresh token invalido ou expirado."));
    }

    @Test
    void registerReturnsBadRequestWhenPayloadIsInvalid() throws Exception {
        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "",
                                  "email": "invalido",
                                  "password": "123"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists());
    }
}
