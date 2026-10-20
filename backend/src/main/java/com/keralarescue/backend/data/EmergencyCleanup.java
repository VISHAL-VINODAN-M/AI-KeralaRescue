package com.keralarescue.backend.data;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Moves requests older than one hour from the active queue into dispatcher history. */
@Component
public class EmergencyCleanup {
    private final EmergencyRepository repository;

    public EmergencyCleanup(EmergencyRepository repository) {
        this.repository = repository;
    }

    @Scheduled(fixedDelay = 60_000L, initialDelay = 60_000L)
    public void archiveExpiredRequests() {
        repository.archiveExpired();
    }
}
