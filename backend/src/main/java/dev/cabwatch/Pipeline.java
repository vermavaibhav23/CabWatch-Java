package dev.cabwatch;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.*;
import org.springframework.kafka.listener.*;
import org.springframework.stereotype.Service;

@Service
public class Pipeline {
  public static final String TOPIC = "cabwatch.telemetry.v1";
  private static final DefaultRedisScript<Long> SAVE =
      new DefaultRedisScript<>(
          "local old=redis.call('GET',KEYS[1]); if old and"
              + " cjson.decode(old).sequence>=tonumber(ARGV[1]) then return 0 end;"
              + " redis.call('SET',KEYS[1],ARGV[2],'EX',86400); local events=cjson.decode(ARGV[3]);"
              + " for _,event in ipairs(events) do"
              + " redis.call('LPUSH','cabwatch:alerts',cjson.encode(event)) end;"
              + " redis.call('LTRIM','cabwatch:alerts',0,199); return 1",
          Long.class);
  private final ObjectMapper json;
  private final Trips trips;
  private final City city;
  private final JdbcTemplate db;
  private final StringRedisTemplate redis;
  private final MeterRegistry meters;
  private final long stale;
  private final LiveDetector detector = new LiveDetector();

  public Pipeline(
      ObjectMapper j,
      Trips t,
      City c,
      JdbcTemplate d,
      StringRedisTemplate r,
      MeterRegistry m,
      @Value("${cabwatch.stale-ms}") long s) {
    json = j;
    trips = t;
    city = c;
    db = d;
    redis = r;
    meters = m;
    stale = s;
  }

  public LiveDetector.State state(UUID id) throws Exception {
    return states(List.of(id)).get(id);
  }

  public Map<UUID, LiveDetector.State> states(Collection<UUID> vehicleIds) throws Exception {
    var ids = new ArrayList<>(vehicleIds);
    Map<UUID, LiveDetector.State> result = new LinkedHashMap<>();
    if (ids.isEmpty()) return result;
    var saved = redis.opsForValue().multiGet(ids.stream().map(id -> "live:" + id).toList());
    for (int i = 0; i < ids.size(); i++) {
      String value = saved == null ? null : saved.get(i);
      result.put(
          ids.get(i), value == null ? null : json.readValue(value, LiveDetector.State.class));
    }
    return result;
  }

