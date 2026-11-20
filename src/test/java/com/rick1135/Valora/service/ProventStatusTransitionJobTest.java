package com.rick1135.Valora.service;

import com.rick1135.Valora.entity.Portfolio;
import com.rick1135.Valora.entity.Provent;
import com.rick1135.Valora.entity.ProventProvision;
import com.rick1135.Valora.entity.ProventStatus;
import com.rick1135.Valora.repository.ProventProvisionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class ProventStatusTransitionJobTest {

    @Mock
    private ProventProvisionRepository proventProvisionRepository;
    @Mock
    private MarketCalendar marketCalendar;

    @Mock
    private PortfolioSnapshotService portfolioSnapshotService;

    @Mock
    private ObjectProvider<PortfolioSnapshotService> snapshotServiceProvider;

    @Test
    void transitionPendingToPaidShouldCallRepositoryWithCurrentMarketDate() {
        LocalDate currentDate = LocalDate.of(2026, 3, 20);
        when(marketCalendar.today()).thenReturn(currentDate);
        ProventStatusTransitionJob job = new ProventStatusTransitionJob(proventProvisionRepository, marketCalendar);

        job.transitionPendingToPaid();

        verify(proventProvisionRepository).updatePendingToPaid(currentDate);
    }

    @Test
    void transitionPendingToPaidShouldInvalidateEachPortfolioFromItsEarliestPaymentDate() {
        LocalDate today = LocalDate.of(2026, 3, 20);
        LocalDate earliestPayment = today.minusDays(6);
        Portfolio first = portfolio();
        Portfolio second = portfolio();
        when(marketCalendar.today()).thenReturn(today);
        when(proventProvisionRepository.findByStatusAndProventPaymentDateLessThanEqual(ProventStatus.PENDING, today))
                .thenReturn(List.of(
                        pendingProvision(first, today.minusDays(2)),
                        pendingProvision(first, earliestPayment),
                        pendingProvision(second, today)
                ));
        when(proventProvisionRepository.updatePendingToPaid(today)).thenReturn(3);

        jobWithSnapshots().transitionPendingToPaid();

        InOrder transition = inOrder(proventProvisionRepository, portfolioSnapshotService);
        transition.verify(proventProvisionRepository)
                .findByStatusAndProventPaymentDateLessThanEqual(ProventStatus.PENDING, today);
        transition.verify(proventProvisionRepository).updatePendingToPaid(today);
        transition.verify(portfolioSnapshotService, times(2))
                .invalidateSnapshotsFrom(any(Portfolio.class), any(LocalDate.class));
        verify(portfolioSnapshotService).invalidateSnapshotsFrom(first, earliestPayment);
        verify(portfolioSnapshotService).invalidateSnapshotsFrom(second, today);
        verifyNoMoreInteractions(portfolioSnapshotService);
    }

    @Test
    void transitionPendingToPaidShouldSkipInvalidationWhenNoProvisionWasUpdated() {
        LocalDate today = LocalDate.of(2026, 3, 20);
        when(marketCalendar.today()).thenReturn(today);
        when(proventProvisionRepository.findByStatusAndProventPaymentDateLessThanEqual(ProventStatus.PENDING, today))
                .thenReturn(List.of(pendingProvision(portfolio(), today.minusDays(2))));

        jobWithSnapshots().transitionPendingToPaid();

        verify(proventProvisionRepository).updatePendingToPaid(today);
        verifyNoInteractions(portfolioSnapshotService);
    }

    private ProventStatusTransitionJob jobWithSnapshots() {
        when(snapshotServiceProvider.getIfAvailable()).thenReturn(portfolioSnapshotService);
        return new ProventStatusTransitionJob(
                proventProvisionRepository, marketCalendar, snapshotServiceProvider);
    }

    private Portfolio portfolio() {
        Portfolio portfolio = new Portfolio();
        portfolio.setId(UUID.randomUUID());
        return portfolio;
    }

    private ProventProvision pendingProvision(Portfolio portfolio, LocalDate paymentDate) {
        Provent provent = new Provent();
        provent.setPaymentDate(paymentDate);
        ProventProvision provision = new ProventProvision();
        provision.setPortfolio(portfolio);
        provision.setProvent(provent);
        provision.setStatus(ProventStatus.PENDING);
        return provision;
    }
}
