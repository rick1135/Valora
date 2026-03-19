package com.rick1135.Valora.service;

import com.rick1135.Valora.dto.response.PositionResponseDTO;
import com.rick1135.Valora.entity.User;
import com.rick1135.Valora.repository.PositionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PositionService {
    private final PositionRepository positionRepository;

    public List<PositionResponseDTO> getUserPortfolio(User user) {
        return positionRepository.findByUser(user).stream()
                .filter(position -> position.getQuantity().compareTo(BigDecimal.ZERO) > 0)
                .map(position -> new PositionResponseDTO(
                        position.getAsset().getId(),
                        position.getAsset().getTicker(),
                        position.getAsset().getName(),
                        position.getQuantity(),
                        position.getAveragePrice(),
                        position.getQuantity().multiply(position.getAveragePrice())
                )).collect(Collectors.toList());
    }
}
