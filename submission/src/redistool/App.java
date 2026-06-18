package redistool;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import redistool.Cluster.Facts;
import redistool.Cluster.NodeInfo;
import redistool.Cluster.SeedResult;
import redistool.Cluster.VerifyResult;

/** Per-invocation context + every host-side command (Ansible orchestration). */
final class App {
    // ---- fixed cluster addressing ----
    static final String SUBNET = "10.10.0.0/24", GATEWAY = "10.10.0.1", NET = "redis-net";
    static final String IMAGE = "redis-cluster-node:ubuntu22.04";
    static final int REDIS_PORT = 6379, BASE_SSH = 2210, BASE_IP = 10;

    record NodeSpec(int index, String name, String redisIp, int sshPort) {
    }

    static NodeSpec nodeSpec(int i) {
        return new NodeSpec(i, "redis-node-" + i, "10.10.0." + (BASE_IP + i), BASE_SSH + i);
    }

    static List<NodeSpec> nodeSpecs(int n) {
        List<NodeSpec> out = new ArrayList<>();
        for (int i = 1; i <= n; i++)
            out.add(nodeSpec(i));
        return out;
    }

    final String cmd;
    final Path root, ansibleDir, infraDir, logsDir, runtimeDir, outputDir;
    ContainerRuntime rt;
    Logx log;

    private App(String cmd, Path root) {
        this.cmd = cmd;
        this.root = root;
        this.ansibleDir = root.resolve("ansible");
        this.infraDir = root.resolve("infra");
        this.logsDir = root.resolve("logs");
        this.runtimeDir = ansibleDir.resolve(".runtime");
        this.outputDir = root.resolve("output");
    }

