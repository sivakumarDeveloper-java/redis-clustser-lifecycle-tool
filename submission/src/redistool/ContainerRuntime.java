package redistool;

import java.util.List;

/** Detected container runtime (Podman preferred) and how to invoke compose. */
final class ContainerRuntime {
    final String bin; // "podman" | "docker"
    final String pretty; // "Podman" | "Docker"
    final String version; // e.g. "5.8.2"
    final List<String> composeCmd;

    ContainerRuntime(String bin, String pretty, String version, List<String> composeCmd) {
        this.bin = bin;
        this.pretty = pretty;
        this.version = version;
        this.composeCmd = composeCmd;
    }
}
