package com.rick1135.Valora.service;

import com.rick1135.Valora.entity.Portfolio;
import com.rick1135.Valora.entity.ProventProvision;
import com.rick1135.Valora.entity.ProventStatus;
import com.rick1135.Valora.repository.ProventProvisionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

@Service
public class ProventStatusTransitionJob {

    private static final Logger logger = LoggerFactory.getLogger(ProventStatusTransitionJob.class);

    private final ProventProvisionRepository proventProvisionRepository;
    private final MarketCalendar marketCalendar;
    private final PortfolioSnapshotService portfolioSnapshotService;

    public ProventStatusTransitionJob(
            ProventProvisionRepository proventProvisionRepository,
            MarketCalendar marketCalendar
    ) {
        this(proventProvisionRepository, marketCalendar, null);
    }

    @Autowired
    public ProventStatusTransitionJob(
            ProventProvisionRepository proventProvisionRepository,
            MarketCalendar marketCalendar,
            ObjectProvider<PortfolioSnapshotService> snapshotServiceProvider
    ) {
        this.proventProvisionRepository = proventProvisionRepository;
        this.marketCalendar = marketCalendar;
        this.portfolioSnapshotService = snapshotServiceProvider == null
                ? null
                : snapshotServiceProvider.getIfAvailable();
    }

    @Scheduled(cron = "0 0 1 * * *", zone = "America/Sao_Paulo")
    @Transactional
    public void transitionPendingToPaid() {
        logger.info("Iniciando transicao de proventos de PENDING para PAID");
        LocalDate today = marketCalendar.today();
        Map<Portfolio, LocalDate> earliestPaymentByPortfolio = new HashMap<>();
        if (portfolioSnapshotService != null) {
            for (ProventProvision provision : proventProvisionRepository
                    .findByStatusAndProventPaymentDateLessThanEqual(ProventStatus.PENDING, today)) {
                earliestPaymentByPortfolio.merge(provision.getPortfolio(), provision.getProvent().getPaymentDate(),
                        (first, second) -> first.isBefore(second) ? first : second);
            }
        }

        int updatedCount = proventProvisionRepository.updatePendingToPaid(today);
        if (updatedCount > 0 && portfolioSnapshotService != null) {
            earliestPaymentByPortfolio.forEach(portfolioSnapshotService::invalidateSnapshotsFrom);
        }
        logger.info("Transicao concluida. {} proventos foram atualizados para PAID", updatedCount);
    }
}
