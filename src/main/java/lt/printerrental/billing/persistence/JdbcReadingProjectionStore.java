package lt.printerrental.billing.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.*;
import lt.printerrental.billing.application.ReadingProjectionStore;
import lt.printerrental.billing.domain.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcReadingProjectionStore implements ReadingProjectionStore {
  private final JdbcTemplate db;
  private final ObjectMapper json;
  private final String table;

  public JdbcReadingProjectionStore(JdbcTemplate db, ObjectMapper json) {
    this(db, json, "billing");
  }

  @Autowired
  public JdbcReadingProjectionStore(
      JdbcTemplate db, ObjectMapper json, @Value("${DB_SCHEMA:billing}") String schema) {
    if (schema == null || !schema.matches("[a-z][a-z0-9_]*"))
      throw new IllegalArgumentException("Invalid schema");
    this.db = db;
    this.json = json;
    this.table = schema + ".reading_projections";
  }

  public boolean receive(
      UUID contract, UUID readingId, LocalDate asOf, List<Long> counters, long revision) {
    Quantities.counters(counters);
    if (revision < 1) throw new IllegalArgumentException("Invalid stream revision");
    try {
      return db.update(
              "INSERT INTO "
                  + table
                  + "(contract_id,as_of,reading_id,counters,stream_revision)"
                  + " VALUES(?,?,?,?::jsonb,?) ON CONFLICT(contract_id,as_of) DO UPDATE SET"
                  + " reading_id=EXCLUDED.reading_id,counters=EXCLUDED.counters,stream_revision=EXCLUDED.stream_revision"
                  + " WHERE "
                  + table
                  + ".stream_revision<EXCLUDED.stream_revision",
              contract,
              asOf,
              readingId,
              json.writeValueAsString(counters),
              revision)
          > 0;
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalArgumentException("Invalid counters");
    }
  }

  public List<UsageHistory.Reading> readings(UUID contract) {
    return db.query(
        "SELECT reading_id,as_of,counters FROM " + table + " WHERE contract_id=? ORDER BY as_of",
        (r, n) -> {
          try {
            return new UsageHistory.Reading(
                r.getObject(1, UUID.class),
                r.getObject(2, LocalDate.class),
                json.readValue(
                    r.getString(3),
                    new com.fasterxml.jackson.core.type.TypeReference<List<Long>>() {}));
          } catch (Exception e) {
            throw new java.sql.SQLException("Invalid reading projection", e);
          }
        },
        contract);
  }

  @Override
  public OptionalLong streamRevision(UUID contract, LocalDate asOf) {
    var revisions =
        db.query(
            "SELECT stream_revision FROM " + table + " WHERE contract_id=? AND as_of=?",
            (r, n) -> r.getLong(1),
            contract,
            asOf);
    return revisions.isEmpty() ? OptionalLong.empty() : OptionalLong.of(revisions.getFirst());
  }
}
