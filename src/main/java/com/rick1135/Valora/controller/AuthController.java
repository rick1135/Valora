package com.rick1135.Valora.controller;

import com.rick1135.Valora.dto.request.AuthenticationDTO;
import com.rick1135.Valora.dto.request.RefreshTokenRequestDTO;
import com.rick1135.Valora.dto.request.RegisterDTO;
import com.rick1135.Valora.dto.response.LoginResponseDTO;
import com.rick1135.Valora.entity.User;
import com.rick1135.Valora.exception.GlobalExceptionHandler;
import com.rick1135.Valora.exception.InvalidTokenException;
import com.rick1135.Valora.service.TokenService;
import com.rick1135.Valora.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
public class AuthController {
    private final TokenService tokenService;
    private final UserService userService;
    private final AuthenticationManager authenticationManager;

    public AuthController(
            TokenService tokenService,
            UserService userService,
            AuthenticationManager authenticationManager
    ) {
        this.tokenService = tokenService;
        this.userService = userService;
        this.authenticationManager = authenticationManager;
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponseDTO> login(@Valid @RequestBody AuthenticationDTO data) {
        var usernamePassword = new UsernamePasswordAuthenticationToken(data.email(), data.password());
        var auth = authenticationManager.authenticate(usernamePassword);
        User user = (User) auth.getPrincipal();
        return ResponseEntity.ok(buildLoginResponse(user));
    }

    @PostMapping("/register")
    public ResponseEntity<LoginResponseDTO> register(@Valid @RequestBody RegisterDTO data) {
        User newUser = userService.registerUser(data);
        return ResponseEntity.status(201).body(buildLoginResponse(newUser));
    }

    @PostMapping("/refresh")
    public ResponseEntity<LoginResponseDTO> refresh(@Valid @RequestBody RefreshTokenRequestDTO data) {
        var consumed = tokenService.consumeRefreshTokenWithVersion(data.refreshToken());
        User user = userService.getById(consumed.userId());
        if (user.getTokenVersion() != consumed.tokenVersion()) {
            throw new InvalidTokenException(GlobalExceptionHandler.GENERIC_REFRESH_TOKEN_MESSAGE);
        }
        return ResponseEntity.ok(buildLoginResponse(user));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshTokenRequestDTO data) {
        tokenService.revokeRefreshToken(data.refreshToken());
        return ResponseEntity.noContent().build();
    }

    private LoginResponseDTO buildLoginResponse(User user) {
        String token = tokenService.generateToken(user);
        String refreshToken = tokenService.generateRefreshToken(user);
        return new LoginResponseDTO(token, refreshToken);
    }
}
