package com.ledgerlens.transaction.service;

import com.ledgerlens.transaction.domain.DailyPrice;
import com.ledgerlens.transaction.domain.DailyPriceBatchWriter;
import com.ledgerlens.transaction.domain.DailyPriceRepository;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Locale;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class PriceService {

    private final DailyPriceRepository repository;
    private final DailyPriceBatchWriter batchWriter;

    public PriceService(DailyPriceRepository repository, DailyPriceBatchWriter batchWriter) {
        this.repository = repository;
        this.batchWriter = batchWriter;
    }

    /**
     * Idempotent by construction: loading the same day twice leaves the same
     * single row, and a restated price overwrites the old one.
     */
    @Transactional
    public int upsertAll(List<DailyPriceBatchWriter.PriceRow> prices) {
        return batchWriter.upsertAll(prices);
    }

    public List<DailyPrice> find(Collection<String> symbols, LocalDate from, LocalDate to) {
        Set<String> normalised = symbols.stream()
                .map(s -> s.trim().toUpperCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
        if (normalised.isEmpty()) {
            return List.of();
        }
        return repository.findBySymbolInAndPriceDateBetweenOrderByPriceDateAscSymbolAsc(normalised, from, to);
    }
}