    static App newApp(String cmd) {
        Path root = Paths.get("").toAbsolutePath();
        App a = new App(cmd, root);
        if (!Files.isDirectory(a.ansibleDir)) {
            throw new RuntimeException("run redis-tool from the project root (no ./ansible directory found here)");
        }
        try {
            Files.createDirectories(a.logsDir);
            Files.createDirectories(a.runtimeDir);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        a.log = new Logx(a.logsDir, cmd);
        return a;
    }

    // ============================================================ prerequisites
    void checkPrereqs() {
        ContainerRuntime r = detectRuntime();
        String[] ans = detectAnsible();

        if (r == null) {
            String forced = System.getenv("REDIS_TOOL_RUNTIME");
            if (forced != null && !forced.isBlank()) {
                System.out.printf("\u001b[31m✗ %s not found (REDIS_TOOL_RUNTIME=%s)\u001b[0m%n", forced, forced);
            } else {
                System.out.println("\u001b[31m✗ Container runtime not found (Docker or Podman)\u001b[0m");
            }
            System.out.println("  Install Docker: https://docs.docker.com/engine/install/");
            System.out.println("  Install Podman: https://podman.io/docs/installation");
            System.out.println(
                    "  Tip: set REDIS_TOOL_RUNTIME=docker (or podman) to pick one explicitly if you have both installed.");
        } else {
            System.out.printf("\u001b[32m✓ %s %s found\u001b[0m%n", r.pretty, r.version);
        }
        if (ans == null) {
            System.out.println("\u001b[31m✗ Ansible not found (or older than 2.14)\u001b[0m");
            System.out.println(
                    "  Install: pip install ansible  (or use your OS package manager, e.g. brew install ansible)");
        } else {
            System.out.printf("\u001b[32m✓ Ansible %s found\u001b[0m%n", ans[0]);
        }
        if (r == null || ans == null) {
            System.out.println("\nPlease install the missing dependencies and retry.");
            throw new RuntimeException("prerequisites not satisfied");
        }
        this.rt = r;
        System.out.println("Proceeding...");
        Map<String, String> f = new LinkedHashMap<>();
        f.put("runtime", r.bin);
        f.put("runtime_version", r.version);
        f.put("ansible_version", ans[0]);
        log.event("prereq_ok", f);
    }

    /**
     * Detect the container runtime. Podman is preferred if both are present,
     * unless REDIS_TOOL_RUNTIME forces a choice (e.g. REDIS_TOOL_RUNTIME=docker
     * for hosts where only Docker is installed/known).
     */
    private static ContainerRuntime detectRuntime() {
        String forced = System.getenv("REDIS_TOOL_RUNTIME");
        if (forced != null && !forced.isBlank()) {
            switch (forced.strip().toLowerCase()) {
                case "docker":
                    return tryDocker();
                case "podman":
                    return tryPodman();
                default:
                    throw new RuntimeException(
                            "REDIS_TOOL_RUNTIME must be 'docker' or 'podman' (got '" + forced + "')");
            }
        }
        ContainerRuntime r = tryPodman();
        if (r != null)
            return r;
        return tryDocker();
    }

    private static ContainerRuntime tryPodman() {
        if (onPath("podman")) {
            Util.ExecResult r = Util.capture("podman", "--version");
            if (r.code() == 0)
                return new ContainerRuntime("podman", "Podman", Util.extractVersion(r.out()), podmanCompose());
        }
        return null;
    }

    private static ContainerRuntime tryDocker() {
        if (onPath("docker")) {
            Util.ExecResult r = Util.capture("docker", "--version");
            if (r.code() == 0)
                return new ContainerRuntime("docker", "Docker", Util.extractVersion(r.out()), dockerCompose());
        }
        return null;
    }

    private static List<String> podmanCompose() {
        return onPath("podman-compose") ? List.of("podman-compose") : List.of("podman", "compose");
    }

    private static List<String> dockerCompose() {
        if (Util.capture("docker", "compose", "version").code() == 0)
            return List.of("docker", "compose");
        if (onPath("docker-compose"))
            return List.of("docker-compose");
        return List.of("docker", "compose");
    }

    /** Returns [version] if ansible-playbook >= 2.14, else null. */
    private static String[] detectAnsible() {
        if (!onPath("ansible-playbook"))
            return null;
        Util.ExecResult r = Util.capture("ansible-playbook", "--version");
        if (r.code() != 0)
            return null;
        String first = r.out().split("\n", 2)[0];
        String v = Util.extractVersion(first);
        if (v.isEmpty() || !Util.versionAtLeast(v, "2.14"))
            return null;
        return new String[] { v };
    }

    private static boolean onPath(String exe) {
        String path = System.getenv("PATH");
        if (path == null)
            return false;
        for (String dir : path.split(File.pathSeparator)) {
            if (new File(dir, exe).canExecute())
                return true;
        }
        return false;
    }

    // ============================================================ exec helpers
    private Util.ExecResult rtOut(String... args) {
        String[] cmd = prepend(rt.bin, args);
        return Util.capture(cmd);
    }

    private int streamRuntime(String... args) {
        return Util.stream(null, null, prepend(rt.bin, args));
    }

    private static String[] prepend(String head, String[] tail) {
        String[] out = new String[tail.length + 1];
        out[0] = head;
        System.arraycopy(tail, 0, out, 1, tail.length);
        return out;
    }

    // ============================================================ infra
    void cmdInfra(String[] args) {
        String sub = args.length > 0 ? args[0] : "up";
        switch (sub) {
            case "up" -> infraUp();
            case "down" -> infraDown();
            case "status" -> infraStatus();
            default -> throw new RuntimeException("infra: unknown subcommand " + sub + " (use up|down|status)");
        }
    }

    void infraUp() {
        log.step("Ensuring SSH control key");
        ensureSSHKey();
        log.step("Building node image (%s)", IMAGE);
        if (buildImage() != 0)
            throw new RuntimeException("image build failed");
        log.step("Starting 6 nodes via %s", String.join(" ", rt.composeCmd));
        if (composeUp() != 0) {
            log.warn("compose path failed; falling back to direct %s commands", rt.bin);
            directUp(nodeSpecs(6));
        }
        log.step("Waiting for SSH on all nodes");
        for (NodeSpec n : nodeSpecs(6)) {
            if (!Util.waitTCP("127.0.0.1", n.sshPort(), 90_000)) {
                log.warn("%s not reachable on :%d; repairing via direct run", n.name(), n.sshPort());
                directUp(nodeSpecs(6));
                if (!Util.waitTCP("127.0.0.1", n.sshPort(), 90_000)) {
                    throw new RuntimeException(n.name() + " SSH never came up on :" + n.sshPort());
                }
            }
            log.ok("%s reachable on 127.0.0.1:%d (cluster IP %s)", n.name(), n.sshPort(), n.redisIp());
        }
        log.ok("Infrastructure ready — 6 nodes up");
        System.out.println("\nNext: ./redis-tool provision --version 7.0.15 --masters 3 --replicas-per-master 1");
    }

    private void ensureSSHKey() {
        try {
            Path keyDir = ansibleDir.resolve("keys");
            Files.createDirectories(keyDir);
            Path priv = keyDir.resolve("redis_cluster");
            Path pub = keyDir.resolve("redis_cluster.pub");
            if (!Files.exists(priv)) {
                log.info("Generating SSH keypair at ansible/keys/redis_cluster");
                Util.ExecResult r = Util.capture("ssh-keygen", "-t", "ed25519", "-N", "", "-f", priv.toString(), "-q",
                        "-C", "redis-tool-control");
                if (r.code() != 0)
                    throw new RuntimeException("ssh-keygen failed: " + r.out());
            }
            byte[] pubBytes = Files.readAllBytes(pub);
            Files.write(infraDir.resolve("authorized_keys"), pubBytes);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private int buildImage() {
        return streamRuntime("build", "-t", IMAGE, "-f", infraDir.resolve("Containerfile").toString(),
                infraDir.toString());
    }

    private int composeUp() {
        List<String> cmd = new ArrayList<>(rt.composeCmd);
        cmd.add("-f");
        cmd.add(infraDir.resolve("compose.yml").toString());
        cmd.add("up");
        cmd.add("-d");
        return Util.stream(null, null, cmd);
    }

    private void directUp(List<NodeSpec> nodes) {
        rtOut("network", "create", "--subnet", SUBNET, "--gateway", GATEWAY, NET); // ignore "exists"
        for (NodeSpec n : nodes) {
            rtOut("rm", "-f", n.name());
            int code = streamRuntime("run", "-d", "--name", n.name(), "--hostname", n.name(),
                    "--network", NET, "--ip", n.redisIp(), "-p", n.sshPort() + ":22", IMAGE);
            if (code != 0)
                throw new RuntimeException("run " + n.name() + " failed");
        }
    }

    private void infraDown() {
        List<String> cmd = new ArrayList<>(rt.composeCmd);
        cmd.add("-f");
        cmd.add(infraDir.resolve("compose.yml").toString());
        cmd.add("down");
        Util.stream(null, null, cmd);
        for (String name : listNodeContainers())
            rtOut("rm", "-f", name);
        rtOut("network", "rm", NET);
        log.ok("Infrastructure torn down");
    }

    private void infraStatus() {
        Util.ExecResult r = rtOut("ps", "-a", "--filter", "name=redis-node-", "--format",
                "{{.Names}}\t{{.Status}}\t{{.Ports}}");
        if (r.out().isBlank()) {
            System.out.println("No redis-node-* containers found. Run: ./redis-tool infra up");
            return;
        }
        System.out.println("CONTAINER\tSTATUS\tPORTS");
        System.out.println(r.out());
    }

    private List<String> listNodeContainers() {
        Util.ExecResult r = rtOut("ps", "-a", "--filter", "name=redis-node-", "--format", "{{.Names}}");
        List<String> names = new ArrayList<>();
        for (String l : r.out().split("\n"))
            if (!l.isBlank())
                names.add(l.strip());
        return names;
    }

    // ============================================================ ansible + agent
    void runPlaybook(String playbook, Map<String, String> extraVars, String limit) {
        List<String> args = new ArrayList<>();
        args.add("ansible-playbook");
        args.add("playbooks/" + playbook);
        if (extraVars != null && !extraVars.isEmpty()) {
            args.add("-e");
            args.add(Util.jsonObject(extraVars));
        }
        if (limit != null && !limit.isEmpty()) {
            args.add("--limit");
            args.add(limit);
        }
        Map<String, String> evt = new LinkedHashMap<>();
        evt.put("playbook", playbook);
        if (limit != null)
            evt.put("limit", limit);
        log.event("ansible", evt);
        int code = Util.stream(ansibleDir.toFile(), Map.of("ANSIBLE_FORCE_COLOR", "1"), args);
        if (code != 0)
            throw new RuntimeException("ansible-playbook " + playbook + " failed (exit " + code + ")");
    }

    /** Absolute path to the running jar (shipped to nodes as the agent). */
    private String jarPath() {
        try {
            String p = new File(App.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                    .getAbsolutePath();
            if (!p.endsWith(".jar")) {
                throw new RuntimeException(
                        "run the packaged jar (build with java/build.sh and use the ./redis-tool wrapper) — needed to ship the node-agent");
            }
            return p;
        } catch (Exception e) {
            throw new RuntimeException("could not locate redis-tool.jar: " + e.getMessage());
        }
    }

    /** Ship the agent jar to a node, run a data op, return its text output. */
    String runAgent(String agentCmd, int keys, String target) {
        if (target == null || target.isEmpty())
            target = "redis-node-1";
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("agent_cmd", agentCmd);
        vars.put("agent_keys", String.valueOf(keys));
        vars.put("agent_seed_ip", "127.0.0.1");
        vars.put("jar_src", jarPath());
        runPlaybook("node_agent_java.yml", vars, target);
        try {
            return Files.readString(runtimeDir.resolve("agent_out.txt"), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("reading agent output: " + e.getMessage());
        }
    }

    Facts loadFacts(String target) {
        return Cluster.parseFacts(runAgent("facts", 0, target));
    }

    // ============================================================ provision
    void cmdProvision(String[] args) {
        String version = optVal(args, "--version", "7.0.15");
        int masters = optInt(args, "--masters", 3);
        int replicas = optInt(args, "--replicas-per-master", 1);
        int total = masters * (1 + replicas);
        if (total != 6)
            throw new RuntimeException(
                    "this infrastructure has 6 nodes; masters*(1+replicas-per-master) must equal 6 (got " + total
                            + ")");

        ensureInfraUp();
        log.step("Provisioning Redis %s on 6 nodes and forming the cluster", version);
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("redis_version", version);
        vars.put("redis_replicas_per_master", String.valueOf(replicas));
        runPlaybook("provision.yml", vars, null);

        log.step("Gathering cluster topology");
        Facts f = loadFacts("redis-node-1");
        Cluster.printStatus(f);
        if (!"ok".equals(f.clusterState))
            throw new RuntimeException("cluster formed but state is " + f.clusterState);
        log.ok("Redis Cluster %s is operational (cluster_state:ok)", version);
    }

    private void ensureInfraUp() {
        boolean allUp = true;
        for (NodeSpec n : nodeSpecs(6)) {
            if (!Util.waitTCP("127.0.0.1", n.sshPort(), 2000)) {
                allUp = false;
                break;
            }
        }
        if (allUp)
            return;
        log.warn("Containers not all reachable; bringing infrastructure up first");
        infraUp();
    }

    // ============================================================ data
    void cmdData(String[] args) {
        if (args.length == 0)
            throw new RuntimeException("data: expected 'seed' or 'verify'");
        String[] rest = tail(args);
        switch (args[0]) {
            case "seed" -> dataSeed(rest);
            case "verify" -> dataVerify();
            default -> throw new RuntimeException("data: unknown subcommand " + args[0] + " (use seed|verify)");
        }
    }

    private void dataSeed(String[] args) {
        int keys = optInt(args, "--keys", 1000);
        log.step("Seeding %d deterministic keys (value = sha256(key))", keys);
        SeedResult res = Cluster.parseSeed(runAgent("seed", keys, "redis-node-1"));
        saveSeedCount(keys);
        System.out.printf("%nSeed summary:%n");
        System.out.printf("  inserted: %d / %d%n", res.inserted, res.requested);
        System.out.println("  distribution across masters:");
        for (String[] d : res.distribution)
            System.out.printf("    %s : %s keys%n", d[0], d[1]);
        System.out.printf("  failures: %d%n", res.failures);
        if (res.failures > 0)
            throw new RuntimeException(res.failures + " keys failed to insert");
        log.ok("Seeded %d keys", res.inserted);
    }

    private void dataVerify() {
        VerifyResult res = runVerify(seedCount(), "redis-node-1");
        printVerify(res);
        if (!res.pass)
            throw new RuntimeException("data verification FAILED");
    }

    private VerifyResult runVerify(int keys, String target) {
        return Cluster.parseVerify(runAgent("verify", keys, target));
    }

    private void printVerify(VerifyResult res) {
        if (res.pass) {
            System.out.printf("%n\u001b[32mPASS — %d/%d keys verified\u001b[0m%n", res.verified, res.total);
        } else {
            System.out.printf("%n\u001b[31mFAIL — %d keys missing, %d values mismatched\u001b[0m%n", res.missing,
                    res.mismatched);
        }
        Map<String, String> evt = new LinkedHashMap<>();
        evt.put("verified", String.valueOf(res.verified));
        evt.put("missing", String.valueOf(res.missing));
        evt.put("mismatched", String.valueOf(res.mismatched));
        evt.put("pass", String.valueOf(res.pass));
        log.event("verify_result", evt);
    }

    // ============================================================ status
    void cmdStatus(String[] args) {
        Cluster.printStatus(loadFacts("redis-node-1"));
    }

    // ============================================================ upgrade
    void cmdUpgrade(String[] args) {
        String target = optVal(args, "--target-version", "");
        String strategy = optVal(args, "--strategy", "rolling");
        if (target.isEmpty())
            throw new RuntimeException("upgrade: --target-version is required");
        if (!strategy.equals("rolling"))
            throw new RuntimeException("upgrade: only the 'rolling' strategy is supported");

        log.step("Pre-flight checks");
        Facts f = loadFacts("redis-node-1");
        if (!"ok".equals(f.clusterState))
            throw new RuntimeException("pre-flight: cluster_state is " + f.clusterState + " (need ok)");
        log.ok("cluster_state:ok");
        for (NodeSpec n : nodeSpecs(6)) {
            if (!Util.waitTCP("127.0.0.1", n.sshPort(), 5000))
                throw new RuntimeException("pre-flight: " + n.name() + " unreachable");
        }
        log.ok("all 6 nodes reachable");

        boolean allTarget = true;
        TreeSet<String> versions = new TreeSet<>();
        for (NodeInfo n : f.nodes) {
            versions.add(n.version);
            if (!n.version.equals(target))
                allTarget = false;
        }
        if (allTarget) {
            log.ok("All nodes already on v%s — nothing to upgrade.", target);
            return;
        }
        log.ok("current version(s) %s differ from target v%s", versions, target);

        log.step("Establishing pre-upgrade data integrity baseline");
        VerifyResult base = runVerify(seedCount(), "redis-node-1");
        printVerify(base);
        int baseline = base.verified;

        int step = 0, totalSteps = 6;
        for (NodeInfo r : f.replicas()) {
            step++;
            String node = nodeNameForIP(r.ip);
            log.step("[%d/%d] Upgrading replica %s (%s) -> v%s", step, totalSteps, node, r.ip, target);
            upgradeNode(node, r.ip, target, node);
            System.out.printf("\u001b[32m[%d/%d] Upgraded replica %s — cluster: %s\u001b[0m%n", step, totalSteps, r.ip,
                    mustState(node));
        }

        f = loadFacts("redis-node-1");
        for (NodeInfo m : f.masters()) {
            step++;
            String masterNode = nodeNameForIP(m.ip);
            NodeInfo rep = null;
            for (NodeInfo n : f.nodes)
                if (n.role.equals("replica") && n.masterId.equals(m.id)) {
                    rep = n;
                    break;
                }
            if (rep == null)
                throw new RuntimeException("master " + m.ip + " has no replica to fail over to");
            String repNode = nodeNameForIP(rep.ip);
            log.step("[%d/%d] Upgrading master %s (%s): failing over to replica %s (%s)", step, totalSteps, masterNode,
                    m.ip, repNode, rep.ip);
            failover(repNode);
            if (!waitRoleSwap(m.ip, rep.ip))
                throw new RuntimeException("master " + m.ip + ": failover did not complete (role swap timed out)");
            log.ok("failover complete: %s is now master, %s is now replica", rep.ip, m.ip);
            upgradeNode(masterNode, m.ip, target, repNode);
            System.out.printf("\u001b[32m[%d/%d] Upgraded master %s (now replica) — cluster: %s\u001b[0m%n", step,
                    totalSteps, m.ip, mustState(repNode));
        }

        log.step("Post-upgrade verification");
        VerifyResult post = runVerify(seedCount(), "redis-node-1");
        printVerify(post);
        if (post.verified < baseline)
            throw new RuntimeException(
                    "post-upgrade integrity regressed: " + post.verified + " < baseline " + baseline);

        f = loadFacts("redis-node-1");
        Cluster.printStatus(f);
        for (NodeInfo n : f.nodes)
            if (!n.version.equals(target))
                throw new RuntimeException("node " + n.ip + " still on v" + n.version + " after upgrade");

        System.out.printf("%n\u001b[32m✓ UPGRADE COMPLETE — all nodes on v%s, data integrity verified\u001b[0m%n",
                target);
        log.event("upgrade_complete", Map.of("target", target, "verified", String.valueOf(post.verified)));
    }

    private void upgradeNode(String node, String ip, String target, String seedNode) {
        log.step("  %s: stopping Redis", node);
        serviceAction(node, "stop");
        log.step("  %s: installing Redis %s", node, target);
        installVersion(node, target);
        log.step("  %s: starting Redis", node);
        serviceAction(node, "start");
        log.step("  %s: waiting to rejoin cluster + sync", node);
        if (!waitRejoin(ip, target, seedNode))
            throw new RuntimeException("node " + ip + " did not rejoin healthily within timeout");
    }

    private void serviceAction(String node, String action) {
        runPlaybook("service.yml", Map.of("redis_action", action), node);
    }

    private void installVersion(String node, String version) {
        runPlaybook("install_version.yml", Map.of("redis_version", version), node);
    }

    private void failover(String replicaNode) {
        runPlaybook("failover.yml", null, replicaNode);
    }

    private boolean waitRejoin(String ip, String target, String seedNode) {
        return Util.pollUntil(150_000, 4000, () -> {
            try {
                Facts f = loadFacts(seedNode);
                if (!"ok".equals(f.clusterState))
                    return false;
                NodeInfo n = f.byIp(ip);
                if (n == null || !n.version.equals(target))
                    return false;
                return !n.role.equals("replica") || "up".equals(n.masterLinkStatus);
            } catch (RuntimeException e) {
                return false;
            }
        });
    }

    private boolean waitRoleSwap(String oldMasterIp, String newMasterIp) {
        return Util.pollUntil(90_000, 3000, () -> {
            try {
                Facts f = loadFacts(nodeNameForIP(newMasterIp));
                if (!"ok".equals(f.clusterState))
                    return false;
                NodeInfo om = f.byIp(oldMasterIp), nm = f.byIp(newMasterIp);
                return om != null && nm != null && om.role.equals("replica") && nm.role.equals("master");
            } catch (RuntimeException e) {
                return false;
            }
        });
    }

    private String mustState(String seedNode) {
        try {
            return loadFacts(seedNode).clusterState;
        } catch (RuntimeException e) {
            return "unknown";
        }
    }

    // ============================================================ verify --full
    void cmdVerify(String[] args) {
        if (!hasFlag(args, "--full")) {
            dataVerify();
            return;
        }
        Facts f = loadFacts("redis-node-1");
        List<String[]> checks = new ArrayList<>(); // [name, pass, note]

        VerifyResult v = runVerify(seedCount(), "redis-node-1");
        checks.add(new String[] { "Data integrity", String.valueOf(v.pass),
                String.format("%d/%d verified, %d missing, %d mismatched", v.verified, v.total, v.missing,
                        v.mismatched) });

        TreeSet<String> versions = new TreeSet<>();
        for (NodeInfo n : f.nodes)
            versions.add(n.version);
        checks.add(new String[] { "Version consistency", String.valueOf(versions.size() == 1),
                "versions present: " + versions });

        int covered = f.slotsCovered();
        Map<String, Integer> replicaOf = new java.util.HashMap<>();
        for (NodeInfo n : f.replicas())
            replicaOf.merge(n.masterId, 1, Integer::sum);
        boolean allHaveReplica = true;
        for (NodeInfo m : f.masters())
            if (replicaOf.getOrDefault(m.id, 0) == 0)
                allHaveReplica = false;
        checks.add(new String[] { "Topology health", String.valueOf(covered == 16384 && allHaveReplica),
                String.format("%d/16384 slots covered, every master has a replica: %s", covered, allHaveReplica) });

        checks.add(new String[] { "Cluster state", String.valueOf("ok".equals(f.clusterState)),
                "cluster_state:" + f.clusterState });

        boolean linksUp = true;
        List<String> down = new ArrayList<>();
        for (NodeInfo n : f.replicas())
            if (!"up".equals(n.masterLinkStatus)) {
                linksUp = false;
                down.add(n.ip);
            }
        checks.add(new String[] { "Replication links", String.valueOf(linksUp),
                linksUp ? "all replica links up" : "down: " + String.join(", ", down) });

        System.out.println("\nFull verification:");
        boolean allPass = true;
        for (String[] c : checks) {
            boolean ok = Boolean.parseBoolean(c[1]);
            if (!ok)
                allPass = false;
            String mark = ok ? "\u001b[32mPASS\u001b[0m" : "\u001b[31mFAIL\u001b[0m";
            System.out.printf("  [%s] %-22s — %s%n", mark, c[0], c[2]);
        }
        log.event("verify_full", Map.of("all_pass", String.valueOf(allPass)));
        if (!allPass)
            throw new RuntimeException("full verification failed");
        System.out.printf("%n\u001b[32m✓ All checks passed\u001b[0m%n");
    }

    // ============================================================ scale / rollback
    void cmdScale(String[] args) {
        int add = optInt(args, "--add-nodes", 0);
        String remove = optVal(args, "--remove-node", "");
        if (add > 0)
            ScaleOps.scaleOut(this, add);
        else if (!remove.isEmpty())
            ScaleOps.scaleIn(this, remove);
        else
            throw new RuntimeException("scale: use --add-nodes N or --remove-node ID");
    }

    void cmdRollback(String[] args) {
        String target = optVal(args, "--target-version", "");
        if (target.isEmpty())
            throw new RuntimeException("rollback: --target-version is required");
        log.info("Rollback requested → performing a rolling transition to v%s", target);
        cmdUpgrade(new String[] { "--target-version", target, "--strategy", "rolling" });
    }

    // ---- shared helpers used by ScaleOps ----
    void clusterAdmin(String argv) {
        clusterAdminOn("redis-node-1", argv);
    }

    void clusterAdminOn(String host, String argv) {
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("admin_host", host);
        vars.put("admin_argv", argv);
        runPlaybook("cluster_admin.yml", vars, null);
    }

    String detectNetwork() {
        Util.ExecResult r = rtOut("inspect", "redis-node-1", "--format",
                "{{range $k,$v := .NetworkSettings.Networks}}{{$k}} {{end}}");
        if (r.code() != 0)
            throw new RuntimeException("detecting network: " + r.out());
        String[] fields = r.out().strip().split("\\s+");
        if (fields.length == 0 || fields[0].isEmpty())
            throw new RuntimeException("redis-node-1 has no network");
        return fields[0];
    }

    void runNodeOnNet(NodeSpec ns, String network) {
        rtOut("rm", "-f", ns.name());
        int code = streamRuntime("run", "-d", "--name", ns.name(), "--hostname", ns.name(),
                "--network", network, "--ip", ns.redisIp(), "-p", ns.sshPort() + ":22", IMAGE);
        if (code != 0)
            throw new RuntimeException("run " + ns.name() + " failed");
    }

    void removeContainer(String name) {
        rtOut("rm", "-f", name);
    }

    int maxNodeIndex() {
        int mx = 0;
        for (String n : listNodeContainers()) {
            int i = n.lastIndexOf('-');
            if (i >= 0) {
                try {
                    mx = Math.max(mx, Integer.parseInt(n.substring(i + 1)));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return mx == 0 ? 6 : mx;
    }

    // ---- misc helpers ----
    static String nodeNameForIP(String ip) {
        String[] p = ip.split("\\.");
        if (p.length != 4)
            return ip;
        return "redis-node-" + (Integer.parseInt(p[3]) - BASE_IP);
    }

    void saveSeedCount(int n) {
        try {
            Files.writeString(runtimeDir.resolve("seed_count"), String.valueOf(n));
        } catch (IOException ignored) {
        }
    }

    int seedCount() {
        try {
            return Integer.parseInt(Files.readString(runtimeDir.resolve("seed_count")).strip());
        } catch (Exception e) {
            return 1000;
        }
    }

    // ---- tiny arg parsing ----
    static String optVal(String[] args, String name, String def) {
        for (int i = 0; i < args.length - 1; i++)
            if (args[i].equals(name))
                return args[i + 1];
        return def;
    }

    static int optInt(String[] args, String name, int def) {
        String v = optVal(args, name, null);
        if (v == null)
            return def;
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    static boolean hasFlag(String[] args, String name) {
        for (String a : args)
            if (a.equals(name))
                return true;
        return false;
    }

    static String[] tail(String[] args) {
        String[] out = new String[Math.max(0, args.length - 1)];
        System.arraycopy(args, 1, out, 0, out.length);
        return out;
    }
}
