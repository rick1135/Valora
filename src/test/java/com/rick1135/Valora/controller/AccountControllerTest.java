package com.rick1135.Valora.controller;

import com.rick1135.Valora.dto.request.ChangePasswordRequestDTO;
import com.rick1135.Valora.dto.request.DeleteAccountRequestDTO;
import com.rick1135.Valora.dto.request.UpdateProfileRequestDTO;
import com.rick1135.Valora.dto.response.AccountProfileResponseDTO;
import com.rick1135.Valora.entity.User;
import com.rick1135.Valora.entity.UserRole;
import com.rick1135.Valora.exception.GlobalExceptionHandler;
import com.rick1135.Valora.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AccountController.class)
@AutoConfigureMockMvc(addFilters = false)
@ContextConfiguration(classes = {
        AccountController.class, GlobalExceptionHandler.class, AccountControllerTest.SecurityTestConfiguration.class
})
class AccountControllerTest {
    @EnableWebSecurity
    static class SecurityTestConfiguration {
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserService userService;

    private User account;

    @BeforeEach
    void setUp() {
        account = new User();
        account.setId(UUID.randomUUID());
        account.setEmail("user@valora.dev");
        account.setRole(UserRole.USER);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(account, null, account.getAuthorities())
        );
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void getProfileReturnsAuthenticatedAccountDetails() throws Exception {
        when(userService.getProfile(account)).thenReturn(profile("Usuario"));

        mockMvc.perform(get("/account/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(account.getId().toString()))
                .andExpect(jsonPath("$.name").value("Usuario"))
                .andExpect(jsonPath("$.email").value(account.getEmail()))
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.passwordHash").doesNotHaveJsonPath());
    }

    @Test
    void updateProfileReturnsUpdatedAccount() throws Exception {
        when(userService.updateProfile(account, new UpdateProfileRequestDTO("Novo Nome")))
                .thenReturn(profile("Novo Nome"));

        mockMvc.perform(put("/account/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Novo Nome"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Novo Nome"));
    }

    @Test
    void changePasswordReturnsNoContent() throws Exception {
        mockMvc.perform(put("/account/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"old-password","newPassword":"new-password"}
                                """))
                .andExpect(status().isNoContent());

        verify(userService).changePassword(account, new ChangePasswordRequestDTO("old-password", "new-password"));
    }

    @Test
    void deleteAccountReturnsNoContent() throws Exception {
        mockMvc.perform(delete("/account")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"old-password"}
                                """))
                .andExpect(status().isNoContent());

        verify(userService).deleteAccount(account, new DeleteAccountRequestDTO("old-password"));
    }

    @Test
    void rejectsInvalidPayloadsBeforeCallingUserService() throws Exception {
        mockMvc.perform(put("/account/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"A"}
                                """))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/account/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"old-password","newPassword":"short"}
                                """))
                .andExpect(status().isBadRequest());
        mockMvc.perform(delete("/account")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":" "}
                                """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(userService);
    }

    private AccountProfileResponseDTO profile(String name) {
        return new AccountProfileResponseDTO(
                account.getId(), name, account.getEmail(), account.getRole(), LocalDateTime.of(2026, 1, 1, 12, 0)
        );
    }
}
