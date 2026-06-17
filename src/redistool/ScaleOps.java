package redistool;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import redistool.App.NodeSpec;
import redistool.Cluster.Facts;
import redistool.Cluster.NodeInfo;

/** Stretch S1/S2 — scale out (add master+replica, rebalance) and scale in. */
final class ScaleOps {
    private ScaleOps() {}

    static void scaleOut(App app, int n) {
        if (n != 2) throw new RuntimeException("scale --add-nodes currently supports exactly 2 (one master + one replica)");
        Facts f = app.loadFacts("redis-node-1");
        if (!"ok".equals(f.clusterState)) throw new RuntimeException("cluster_state is " + f.clusterState + "; refusing to scale");
        String version = f.masters().get(0).version;
        int existing = app.maxNodeIndex();
        NodeSpec master = App.nodeSpec(existing + 1), replica = App.nodeSpec(existing + 2);
        app.log.step("Scaling out: new master %s (%s), new replica %s (%s), version %s",
                master.name(), master.redisIp(), replica.name(), replica.redisIp(), version);

        String network = app.detectNetwork();
        for (NodeSpec ns : List.of(master, replica)) {
            app.runNodeOnNet(ns, network);
            if (!Util.waitTCP("127.0.0.1", ns.sshPort(), 90_000)) throw new RuntimeException(ns.name() + " SSH never came up");
            app.log.ok("%s up (cluster IP %s, ssh :%d)", ns.name(), ns.redisIp(), ns.sshPort());
        }

        app.log.step("Installing Redis %s on new nodes", version);
        app.runPlaybook("node_setup.yml", Map.of("redis_version", version), master.name() + "," + replica.name());

        String node1Ip = App.nodeSpec(1).redisIp();
        int port = App.REDIS_PORT;
        app.log.step("Adding %s as a new master", master.redisIp());
        app.clusterAdmin(String.format("redis-cli --cluster add-node %s:%d %s:%d", master.redisIp(), port, node1Ip, port));

        final Facts[] holder = {null};
        boolean appeared = Util.pollUntil(30_000, 3000, () -> {
            holder[0] = app.loadFacts("redis-node-1");
            return holder[0].byIp(master.redisIp()) != null;
        });
        if (!appeared) throw new RuntimeException("new master " + master.redisIp() + " did not appear in topology");
        String masterId = holder[0].byIp(master.redisIp()).id;

        app.log.step("Adding %s as a replica of new master %s", replica.redisIp(), masterId);
        app.clusterAdmin(String.format("redis-cli --cluster add-node %s:%d %s:%d --cluster-slave --cluster-master-id %s",
                replica.redisIp(), port, node1Ip, port, masterId));

        app.log.step("Rebalancing hash slots across all masters");
        app.clusterAdmin(String.format("redis-cli --cluster rebalance %s:%d --cluster-use-empty-masters --cluster-yes", node1Ip, port));

        f = app.loadFacts("redis-node-1");
        Cluster.printStatus(f);
        app.log.ok("Scale-out complete: %d masters, cluster_state:%s", f.masters().size(), f.clusterState);
    }

    static void scaleIn(App app, String idOrIp) {
        Facts f = app.loadFacts("redis-node-1");
        NodeInfo target = null;
        for (NodeInfo n : f.nodes) if (n.id.equals(idOrIp) || n.ip.equals(idOrIp)) { target = n; break; }
        if (target == null) throw new RuntimeException("no node matches " + idOrIp);
        if (!target.role.equals("master")) throw new RuntimeException(idOrIp + " is a " + target.role + "; pass a master node id/IP");
        if (f.masters().size() <= 1) throw new RuntimeException("refusing to remove the last master");

        List<NodeInfo> replicas = new ArrayList<>();
        for (NodeInfo nn : f.replicas()) if (nn.masterId.equals(target.id)) replicas.add(nn);

        String node1Ip = App.nodeSpec(1).redisIp();
        int port = App.REDIS_PORT;
        String admin = target.ip.equals(node1Ip) ? "redis-node-2" : "redis-node-1";
        final NodeInfo tgt = target;
        app.log.step("Scaling in master %s (%s) and its %d replica(s)", tgt.ip, tgt.id, replicas.size());

        app.log.step("Migrating slots off %s", tgt.ip);
        app.clusterAdminOn(admin, String.format(
                "redis-cli --cluster rebalance %s:%d --cluster-weight %s=0 --cluster-use-empty-masters --cluster-yes",
                node1Ip, port, tgt.id));

        boolean drained = Util.pollUntil(60_000, 3000, () -> {
            Facts ff = app.loadFacts(admin);
            NodeInfo nn = ff.byIp(tgt.ip);
            return nn != null && nn.slots.isEmpty();
        });
        if (!drained) throw new RuntimeException("slots did not fully migrate off " + tgt.ip);
        app.log.ok("%s now owns 0 slots", tgt.ip);

        for (NodeInfo r : replicas) {
            app.log.step("Removing replica %s", r.ip);
            app.clusterAdminOn(admin, String.format("redis-cli --cluster del-node %s:%d %s", node1Ip, port, r.id));
        }
        app.log.step("Removing master %s", tgt.ip);
        app.clusterAdminOn(admin, String.format("redis-cli --cluster del-node %s:%d %s", node1Ip, port, tgt.id));

        List<NodeInfo> removed = new ArrayList<>(replicas);
        removed.add(tgt);
        for (NodeInfo nn : removed) {
            String name = App.nodeNameForIP(nn.ip);
            app.removeContainer(name);
            app.log.ok("removed container %s", name);
        }

        f = app.loadFacts(admin);
        Cluster.printStatus(f);
        app.log.ok("Scale-in complete: %d masters, cluster_state:%s", f.masters().size(), f.clusterState);
    }
}
