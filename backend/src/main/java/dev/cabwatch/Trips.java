package dev.cabwatch;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class Trips {
  public record Trip(
      UUID id,
      String pickup,
      String dropoff,
      List<String> route,
      boolean completed,
      String owner) {}

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
                        rs.getBoolean("completed"),
                        rs.getString("owner_id")),
                id));
  }

  public Trip owned(UUID id, String owner) {
    Trip t;
    try {
      t = get(id);
    } catch (org.springframework.dao.EmptyResultDataAccessException e) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.NOT_FOUND);
    }
    if (!t.owner().equals(owner))
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.NOT_FOUND);
    return t;
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

  public Trip create(UUID id, String start, String end, String owner) {
    city.route(start, end);
    db.update(
        "insert into trips(id,pickup,dropoff,owner_id) values (?,?,?,?) on conflict do nothing",
        id,
        start,
        end,
        owner);
    Trip t = owned(id, owner);
    if (!t.pickup.equals(start) || !t.dropoff.equals(end))
      throw new IllegalArgumentException("Trip ID already used for different stops");
    return t;
  }

  public List<Trip> all(String owner) {
    return db.query(
        "select id from trips where owner_id=? order by created_at desc limit 500",
        (rs, n) -> get(rs.getObject(1, UUID.class)),
        owner);
  }
}
