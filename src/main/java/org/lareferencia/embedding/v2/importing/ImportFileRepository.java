package org.lareferencia.embedding.v2.importing;

import java.io.InputStream;
import java.util.*;
import org.lareferencia.embedding.v2.common.V2Exception;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Resolves retry descendants to the original immutable file. */
@Component
@ConditionalOnProperty(name="app.dvp.enabled",havingValue="true")
public class ImportFileRepository {
  private final JdbcTemplate jdbc;private final DurableImportStore files;
  public ImportFileRepository(@Qualifier("jdbcTemplate") JdbcTemplate jdbc,DurableImportStore files) {this.jdbc=jdbc;this.files=files;}
  private record FileRow(UUID root,String key,String sha256,long size,boolean expired) { }
  private FileRow find(UUID job,boolean lock) {
    String suffix=lock?" FOR UPDATE OF f":"";
    return jdbc.query("""
        WITH RECURSIVE ancestors AS (
          SELECT id,parent_job_id FROM dvp.jobs WHERE id=?
          UNION ALL SELECT j.id,j.parent_job_id FROM dvp.jobs j JOIN ancestors a ON j.id=a.parent_job_id
        ) SELECT f.job_id,f.storage_key,f.sha256,f.byte_size,(f.expires_at IS NOT NULL AND f.expires_at<=clock_timestamp())
          FROM dvp.import_files f JOIN ancestors a ON a.id=f.job_id
        """+suffix,(rs,n)->new FileRow(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getLong(4),rs.getBoolean(5)),job).stream().findFirst()
        .orElseThrow(()->new V2Exception(409,"import_file_unavailable","Import file is unavailable"));
  }
  public InputStream openForActiveJob(UUID job) {var file=find(job,false);return files.openVerified(file.key(),file.sha256(),file.size());}
  /** Call from the retry-creation transaction so cleanup cannot race the eligibility check. */
  public void requireRetainedForRetry(UUID job) {
    FileRow file=find(job,true);
    if(file.expired()) {
      Boolean active=jdbc.queryForObject("""
          WITH RECURSIVE descendants AS (
            SELECT id,status FROM dvp.jobs WHERE id=?
            UNION ALL SELECT j.id,j.status FROM dvp.jobs j JOIN descendants d ON j.parent_job_id=d.id
          ) SELECT EXISTS(SELECT 1 FROM descendants WHERE status IN ('queued','running'))
          """,Boolean.class,file.root());
      if(!Boolean.TRUE.equals(active))throw new V2Exception(409,"import_file_unavailable","Import file is unavailable");
    }
    try(InputStream ignored=files.open(file.key())) { }
    catch(java.io.IOException e) {throw new V2Exception(503,"import_storage_unavailable","Import storage is unavailable");}
  }
}
