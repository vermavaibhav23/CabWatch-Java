package dev.cabwatch;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.http.*;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class Api {
  private final Trips trips;
  private final City city;
  private final Pipeline pipeline;
  private final KafkaTemplate<String, String> kafka;
  private final ObjectMapper json;

  public Api(Trips t, City c, Pipeline p, KafkaTemplate<String, String> k, ObjectMapper j) {
    trips = t;
    city = c;
    pipeline = p;
    kafka = k;
    json = j;
  }

  @GetMapping("/alerts")
  public Object alerts() throws Exception {
    return pipeline.alerts();
  }

  @GetMapping("/network")
  public City.Network network() {
    return city.network;
  }

  @GetMapping("/trips")
  public Object list() {
    return trips.all();
  }

  @PostMapping("/trips/{id}/complete")
  public Object complete(@PathVariable UUID id) {
    return trips.complete(id);
  }

  @GetMapping("/trips/{id}/history")
  public Object history(@PathVariable UUID id, @RequestParam(defaultValue = "0") long after) {
    return trips.history(id, Math.max(0, after));
  }

  public record Dispatch(UUID id, String pickup, String dropoff) {}

  @PostMapping("/trips")
  public Object create(@RequestBody Dispatch d) {
    return trips.create(Objects.requireNonNull(d.id()), d.pickup(), d.dropoff());
  }

  @PostMapping("/telemetry")
  public ResponseEntity<?> ingest(@Valid @RequestBody Reading r) throws Exception {
    if (!Double.isFinite(r.x())
        || !Double.isFinite(r.y())
        || r.capturedAt() > System.currentTimeMillis() + 2000)
      throw new IllegalArgumentException("Invalid coordinates or future capture time");
    trips.get(r.tripId()); // Cached after first access, no SQL on steady-state ingestion/detection.
    kafka
        .send(Pipeline.TOPIC, r.tripId().toString(), json.writeValueAsString(r))
        .get(12, TimeUnit.SECONDS);
    return ResponseEntity.accepted()
        .body(Map.of("eventId", r.eventId(), "status", "DURABLY_QUEUED"));
  }

  public record TelemetryBatch(@NotEmpty @Size(max = 500) List<@Valid Reading> readings) {}

  @PostMapping("/telemetry/batch")
  public ResponseEntity<?> ingestBatch(@Valid @RequestBody TelemetryBatch batch) throws Exception {
    // Validate the entire batch before publishing any event. Partial broker acknowledgments can
    // still occur, so a retry must preserve every event ID and sequence in the original batch.
    for (var r : batch.readings()) {
      if (!Double.isFinite(r.x())
          || !Double.isFinite(r.y())
          || r.capturedAt() > System.currentTimeMillis() + 2000)
        throw new IllegalArgumentException("Invalid reading");
      trips.get(r.tripId());
    }
    List<CompletableFuture<?>> sends = new ArrayList<>();
    for (var r : batch.readings())
      sends.add(kafka.send(Pipeline.TOPIC, r.tripId().toString(), json.writeValueAsString(r)));
    CompletableFuture.allOf(sends.toArray(CompletableFuture[]::new)).get(12, TimeUnit.SECONDS);
    return ResponseEntity.accepted()
        .body(Map.of("count", batch.readings().size(), "status", "DURABLY_QUEUED"));
  }

  @GetMapping("/state")
  public Object state() throws Exception {
    List<Object> result = new ArrayList<>();
    long now = System.currentTimeMillis();
    var all = trips.all();
    var states = pipeline.states(all.stream().map(Trips.Trip::id).toList());
    for (var t : all) {
      var s = states.get(t.id());
      result.add(
          Map.of(
              "trip",
              t,
              "state",
              s == null ? Map.of() : s,
              "delayed",
              !t.completed()
                  && (s == null || s.status().equals("DELAYED") || now - s.capturedAt() > 10000),
              "serverTime",
              now));
    }
    return result;
  }

  @ExceptionHandler({
    IllegalArgumentException.class,
    NullPointerException.class,
    org.springframework.dao.EmptyResultDataAccessException.class
  })
  ResponseEntity<?> bad(Exception e) {
    return ResponseEntity.badRequest().body(Map.of("error", "Invalid request or unknown trip"));
  }

  @ExceptionHandler({
    TimeoutException.class,
    ExecutionException.class,
    org.springframework.kafka.KafkaException.class
  })
  ResponseEntity<?> unavailable(Exception e) {
    return ResponseEntity.status(503)
        .header("Retry-After", "2")
        .body(Map.of("error", "Telemetry not acknowledged; retry same event ID and sequence"));
  }
}
