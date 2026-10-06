package com.ordersystem.common.experiment;

import java.nio.file.*;

/** Test-only gate. Call only after the transaction manager returns or broker future succeeds. */
public final class BoundaryGate {
    private BoundaryGate() { }
    public static void hit(String name, String eventId) {
        String directory = System.getenv("EXPERIMENT_HOOK_DIR");
        if (directory == null) return;
        Path root = Path.of(directory);
        try {
            if (!Files.exists(root.resolve(name + ".arm"))) return;
            Files.writeString(root.resolve(name + ".reached"), eventId + "\n" + System.currentTimeMillis());
            while (Files.exists(root.resolve(name + ".arm"))) Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
