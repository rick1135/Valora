package com.rick1135.Valora.service;

import com.rick1135.Valora.dto.response.PositionResponseDTO;
import com.rick1135.Valora.entity.Asset;
import com.rick1135.Valora.entity.Portfolio;
import com.rick1135.Valora.entity.Position;
import com.rick1135.Valora.entity.User;
import com.rick1135.Valora.exception.PortfolioNotFoundException;
import com.rick1135.Valora.mapper.PositionMapper;
import com.rick1135.Valora.repository.PositionRepository;
import org.mapstruct.factory.Mappers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PositionServiceTest {

    @Mock
    private PositionRepository positionRepository;

    @Mock
    private QuoteService quoteService;

    @Mock
    private PortfolioService portfolioService;

    @Spy
    private PositionMapper positionMapper = Mappers.getMapper(PositionMapper.class);

    @InjectMocks
    private PositionService positionService;

    @Test
    void getUserPortfolioShouldFilterZeroQuantityAndComputeTotalCost() {
        User user = new User();
        Portfolio portfolioEntity = new Portfolio();
        portfolioEntity.setId(UUID.randomUUID());
        portfolioEntity.setUser(user);
        portfolioEntity.setName("Principal");

        Asset assetWithQuantity = new Asset();
        assetWithQuantity.setId(UUID.randomUUID());
        assetWithQuantity.setTicker("ITSA4");
        assetWithQuantity.setName("Itausa");

        Position positivePosition = new Position();
        positivePosition.setPortfolio(portfolioEntity);
        positivePosition.setAsset(assetWithQuantity);
        positivePosition.setQuantity(new BigDecimal("2.00000000"));
        positivePosition.setAveragePrice(new BigDecimal("10.50000000"));

        Asset zeroAsset = new Asset();
        zeroAsset.setId(UUID.randomUUID());
        zeroAsset.setTicker("PETR4");
        zeroAsset.setName("Petrobras");

        Position zeroPosition = new Position();
        zeroPosition.setPortfolio(portfolioEntity);
        zeroPosition.setAsset(zeroAsset);
        zeroPosition.setQuantity(BigDecimal.ZERO);
        zeroPosition.setAveragePrice(new BigDecimal("30.00000000"));

        when(portfolioService.resolveOwnedPortfolio(user, portfolioEntity.getId())).thenReturn(portfolioEntity);
        when(positionRepository.findByPortfolio(portfolioEntity)).thenReturn(List.of(positivePosition, zeroPosition));
        when(quoteService.getCurrentPrices(List.of("ITSA4"))).thenReturn(Map.of("ITSA4", new BigDecimal("12.00000000")));

        List<PositionResponseDTO> portfolio = positionService.getUserPortfolio(user, portfolioEntity.getId());

        assertThat(portfolio).hasSize(1);
        assertThat(portfolio.getFirst().ticker()).isEqualTo("ITSA4");
        assertThat(portfolio.getFirst().totalCost()).isEqualByComparingTo("21.0000000000000000");
        assertThat(portfolio.getFirst().currentPrice()).isEqualByComparingTo("12.00000000");
    }

    @Test
    void getUserPortfolioShouldRejectPortfolioNotOwnedBeforeQueryingPositions() {
        User user = new User();
        UUID portfolioId = UUID.randomUUID();
        when(portfolioService.resolveOwnedPortfolio(user, portfolioId))
                .thenThrow(new PortfolioNotFoundException("Carteira nao encontrada."));

        assertThatThrownBy(() -> positionService.getUserPortfolio(user, portfolioId))
                .isInstanceOf(PortfolioNotFoundException.class);
        verify(positionRepository, never()).findByPortfolio(any());
    }
}
