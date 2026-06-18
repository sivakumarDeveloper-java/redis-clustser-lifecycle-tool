package redistool;

import java.nio.file.Files;
import java.nio.file.Path;

/** Temporary verification harness (not part of the shipped CLI). */
final class StatusFormatTest {
    public static void main(String[] args) throws Exception {
        String facts = Files.readString(Path.of(args[0]));
        Cluster.Facts f = Cluster.parseFacts(facts);
        Cluster.printStatus(f);
        System.out.println("slotsCovered=" + f.slotsCovered() + " (expect 16384)");
        System.out.println("masters=" + f.masters().size() + " replicas=" + f.replicas().size());
    }
}
