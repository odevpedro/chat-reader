package com.example.chatreader.sync.infrastructure;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface ChangeLogJpaRepository extends JpaRepository<ChangeLogEntity, Long> {

    List<ChangeLogEntity> findByVersionGreaterThanOrderByVersionAsc(Long version, Pageable pageable);

    @Query("SELECT COALESCE(MAX(c.version), 0) FROM ChangeLogEntity c")
    Long maxVersion();

    @Query("SELECT COALESCE(MIN(c.version), 0) FROM ChangeLogEntity c")
    Long minVersion();

    @Modifying
    @Query("DELETE FROM ChangeLogEntity c WHERE c.createdAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") Instant cutoff);

    @Modifying
    @Query("DELETE FROM ChangeLogEntity c WHERE c.version <= :version")
    int deleteUpToVersion(@Param("version") long version);

    /**
     * Ultima versao consumida pela sequence do {@code BIGSERIAL}. Sobrevive a
     * {@code DELETE}/{@code TRUNCATE} da tabela, entao continua sendo a marca dagua
     * do log mesmo depois que a retencao limpa todas as linhas.
     */
    @Query(value = "SELECT COALESCE(pg_sequence_last_value(:sequence), 0)", nativeQuery = true)
    Long sequenceLastValue(@Param("sequence") String sequence);

    @Query(value = "SELECT nextval(:sequence)", nativeQuery = true)
    Long nextSequenceValue(@Param("sequence") String sequence);
}
