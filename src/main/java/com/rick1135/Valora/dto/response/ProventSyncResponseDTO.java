package com.rick1135.Valora.dto.response;

public record ProventSyncResponseDTO(
        int assetsScanned,
        int eventsReceived,
        int proventsCreated,
        int duplicatesIgnored,
        int invalidEventsIgnored,
        int integrationErrors
) {
}
