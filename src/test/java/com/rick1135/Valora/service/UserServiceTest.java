package com.rick1135.Valora.service;

import com.rick1135.Valora.dto.request.RegisterDTO;
import com.rick1135.Valora.entity.User;
import com.rick1135.Valora.entity.UserRole;
import com.rick1135.Valora.exception.EmailAlreadyRegisteredException;
import com.rick1135.Valora.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private UserService userService;

    @Test
    void registerUserHashesPasswordAssignsDefaultRoleAndSaves() {
        RegisterDTO request = new RegisterDTO("Novo Usuario", "new@valora.dev", "12345678");
        when(userRepository.existsByEmail("new@valora.dev")).thenReturn(false);
        when(passwordEncoder.encode("12345678")).thenReturn("hashed-password");

        User persistedUser = new User();
        persistedUser.setEmail("new@valora.dev");
        persistedUser.setPasswordHash("hashed-password");
        persistedUser.setRole(UserRole.USER);
        when(userRepository.save(any(User.class))).thenReturn(persistedUser);

        User result = userService.registerUser(request);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        User savedUser = captor.getValue();

        assertThat(savedUser.getName()).isEqualTo("Novo Usuario");
        assertThat(savedUser.getEmail()).isEqualTo("new@valora.dev");
        assertThat(savedUser.getPasswordHash()).isEqualTo("hashed-password");
        assertThat(savedUser.getRole()).isEqualTo(UserRole.USER);
        assertThat(result).isSameAs(persistedUser);
    }

    @Test
    void registerUserThrowsConflictWhenEmailAlreadyExists() {
        RegisterDTO request = new RegisterDTO("Usuario", "existing@valora.dev", "12345678");
        when(userRepository.existsByEmail("existing@valora.dev")).thenReturn(true);

        assertThatThrownBy(() -> userService.registerUser(request))
                .isInstanceOf(EmailAlreadyRegisteredException.class)
                .hasMessage("Email ja cadastrado.");
    }
}
