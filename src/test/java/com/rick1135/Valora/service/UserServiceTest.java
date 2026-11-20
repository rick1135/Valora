package com.rick1135.Valora.service;

import com.rick1135.Valora.dto.request.ChangePasswordRequestDTO;
import com.rick1135.Valora.dto.request.DeleteAccountRequestDTO;
import com.rick1135.Valora.dto.request.RegisterDTO;
import com.rick1135.Valora.dto.request.UpdateProfileRequestDTO;
import com.rick1135.Valora.dto.response.AccountProfileResponseDTO;
import com.rick1135.Valora.entity.Portfolio;
import com.rick1135.Valora.entity.User;
import com.rick1135.Valora.entity.UserRole;
import com.rick1135.Valora.exception.EmailAlreadyRegisteredException;
import com.rick1135.Valora.repository.PortfolioRepository;
import com.rick1135.Valora.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PortfolioRepository portfolioRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private TokenService tokenService;

    @Mock
    private EntityManager entityManager;

    @Mock
    private Query deleteTransactions;

    @Mock
    private Query deletePositions;

    @Mock
    private Query deleteProventProvisions;

    @Mock
    private Query deleteProvents;

    @Mock
    private Query deleteAssets;

    private UserService userService;

    @BeforeEach
    void setUp() {
        userService = new UserService(userRepository, portfolioRepository, passwordEncoder, tokenService);
        ReflectionTestUtils.setField(userService, "entityManager", entityManager);
    }

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

        ArgumentCaptor<com.rick1135.Valora.entity.Portfolio> portfolioCaptor =
                ArgumentCaptor.forClass(com.rick1135.Valora.entity.Portfolio.class);
        verify(portfolioRepository).save(portfolioCaptor.capture());
        assertThat(portfolioCaptor.getValue().getUser()).isSameAs(persistedUser);
        assertThat(portfolioCaptor.getValue().getName()).isEqualTo("Principal");
    }

    @Test
    void registerUserThrowsConflictWhenEmailAlreadyExists() {
        RegisterDTO request = new RegisterDTO("Usuario", "existing@valora.dev", "12345678");
        when(userRepository.existsByEmail("existing@valora.dev")).thenReturn(true);

        assertThatThrownBy(() -> userService.registerUser(request))
                .isInstanceOf(EmailAlreadyRegisteredException.class)
                .hasMessage("Email ja cadastrado.");
    }

    @Test
    void getProfileReturnsPersistedAccountDetails() {
        User persisted = persistedAccount();
        when(userRepository.findById(persisted.getId())).thenReturn(Optional.of(persisted));
        User principal = new User();
        principal.setId(persisted.getId());

        assertThat(userService.getProfile(principal)).isEqualTo(new AccountProfileResponseDTO(
                persisted.getId(), persisted.getName(), persisted.getEmail(), persisted.getRole(), persisted.getCreatedAt()
        ));
    }

    @Test
    void updateProfileTrimsAndSavesName() {
        User persisted = persistedAccountForUpdate();
        when(userRepository.save(persisted)).thenReturn(persisted);

        var profile = userService.updateProfile(persisted, new UpdateProfileRequestDTO("  Nome Atualizado  "));

        verify(userRepository).save(same(persisted));
        assertThat(profile.name()).isEqualTo("Nome Atualizado");
        assertThat(profile.email()).isEqualTo(persisted.getEmail());
    }

    @Test
    void changePasswordSavesNewHashBeforeRevokingTokens() {
        User persisted = persistedAccountForUpdate();
        persisted.setTokenVersion(8L);
        when(passwordEncoder.matches("current-password", "old-hash")).thenReturn(true);
        when(passwordEncoder.encode("new-password")).thenReturn("new-hash");

        userService.changePassword(persisted, new ChangePasswordRequestDTO("current-password", "new-password"));

        assertThat(persisted.getPasswordHash()).isEqualTo("new-hash");
        assertThat(persisted.getTokenVersion()).isEqualTo(9L);
        var order = inOrder(userRepository, tokenService);
        order.verify(userRepository).save(same(persisted));
        order.verify(tokenService).revokeAllUserTokens(persisted.getId());
    }

    @Test
    void changePasswordDoesNotRevokeTokensOnRollback() {
        User persisted = persistedAccountForUpdate();
        when(passwordEncoder.matches("current-password", "old-hash")).thenReturn(true);
        when(passwordEncoder.encode("new-password")).thenReturn("new-hash");

        TransactionSynchronizationManager.initSynchronization();
        try {
            userService.changePassword(persisted, new ChangePasswordRequestDTO("current-password", "new-password"));

            verify(userRepository).save(same(persisted));
            assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);
            verifyNoInteractions(tokenService);
            TransactionSynchronizationUtils.triggerAfterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
            verifyNoInteractions(tokenService);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void changePasswordRejectsInvalidCurrentPassword() {
        User persisted = persistedAccountForUpdate();

        assertThatThrownBy(() -> userService.changePassword(
                persisted, new ChangePasswordRequestDTO("wrong-password", "new-password")))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("Senha atual invalida.");

        verify(passwordEncoder).matches("wrong-password", "old-hash");
        verify(passwordEncoder, never()).encode(anyString());
        verify(userRepository, never()).save(any());
        assertThat(persisted.getTokenVersion()).isZero();
        verifyNoInteractions(tokenService);
    }

    @Test
    void changePasswordRejectsPasswordMatchingExistingHash() {
        User persisted = persistedAccountForUpdate();
        when(passwordEncoder.matches("current-password", "old-hash")).thenReturn(true);

        assertThatThrownBy(() -> userService.changePassword(
                persisted, new ChangePasswordRequestDTO("current-password", "current-password")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("A nova senha deve ser diferente da senha atual.");

        verify(passwordEncoder, never()).encode(anyString());
        verify(userRepository, never()).save(any());
        verifyNoInteractions(tokenService);
    }

    @Test
    void deleteAccountDeletesPrivateAssetsBeforeUserAndRevokesTokensAfterCommit() {
        User persisted = persistedAccountForUpdate();
        Portfolio portfolio = new Portfolio();
        portfolio.setId(UUID.randomUUID());
        portfolio.setUser(persisted);
        List<Portfolio> portfolios = List.of(portfolio);
        when(passwordEncoder.matches("current-password", "old-hash")).thenReturn(true);
        when(portfolioRepository.findByUserOrderByCreatedAtAsc(persisted)).thenReturn(portfolios);
        when(entityManager.createQuery("delete from Transaction t where t.portfolio in :portfolios"))
                .thenReturn(deleteTransactions);
        when(entityManager.createQuery("delete from Position p where p.portfolio in :portfolios"))
                .thenReturn(deletePositions);
        when(entityManager.createQuery("delete from ProventProvision p where p.portfolio in :portfolios"))
                .thenReturn(deleteProventProvisions);
        when(entityManager.createQuery("""
                delete from Provent p
                where p.asset in (select a from Asset a where a.ownerUser = :user)
                """)).thenReturn(deleteProvents);
        when(entityManager.createQuery("delete from Asset a where a.ownerUser = :user")).thenReturn(deleteAssets);
        when(deleteTransactions.setParameter("portfolios", portfolios)).thenReturn(deleteTransactions);
        when(deletePositions.setParameter("portfolios", portfolios)).thenReturn(deletePositions);
        when(deleteProventProvisions.setParameter("portfolios", portfolios)).thenReturn(deleteProventProvisions);
        when(deleteProvents.setParameter("user", persisted)).thenReturn(deleteProvents);
        when(deleteAssets.setParameter("user", persisted)).thenReturn(deleteAssets);

        TransactionSynchronizationManager.initSynchronization();
        try {
            userService.deleteAccount(persisted, new DeleteAccountRequestDTO("current-password"));

            assertThat(persisted.getTokenVersion()).isEqualTo(1L);
            var order = inOrder(deleteTransactions, deletePositions, deleteProventProvisions,
                    portfolioRepository, deleteProvents, deleteAssets, userRepository, tokenService);
            order.verify(deleteTransactions).executeUpdate();
            order.verify(deletePositions).executeUpdate();
            order.verify(deleteProventProvisions).executeUpdate();
            order.verify(portfolioRepository).deleteAll(portfolios);
            order.verify(portfolioRepository).flush();
            order.verify(deleteProvents).executeUpdate();
            order.verify(deleteAssets).executeUpdate();
            order.verify(userRepository).delete(same(persisted));
            order.verify(userRepository).flush();
            verifyNoInteractions(tokenService);

            TransactionSynchronizationUtils.triggerAfterCommit();
            order.verify(tokenService).revokeAllUserTokens(persisted.getId());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void deleteAccountRejectsInvalidCurrentPassword() {
        User persisted = persistedAccountForUpdate();

        assertThatThrownBy(() -> userService.deleteAccount(persisted, new DeleteAccountRequestDTO("wrong-password")))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("Senha atual invalida.");

        verify(passwordEncoder).matches("wrong-password", "old-hash");
        verify(userRepository, never()).delete(any());
        assertThat(persisted.getTokenVersion()).isZero();
        verifyNoInteractions(tokenService, portfolioRepository);
        verify(entityManager, never()).createQuery(anyString());
    }

    private User persistedAccountForUpdate() {
        User user = persistedAccount();
        when(entityManager.find(User.class, user.getId(), LockModeType.PESSIMISTIC_WRITE)).thenReturn(user);
        return user;
    }

    private User persistedAccount() {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setName("Usuario");
        user.setEmail("user@valora.dev");
        user.setPasswordHash("old-hash");
        user.setRole(UserRole.USER);
        user.setCreatedAt(LocalDateTime.of(2026, 1, 1, 12, 0));
        return user;
    }
}
