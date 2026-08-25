package com.ledgerlens.transaction.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerlens.transaction.PostgresTestcontainerConfiguration;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The upsert is the whole point of this class, so it is what gets tested:
 * loading the same trading day twice must not create a second row, and a
 * restated price must win.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PostgresTestcontainerConfiguration.class, DailyPriceBatchWriter.class})
class DailyPriceRepositoryTest {

    private static final LocalDate DAY = LocalDate.of(2026, 1, 15);

    @Autowired
    private DailyPriceRepository repository;

    @Autowired
    private DailyPriceBatchWriter batchWriter;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void loadingTheSameDayTwiceRestatesThePriceInsteadOfDuplicatingIt() {
        batchWriter.upsertAll(List.of(price("IWDA", DAY, "98.75")));
        batchWriter.upsertAll(List.of(price("IWDA", DAY, "99.10")));

        Integer rows = jdbcTemplate.queryForObject(
                "select count(*) from daily_prices where symbol = 'IWDA' and price_date = ?",
                Integer.class, DAY);
        assertThat(rows).isEqualTo(1);

        List<DailyPrice> found = repository.findBySymbolInAndPriceDateBetweenOrderByPriceDateAscSymbolAsc(
                List.of("IWDA"), DAY, DAY);
        assertThat(found).singleElement()
                .satisfies(p -> assertThat(p.getClosePrice()).isEqualByComparingTo("99.10"));
    }

    @Test
    void symbolsAreNormalisedOnTheWayInSoLookupsAreCaseInsensitiveByConstruction() {
        batchWriter.upsertAll(List.of(price("iwda", DAY, "98.75")));

        List<DailyPrice> found = repository.findBySymbolInAndPriceDateBetweenOrderByPriceDateAscSymbolAsc(
                List.of("IWDA"), DAY, DAY);
        assertThat(found).hasSize(1);
    }

    @Test
    void rangeQueryReturnsSeveralSymbolsInDateThenSymbolOrder() {
        batchWriter.upsertAll(List.of(
                price("VWCE", DAY.plusDays(1), "112.00"),
                price("IWDA", DAY, "98.75"),
                price("VWCE", DAY, "111.50"),
                price("IWDA", DAY.plusDays(1), "99.00"),
                price("IWDA", DAY.plusDays(9), "101.00")));

        List<DailyPrice> found = repository.findBySymbolInAndPriceDateBetweenOrderByPriceDateAscSymbolAsc(
                List.of("IWDA", "VWCE"), DAY, DAY.plusDays(1));

        assertThat(found).extracting(p -> p.getSymbol() + "@" + p.getPriceDate())
                .containsExactly(
                        "IWDA@2026-01-15", "VWCE@2026-01-15",
                        "IWDA@2026-01-16", "VWCE@2026-01-16");
    }

    private static DailyPriceBatchWriter.PriceRow price(String symbol, LocalDate date, String close) {
        return new DailyPriceBatchWriter.PriceRow(symbol, date, new BigDecimal(close), "EUR");
    }
}
