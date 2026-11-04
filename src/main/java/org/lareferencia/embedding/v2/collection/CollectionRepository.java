package org.lareferencia.embedding.v2.collection;

import java.util.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(name="app.dvp.enabled",havingValue="true")
public class CollectionRepository {
  private final JdbcTemplate write;
  private final JdbcTemplate read;
  private static final RowMapper<CollectionResource> ROW=(rs,n)->new CollectionResource(
      rs.getObject("id",UUID.class),rs.getString("slug"),rs.getString("name"),rs.getTimestamp("created_at").toInstant());
  public CollectionRepository(@Qualifier("jdbcTemplate") JdbcTemplate write,@Qualifier("readJdbc") JdbcTemplate read) {
    this.write=write;this.read=read;
  }
  public CollectionResource insert(UUID id,String slug,String name) {
    return write.queryForObject("INSERT INTO dvp.collections(id,slug,name) VALUES (?,?,?) RETURNING id,slug,name,created_at",ROW,id,slug,name);
  }
  public Optional<CollectionResource> find(UUID id) {
    return read.query("SELECT id,slug,name,created_at FROM dvp.collections WHERE id=?",ROW,id).stream().findFirst();
  }
  public List<CollectionResource> list(UUID after,int limit) {
    return after==null
        ? read.query("SELECT id,slug,name,created_at FROM dvp.collections ORDER BY id LIMIT ?",ROW,limit)
        : read.query("SELECT id,slug,name,created_at FROM dvp.collections WHERE id>? ORDER BY id LIMIT ?",ROW,after,limit);
  }
}
