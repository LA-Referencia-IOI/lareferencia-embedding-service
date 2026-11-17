package org.lareferencia.embedding.v2.indexation;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Creates an indexation's owned table inside the caller's write transaction. */
public final class IndexTableProvisioner {
  private IndexTableProvisioner() { }
  public static void create(JdbcTemplate jdbc,UUID index,String table,int dimensions) {
    if(!TransactionSynchronizationManager.isActualTransactionActive() || TransactionSynchronizationManager.isCurrentTransactionReadOnly())
      throw new IllegalStateException("An active write transaction is required");
    if(index==null || !IndexationService.table(index).equals(table) || dimensions<1 || dimensions>16000)
      throw new IllegalArgumentException("Invalid index table configuration");
    jdbc.execute("""
        CREATE TABLE dvp.%s (unit_id uuid PRIMARY KEY,document_id uuid NOT NULL,
          indexation_id uuid NOT NULL CHECK (indexation_id='%s'::uuid),
          embedding vector(%d) NOT NULL,input_hash char(64),
          origin varchar(10) NOT NULL CHECK (origin IN ('generated','imported','reused')),origin_job_id uuid,
          created_at timestamptz NOT NULL DEFAULT now(),
          CONSTRAINT %s_unit_scope_fk FOREIGN KEY (unit_id,document_id,indexation_id)
          REFERENCES dvp.vector_units(id,document_id,indexation_id) ON DELETE CASCADE)
        """.formatted(table,index,dimensions,table));
    jdbc.execute("CREATE INDEX "+table+"_doc ON dvp."+table+"(document_id,unit_id)");
    jdbc.execute("CREATE INDEX "+table+"_hash ON dvp."+table+"(input_hash)");
  }
}
