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
import com.rick1135.Valora.exception.GlobalExceptionHandler;
import com.rick1135.Valora.exception.InvalidTokenException;
import com.rick1135.Valora.repository.PortfolioRepository;
import com.rick1135.Valora.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class UserService {
    private final UserRepository userRepository;
    private final PortfolioRepository portfolioRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;

    @PersistenceContext
    private EntityManager entityManager;

    public UserService(UserRepository userRepository, PortfolioRepository portfolioRepository, PasswordEncoder passwordEncoder) {
        this(userRepository, portfolioRepository, passwordEncoder, null);
    }

    @Autowired
    public UserService(
            UserRepository userRepository,
            PortfolioRepository portfolioRepository,
            PasswordEncoder passwordEncoder,
            TokenService tokenService
    ) {
        this.userRepository = userRepository;
        this.portfolioRepository = portfolioRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
    }

    @Transactional
    public User registerUser(RegisterDTO data) {
        if (userRepository.existsByEmail(data.email())) {
            throw new EmailAlreadyRegisteredException("Email ja cadastrado.");
        }

        User newUser = new User();
        newUser.setEmail(data.email());
        newUser.setName(data.name());
        newUser.setPasswordHash(passwordEncoder.encode(data.password()));
        newUser.setRole(UserRole.USER);

        User savedUser = userRepository.save(newUser);
        Portfolio defaultPortfolio = new Portfolio();
        defaultPortfolio.setUser(savedUser);
        defaultPortfolio.setName(Portfolio.DEFAULT_NAME);
        portfolioRepository.save(defaultPortfolio);

        return savedUser;
    }

    @Transactional(readOnly = true)
    public User getByEmail(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("Usuario nao encontrado."));
    }

    @Transactional(readOnly = true)
    public User getById(UUID id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new InvalidTokenException(GlobalExceptionHandler.GENERIC_REFRESH_TOKEN_MESSAGE));
    }

    @Transactional(readOnly = true)
    public AccountProfileResponseDTO getProfile(User user) {
        return toProfile(findAccount(user.getId()));
    }

    @Transactional
    public AccountProfileResponseDTO updateProfile(User user, UpdateProfileRequestDTO request) {
        User persisted = findAccount(user.getId());
        persisted.setName(request.name().trim());
        return toProfile(userRepository.save(persisted));
    }

    @Transactional
    public void changePassword(User user, ChangePasswordRequestDTO request) {
        User persisted = findAccount(user.getId());
        verifyCurrentPassword(persisted, request.currentPassword());
        if (passwordEncoder.matches(request.newPassword(), persisted.getPasswordHash())) {
            throw new IllegalArgumentException("A nova senha deve ser diferente da senha atual.");
        }

        persisted.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        userRepository.save(persisted);
        if (tokenService != null) {
            tokenService.revokeAllUserTokens(persisted.getId());
        }
    }

    @Transactional
    public void deleteAccount(User user, DeleteAccountRequestDTO request) {
        User persisted = findAccount(user.getId());
        verifyCurrentPassword(persisted, request.currentPassword());
        if (tokenService != null) {
            tokenService.revokeAllUserTokens(persisted.getId());
        }

        var portfolios = portfolioRepository.findByUserOrderByCreatedAtAsc(persisted);
        if (entityManager != null && !portfolios.isEmpty()) {
            // These relationships have foreign keys without ON DELETE CASCADE.
            entityManager.createQuery("delete from Transaction t where t.portfolio in :portfolios")
                    .setParameter("portfolios", portfolios).executeUpdate();
            entityManager.createQuery("delete from Position p where p.portfolio in :portfolios")
                    .setParameter("portfolios", portfolios).executeUpdate();
            entityManager.createQuery("delete from ProventProvision p where p.portfolio in :portfolios")
                    .setParameter("portfolios", portfolios).executeUpdate();
        }
        portfolioRepository.deleteAll(portfolios);
        portfolioRepository.flush();
        userRepository.delete(persisted);
    }

    private User findAccount(UUID id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new UsernameNotFoundException("Usuario nao encontrado."));
    }

    private void verifyCurrentPassword(User user, String currentPassword) {
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new BadCredentialsException("Senha atual invalida.");
        }
    }

    private AccountProfileResponseDTO toProfile(User user) {
        return new AccountProfileResponseDTO(
                user.getId(), user.getName(), user.getEmail(), user.getRole(), user.getCreatedAt()
        );
    }
}
