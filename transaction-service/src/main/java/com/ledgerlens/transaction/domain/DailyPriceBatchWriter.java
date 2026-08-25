package com.ledgerlens.transaction.domain;

import java.math.BigDecimal;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Bulk upsert of closing prices, deliberately written in SQL rather than JPA.
 *
 * <p>{@code repository.saveAll(...)} on an entity with an assigned identifier
 * issues a SELECT per row to decide between INSERT and UPDATE, so loading a
 * year of prices for ten symbols would be about 2,500 round trips before a
 * single write. Postgres has {@code INSERT ... ON CONFLICT DO UPDATE}, which
 * does the same job atomically in one batched statement and with no race
 * between the check and the write.
 *
 * <p>This is the case for knowing when to step outside the ORM: JPA is for the
 * object graph, set-based work belongs in SQL. Keeping it behind this class
 * means the rest of the service never sees the difference.
 */
@Repository
public class DailyPriceBatchWriter {

    private static final String UPSERT = """
            insert into daily_prices (symbol, price_date, close_price, currency, updated_at)
            values (?, ?, ?, ?, ?)
            on conflict (symbol, price_date) do update
                set close_price = excluded.close_price,
                    currency    = excluded.currency,
                    updated_at  = excluded.updated_at
            """;

    private final JdbcTemplate jdbcTemplate;

    public DailyPriceBatchWriter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * @return the number of rows written, inserted and updated together —
     *         Postgres reports both as affected, and the caller does not care
     *         which a given price was.
     */
    public int upsertAll(List<PriceRow> prices) {
        OffsetDateTime now = OffsetDateTime.ofInstant(Instant.now(), ZoneOffset.UTC);
        // batchUpdate returns one int[] per batch, hence the array of arrays.
        int[][] affected = jdbcTemplate.batchUpdate(UPSERT, prices, prices.size(), (ps, price) -> {
            ps.setString(1, price.symbol().toUpperCase(Locale.ROOT));
            ps.setObject(2, price.priceDate(), Types.DATE);
            ps.setBigDecimal(3, price.closePrice());
            ps.setString(4, price.currency().toUpperCase(Locale.ROOT));
            ps.setObject(5, now, Types.TIMESTAMP_WITH_TIMEZONE);
        });
        return Arrays.stream(affected).mapToInt(batch -> batch.length).sum();
    }

    /** The minimum a caller must supply to write a price. */
    public record PriceRow(String symbol, LocalDate priceDate, BigDecimal closePrice, String currency) {
    }
}
