package dev.cabwatch;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class Trips {
  public record Trip(
      UUID id, String pickup, String dropoff, List<String> route, boolean completed) {}

  private final JdbcTemplate db;
  private final City city;
  private final Map<UUID, Trip> cache = new ConcurrentHashMap<>();

  public Trips(JdbcTemplate db, City city) {
    this.db = db;
    this.city = city;
  }

  public Trip get(UUID id) {
    return cache.computeIfAbsent(
        id,
        k ->
            db.queryForObject(
                "select * from trips where id=?",
                (rs, n) ->
                    new Trip(
                        id,
                        rs.getString("pickup"),
                        rs.getString("dropoff"),
                        city.route(rs.getString("pickup"), rs.getString("dropoff")),
                        rs.getBoolean("completed")),
                id));
  }

  public Trip complete(UUID id) {
    get(id);
    db.update("update trips set completed=true where id=?", id);
    cache.remove(id);
    return get(id);
  }

  public Object history(UUID id, long after) {
    get(id);
    return db.queryForList(
        "select event_id, sequence, captured_at, x, y from telemetry where trip_id=? and sequence>?"
            + " order by sequence limit 1000",
        id,
        after);
  }

  public Trip create(UUID id, String start, String end) {
    city.route(start, end);
    db.update(
        "insert into trips(id,pickup,dropoff) values (?,?,?) on conflict do nothing",
        id,
        start,
        end);
    Trip t = get(id);
    if (!t.pickup.equals(start) || !t.dropoff.equals(end))
      throw new IllegalArgumentException("Trip ID already used for different stops");
    return t;
  }

  public List<Trip> all() {
    return db.query(
        "select id from trips order by created_at desc limit 500",
        (rs, n) -> get(rs.getObject(1, UUID.class)));
  }
}
