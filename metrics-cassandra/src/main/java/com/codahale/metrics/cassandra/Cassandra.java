package com.codahale.metrics.cassandra;

import com.datastax.driver.core.*;
import com.datastax.driver.core.ProtocolOptions.Compression;
import com.datastax.driver.core.exceptions.DriverException;
import com.datastax.driver.core.exceptions.NoHostAvailableException;
import com.datastax.driver.core.policies.DowngradingConsistencyRetryPolicy;
import com.datastax.driver.core.policies.LatencyAwarePolicy;
import com.datastax.driver.core.policies.RoundRobinPolicy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.List;

/**
 * A client to a Carbon server.
 */
public class Cassandra implements Closeable {
  private static final Pattern UNSAFE = Pattern.compile("[\\.\\s]+");

  private final List<String> addresses;
  private final int port;
  private final String keyspace;
  private final String table;
  private final int ttl;

  private Cluster cluster;
  private int failures;
  private boolean initialized;
  private Session session;
  private ConsistencyLevel consistency;
  private Map<String, PreparedStatement> preparedStatements;

  private static final Logger LOGGER = LoggerFactory.getLogger(Cassandra.class);

  /**
   * Creates a new client which connects to the given address and socket factory.
   *
   * @param address Contact point of the Cassandra cluster
   * @param keyspace Keyspace for metrics
   * @param table name of metric table
   * @param ttl TTL for entries
   * @param port Port for Cassandra cluster native transport contact point
   * @param consistency Consistency level to attain
   */
  public Cassandra(List<String> addresses, String keyspace, String table,
      int ttl, int port, String consistency) {
    this.addresses = addresses;
    this.port = port;

    this.keyspace = keyspace;
    this.table = table;
    this.ttl = ttl;
    this.consistency = ConsistencyLevel.valueOf(consistency);

    this.cluster = build();

    this.initialized = false;
    this.failures = 0;
    this.preparedStatements = new HashMap<String, PreparedStatement>();
  }

  private Cluster build() {
    Cluster.Builder builder = Cluster.builder();
    for (String address : addresses) {
      builder.addContactPoint(address);
    }
    return builder
      .withPort(port)
      .withCompression(Compression.LZ4)
      .withRetryPolicy(DowngradingConsistencyRetryPolicy.INSTANCE)
      .withLoadBalancingPolicy(LatencyAwarePolicy.builder(new RoundRobinPolicy()).build())
			.build();
  }

  private void tryConnect() {
    preparedStatements.clear();
    session = cluster.connect(keyspace);
  }

  /**
   * Connects to the server.
   *
   */
  public void connect() {
    try {
      tryConnect();
    } catch (NoHostAvailableException e) {
      LOGGER.warn("Unable to connect to Cassandra, will retry contact points next time",
          cluster, e);
      session.close();
      cluster.close();
      cluster = build();
      tryConnect();
    }
  }

  /**
   * Sends the given measurement to the server.
   *
   * @param name      the name of the metric
   * @param value     the value of the metric
   * @param timestamp the timestamp of the metric
   * @throws DriverException if there was an error sending the metric
   */
  public void send(String name, Double value, Date timestamp) throws DriverException {
    try {
      String tableName = sanitize(table);
      if (!initialized) {
        session.execute(new SimpleStatement(
              "CREATE TABLE IF NOT EXISTS " + tableName + " (      " +
              "  name VARCHAR,                                     " +
              "  timestamp TIMESTAMP,                              " +
              "  value DOUBLE,                                     " +
              "  PRIMARY KEY (name, timestamp))                    " +
              "  WITH bloom_filter_fp_chance=0.100000 AND          " +
              "  compaction = {'class':'LeveledCompactionStrategy'}")
        );
        session.execute(new SimpleStatement(
              "CREATE TABLE IF NOT EXISTS " + tableName + "_names (   " +
              "  name VARCHAR,                                        " +
              "  last_updated TIMESTAMP,                              " +
              "  PRIMARY KEY (name))                                  " +
              "  WITH bloom_filter_fp_chance=0.100000 AND             " +
              "  compaction = {'class':'LeveledCompactionStrategy'}   ")
        );
        initialized = true;
      }

      if (!preparedStatements.containsKey("values-" + tableName)) {
        preparedStatements.put("values-" + tableName,
            session.prepare(
                "INSERT INTO " + tableName + " (name, timestamp, value) VALUES (?, ?, ?) USING TTL ?")
                .setConsistencyLevel(consistency));
      }
      if (!preparedStatements.containsKey("names-" + tableName)) {
        preparedStatements.put("names-" + tableName,
            session.prepare(
                "UPDATE " + tableName + "_names SET last_updated = ? WHERE name = ?")
                .setConsistencyLevel(consistency));
      }

      session.executeAsync(
          preparedStatements.get("values-" + tableName).bind(
            name, timestamp, value, ttl)
      );

      session.executeAsync(
          preparedStatements.get("names-" + tableName).bind(
            timestamp, name).setConsistencyLevel(consistency)
      );

      this.failures = 0;
    } catch (DriverException e) {
      failures++;
      throw e;
    }
  }

  /**
   * Returns the number of failed writes to the server.
   *
   * @return the number of failed writes to the server
   */
  public int getFailures() {
    return failures;
  }

  @Override
  public void close() throws DriverException {
    session.close();
  }

  protected String sanitize(String s) {
    return UNSAFE.matcher(s).replaceAll("_");
  }
}
