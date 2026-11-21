package dev.cabwatch;

/** Pure state machine. A stale event is never presented as a current alert. */
public class LiveDetector {
  public record State(
      long sequence,
      long capturedAt,
      long processedAt,
      double x,
      double y,
      int consecutive,
      String status,
      boolean offRoute,
      double latencyMs) {}

  public State apply(State old, Reading r, long now, long staleMs, double distance) {
    if (old != null && r.sequence() <= old.sequence()) return old;
    long age = now - r.capturedAt();
    if (age > staleMs || age < -2000)
      return new State(
          r.sequence(),
          old == null ? 0 : old.capturedAt(),
          now,
          old == null ? 0 : old.x(),
          old == null ? 0 : old.y(),
          0,
          "DELAYED",
          false,
          Math.max(0, age));
    boolean contiguous =
        old != null
            && r.sequence() == old.sequence() + 1
            && r.capturedAt() > old.capturedAt()
            && r.capturedAt() - old.capturedAt() <= staleMs
            && !old.status().equals("DELAYED");
    if (old != null && r.capturedAt() <= old.capturedAt())
      return new State(
          r.sequence(),
          old.capturedAt(),
          now,
          old.x(),
          old.y(),
          0,
          "DELAYED",
          false,
          Math.max(0, age));
    int n = distance >= 200 ? (contiguous ? Math.min(3, old.consecutive() + 1) : 1) : 0;
    String status = age > 5000 ? "LAGGING" : n > 0 && n < 3 ? "CONFIRMING" : "LIVE";
    return new State(
        r.sequence(), r.capturedAt(), now, r.x(), r.y(), n, status, n >= 3, Math.max(0, age));
  }
}
