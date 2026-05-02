package com.rick1135.Valora.service;

import com.rick1135.Valora.dto.request.RegisterDTO;
import com.rick1135.Valora.entity.Portfolio;
import com.rick1135.Valora.entity.User;
import com.rick1135.Valora.entity.UserRole;
import com.rick1135.Valora.exception.EmailAlreadyRegisteredException;
import com.rick1135.Valora.repository.PortfolioRepository;
import com.rick1135.Valora.repository.UserRepository;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {
    private final UserRepository userRepository;
    private final PortfolioRepository portfolioRepository;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository, PortfolioRepository portfolioRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.portfolioRepository = portfolioRepository;
        this.passwordEncoder = passwordEncoder;
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
}
