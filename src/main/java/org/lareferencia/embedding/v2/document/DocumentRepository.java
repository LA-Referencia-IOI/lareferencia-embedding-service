package org.lareferencia.embedding.v2.document;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(name="app.dvp.enabled",havingValue="true")
public class DocumentRepository {
  public record DocumentRow(UUID id,UUID collectionId,long sequenceId,String externalId,JsonNode metadata,
      long revision,Instant createdAt,Instant updatedAt) {
    @Override public String toString() { return "DocumentRow[redacted]"; }
  }
  public record TextRow(UUID id,String textId,String kind,String language,String content,String contentHash,
      long revision,Instant updatedAt) {
    @Override public String toString() { return "TextRow[redacted]"; }
    SourceTextInput asInput() { return new SourceTextInput(textId,kind,language,content); }
    SourceTextResource resource() { return new SourceTextResource(textId,kind,language,content,revision,contentHash,updatedAt); }
  }
  private final JdbcTemplate write;
  private final JdbcTemplate read;
  private final ObjectMapper mapper;
  private static final String COLUMNS="id,collection_id,sequence_id,external_id,metadata,revision,created_at,updated_at";
  private final RowMapper<DocumentRow> documentRow=(rs,n)->documentRow(rs);
  private static final RowMapper<TextRow> TEXT_ROW=(rs,n)->new TextRow(rs.getObject("id",UUID.class),
      rs.getString("text_key"),rs.getString("kind"),rs.getString("language"),rs.getString("content"),
      rs.getString("content_hash"),rs.getLong("revision"),rs.getTimestamp("updated_at").toInstant());

  public DocumentRepository(@Qualifier("jdbcTemplate") JdbcTemplate write,@Qualifier("readJdbc") JdbcTemplate read,
      ObjectMapper mapper) {
    this.write=write; this.read=read;
    this.mapper=mapper.copy().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
  }
  private DocumentRow documentRow(ResultSet rs) throws SQLException {
    try {
      return new DocumentRow(rs.getObject("id",UUID.class),rs.getObject("collection_id",UUID.class),
          rs.getLong("sequence_id"),rs.getString("external_id"),mapper.readTree(rs.getString("metadata")),
          rs.getLong("revision"),rs.getTimestamp("created_at").toInstant(),rs.getTimestamp("updated_at").toInstant());
    } catch(java.io.IOException e) { throw new SQLException("Invalid stored metadata",e); }
  }
  public boolean collectionExists(UUID id,boolean forWrite) {
    return Boolean.TRUE.equals((forWrite?write:read).queryForObject(
        "SELECT EXISTS(SELECT 1 FROM dvp.collections WHERE id=?)",Boolean.class,id));
  }
  public boolean insertIfAbsent(UUID id,UUID collectionId,String externalId) {
    return !write.query("""
        INSERT INTO dvp.documents(id,collection_id,external_id) VALUES (?,?,?)
        ON CONFLICT (collection_id,external_id) DO NOTHING RETURNING id
        """,(rs,n)->rs.getObject(1,UUID.class),id,collectionId,externalId).isEmpty();
  }
  public Optional<DocumentRow> lockByExternal(UUID collectionId,String externalId) {
    return write.query("SELECT "+COLUMNS+" FROM dvp.documents WHERE collection_id=? AND external_id=? FOR UPDATE",
        documentRow,collectionId,externalId).stream().findFirst();
  }
  public Optional<DocumentRow> lockById(UUID collectionId,UUID id) {
    return write.query("SELECT "+COLUMNS+" FROM dvp.documents WHERE collection_id=? AND id=? FOR UPDATE",
        documentRow,collectionId,id).stream().findFirst();
  }
  public Optional<DocumentRow> findByExternal(UUID collectionId,String externalId) {
    return read.query("SELECT "+COLUMNS+" FROM dvp.documents WHERE collection_id=? AND external_id=?",
        documentRow,collectionId,externalId).stream().findFirst();
  }
  public Optional<DocumentRow> findById(UUID collectionId,UUID id,boolean forWrite) {
    return (forWrite?write:read).query("SELECT "+COLUMNS+" FROM dvp.documents WHERE collection_id=? AND id=?",
        documentRow,collectionId,id).stream().findFirst();
  }
  public List<TextRow> texts(UUID documentId,boolean forWrite) {
    return (forWrite?write:read).query("""
        SELECT id,text_key,kind,language,content,content_hash,revision,updated_at
        FROM dvp.source_texts WHERE document_id=? ORDER BY text_key COLLATE "C"
        """,TEXT_ROW,documentId);
  }
  public List<DocumentRow> list(UUID collectionId,Long after,int count,boolean includeMetadata) {
    String columns=includeMetadata?COLUMNS:
        "id,collection_id,sequence_id,external_id,'{}'::jsonb AS metadata,revision,created_at,updated_at";
    if(after==null) return read.query("SELECT "+columns+" FROM dvp.documents WHERE collection_id=? ORDER BY sequence_id LIMIT ?",
        documentRow,collectionId,count);
    return read.query("SELECT "+columns+" FROM dvp.documents WHERE collection_id=? AND sequence_id>? ORDER BY sequence_id LIMIT ?",
        documentRow,collectionId,after,count);
  }
  public boolean metadataDiffers(UUID documentId,String metadata) {
    return Boolean.TRUE.equals(write.queryForObject("SELECT metadata IS DISTINCT FROM ?::jsonb FROM dvp.documents WHERE id=?",
        Boolean.class,metadata,documentId));
  }
  public void insertText(UUID documentId,SourceTextInput text,String hash) {
    write.update("""
        INSERT INTO dvp.source_texts(id,document_id,text_key,kind,language,content,content_hash)
        VALUES (?,?,?,?,?,?,?)
        """,UUID.randomUUID(),documentId,text.textId(),text.kind(),text.language(),text.content(),hash);
  }
  public void updateText(TextRow original,SourceTextInput text,String hash) {
    // The parent document lock is already held; delete all derived units before changing the source.
    write.update("DELETE FROM dvp.vector_units WHERE source_text_id=?",original.id());
    write.update("""
        UPDATE dvp.source_texts SET kind=?,language=?,content=?,content_hash=?,revision=revision+1,updated_at=clock_timestamp()
        WHERE id=?
        """,text.kind(),text.language(),text.content(),hash,original.id());
  }
  public void deleteText(UUID id) { write.update("DELETE FROM dvp.source_texts WHERE id=?",id); }
  public void deleteDocument(UUID id) { write.update("DELETE FROM dvp.documents WHERE id=?",id); }
  public void updateDocument(UUID id,String metadata,boolean increment) {
    if(increment) write.update("""
        UPDATE dvp.documents SET metadata=?::jsonb,revision=revision+1,updated_at=clock_timestamp() WHERE id=?
        """,metadata,id);
    else write.update("UPDATE dvp.documents SET metadata=?::jsonb WHERE id=?",metadata,id);
  }
}
