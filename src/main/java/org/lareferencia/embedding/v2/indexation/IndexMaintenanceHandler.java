package org.lareferencia.embedding.v2.indexation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.lareferencia.embedding.v2.common.IdempotencyService;
import org.lareferencia.embedding.v2.job.JobHandler;
import org.lareferencia.embedding.v2.job.JobRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Maintenance uses an isolated connection and session lock, never the heartbeat pool. */
@Component
@ConditionalOnProperty(name="app.dvp.enabled",havingValue="true")
public class IndexMaintenanceHandler implements JobHandler {
  private final JdbcTemplate write;private final TransactionTemplate writes;private final DataSource maintenance;private final ObjectMapper mapper;
  private final java.util.concurrent.Semaphore maintenanceSlot=new java.util.concurrent.Semaphore(1);
  public IndexMaintenanceHandler(@Qualifier("jdbcTemplate") JdbcTemplate write,@Qualifier("writeTransactions") TransactionTemplate writes,
      @Qualifier("maintenanceDataSource") DataSource maintenance,ObjectMapper mapper) {
    this.write=write;this.writes=writes;this.maintenance=maintenance;this.mapper=mapper;
  }
  public String kind() {return "maintenance";}
  public JsonNode publicParameters(JsonNode internal) {return mapper.createObjectNode().put("operation",internal.path("operation").asText());}
  private record Index(UUID id,int dims,String table,String metric,String strategy,String state) { }
  public void run(JobRepository.Lease lease,JobRepository jobs) throws Exception {
    if(!maintenanceSlot.tryAcquire()) return;
    try { runMaintenance(lease,jobs); } finally { maintenanceSlot.release(); }
  }
  private void runMaintenance(JobRepository.Lease lease,JobRepository jobs) throws Exception {
    var job=jobs.find(lease.jobId()).orElseThrow(JobRepository.LeaseLostException::new);
    UUID id=job.indexationId();if(id==null) throw new IllegalStateException("Maintenance index unavailable");
    String operation=job.parameters().path("operation").asText();
    try(Connection connection=maintenance.getConnection()) {
      connection.setAutoCommit(true);long lock=IdempotencyService.lockId("indexation-maintenance",id.toString());
      try(var statement=connection.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
        statement.setLong(1,lock);try(var result=statement.executeQuery()) {result.next();if(!result.getBoolean(1))return;}
      }
      try {
        Index index=load(id);if(!index.table().equals(IndexationService.table(id))) throw new IllegalStateException("Invalid physical table identifier");
        try {
          switch(operation) {
            case "provision" -> provision(lease,index);
            case "reconcile" -> reconcile(lease,index,connection);
            case "build_ann" -> build(lease,index,connection);
            default -> throw new IllegalStateException("Invalid maintenance operation");
          }
        } catch (Exception failure) {
          if (!(failure instanceof JobRepository.LeaseLostException)) {
            try { writes.executeWithoutResult(tx->{fence(lease);
              if(operation.equals("provision")) write.update("UPDATE dvp.indexations SET state='failed' WHERE id=?",id);
              if(operation.equals("build_ann")) write.update("UPDATE dvp.indexations SET ann_state='failed' WHERE id=?",id);
            }); } catch (JobRepository.LeaseLostException ignored) { }
          }
          throw failure;
        }
      } finally {
        try(var statement=connection.prepareStatement("SELECT pg_advisory_unlock(?)")) {statement.setLong(1,lock);statement.execute();}
      }
    }
  }
  private Index load(UUID id) {
    return write.query("SELECT i.id,i.vector_table,i.metric,i.ann_strategy,i.state,p.dimensions FROM dvp.indexations i JOIN dvp.embedding_profiles p ON p.id=i.profile_id WHERE i.id=?",
        (rs,n)->new Index(rs.getObject(1,UUID.class),rs.getInt(6),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5)),id).stream().findFirst()
        .orElseThrow(()->new IllegalStateException("Index unavailable"));
  }
  private void fence(JobRepository.Lease lease) {
    Boolean ok=write.query("SELECT status='running' AND lease_owner=? AND fencing_token=? AND lease_until>clock_timestamp() FROM dvp.jobs WHERE id=? FOR UPDATE",
        rs->rs.next()&&rs.getBoolean(1),lease.owner(),lease.token(),lease.jobId());if(!Boolean.TRUE.equals(ok))throw new JobRepository.LeaseLostException();
  }
  private void complete(JobRepository.Lease lease) {write.update("UPDATE dvp.jobs SET checkpoint=jsonb_build_object('exhausted',true) WHERE id=?",lease.jobId());}
  private void provision(JobRepository.Lease lease,Index x) {
    writes.executeWithoutResult(tx->{fence(lease);
      if(!"provisioning".equals(x.state())) throw new IllegalStateException("Index configuration is not provisioning");
      write.execute("""
        CREATE TABLE dvp.%s (unit_id uuid PRIMARY KEY,document_id uuid NOT NULL,source_text_id uuid NOT NULL,
          source_revision bigint NOT NULL,indexation_id uuid NOT NULL CHECK (indexation_id='%s'::uuid),
          embedding vector(%d) NOT NULL,input_hash char(64) NOT NULL,
          origin varchar(10) NOT NULL CHECK (origin IN ('generated','imported','reused')),origin_job_id uuid,
          created_at timestamptz NOT NULL DEFAULT now(),
          FOREIGN KEY (unit_id,document_id,source_text_id,source_revision,indexation_id)
          REFERENCES dvp.vector_units(id,document_id,source_text_id,source_revision,indexation_id) ON DELETE CASCADE)
        """.formatted(x.table(),x.id(),x.dims()));
      write.execute("CREATE INDEX "+x.table()+"_doc ON dvp."+x.table()+"(document_id,unit_id)");
      write.execute("CREATE INDEX "+x.table()+"_hash ON dvp."+x.table()+"(input_hash)");
      fence(lease);write.update("UPDATE dvp.indexations SET state='ready' WHERE id=?",x.id());complete(lease);
    });
  }
  private boolean tableValid(Index x,Connection connection) {
    return Boolean.TRUE.equals(maintenanceJdbc(connection).queryForObject("""
       SELECT EXISTS(SELECT 1 FROM pg_class t JOIN pg_namespace n ON n.oid=t.relnamespace
         JOIN pg_attribute a ON a.attrelid=t.oid AND a.attname='embedding' AND NOT a.attisdropped
         WHERE n.nspname='dvp' AND t.relname=? AND t.relkind='r' AND format_type(a.atttypid,a.atttypmod)=?
         AND NOT EXISTS (SELECT 1 FROM (VALUES
           ('unit_id','uuid',true),('document_id','uuid',true),('source_text_id','uuid',true),
           ('source_revision','bigint',true),('indexation_id','uuid',true),('embedding',?,true),
           ('input_hash','character(64)',true),('origin','character varying(10)',true),
           ('origin_job_id','uuid',false),('created_at','timestamp with time zone',true)
         ) expected(name,datatype,required) WHERE NOT EXISTS
           (SELECT 1 FROM pg_attribute col WHERE col.attrelid=t.oid AND col.attname=expected.name
             AND NOT col.attisdropped AND format_type(col.atttypid,col.atttypmod)=expected.datatype
             AND (NOT expected.required OR col.attnotnull)))
         AND EXISTS(SELECT 1 FROM pg_constraint fk WHERE fk.conrelid=t.oid AND fk.contype='f'
           AND fk.confrelid='dvp.vector_units'::regclass AND fk.convalidated
           AND fk.confdeltype='c'
           AND (SELECT array_agg(a.attname::text ORDER BY k.n) FROM unnest(fk.conkey) WITH ORDINALITY k(attnum,n)
             JOIN pg_attribute a ON a.attrelid=fk.conrelid AND a.attnum=k.attnum)=ARRAY['unit_id','document_id','source_text_id','source_revision','indexation_id']
           AND (SELECT array_agg(a.attname::text ORDER BY k.n) FROM unnest(fk.confkey) WITH ORDINALITY k(attnum,n)
             JOIN pg_attribute a ON a.attrelid=fk.confrelid AND a.attnum=k.attnum)=ARRAY['id','document_id','source_text_id','source_revision','indexation_id'])
         AND EXISTS(SELECT 1 FROM pg_constraint pk WHERE pk.conrelid=t.oid AND pk.contype='p'
           AND pg_get_constraintdef(pk.oid)='PRIMARY KEY (unit_id)')
         AND EXISTS(SELECT 1 FROM pg_constraint ck WHERE ck.conrelid=t.oid AND ck.contype='c'
           AND ck.convalidated AND pg_get_expr(ck.conbin,ck.conrelid)=?))
       """,Boolean.class,x.table(),"vector("+x.dims()+")","vector("+x.dims()+")","(indexation_id = '"+x.id()+"'::uuid)"));
  }
  private String annStatus(Index x,Connection connection) {
    var rows=maintenanceJdbc(connection).query("""
      SELECT i.indisvalid AND i.indisready AND i.indislive AND i.indpred IS NULL AND i.indnatts=1 AND am.amname='hnsw' AND opc.opcname=?
        AND ((?='hnsw_vector' AND pg_get_indexdef(i.indexrelid,1,true)='embedding') OR (?='hnsw_halfvec' AND pg_get_indexdef(i.indexrelid,1,true)=?))
      FROM pg_index i JOIN pg_class c ON c.oid=i.indexrelid JOIN pg_am am ON am.oid=c.relam
      JOIN pg_opclass opc ON opc.oid=i.indclass[0]
      JOIN pg_class t ON t.oid=i.indrelid JOIN pg_namespace n ON n.oid=t.relnamespace
      WHERE n.nspname='dvp' AND t.relname=? AND c.relname=?
      """,(rs,n)->rs.getBoolean(1),operator(x.metric(),x.strategy()),x.strategy(),x.strategy(),
        "(embedding::halfvec("+x.dims()+"))",x.table(),x.table()+"_ann");
    return rows.isEmpty()?"pending":rows.getFirst()?"ready":"failed";
  }
  private void reconcile(JobRepository.Lease lease,Index x,Connection connection) throws SQLException {
    boolean valid=tableValid(x,connection);boolean halfCompatible=true;
    if(valid && x.strategy().equals("hnsw_halfvec")) {
      try { halfCompatible=halfCompatible(x,connection); } catch(SQLException invalid) { halfCompatible=false; }
    }
    String ann=x.strategy().equals("none")?"disabled":valid && halfCompatible?annStatus(x,connection):"failed";
    writes.executeWithoutResult(tx->{fence(lease);
      write.update("UPDATE dvp.indexations SET state=?,ann_state=? WHERE id=?",valid?"ready":"failed",ann,x.id());complete(lease);});
  }
  private void build(JobRepository.Lease lease,Index x,Connection connection) throws SQLException {
    if(!"ready".equals(x.state()) || !java.util.Set.of("hnsw_vector","hnsw_halfvec").contains(x.strategy()) || !tableValid(x,connection)) throw new IllegalStateException("ANN build unavailable");
    String status=annStatus(x,connection);
    if(status.equals("failed")) throw new IllegalStateException("Invalid ANN index requires operator repair");
    if(x.strategy().equals("hnsw_halfvec") && !halfCompatible(x,connection)) throw new IllegalStateException("Incompatible halfvec values");
    writes.executeWithoutResult(tx->{fence(lease);write.update("UPDATE dvp.indexations SET ann_state=? WHERE id=?",status.equals("ready")?"ready":"building",x.id());});
    if(!status.equals("ready")) {
      String operator=operator(x.metric(),x.strategy());
      String expression="embedding";
      if(x.strategy().equals("hnsw_halfvec")) {
        expression="(embedding::halfvec("+x.dims()+"))";
      }
      try(var sql=connection.createStatement()) {sql.execute("CREATE INDEX CONCURRENTLY "+x.table()+"_ann ON dvp."+x.table()+" USING hnsw ("+expression+" "+operator+")");}
    }
    if(!connectionValid(connection) || !annStatus(x,connection).equals("ready")) throw new IllegalStateException("ANN index is invalid");
    writes.executeWithoutResult(tx->{fence(lease);if(!connectionValid(connection)) throw new IllegalStateException("ANN lock connection unavailable");
      write.update("UPDATE dvp.indexations SET ann_state='ready' WHERE id=?",x.id());complete(lease);});
  }
  private JdbcTemplate maintenanceJdbc(Connection connection) {
    return new JdbcTemplate(new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection,true));
  }
  private boolean halfCompatible(Index x,Connection connection) throws SQLException {
    try(var sql=connection.createStatement();var rows=sql.executeQuery("SELECT COALESCE(bool_and(l2_norm(embedding::halfvec("+x.dims()+"))"+(x.metric().equals("cosine")?">0":">=0")+"),true) FROM dvp."+x.table())) {
      rows.next();return rows.getBoolean(1);
    }
  }
  private static String operator(String metric,String strategy) {
    String prefix=strategy.equals("hnsw_halfvec")?"halfvec_":"vector_";
    return prefix+switch(metric) {case "cosine"->"cosine_ops";case "l2"->"l2_ops";case "inner_product"->"ip_ops";default->throw new IllegalStateException("Invalid metric");};
  }
  private static boolean connectionValid(Connection c) {try{return !c.isClosed() && c.isValid(1);}catch(SQLException e){return false;}}
}
