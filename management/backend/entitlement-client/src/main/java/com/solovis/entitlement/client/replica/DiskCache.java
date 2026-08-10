package com.solovis.entitlement.client.replica;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * An on-disk copy of the last replica, so a caller that restarts during an outage still knows what
 * its customers are entitled to (spec §11).
 *
 * <p>The file is a full-snapshot feed body, which means one reader serves both the network and the
 * disk path, and the round-trip is an identity rather than a second format to keep in step.
 */
public final class DiskCache {

    private static final Logger LOG = Logger.getLogger(DiskCache.class.getName());
    private static final String FILE = "snapshot.ndjson";
    private static final String TEMP = "snapshot.ndjson.tmp";

    private final Path directory;

    public DiskCache(Path directory) {
        this.directory = Objects.requireNonNull(directory, "directory");
    }

    /**
     * Writes atomically: a process killed mid-write leaves the previous cache intact. Never
     * throws — a cache that cannot be written must not take down the caller.
     */
    public void store(Replica replica) {
        try {
            Files.createDirectories(directory);
            var temp = directory.resolve(TEMP);
            try (var out = new BufferedOutputStream(Files.newOutputStream(temp))) {
                ReplicaNdjsonWriter.write(replica, out);
            }
            var target = directory.resolve(FILE);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.WARNING, "Could not write the entitlement replica cache; continuing without it.", e);
        }
    }

    /** Empty when there is no cache, or the cache cannot be trusted. Never throws. */
    public Optional<Replica> load() {
        var file = directory.resolve(FILE);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try (InputStream in = Files.newInputStream(file)) {
            return Optional.of(FullSnapshotReader.read(in));
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.WARNING, "Ignoring an unreadable entitlement replica cache at " + file, e);
            return Optional.empty();
        }
    }
}
