package com.rick1135.Valora.service;

import com.rick1135.Valora.dto.request.ProventRequestDTO;
import com.rick1135.Valora.dto.response.ProventProvisionResponseDTO;
import com.rick1135.Valora.dto.response.ProventResponseDTO;
import com.rick1135.Valora.entity.*;
import com.rick1135.Valora.exception.AssetNotFoundException;
import com.rick1135.Valora.exception.PortfolioNotFoundException;
import com.rick1135.Valora.exception.ProventAlreadyExistsException;
import com.rick1135.Valora.mapper.ProventMapper;
import com.rick1135.Valora.repository.*;
import com.rick1135.Valora.repository.projection.UserAssetHoldingProjection;
import org.mapstruct.factory.Mappers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProventServiceTest {

    @Mock
    private AssetRepository assetRepository;
    @Mock
    private ProventRepository proventRepository;
    @Mock
    private ProventProvisionRepository proventProvisionRepository;
    @Mock
    private TransactionRepository transactionRepository;
    @Mock
    private PortfolioRepository portfolioRepository;
    @Mock
    private PortfolioService portfolioService;
    @Mock
    private MarketCalendar marketCalendar;

    @Spy
    private ProventMapper proventMapper = Mappers.getMapper(ProventMapper.class);

    @InjectMocks
    private ProventService proventService;

    @Test
    void createProventShouldProvisionDividendWithoutTax() {
        Asset asset = new Asset();
        asset.setId(UUID.randomUUID());
        asset.setTicker("ITSA4");
        asset.setName("Itausa");
        asset.setCategory(AssetCategory.ACOES);

        Portfolio portfolio = portfolio();

        LocalDate comDate = LocalDate.of(2026, 3, 20);
        LocalDate paymentDate = LocalDate.of(2026, 3, 30);
        ProventRequestDTO request = new ProventRequestDTO(
                asset.getId(),
                ProventType.DIVIDEND,
                new BigDecimal("1.23000000"),
                comDate,
                paymentDate
        );

        when(assetRepository.findById(asset.getId())).thenReturn(Optional.of(asset));
        when(proventRepository.existsByOriginSourceAndOriginEventKey(eq(ProventSource.MANUAL), anyString()))
                .thenReturn(false);
        when(proventRepository.save(any(Provent.class))).thenAnswer(invocation -> {
            Provent provent = invocation.getArgument(0);
            provent.setId(UUID.randomUUID());
            return provent;
        });
        when(transactionRepository.findPortfolioHoldingsByAssetAtDate(eq(asset.getId()), any(Instant.class), eq(TransactionType.BUY)))
                .thenReturn(List.of(holding(portfolio.getId(), new BigDecimal("10.00000000"))));
        when(portfolioRepository.findAllById(any())).thenReturn(List.of(portfolio));
        when(marketCalendar.endOfMarketDay(comDate)).thenReturn(Instant.parse("2026-03-21T02:59:59.999999999Z"));

        ProventResponseDTO response = proventService.createProvent(request);

        assertThat(response.provisionedUsers()).isEqualTo(1);
        assertThat(response.type()).isEqualTo(ProventType.DIVIDEND);

        ArgumentCaptor<List<ProventProvision>> captor = ArgumentCaptor.forClass(List.class);
        verify(proventProvisionRepository).saveAll(captor.capture());
        ProventProvision provision = captor.getValue().getFirst();
        assertThat(provision.getGrossAmount()).isEqualByComparingTo("12.30000000");
        assertThat(provision.getWithholdingTaxAmount()).isEqualByComparingTo("0.00000000");
        assertThat(provision.getNetAmount()).isEqualByComparingTo("12.30000000");
        assertThat(provision.getStatus()).isEqualTo(ProventStatus.PENDING);
        assertThat(provision.getPortfolio()).isSameAs(portfolio);

        ArgumentCaptor<Provent> proventCaptor = ArgumentCaptor.forClass(Provent.class);
        verify(proventRepository).save(proventCaptor.capture());
        assertThat(proventCaptor.getValue().getOriginSource()).isEqualTo(ProventSource.MANUAL);
        assertThat(proventCaptor.getValue().getOriginEventKey()).isNotBlank();
        assertThat(proventCaptor.getValue().getOriginRateBasis()).isEqualTo(ProventRateBasis.NET);
    }

    @Test
    void createProventShouldNotApplyTaxForJcpWhenAmountIsAlreadyNet() {
        Asset asset = new Asset();
        asset.setId(UUID.randomUUID());
        asset.setTicker("BBAS3");
        asset.setCategory(AssetCategory.ACOES);

        Portfolio portfolio = portfolio();

        LocalDate comDate = LocalDate.of(2026, 3, 20);
        LocalDate paymentDate = LocalDate.of(2026, 4, 5);
        ProventRequestDTO request = new ProventRequestDTO(
                asset.getId(),
                ProventType.JCP,
                new BigDecimal("1.00000000"),
                comDate,
                paymentDate
        );

        when(assetRepository.findById(asset.getId())).thenReturn(Optional.of(asset));
        when(proventRepository.existsByOriginSourceAndOriginEventKey(eq(ProventSource.MANUAL), anyString()))
                .thenReturn(false);
        when(proventRepository.save(any(Provent.class))).thenAnswer(invocation -> {
            Provent provent = invocation.getArgument(0);
            provent.setId(UUID.randomUUID());
            return provent;
        });
        when(transactionRepository.findPortfolioHoldingsByAssetAtDate(eq(asset.getId()), any(Instant.class), eq(TransactionType.BUY)))
                .thenReturn(List.of(holding(portfolio.getId(), new BigDecimal("10.00000000"))));
        when(portfolioRepository.findAllById(any())).thenReturn(List.of(portfolio));
        when(marketCalendar.endOfMarketDay(comDate)).thenReturn(Instant.parse("2026-03-21T02:59:59.999999999Z"));

        proventService.createProvent(request);

        ArgumentCaptor<List<ProventProvision>> captor = ArgumentCaptor.forClass(List.class);
        verify(proventProvisionRepository).saveAll(captor.capture());
        ProventProvision provision = captor.getValue().getFirst();
        assertThat(provision.getGrossAmount()).isEqualByComparingTo("10.00000000");
        assertThat(provision.getWithholdingTaxAmount()).isEqualByComparingTo("0.00000000");
        assertThat(provision.getNetAmount()).isEqualByComparingTo("10.00000000");

        ArgumentCaptor<Provent> proventCaptor = ArgumentCaptor.forClass(Provent.class);
        verify(proventRepository).save(proventCaptor.capture());
        assertThat(proventCaptor.getValue().getOriginSource()).isEqualTo(ProventSource.MANUAL);
        assertThat(proventCaptor.getValue().getOriginRateBasis()).isEqualTo(ProventRateBasis.NET);
    }

    @Test
    void createProventShouldRejectPaymentDateBeforeComDate() {
        ProventRequestDTO request = new ProventRequestDTO(
                UUID.randomUUID(),
                ProventType.DIVIDEND,
                new BigDecimal("1.00000000"),
                LocalDate.of(2026, 3, 20),
                LocalDate.of(2026, 3, 19)
        );

        assertThatThrownBy(() -> proventService.createProvent(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("data de pagamento");
    }

    @Test
    void createProventShouldUseEndOfComDateMarketDay() {
        Asset asset = new Asset();
        asset.setId(UUID.randomUUID());
        asset.setTicker("TAEE11");
        asset.setCategory(AssetCategory.ACOES);

        LocalDate comDate = LocalDate.of(2026, 3, 20);
        ProventRequestDTO request = new ProventRequestDTO(
                asset.getId(),
                ProventType.DIVIDEND,
                new BigDecimal("0.50000000"),
                comDate,
                LocalDate.of(2026, 3, 30)
        );

        when(assetRepository.findById(asset.getId())).thenReturn(Optional.of(asset));
        when(proventRepository.existsByOriginSourceAndOriginEventKey(eq(ProventSource.MANUAL), anyString()))
                .thenReturn(false);
        when(proventRepository.save(any(Provent.class))).thenAnswer(invocation -> {
            Provent provent = invocation.getArgument(0);
            provent.setId(UUID.randomUUID());
            return provent;
        });
        when(transactionRepository.findPortfolioHoldingsByAssetAtDate(eq(asset.getId()), any(Instant.class), eq(TransactionType.BUY)))
                .thenReturn(List.of());
        when(portfolioRepository.findAllById(any())).thenReturn(List.of());
        Instant expectedEndOfMarketDay = LocalDate.of(2026, 3, 20)
                .plusDays(1)
                .atStartOfDay(ZoneId.of("America/Sao_Paulo"))
                .toInstant()
                .minusNanos(1);
        when(marketCalendar.endOfMarketDay(comDate)).thenReturn(expectedEndOfMarketDay);

        proventService.createProvent(request);

        ArgumentCaptor<Instant> dateCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(transactionRepository).findPortfolioHoldingsByAssetAtDate(
                eq(asset.getId()),
                dateCaptor.capture(),
                eq(TransactionType.BUY)
        );
        assertThat(dateCaptor.getValue()).isEqualTo(expectedEndOfMarketDay);
    }

    @Test
    void createProventShouldRejectWhenDuplicated() {
        UUID assetId = UUID.randomUUID();
        LocalDate comDate = LocalDate.of(2026, 3, 20);
        LocalDate paymentDate = LocalDate.of(2026, 3, 25);
        ProventRequestDTO request = new ProventRequestDTO(
                assetId,
                ProventType.DIVIDEND,
                new BigDecimal("0.50000000"),
                comDate,
                paymentDate
        );

        Asset asset = new Asset();
        asset.setId(assetId);
        when(assetRepository.findById(assetId)).thenReturn(Optional.of(asset));
        when(proventRepository.existsByOriginSourceAndOriginEventKey(eq(ProventSource.MANUAL), anyString()))
                .thenReturn(true);

        assertThatThrownBy(() -> proventService.createProvent(request))
                .isInstanceOf(ProventAlreadyExistsException.class);
    }

    @Test
    void createProventShouldFailWhenAssetDoesNotExist() {
        UUID assetId = UUID.randomUUID();
        ProventRequestDTO request = new ProventRequestDTO(
                assetId,
                ProventType.DIVIDEND,
                new BigDecimal("1.00000000"),
                LocalDate.of(2026, 3, 20),
                LocalDate.of(2026, 3, 25)
        );
        when(assetRepository.findById(assetId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> proventService.createProvent(request))
                .isInstanceOf(AssetNotFoundException.class);
    }

    @Test
    void getMyProventsShouldMapRepositoryResult() {
        User user = new User();
        user.setId(UUID.randomUUID());
        Portfolio portfolio = portfolio();
        portfolio.setUser(user);

        Asset asset = new Asset();
        asset.setId(UUID.randomUUID());
        asset.setTicker("TAEE11");

        Provent provent = new Provent();
        provent.setId(UUID.randomUUID());
        provent.setAsset(asset);
        provent.setType(ProventType.DIVIDEND);
        provent.setAmountPerShare(new BigDecimal("0.20000000"));
        provent.setOriginSource(ProventSource.MANUAL);
        provent.setOriginEventKey("manual-key");
        provent.setOriginRateBasis(ProventRateBasis.NET);
        provent.setComDate(LocalDate.of(2026, 3, 10));
        provent.setPaymentDate(LocalDate.of(2026, 3, 20));

        ProventProvision provision = new ProventProvision();
        provision.setId(UUID.randomUUID());
        provision.setPortfolio(portfolio);
        provision.setAsset(asset);
        provision.setProvent(provent);
        provision.setQuantityOnComDate(new BigDecimal("100.00000000"));
        provision.setGrossAmount(new BigDecimal("20.00000000"));
        provision.setWithholdingTaxAmount(BigDecimal.ZERO.setScale(8));
        provision.setNetAmount(new BigDecimal("20.00000000"));
        provision.setStatus(ProventStatus.PENDING);

        PageRequest pageable = PageRequest.of(0, 20);
        when(portfolioService.resolveOwnedPortfolio(user, portfolio.getId())).thenReturn(portfolio);
        when(proventProvisionRepository.findByPortfolio(portfolio, pageable))
                .thenReturn(new PageImpl<>(List.of(provision), pageable, 1));

        Page<ProventProvisionResponseDTO> result = proventService.getMyProvents(user, portfolio.getId(), pageable);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().getFirst().ticker()).isEqualTo("TAEE11");
        assertThat(result.getContent().getFirst().netAmount()).isEqualByComparingTo("20.00000000");
        assertThat(result.getContent().getFirst().originEventKey()).isEqualTo("manual-key");
    }

    @Test
    void getMyProventsShouldRejectPortfolioNotOwnedBeforeQueryingProvisions() {
        User user = new User();
        user.setId(UUID.randomUUID());
        UUID portfolioId = UUID.randomUUID();
        PageRequest pageable = PageRequest.of(0, 20);

        when(portfolioService.resolveOwnedPortfolio(user, portfolioId))
                .thenThrow(new PortfolioNotFoundException("Carteira nao encontrada."));

        assertThatThrownBy(() -> proventService.getMyProvents(user, portfolioId, pageable))
                .isInstanceOf(PortfolioNotFoundException.class);
        verify(proventProvisionRepository, never()).findByPortfolio(any(), any());
    }

    @Test
    void createProventShouldApplySeventeenPointFivePercentWithholdingTaxForGrossJcpFrom2026() {
        Asset asset = new Asset();
        asset.setId(UUID.randomUUID());
        asset.setTicker("BBAS3");
        asset.setCategory(AssetCategory.ACOES);

        Portfolio portfolio = portfolio();

        LocalDate comDate = LocalDate.of(2026, 3, 20);
        LocalDate paymentDate = LocalDate.of(2026, 4, 5);
        ProventRequestDTO request = new ProventRequestDTO(
                asset.getId(),
                ProventType.JCP,
                new BigDecimal("1.00000000"),
                comDate,
                paymentDate
        );

        ProventOriginMetadata grossOrigin = grossJcpOrigin("brapi-bbas3-jcp-2026", "BBAS3", new BigDecimal("1.00000000"));

        when(proventRepository.existsByOriginSourceAndOriginEventKey(ProventSource.BRAPI, "brapi-bbas3-jcp-2026"))
                .thenReturn(false);
        when(proventRepository.save(any(Provent.class))).thenAnswer(invocation -> {
            Provent provent = invocation.getArgument(0);
            provent.setId(UUID.randomUUID());
            return provent;
        });
        when(transactionRepository.findPortfolioHoldingsByAssetAtDate(eq(asset.getId()), any(Instant.class), eq(TransactionType.BUY)))
                .thenReturn(List.of(holding(portfolio.getId(), new BigDecimal("100.00000000"))));
        when(portfolioRepository.findAllById(any())).thenReturn(List.of(portfolio));
        when(marketCalendar.endOfMarketDay(comDate)).thenReturn(Instant.parse("2026-03-21T02:59:59.999999999Z"));
        when(marketCalendar.today()).thenReturn(LocalDate.of(2026, 3, 21));

        proventService.createProvent(request, asset, grossOrigin);

        ArgumentCaptor<List<ProventProvision>> captor = ArgumentCaptor.forClass(List.class);
        verify(proventProvisionRepository).saveAll(captor.capture());
        ProventProvision provision = captor.getValue().getFirst();
        assertThat(provision.getGrossAmount()).isEqualByComparingTo("100.00000000");
        assertThat(provision.getWithholdingTaxAmount()).isEqualByComparingTo("17.50000000");
        assertThat(provision.getNetAmount()).isEqualByComparingTo("82.50000000");
        assertThat(provision.getStatus()).isEqualTo(ProventStatus.PENDING);
    }

    @Test
    void createProventShouldApplySeventeenPointFivePercentWithholdingTaxForGrossJcpOnExactEffectiveDate() {
        Asset asset = new Asset();
        asset.setId(UUID.randomUUID());
        asset.setTicker("BBAS3");
        asset.setCategory(AssetCategory.ACOES);

        Portfolio portfolio = portfolio();

        LocalDate comDate = LocalDate.of(2025, 12, 20);
        LocalDate paymentDate = LocalDate.of(2026, 1, 1);
        ProventRequestDTO request = new ProventRequestDTO(
                asset.getId(),
                ProventType.JCP,
                new BigDecimal("1.00000000"),
                comDate,
                paymentDate
        );

        ProventOriginMetadata grossOrigin = grossJcpOrigin("brapi-bbas3-jcp-boundary-2026", "BBAS3", new BigDecimal("1.00000000"));

        when(proventRepository.existsByOriginSourceAndOriginEventKey(ProventSource.BRAPI, "brapi-bbas3-jcp-boundary-2026"))
                .thenReturn(false);
        when(proventRepository.save(any(Provent.class))).thenAnswer(invocation -> {
            Provent provent = invocation.getArgument(0);
            provent.setId(UUID.randomUUID());
            return provent;
        });
        when(transactionRepository.findPortfolioHoldingsByAssetAtDate(eq(asset.getId()), any(Instant.class), eq(TransactionType.BUY)))
                .thenReturn(List.of(holding(portfolio.getId(), new BigDecimal("100.00000000"))));
        when(portfolioRepository.findAllById(any())).thenReturn(List.of(portfolio));
        when(marketCalendar.endOfMarketDay(comDate)).thenReturn(Instant.parse("2025-12-21T02:59:59.999999999Z"));
        when(marketCalendar.today()).thenReturn(LocalDate.of(2026, 1, 2));

        proventService.createProvent(request, asset, grossOrigin);

        ArgumentCaptor<List<ProventProvision>> captor = ArgumentCaptor.forClass(List.class);
        verify(proventProvisionRepository).saveAll(captor.capture());
        ProventProvision provision = captor.getValue().getFirst();
        assertThat(provision.getGrossAmount()).isEqualByComparingTo("100.00000000");
        assertThat(provision.getWithholdingTaxAmount()).isEqualByComparingTo("17.50000000");
        assertThat(provision.getNetAmount()).isEqualByComparingTo("82.50000000");
        assertThat(provision.getStatus()).isEqualTo(ProventStatus.PAID);
    }

    @Test
    void createProventShouldPreserveFifteenPercentWithholdingTaxForGrossJcpBefore2026() {
        Asset asset = new Asset();
        asset.setId(UUID.randomUUID());
        asset.setTicker("BBAS3");
        asset.setCategory(AssetCategory.ACOES);

        Portfolio portfolio = portfolio();

        LocalDate comDate = LocalDate.of(2025, 12, 20);
        LocalDate paymentDate = LocalDate.of(2025, 12, 31);
        ProventRequestDTO request = new ProventRequestDTO(
                asset.getId(),
                ProventType.JCP,
                new BigDecimal("1.00000000"),
                comDate,
                paymentDate
        );

        ProventOriginMetadata grossOrigin = grossJcpOrigin("brapi-bbas3-jcp-2025", "BBAS3", new BigDecimal("1.00000000"));

        when(proventRepository.existsByOriginSourceAndOriginEventKey(ProventSource.BRAPI, "brapi-bbas3-jcp-2025"))
                .thenReturn(false);
        when(proventRepository.save(any(Provent.class))).thenAnswer(invocation -> {
            Provent provent = invocation.getArgument(0);
            provent.setId(UUID.randomUUID());
            return provent;
        });
        when(transactionRepository.findPortfolioHoldingsByAssetAtDate(eq(asset.getId()), any(Instant.class), eq(TransactionType.BUY)))
                .thenReturn(List.of(holding(portfolio.getId(), new BigDecimal("100.00000000"))));
        when(portfolioRepository.findAllById(any())).thenReturn(List.of(portfolio));
        when(marketCalendar.endOfMarketDay(comDate)).thenReturn(Instant.parse("2025-12-21T02:59:59.999999999Z"));
        when(marketCalendar.today()).thenReturn(LocalDate.of(2026, 1, 2));

        proventService.createProvent(request, asset, grossOrigin);

        ArgumentCaptor<List<ProventProvision>> captor = ArgumentCaptor.forClass(List.class);
        verify(proventProvisionRepository).saveAll(captor.capture());
        ProventProvision provision = captor.getValue().getFirst();
        assertThat(provision.getGrossAmount()).isEqualByComparingTo("100.00000000");
        assertThat(provision.getWithholdingTaxAmount()).isEqualByComparingTo("15.00000000");
        assertThat(provision.getNetAmount()).isEqualByComparingTo("85.00000000");
        assertThat(provision.getStatus()).isEqualTo(ProventStatus.PAID);
    }

    @Test
    void createProventShouldNotApplyTaxForNetJcpFrom2026() {
        Asset asset = new Asset();
        asset.setId(UUID.randomUUID());
        asset.setTicker("BBAS3");
        asset.setCategory(AssetCategory.ACOES);

        Portfolio portfolio = portfolio();

        LocalDate comDate = LocalDate.of(2026, 3, 20);
        LocalDate paymentDate = LocalDate.of(2026, 4, 5);
        ProventRequestDTO request = new ProventRequestDTO(
                asset.getId(),
                ProventType.JCP,
                new BigDecimal("1.00000000"),
                comDate,
                paymentDate
        );

        ProventOriginMetadata netOrigin = new ProventOriginMetadata(
                ProventSource.BRAPI,
                "brapi-bbas3-jcp-net-2026",
                "JCP",
                null,
                "BBAS3",
                null,
                null,
                null,
                null,
                new BigDecimal("1.00000000"),
                ProventRateBasis.NET
        );

        when(proventRepository.existsByOriginSourceAndOriginEventKey(ProventSource.BRAPI, "brapi-bbas3-jcp-net-2026"))
                .thenReturn(false);
        when(proventRepository.save(any(Provent.class))).thenAnswer(invocation -> {
            Provent provent = invocation.getArgument(0);
            provent.setId(UUID.randomUUID());
            return provent;
        });
        when(transactionRepository.findPortfolioHoldingsByAssetAtDate(eq(asset.getId()), any(Instant.class), eq(TransactionType.BUY)))
                .thenReturn(List.of(holding(portfolio.getId(), new BigDecimal("100.00000000"))));
        when(portfolioRepository.findAllById(any())).thenReturn(List.of(portfolio));
        when(marketCalendar.endOfMarketDay(comDate)).thenReturn(Instant.parse("2026-03-21T02:59:59.999999999Z"));
        when(marketCalendar.today()).thenReturn(LocalDate.of(2026, 3, 21));

        proventService.createProvent(request, asset, netOrigin);

        ArgumentCaptor<List<ProventProvision>> captor = ArgumentCaptor.forClass(List.class);
        verify(proventProvisionRepository).saveAll(captor.capture());
        ProventProvision provision = captor.getValue().getFirst();
        assertThat(provision.getGrossAmount()).isEqualByComparingTo("100.00000000");
        assertThat(provision.getWithholdingTaxAmount()).isEqualByComparingTo("0.00000000");
        assertThat(provision.getNetAmount()).isEqualByComparingTo("100.00000000");
        assertThat(provision.getStatus()).isEqualTo(ProventStatus.PENDING);
    }

    @Test
    void reconcileProvisionsForPortfolioAndAssetShouldCreateUpdateAndDeleteBasedOnComDateHoldings() {
        Portfolio portfolio = portfolio();
        Asset asset = new Asset();
        asset.setId(UUID.randomUUID());
        asset.setTicker("PETR4");

        LocalDate fromComDate = LocalDate.of(2026, 1, 1);

        Provent pCreate = new Provent();
        pCreate.setId(UUID.randomUUID());
        pCreate.setAsset(asset);
        pCreate.setType(ProventType.JCP);
        pCreate.setOriginRateBasis(ProventRateBasis.GROSS);
        pCreate.setAmountPerShare(new BigDecimal("1.00000000"));
        pCreate.setComDate(LocalDate.of(2026, 2, 10));
        pCreate.setPaymentDate(LocalDate.of(2026, 2, 20));

        Provent pUpdate = new Provent();
        pUpdate.setId(UUID.randomUUID());
        pUpdate.setAsset(asset);
        pUpdate.setType(ProventType.DIVIDEND);
        pUpdate.setOriginRateBasis(ProventRateBasis.NET);
        pUpdate.setAmountPerShare(new BigDecimal("0.50000000"));
        pUpdate.setComDate(LocalDate.of(2026, 3, 10));
        pUpdate.setPaymentDate(LocalDate.of(2026, 4, 10));

        Provent pDelete = new Provent();
        pDelete.setId(UUID.randomUUID());
        pDelete.setAsset(asset);
        pDelete.setType(ProventType.DIVIDEND);
        pDelete.setOriginRateBasis(ProventRateBasis.NET);
        pDelete.setAmountPerShare(new BigDecimal("0.30000000"));
        pDelete.setComDate(LocalDate.of(2026, 3, 25));
        pDelete.setPaymentDate(LocalDate.of(2026, 4, 25));

        ProventProvision existingUpdate = new ProventProvision();
        existingUpdate.setId(UUID.randomUUID());
        existingUpdate.setProvent(pUpdate);
        existingUpdate.setPortfolio(portfolio);
        existingUpdate.setAsset(asset);
        existingUpdate.setQuantityOnComDate(new BigDecimal("10.00000000"));
        existingUpdate.setStatus(ProventStatus.PENDING);

        ProventProvision existingDelete = new ProventProvision();
        existingDelete.setId(UUID.randomUUID());
        existingDelete.setProvent(pDelete);
        existingDelete.setPortfolio(portfolio);
        existingDelete.setAsset(asset);

        when(proventRepository.findByAssetAndComDateGreaterThanEqual(asset, fromComDate))
                .thenReturn(List.of(pCreate, pUpdate, pDelete));
        when(marketCalendar.today()).thenReturn(LocalDate.of(2026, 3, 20));

        Instant endFeb = Instant.parse("2026-02-11T02:59:59.999999999Z");
        Instant endMar10 = Instant.parse("2026-03-11T02:59:59.999999999Z");
        Instant endMar25 = Instant.parse("2026-03-26T02:59:59.999999999Z");
        when(marketCalendar.endOfMarketDay(pCreate.getComDate())).thenReturn(endFeb);
        when(marketCalendar.endOfMarketDay(pUpdate.getComDate())).thenReturn(endMar10);
        when(marketCalendar.endOfMarketDay(pDelete.getComDate())).thenReturn(endMar25);

        when(transactionRepository.findPortfolioHoldingQuantityByAssetAtDate(portfolio.getId(), asset.getId(), endFeb, TransactionType.BUY))
                .thenReturn(new BigDecimal("100.00000000"));
        when(transactionRepository.findPortfolioHoldingQuantityByAssetAtDate(portfolio.getId(), asset.getId(), endMar10, TransactionType.BUY))
                .thenReturn(new BigDecimal("40.00000000"));
        when(transactionRepository.findPortfolioHoldingQuantityByAssetAtDate(portfolio.getId(), asset.getId(), endMar25, TransactionType.BUY))
                .thenReturn(BigDecimal.ZERO);

        when(proventProvisionRepository.findByProventAndPortfolio(pCreate, portfolio)).thenReturn(Optional.empty());
        when(proventProvisionRepository.findByProventAndPortfolio(pUpdate, portfolio)).thenReturn(Optional.of(existingUpdate));
        when(proventProvisionRepository.findByProventAndPortfolio(pDelete, portfolio)).thenReturn(Optional.of(existingDelete));

        proventService.reconcileProvisionsForPortfolioAndAsset(portfolio, asset, fromComDate);

        ArgumentCaptor<ProventProvision> savedCaptor = ArgumentCaptor.forClass(ProventProvision.class);
        verify(proventProvisionRepository, times(2)).save(savedCaptor.capture());
        verify(proventProvisionRepository).delete(existingDelete);

        List<ProventProvision> saved = savedCaptor.getAllValues();
        ProventProvision created = saved.get(0);
        assertThat(created.getProvent()).isSameAs(pCreate);
        assertThat(created.getGrossAmount()).isEqualByComparingTo("100.00000000");
        assertThat(created.getWithholdingTaxAmount()).isEqualByComparingTo("17.50000000");
        assertThat(created.getNetAmount()).isEqualByComparingTo("82.50000000");
        assertThat(created.getStatus()).isEqualTo(ProventStatus.PAID);

        ProventProvision updated = saved.get(1);
        assertThat(updated).isSameAs(existingUpdate);
        assertThat(updated.getQuantityOnComDate()).isEqualByComparingTo("40.00000000");
        assertThat(updated.getGrossAmount()).isEqualByComparingTo("20.00000000");
        assertThat(updated.getWithholdingTaxAmount()).isEqualByComparingTo("0.00000000");
        assertThat(updated.getNetAmount()).isEqualByComparingTo("20.00000000");
        assertThat(updated.getStatus()).isEqualTo(ProventStatus.PENDING);
    }

    @Test
    void reconcileProvisionsForPortfolioAndAssetShouldFallbackToAvailableEventDateWhenPaymentDateIsMissing() {
        Portfolio portfolio = portfolio();
        Asset asset = new Asset();
        asset.setId(UUID.randomUUID());
        asset.setTicker("BBAS3");

        Provent legacyJcpWithoutPaymentDate = new Provent();
        legacyJcpWithoutPaymentDate.setId(UUID.randomUUID());
        legacyJcpWithoutPaymentDate.setAsset(asset);
        legacyJcpWithoutPaymentDate.setType(ProventType.JCP);
        legacyJcpWithoutPaymentDate.setOriginRateBasis(ProventRateBasis.GROSS);
        legacyJcpWithoutPaymentDate.setAmountPerShare(new BigDecimal("1.00000000"));
        legacyJcpWithoutPaymentDate.setComDate(LocalDate.of(2025, 12, 20));
        legacyJcpWithoutPaymentDate.setPaymentDate(null);

        Instant endComDate = Instant.parse("2025-12-21T02:59:59.999999999Z");
        when(proventRepository.findByAsset(asset)).thenReturn(List.of(legacyJcpWithoutPaymentDate));
        when(marketCalendar.endOfMarketDay(LocalDate.of(2025, 12, 20))).thenReturn(endComDate);
        when(marketCalendar.today()).thenReturn(LocalDate.of(2026, 2, 1));
        when(transactionRepository.findPortfolioHoldingQuantityByAssetAtDate(
                portfolio.getId(), asset.getId(), endComDate, TransactionType.BUY))
                .thenReturn(new BigDecimal("100.00000000"));
        when(proventProvisionRepository.findByProventAndPortfolio(legacyJcpWithoutPaymentDate, portfolio))
                .thenReturn(Optional.empty());

        proventService.reconcileProvisionsForPortfolioAndAsset(portfolio, asset);

        ArgumentCaptor<ProventProvision> savedCaptor = ArgumentCaptor.forClass(ProventProvision.class);
        verify(proventProvisionRepository).save(savedCaptor.capture());

        ProventProvision provision = savedCaptor.getValue();
        assertThat(provision.getGrossAmount()).isEqualByComparingTo("100.00000000");
        assertThat(provision.getWithholdingTaxAmount()).isEqualByComparingTo("15.00000000");
        assertThat(provision.getNetAmount()).isEqualByComparingTo("85.00000000");
    }

    private ProventOriginMetadata grossJcpOrigin(String eventKey, String ticker, BigDecimal rate) {
        return new ProventOriginMetadata(
                ProventSource.BRAPI,
                eventKey,
                "JCP",
                null,
                ticker,
                null,
                null,
                null,
                null,
                rate,
                ProventRateBasis.GROSS
        );
    }

    private UserAssetHoldingProjection holding(UUID portfolioId, BigDecimal quantity) {
        return new UserAssetHoldingProjection() {
            @Override
            public UUID getPortfolioId() {
                return portfolioId;
            }

            @Override
            public BigDecimal getQuantity() {
                return quantity;
            }
        };
    }

    private Portfolio portfolio() {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail("investor@valora.dev");
        user.setPasswordHash("hash");
        user.setRole(UserRole.USER);

        Portfolio portfolio = new Portfolio();
        portfolio.setId(UUID.randomUUID());
        portfolio.setUser(user);
        portfolio.setName("Principal");
        return portfolio;
    }
}
