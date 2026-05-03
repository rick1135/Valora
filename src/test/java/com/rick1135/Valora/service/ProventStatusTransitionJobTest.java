package com.rick1135.Valora.service;

import com.rick1135.Valora.repository.ProventProvisionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class ProventStatusTransitionJobTest {

    @Mock
    private ProventProvisionRepository proventProvisionRepository;
    @Mock
    private MarketCalendar marketCalendar;

    @InjectMocks
    private ProventStatusTransitionJob job;

    @Test
    void transitionPendingToPaidShouldCallRepositoryWithCurrentMarketDate() {
        LocalDate currentDate = LocalDate.of(2026, 3, 20);
        when(marketCalendar.today()).thenReturn(currentDate);
        when(proventProvisionRepository.updatePendingToPaid(any(LocalDate.class))).thenReturn(5);

        job.transitionPendingToPaid();

        ArgumentCaptor<LocalDate> captor = ArgumentCaptor.forClass(LocalDate.class);
        verify(proventProvisionRepository).updatePendingToPaid(captor.capture());

        assertThat(captor.getValue()).isEqualTo(currentDate);
    }
}
