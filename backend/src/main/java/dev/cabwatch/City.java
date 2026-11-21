package dev.cabwatch;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class City {
  public record Network(Map<String, double[]> nodes, List<String> roads) {}

  public final Network network;

  public City(ObjectMapper mapper) throws Exception {
    try (var in = getClass().getResourceAsStream("/city.json")) {
      network = mapper.readValue(in, Network.class);
    }
  }

  public List<String> route(String start, String end) {
    if (!network.nodes.containsKey(start) || !network.nodes.containsKey(end) || start.equals(end))
      throw new IllegalArgumentException("Choose two different intersections");
    Map<String, Double> d = new HashMap<>();
    Map<String, String> prev = new HashMap<>();
    Set<String> seen = new HashSet<>();
    d.put(start, 0.0);
    while (true) {
      String u =
          d.keySet().stream()
              .filter(n -> !seen.contains(n))
              .min(Comparator.comparingDouble(d::get))
              .orElseThrow();
      if (u.equals(end)) break;
      seen.add(u);
      for (String road : network.roads) {
        if (!road.contains(u)) continue;
        String v = road.replace(u, "");
        double[] a = network.nodes.get(u), b = network.nodes.get(v);
        double nd = d.get(u) + Math.hypot(a[0] - b[0], a[1] - b[1]);
        if (nd < d.getOrDefault(v, Double.MAX_VALUE)) {
          d.put(v, nd);
          prev.put(v, u);
        }
      }
    }
    LinkedList<String> path = new LinkedList<>();
    for (String at = end; at != null; at = prev.get(at)) path.addFirst(at);
    return path;
  }

  public double deviation(List<String> route, double x, double y) {
    double best = Double.MAX_VALUE;
    for (int i = 1; i < route.size(); i++) {
      double[] a = network.nodes.get(route.get(i - 1)), b = network.nodes.get(route.get(i));
      double dx = b[0] - a[0], dy = b[1] - a[1];
      double t =
          Math.max(0, Math.min(1, ((x - a[0]) * dx + (y - a[1]) * dy) / (dx * dx + dy * dy)));
      best = Math.min(best, Math.hypot(x - a[0] - t * dx, y - a[1] - t * dy) * 6);
    }
    return best;
  }
}
