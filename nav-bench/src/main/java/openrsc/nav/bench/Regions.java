package openrsc.nav.bench;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Where a storage layout's bytes live. Every region lives as long as the JVM. */
final class Regions {

    private Regions() {}

    static MemorySegment heap(long bytes) {
        return MemorySegment.ofArray(new byte[Math.toIntExact(bytes)]);
    }

    /** Native memory, zeroed and so already touched when it is returned. */
    static MemorySegment offHeap(long bytes) {
        return Arena.global().allocate(bytes, Long.BYTES);
    }

    /**
     * A shared read-write mapping of a new file in {@code -Dnavbench.dir} (default {@code scratch}).
     * Put that on a real disk: on tmpfs a mapped number measures shared memory, not the page cache.
     * The file is deleted once mapped, so a killed fork leaves nothing behind; its pages are not
     * present until first touched.
     */
    static MemorySegment mapped(String name, long bytes) throws IOException {
        Path dir = Path.of(System.getProperty("navbench.dir", "scratch"));
        Files.createDirectories(dir);
        Path file = Files.createTempFile(dir, name, ".bin");
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            MemorySegment seg = ch.map(FileChannel.MapMode.READ_WRITE, 0, bytes, Arena.global());
            Files.delete(file);
            return seg;
        }
    }
}
