package org.lareferencia.embedding.v2.profile;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(name="app.dvp.enabled",havingValue="true")
public class ProfileRepository {
  private final JdbcTemplate write,read;
  private final ObjectMapper mapper;
  private final RowMapper<ProfileResource> row=this::mapRow;
  private ProfileResource mapRow(java.sql.ResultSet rs,int n) throws java.sql.SQLException {
    try {
      String mode=rs.getString("execution_mode");
      return new ProfileResource(rs.getObject("id",UUID.class),rs.getString("slug"),rs.getString("model"),
          rs.getString("model_revision"),rs.getInt("dimensions"),mode,rs.getString("provider_ref"),
          mapper.readValue(rs.getString("preparation"),ProfileRequest.Preparation.class),rs.getString("fingerprint"),
          mode.equals("http"),rs.getTimestamp("created_at").toInstant());
    } catch(java.io.IOException e) { throw new IllegalStateException("Invalid stored profile preparation"); }
  }
  public ProfileRepository(@Qualifier("jdbcTemplate") JdbcTemplate write,@Qualifier("readJdbc") JdbcTemplate read,ObjectMapper mapper) {
    this.write=write;this.read=read;this.mapper=mapper.copy().setPropertyNamingStrategy(com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE);
  }
  public Optional<ProfileResource> insert(ProfileRequest p,String fingerprint) {
    try {
      return write.query("""
          INSERT INTO dvp.embedding_profiles(id,slug,model,model_revision,dimensions,execution_mode,provider_ref,preparation,fingerprint)
          VALUES (?,?,?,?,?,?,?,?::jsonb,?) ON CONFLICT DO NOTHING RETURNING *
          """,row,UUID.randomUUID(),p.slug(),p.model(),p.modelRevision(),p.dimensions(),p.executionMode(),p.providerRef(),
          mapper.writeValueAsString(p.preparation()),fingerprint).stream().findFirst();
    } catch(java.io.IOException e) { throw new IllegalStateException("Cannot serialize profile preparation"); }
  }
  public Optional<ProfileResource> duplicate(String slug,String fingerprint) {
    return write.query("SELECT * FROM dvp.embedding_profiles WHERE slug=? OR fingerprint=? ORDER BY (slug=?) DESC LIMIT 1",row,slug,fingerprint,slug).stream().findFirst();
  }
  public Optional<ProfileResource> find(UUID id) { return read.query("SELECT * FROM dvp.embedding_profiles WHERE id=?",row,id).stream().findFirst(); }
  public List<ProfileResource> list(UUID after,int limit) {
    return after==null?read.query("SELECT * FROM dvp.embedding_profiles ORDER BY id LIMIT ?",row,limit)
        :read.query("SELECT * FROM dvp.embedding_profiles WHERE id>? ORDER BY id LIMIT ?",row,after,limit);
  }
}
