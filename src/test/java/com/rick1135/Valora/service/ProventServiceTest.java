package com.rick1135.Valora.service;

import com.rick1135.Valora.dto.request.ProventRequestDTO;
import com.rick1135.Valora.dto.response.ProventProvisionResponseDTO;
import com.rick1135.Valora.dto.response.ProventResponseDTO;
import com.rick1135.Valora.entity.*;
import com.rick1135.Valora.exception.AssetNotFoundException;
import com.rick1135.Valora.exception.ProventAlreadyExistsException;
import com.rick1135.Valora.repository.*;
import com.rick1135.Valora.repository.projection.UserAssetHoldingProjection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.Instant;
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
    private UserRepository userRepository;

    @InjectMocks
    private ProventService proventService;

    @Test
    void createProventShouldProvisionDividendWithoutTax() {
        Asset asset = new Asset();
        asset.setId(UUID.randomUUID());
        asset.setTicker("ITSA4");
        asset.setName("Itausa");
        asset.setCategory(AssetCategory.ACOES);

        User user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail("investor@valora.dev");
        user.setPasswordHash("hash");
        user.setRole(UserRole.User);

        Instant comDate = Instant.parse("2026-03-20T00:00:00Z");
        Instant paymentDate = Instant.parse("2026-03-30T00:00:00Z");
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
        when(transactionRepository.findUserHoldingsByAssetAtDate(asset.getId(), comDate, TransactionType.BUY))
                .thenReturn(List.of(holding(user.getId(), new BigDecimal("10.00000000"))));
        when(userRepository.findAllById(any())).thenReturn(List.of(user));

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

        User user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail("jcp@valora.dev");
        user.setPasswordHash("hash");
        user.setRole(UserRole.User);

        Instant comDate = Instant.parse("2026-03-20T00:00:00Z");
        Instant paymentDate = Instant.parse("2026-04-05T00:00:00Z");
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
        when(transactionRepository.findUserHoldingsByAssetAtDate(asset.getId(), comDate, TransactionType.BUY))
                .thenReturn(List.of(holding(user.getId(), new BigDecimal("10.00000000"))));
        when(userRepository.findAllById(any())).thenReturn(List.of(user));

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
                Instant.parse("2026-03-20T00:00:00Z"),
                Instant.parse("2026-03-19T00:00:00Z")
        );

        assertThatThrownBy(() -> proventService.createProvent(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("data de pagamento");
    }

    @Test
    void createProventShouldRejectWhenDuplicated() {
        UUID assetId = UUID.randomUUID();
        Instant comDate = Instant.parse("2026-03-20T00:00:00Z");
        Instant paymentDate = Instant.parse("2026-03-25T00:00:00Z");
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
                Instant.parse("2026-03-20T00:00:00Z"),
                Instant.parse("2026-03-25T00:00:00Z")
        );
        when(assetRepository.findById(assetId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> proventService.createProvent(request))
                .isInstanceOf(AssetNotFoundException.class);
    }

    @Test
    void getMyProventsShouldMapRepositoryResult() {
        User user = new User();
        user.setId(UUID.randomUUID());

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
        provent.setComDate(Instant.parse("2026-03-10T00:00:00Z"));
        provent.setPaymentDate(Instant.parse("2026-03-20T00:00:00Z"));

        ProventProvision provision = new ProventProvision();
        provision.setId(UUID.randomUUID());
        provision.setUser(user);
        provision.setAsset(asset);
        provision.setProvent(provent);
        provision.setQuantityOnComDate(new BigDecimal("100.00000000"));
        provision.setGrossAmount(new BigDecimal("20.00000000"));
        provision.setWithholdingTaxAmount(BigDecimal.ZERO.setScale(8));
        provision.setNetAmount(new BigDecimal("20.00000000"));
        provision.setStatus(ProventStatus.PENDING);

        PageRequest pageable = PageRequest.of(0, 20);
        when(proventProvisionRepository.findByUser(user, pageable))
                .thenReturn(new PageImpl<>(List.of(provision), pageable, 1));

        Page<ProventProvisionResponseDTO> result = proventService.getMyProvents(user, pageable);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().getFirst().ticker()).isEqualTo("TAEE11");
        assertThat(result.getContent().getFirst().netAmount()).isEqualByComparingTo("20.00000000");
        assertThat(result.getContent().getFirst().originEventKey()).isEqualTo("manual-key");
    }

    private UserAssetHoldingProjection holding(UUID userId, BigDecimal quantity) {
        return new UserAssetHoldingProjection() {
            @Override
            public UUID getUserId() {
                return userId;
            }

            @Override
            public BigDecimal getQuantity() {
                return quantity;
            }
        };
    }
}
