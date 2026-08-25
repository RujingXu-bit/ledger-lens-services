package com.ledgerlens.transaction.domain;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Reads only. Writes go through {@link DailyPriceBatchWriter}, which upserts.
 */
public interface DailyPriceRepository extends JpaRepository<DailyPrice, DailyPriceId> {

    List<DailyPrice> findBySymbolInAndPriceDateBetweenOrderByPriceDateAscSymbolAsc(
            Collection<String> symbols, LocalDate from, LocalDate to);
}
