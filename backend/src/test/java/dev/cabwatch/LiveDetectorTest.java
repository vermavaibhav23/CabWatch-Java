package dev.cabwatch;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class LiveDetectorTest {
  LiveDetector d = new LiveDetector();
  UUID id = UUID.randomUUID();
  long now = 100000;

  Reading r(long n, long time) {
    return new Reading(UUID.randomUUID(), id, n, time, 900, 500);
  }

  @Test
  void staleBacklogCannotAlertAndFreshMustReconfirm() {
    var s = d.apply(null, r(1, now), now, 10000, 300);
    s = d.apply(s, r(2, now + 1000), now + 1000, 10000, 300);
    assertEquals(2, s.consecutive());
    s = d.apply(s, r(3, now + 2000), now + 300000, 10000, 300);
    assertEquals("DELAYED", s.status());
    assertFalse(s.offRoute());
    assertEquals(0, s.consecutive());
    s = d.apply(s, r(4, now + 300000), now + 300000, 10000, 300);
    assertEquals(1, s.consecutive());
    assertFalse(s.offRoute());
    s = d.apply(s, r(5, now + 301000), now + 301000, 10000, 300);
    s = d.apply(s, r(6, now + 302000), now + 302000, 10000, 300);
    assertTrue(s.offRoute());
  }

  @Test
  void duplicateAndOlderSequencesNeverRewind() {
    var s = d.apply(null, r(5, now), now, 10000, 300);
    assertSame(s, d.apply(s, r(4, now + 1), now + 1, 10000, 0));
    assertSame(s, d.apply(s, r(5, now + 1), now + 1, 10000, 0));
  }

  @Test
  void skippedSequenceResetsConfirmation() {
    var s = d.apply(null, r(1, now), now, 10000, 300);
    s = d.apply(s, r(2, now + 1), now + 1, 10000, 300);
    s = d.apply(s, r(4, now + 2), now + 2, 10000, 300);
    assertEquals(1, s.consecutive());
  }

  @Test
  void newerSequenceWithOldTimestampCannotCount() {
    var s = d.apply(null, r(1, now), now, 10000, 300);
    s = d.apply(s, r(2, now - 1), now, 10000, 300);
    assertEquals("DELAYED", s.status());
    assertEquals(0, s.consecutive());
  }

  @Test
  void returningToRouteClearsAlert() {
    var s = d.apply(null, r(1, now), now, 10000, 300);
    s = d.apply(s, r(2, now + 1), now + 1, 10000, 300);
    s = d.apply(s, r(3, now + 2), now + 2, 10000, 300);
    assertTrue(s.offRoute());
    s = d.apply(s, r(4, now + 3), now + 3, 10000, 10);
    assertFalse(s.offRoute());
  }

  @Test
  void warningAndFutureClockAreExplicit() {
    assertEquals("LAGGING", d.apply(null, r(1, now - 6000), now, 10000, 0).status());
    assertEquals("DELAYED", d.apply(null, r(1, now + 3000), now, 10000, 0).status());
  }
}
