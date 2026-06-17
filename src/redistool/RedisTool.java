package redistool;

/**
 * redis-tool (Java) — a CLI that wraps Ansible to provision, operate, and
 * perform a zero-downtime rolling upgrade of a 6-node Redis Cluster.
 *
 * The same jar has two faces:
 * - host mode (provision/status/upgrade/...): orchestrates Ansible.
 * - node-agent mode: shipped into a container (any arch — it's a jar) to
 * speak the Redis cluster protocol directly for seed/verify/facts.
 */
public final class RedisTool {
    static final String USAGE = """
            redis-tool — Redis Cluster lifecycle tool (Ansible-driven, Java)

            USAGE:
              redis-tool <command> [flags]

            INFRASTRUCTURE:
              infra up | down | status

            LIFECYCLE:
              provision --version V --masters 3 --replicas-per-master 1
              data seed --keys N
              data verify
              status
              upgrade --target-version V --strategy rolling
              verify --full

            STRETCH:
              scale --add-nodes 2
              scale --remove-node ID
              rollback --target-version V

            The prerequisite check (container runtime + Ansible) runs first on
            every command.
            """;

    public static void main(String[] argv) {
        if (argv.length < 1) {
            System.out.print(USAGE);
            System.exit(2);
        }

        // node-agent runs INSIDE a container; no host prerequisite check.
        if (argv[0].equals("node-agent")) {
            System.exit(NodeAgent.run(slice(argv, 1)));
        }

        String cmd = argv[0];
        if (cmd.equals("-h") || cmd.equals("--help") || cmd.equals("help")) {
            System.out.print(USAGE);
            return;
        }

        // NOTE: exitCode is computed here and System.exit() is called AFTER
        // this method returns control past try/catch/finally, so the
        // finally block (which closes the structured JSONL log and writes
        // its "end" event — stretch S5) always runs, including on errors.
        int exitCode = 0;
        App app = null;
        try {
            app = App.newApp(cmd);
            app.checkPrereqs();

            String[] args = slice(argv, 1);
            switch (cmd) {
                case "infra" -> app.cmdInfra(args);
                case "provision" -> app.cmdProvision(args);
                case "data" -> app.cmdData(args);
                case "status" -> app.cmdStatus(args);
                case "upgrade" -> app.cmdUpgrade(args);
                case "verify" -> app.cmdVerify(args);
                case "scale" -> app.cmdScale(args);
                case "rollback" -> app.cmdRollback(args);
                default -> {
                    System.err.println("unknown command: " + cmd + "\n");
                    System.out.print(USAGE);
                    exitCode = 2;
                }
            }
        } catch (RuntimeException e) {
            System.err.println("\n\u001b[31m✗ " + e.getMessage() + "\u001b[0m");
            if (app != null && app.log != null)
                app.log.event("error", java.util.Map.of("msg", String.valueOf(e.getMessage())));
            exitCode = 1;
        } finally {
            if (app != null && app.log != null)
                app.log.close();
        }
        if (exitCode != 0)
            System.exit(exitCode);
    }

    private static String[] slice(String[] a, int from) {
        String[] out = new String[Math.max(0, a.length - from)];
        System.arraycopy(a, from, out, 0, out.length);
        return out;
    }
}
