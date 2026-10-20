package com.keralarescue.backend.live;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/** Runs slow work (AI calls, SMS, push) off the request thread so an SOS is never delayed by it. */
@Component
public class Background {
    private static final Logger log = LoggerFactory.getLogger(Background.class);
    private final ExecutorService pool = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "rescue-background");
        t.setDaemon(true);
        return t;
    });

    public void run(String label, Runnable task) {
        try {
            pool.execute(() -> {
                try { task.run(); } catch (Throwable t) { log.warn("Background task {} failed: {}", label, t.toString()); }
            });
        } catch (RejectedExecutionException e) {
            log.warn("Background task {} rejected", label);
        }
    }

    @PreDestroy
    void stop() { pool.shutdown(); }
}
