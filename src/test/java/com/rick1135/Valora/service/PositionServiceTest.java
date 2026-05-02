package com.rick1135.Valora.service;

import com.rick1135.Valora.dto.response.PositionResponseDTO;
import com.rick1135.Valora.entity.Asset;
import com.rick1135.Valora.entity.Position;
import com.rick1135.Valora.entity.User;
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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PositionServiceTest {

    @Mock
    private PositionRepository positionRepository;

    @Mock
    private QuoteService quoteService;

    @Spy
    private PositionMapper positionMapper = Mappers.getMapper(PositionMapper.class);

    @InjectMocks
    private PositionService positionService;

    @Test
    void getUserPortfolioShouldFilterZeroQuantityAndComputeTotalCost() {
        User user = new User();

        Asset assetWithQuantity = new Asset();
        assetWithQuantity.setId(UUID.randomUUID());
        assetWithQuantity.setTicker("ITSA4");
        assetWithQuantity.setName("Itausa");

        Position positivePosition = new Position();
        positivePosition.setUser(user);
        positivePosition.setAsset(assetWithQuantity);
        positivePosition.setQuantity(new BigDecimal("2.00000000"));
        positivePosition.setAveragePrice(new BigDecimal("10.50000000"));

        Asset zeroAsset = new Asset();
        zeroAsset.setId(UUID.randomUUID());
        zeroAsset.setTicker("PETR4");
        zeroAsset.setName("Petrobras");

        Position zeroPosition = new Position();
        zeroPosition.setUser(user);
        zeroPosition.setAsset(zeroAsset);
        zeroPosition.setQuantity(BigDecimal.ZERO);
        zeroPosition.setAveragePrice(new BigDecimal("30.00000000"));

        when(positionRepository.findByUser(user)).thenReturn(List.of(positivePosition, zeroPosition));
        when(quoteService.getCurrentPrices(List.of("ITSA4"))).thenReturn(Map.of("ITSA4", new BigDecimal("12.00000000")));

        List<PositionResponseDTO> portfolio = positionService.getUserPortfolio(user);

        assertThat(portfolio).hasSize(1);
        assertThat(portfolio.getFirst().ticker()).isEqualTo("ITSA4");
        assertThat(portfolio.getFirst().totalCost()).isEqualByComparingTo("21.0000000000000000");
        assertThat(portfolio.getFirst().currentPrice()).isEqualByComparingTo("12.00000000");
    }
}
