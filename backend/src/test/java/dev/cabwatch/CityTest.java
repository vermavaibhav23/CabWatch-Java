package dev.cabwatch;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class CityTest {
  @Test
  void pathsUseConnectedRoadsAndGeometryIsMetric() throws Exception {
    var c = new City(new ObjectMapper());
    var p = c.route("A", "Z");
    assertEquals("A", p.get(0));
    assertEquals("Z", p.get(p.size() - 1));
    for (int i = 1; i < p.size(); i++)
      assertTrue(
          c.network.roads().contains(p.get(i - 1) + p.get(i))
              || c.network.roads().contains(p.get(i) + p.get(i - 1)));
    double[] a = c.network.nodes().get("A");
    assertEquals(0, c.deviation(p, a[0], a[1]), 0.01);
    assertThrows(IllegalArgumentException.class, () -> c.route("A", "?"));
  }
}
