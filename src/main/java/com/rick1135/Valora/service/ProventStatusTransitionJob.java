package com.rick1135.Valora.service;

import com.rick1135.Valora.repository.ProventProvisionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class ProventStatusTransitionJob {

    private static final Logger logger = LoggerFactory.getLogger(ProventStatusTransitionJob.class);

    private final ProventProvisionRepository proventProvisionRepository;

    public ProventStatusTransitionJob(ProventProvisionRepository proventProvisionRepository) {
        this.proventProvisionRepository = proventProvisionRepository;
    }

    @Scheduled(cron = "0 0 1 * * *", zone = "America/Sao_Paulo")
    @Transactional
    public void transitionPendingToPaid() {
        logger.info("Iniciando transicao de proventos de PENDING para PAID");
        int updatedCount = proventProvisionRepository.updatePendingToPaid(Instant.now());
        logger.info("Transicao concluida. {} proventos foram atualizados para PAID", updatedCount);
    }
}
