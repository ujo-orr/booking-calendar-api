package org.example.github_api_practice.calendar;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "calendar.sync.worker-enabled", havingValue = "true", matchIfMissing = true)
public class CalendarSyncWorker {
    private static final Logger log = LoggerFactory.getLogger(CalendarSyncWorker.class);
    private final CalendarSyncStore store;
    private final CalendarSyncService service;
    private final CalendarTokenCipher cipher;

    public CalendarSyncWorker(CalendarSyncStore store, CalendarSyncService service, CalendarTokenCipher cipher) {
        this.store = store;
        this.service = service;
        this.cipher = cipher;
    }

    @Scheduled(fixedDelayString = "${calendar.sync.poll-interval-ms:5000}")
    public void runOnce() {
        if (!cipher.isConfigured()) {
            return;
        }
        try {
            for (Long id : store.dueIds()) {
                service.synchronize(id);
            }
        } catch (RuntimeException exception) {
            log.error("Calendar 복구 작업 조회 실패: {}", exception.getClass().getSimpleName());
        }
    }
}
