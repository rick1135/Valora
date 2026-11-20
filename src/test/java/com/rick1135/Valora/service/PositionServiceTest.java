package com.rick1135.Valora.service;

import com.rick1135.Valora.dto.response.PositionResponseDTO;
import com.rick1135.Valora.entity.Asset;
import com.rick1135.Valora.entity.AssetCategory;
import com.rick1135.Valora.entity.FixedIncomeIndexer;
import com.rick1135.Valora.entity.FixedIncomeRateType;
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
import java.time.LocalDate;
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

    @Mock
    private FixedIncomeValuationService fixedIncomeValuationService;

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
        assertThat(portfolio.getFirst().valuedThrough()).isNull();
        assertThat(portfolio.getFirst().pendingLatestObservation()).isFalse();
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

    @Test
    void getUserPortfolioShouldExposeFixedIncomeCoverageWithoutCallingQuoteService() {
        User user = new User();
        Portfolio portfolioEntity = new Portfolio();
        portfolioEntity.setId(UUID.randomUUID());
        portfolioEntity.setUser(user);
        portfolioEntity.setName("Renda Fixa");

        Asset cdb = new Asset();
        cdb.setId(UUID.randomUUID());
        cdb.setTicker("CDB_CDI");
        cdb.setName("CDB CDI");
        cdb.setCategory(AssetCategory.RENDA_FIXA);
        cdb.setIndexer(FixedIncomeIndexer.CDI);
        cdb.setRateType(FixedIncomeRateType.PERCENTAGE_OF_INDEX);
        cdb.setAnnualRate(new BigDecimal("100.00"));

        Position cdbPosition = new Position();
        cdbPosition.setId(UUID.randomUUID());
        cdbPosition.setPortfolio(portfolioEntity);
        cdbPosition.setAsset(cdb);
        cdbPosition.setQuantity(new BigDecimal("2.00000000"));
        cdbPosition.setAveragePrice(new BigDecimal("1000.00000000"));
        LocalDate valuedThrough = LocalDate.of(2025, 4, 22);

        when(portfolioService.resolveOwnedPortfolio(user, portfolioEntity.getId())).thenReturn(portfolioEntity);
        when(positionRepository.findByPortfolio(portfolioEntity)).thenReturn(List.of(cdbPosition));
        when(fixedIncomeValuationService.valuatePortfolioPositions(portfolioEntity, List.of(cdbPosition)))
                .thenReturn(Map.of(cdbPosition, new FixedIncomeValuationService.FixedIncomeValuationResult(
                        new BigDecimal("2200.00000000"),
                        new BigDecimal("1100.00000000"),
                        true,
                        valuedThrough,
                        true
                )));

        List<PositionResponseDTO> result = positionService.getUserPortfolio(user, portfolioEntity.getId());

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().ticker()).isEqualTo("CDB_CDI");
        assertThat(result.getFirst().currentPrice()).isEqualByComparingTo("1100.00000000");
        assertThat(result.getFirst().currentTotalValue()).isEqualByComparingTo("2200.00000000");
        assertThat(result.getFirst().profitability()).isEqualByComparingTo("10.0000");
        assertThat(result.getFirst().valuedThrough()).isEqualTo(valuedThrough);
        assertThat(result.getFirst().pendingLatestObservation()).isTrue();
        verify(quoteService, never()).getCurrentPrices(any());
    }

    @Test
    void getUserPortfolioShouldLeaveCurrentValueAndProfitabilityNullWhenFixedIncomeValuationIsUnreliable() {
        User user = new User();
        Portfolio portfolioEntity = new Portfolio();
        portfolioEntity.setId(UUID.randomUUID());
        portfolioEntity.setUser(user);
        portfolioEntity.setName("Renda Fixa Legada");

        Asset cdbCdi = new Asset();
        cdbCdi.setId(UUID.randomUUID());
        cdbCdi.setTicker("CDB_CDI");
        cdbCdi.setName("CDB CDI");
        cdbCdi.setCategory(AssetCategory.RENDA_FIXA);
        cdbCdi.setIndexer(FixedIncomeIndexer.CDI);
        cdbCdi.setAnnualRate(new BigDecimal("110.00"));

        Position cdbPosition = new Position();
        cdbPosition.setId(UUID.randomUUID());
        cdbPosition.setPortfolio(portfolioEntity);
        cdbPosition.setAsset(cdbCdi);
        cdbPosition.setQuantity(new BigDecimal("1.00000000"));
        cdbPosition.setAveragePrice(new BigDecimal("1000.00000000"));

        when(portfolioService.resolveOwnedPortfolio(user, portfolioEntity.getId())).thenReturn(portfolioEntity);
        when(positionRepository.findByPortfolio(portfolioEntity)).thenReturn(List.of(cdbPosition));
        when(fixedIncomeValuationService.valuatePortfolioPositions(portfolioEntity, List.of(cdbPosition)))
                .thenReturn(Map.of(cdbPosition, new FixedIncomeValuationService.FixedIncomeValuationResult(
                        new BigDecimal("1000.00000000"),
                        new BigDecimal("1000.00000000"),
                        false
                )));

        List<PositionResponseDTO> result = positionService.getUserPortfolio(user, portfolioEntity.getId());

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().currentPrice()).isNull();
        assertThat(result.getFirst().currentTotalValue()).isNull();
        assertThat(result.getFirst().profitability()).isNull();
        assertThat(result.getFirst().valuedThrough()).isNull();
        assertThat(result.getFirst().pendingLatestObservation()).isFalse();
        verify(quoteService, never()).getCurrentPrices(any());
    }
}
