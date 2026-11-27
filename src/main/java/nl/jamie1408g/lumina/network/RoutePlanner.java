package nl.jamie1408g.lumina.network;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Finds the best journey between two stations with Dijkstra's algorithm.
 * A node is "a station on a specific line", so changing lines can carry a penalty.
 */
public final class RoutePlanner {

    private record Node(Station station, Line line) {
    }

    private record Queued(Node node, long cost) {
    }

    private record Edge(Node to, int cost, boolean transfer) {
    }

    private final Network network;

    public RoutePlanner(Network network) {
        this.network = network;
    }

    /** Best route preferring few transfers, or null when the stations are not connected. */
    public Route plan(Station from, Station to) {
        if (from == null || to == null) {
            return null;
        }
        if (from == to) {
            return new Route(from, to, List.of());
        }
        List<Node> path = search(from, to, network.settings().transferPenalty());
        return path == null ? null : toRoute(from, to, path);
    }

    /**
     * Best route from any of these stations to any of those (for places with several stations):
     * the same choice plan() makes, fewest transfers first. An empty route when they share a
     * station (you're already there), or null when none are connected.
     */
    public Route planBest(List<Station> from, List<Station> to) {
        for (Station a : from) {
            if (to.contains(a)) {
                return new Route(a, a, List.of());
            }
        }
        int penalty = network.settings().transferPenalty();
        Route best = null;
        long bestCost = Long.MAX_VALUE;
        for (Station a : from) {
            for (Station b : to) {
                Route route = plan(a, b);
                if (route == null) {
                    continue;
                }
                long cost = (long) route.distance() + (long) route.transfers() * penalty;
                if (cost < bestCost) {
                    best = route;
                    bestCost = cost;
                }
            }
        }
        return best;
    }

    /** Shortest ride distance in blocks (used for fares), or -1 when not connected. */
    public int shortestDistance(Station from, Station to) {
        if (from == null || to == null) {
            return -1;
        }
        if (from == to) {
            return 0;
        }
        List<Node> path = search(from, to, 0);
        return path == null ? -1 : toRoute(from, to, path).distance();
    }

    private List<Node> search(Station from, Station to, int transferPenalty) {
        Map<Node, Long> best = new HashMap<>();
        Map<Node, Node> previous = new HashMap<>();
        PriorityQueue<Queued> queue = new PriorityQueue<>((a, b) -> Long.compare(a.cost(), b.cost()));

        for (String lineCode : from.lineCodes()) {
            Line line = network.line(lineCode);
            if (line == null) {
                continue;
            }
            Node start = new Node(from, line);
            best.put(start, 0L);
            queue.add(new Queued(start, 0L));
        }

        while (!queue.isEmpty()) {
            Queued current = queue.poll();
            Node node = current.node();
            if (current.cost() > best.getOrDefault(node, Long.MAX_VALUE)) {
                continue;
            }
            if (node.station() == to) {
                List<Node> path = new ArrayList<>();
                for (Node step = node; step != null; step = previous.get(step)) {
                    path.add(step);
                }
                Collections.reverse(path);
                return path;
            }
            for (Edge edge : edges(node, transferPenalty)) {
                long cost = current.cost() + edge.cost();
                if (cost < best.getOrDefault(edge.to(), Long.MAX_VALUE)) {
                    best.put(edge.to(), cost);
                    previous.put(edge.to(), node);
                    queue.add(new Queued(edge.to(), cost));
                }
            }
        }
        return null;
    }

    private List<Edge> edges(Node node, int transferPenalty) {
        List<Edge> edges = new ArrayList<>();
        Line line = node.line();
        List<Station> stations = line.stations();
        int index = line.indexOf(node.station());
        int size = stations.size();

        int[] neighbours = {index - 1, index + 1};
        for (int n : neighbours) {
            int target = n;
            if (line.isLoop()) {
                target = (n + size) % size;
            }
            if (target < 0 || target >= size || target == index) {
                continue;
            }
            Station next = stations.get(target);
            edges.add(new Edge(new Node(next, line), network.hopDistance(node.station(), next), false));
        }

        for (String otherCode : node.station().lineCodes()) {
            if (otherCode.equals(line.code())) {
                continue;
            }
            Line other = network.line(otherCode);
            if (other != null) {
                edges.add(new Edge(new Node(node.station(), other), transferPenalty, true));
            }
        }
        return edges;
    }

    private Route toRoute(Station from, Station to, List<Node> path) {
        List<Route.Leg> legs = new ArrayList<>();
        int i = 0;
        while (i < path.size() - 1) {
            Node start = path.get(i);
            // Skip transfer steps (same station, other line).
            if (path.get(i + 1).station() == start.station()) {
                i++;
                continue;
            }
            Line line = start.line();
            int stops = 0;
            int distance = 0;
            int j = i;
            while (j < path.size() - 1 && path.get(j + 1).line() == line && path.get(j + 1).station() != path.get(j).station()) {
                distance += network.hopDistance(path.get(j).station(), path.get(j + 1).station());
                stops++;
                j++;
            }
            Station legFrom = start.station();
            Station legTo = path.get(j).station();
            legs.add(new Route.Leg(line, legFrom, legTo, towards(line, legFrom, path.get(i + 1).station()), stops, distance));
            i = j;
        }
        return new Route(from, to, legs);
    }

    /** Direction text: the terminus, or "via X" on a loop line. */
    private String towards(Line line, Station from, Station next) {
        if (line.isLoop()) {
            return "via " + next.name();
        }
        return line.indexOf(next) > line.indexOf(from) ? line.last().name() : line.first().name();
    }
}
