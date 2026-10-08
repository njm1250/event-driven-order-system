package com.ordersystem.common.experiment;

import java.nio.file.*;

/**
 * Test-only gate. Call only after the transaction manager returns or broker future succeeds.
 * An empty arm file stops the first event that reaches the boundary; an arm file that contains an
 * eventId stops only that event, so one chosen operation can be held while others keep flowing.
 */
public final class BoundaryGate {
    private BoundaryGate() { }
    public static void hit(String name, String eventId) {
        String directory = System.getenv("EXPERIMENT_HOOK_DIR");
        if (directory == null) return;
        Path root = Path.of(directory);
        Path arm = root.resolve(name + ".arm");
        try {
            if (!Files.exists(arm)) return;
            String target = Files.readString(arm).trim();
            if (!target.isEmpty() && !target.equals(eventId)) return;
            try {
                Files.writeString(root.resolve(name + ".reached"), eventId + "\n" + System.currentTimeMillis(),
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            } catch (FileAlreadyExistsException alreadyReached) {
                // Preserve the first arrival when several workers reach the same boundary.
            }
            while (Files.exists(arm)) Thread.sleep(50);
        } catch (NoSuchFileException disarmed) {
            // The controller removed the arm file between the checks.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