  @KafkaListener(
      id = "live",
      topics = TOPIC,
      groupId = "cabwatch-live-v1",
      containerFactory = "liveFactory",
      autoStartup = "${LIVE_ENABLED:true}")
  public void live(List<ConsumerRecord<String, String>> batch) throws Exception {
    List<Reading> readings = new ArrayList<>();
    Set<UUID> ids = new LinkedHashSet<>();
    for (var record : batch) {
      var reading = json.readValue(record.value(), Reading.class);
      if (!reading.tripId().toString().equals(record.key()))
        throw new IllegalArgumentException("Kafka key differs from vehicle ID");
      readings.add(reading);
      ids.add(reading.tripId());
    }
    Map<UUID, LiveDetector.State> states = states(ids);
    Map<UUID, List<Map<String, Object>>> alerts = new HashMap<>();
    for (var r : readings) {
      UUID id = r.tripId();
      var old = states.get(id);
      long now = System.currentTimeMillis();
      // Stale recovery avoids route calculations and SQL entirely.
      double distance =
          now - r.capturedAt() > stale ? 0 : city.deviation(trips.get(id).route(), r.x(), r.y());
      var next = detector.apply(old, r, now, stale, distance);
      if (next == old) {
        meters.counter("cabwatch.live.duplicates").increment();
        continue;
      }
      if (next.offRoute() && (old == null || !old.offRoute()))
        alerts
            .computeIfAbsent(id, k -> new ArrayList<>())
            .add(
                Map.of(
                    "tripId",
                    id,
                    "sequence",
                    r.sequence(),
                    "capturedAt",
                    r.capturedAt(),
                    "message",
                    "Three fresh consecutive readings are outside the planned route"));
      states.put(id, next);
      meters
          .timer("cabwatch.reading.age")
          .record(Math.max(0, now - r.capturedAt()), TimeUnit.MILLISECONDS);
      if (next.status().equals("DELAYED"))
        meters.counter("cabwatch.live.stale.skipped").increment();
    }
    // MGET plus a pipeline avoids a network round trip per vehicle. Each Lua command
    // still atomically commits one vehicle's state and its alert transitions.
    var entries = new ArrayList<>(states.entrySet());
    List<byte[][]> commands = new ArrayList<>();
    long beforePublish = System.currentTimeMillis();
    for (var e : entries) {
      var s = e.getValue();
      if (!s.status().equals("DELAYED") && beforePublish - s.capturedAt() > stale) {
        s =
            new LiveDetector.State(
                s.sequence(),
                s.capturedAt(),
                beforePublish,
                s.x(),
                s.y(),
                0,
                "DELAYED",
                false,
                beforePublish - s.capturedAt());
        e.setValue(s);
      }
      var events =
          alerts.getOrDefault(e.getKey(), List.of()).stream()
              .filter(event -> beforePublish - (Long) event.get("capturedAt") <= stale)
              .toList();
      alerts.put(e.getKey(), events);
      commands.add(
          new byte[][] {
            bytes("live:" + e.getKey()),
            bytes(Long.toString(s.sequence())),
            bytes(json.writeValueAsString(s)),
            bytes(json.writeValueAsString(events))
          });
    }
    var results =
        redis.executePipelined(
            (RedisCallback<Object>)
                connection -> {
                  for (var args : commands)
                    connection
                        .scriptingCommands()
                        .eval(bytes(SAVE.getScriptAsString()), ReturnType.INTEGER, 1, args);
                  return null;
                });
    long published = System.currentTimeMillis();
    for (int i = 0; i < entries.size(); i++) {
      if (!Long.valueOf(1).equals(results.get(i))) continue;
      var entry = entries.get(i);
      var s = entry.getValue();
      if (!s.status().equals("DELAYED")) {
        long age = Math.max(0, published - s.capturedAt());
        meters.timer("cabwatch.live.detection.latency").record(age, TimeUnit.MILLISECONDS);
        if (age > 5000) meters.counter("cabwatch.live.latency.warnings").increment();
      }
      for (var event : alerts.getOrDefault(entry.getKey(), List.of()))
        meters
            .timer("cabwatch.alert.latency")
            .record(Math.max(0, published - (Long) event.get("capturedAt")), TimeUnit.MILLISECONDS);
    }
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }

  public List<Object> alerts() throws Exception {
    var result = new ArrayList<Object>();
    var values = redis.opsForList().range("cabwatch:alerts", 0, 99);
    if (values != null) for (var value : values) result.add(json.readValue(value, Map.class));
    return result;
  }

  @KafkaListener(
      id = "history",
      topics = TOPIC,
      groupId = "cabwatch-history-v1",
      containerFactory = "liveFactory",
      autoStartup = "${HISTORY_ENABLED:true}")
  public void history(List<ConsumerRecord<String, String>> batch) throws Exception {
    List<Object[]> rows = new ArrayList<>();
    for (var record : batch) {
      var r = json.readValue(record.value(), Reading.class);
      rows.add(
          new Object[] {
            r.eventId(), r.tripId(), r.sequence(), r.capturedAt(), r.x(), r.y(), record.timestamp()
          });
    }
    db.batchUpdate(
        "insert into telemetry(event_id,trip_id,sequence,captured_at,x,y,received_at) values"
            + " (?,?,?,to_timestamp(? / 1000.0),?,?,to_timestamp(? / 1000.0)) on conflict do"
            + " nothing",
        rows);
    meters.counter("cabwatch.history.processed").increment(rows.size());
  }
}
