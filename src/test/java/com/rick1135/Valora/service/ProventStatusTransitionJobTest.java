package com.rick1135.Valora.service;

import com.rick1135.Valora.repository.ProventProvisionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class ProventStatusTransitionJobTest {

    @Mock
    private ProventProvisionRepository proventProvisionRepository;

    @InjectMocks
    private ProventStatusTransitionJob job;

    @Test
    void transitionPendingToPaidShouldCallRepositoryWithCurrentInstant() {
        when(proventProvisionRepository.updatePendingToPaid(any(Instant.class))).thenReturn(5);

        job.transitionPendingToPaid();

        ArgumentCaptor<Instant> captor = ArgumentCaptor.forClass(Instant.class);
        verify(proventProvisionRepository).updatePendingToPaid(captor.capture());

        Instant passedInstant = captor.getValue();
        assertThat(passedInstant).isNotNull();
        assertThat(passedInstant).isBeforeOrEqualTo(Instant.now());
    }
}
