package com.edumy.commerce.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.random.RandomGenerator;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

/** ADR-011 Q5 / R2: order code and transaction reference formats, including the bounds of the reference range. */
class CommerceCodeGeneratorTest {

    static final Instant NOW = Instant.parse("2026-11-15T23:59:59Z");

    /** Always answers the lowest (or highest) value of the asked range. */
    static RandomGenerator edge(boolean highest) {
        return new RandomGenerator() {
            @Override
            public long nextLong() {
                throw new UnsupportedOperationException();
            }

            @Override
            public int nextInt(int bound) {
                return highest ? bound - 1 : 0;
            }

            @Override
            public long nextLong(long origin, long bound) {
                return highest ? bound - 1 : origin;
            }
        };
    }

    @Test
    void reference_lowestAndHighest_are15DigitsWithoutLeadingZero_below2pow53() {
        String lowest = new CommerceCodeGenerator(edge(false)).newTransactionRef();
        String highest = new CommerceCodeGenerator(edge(true)).newTransactionRef();

        assertThat(lowest).isEqualTo("100000000000000");
        assertThat(highest).isEqualTo("999999999999999");
        assertThat(Long.parseLong(highest)).isLessThan(1L << 53); // exact as a JSON / JavaScript number
    }

    @RepeatedTest(20)
    void reference_random_matchesTheFormat_andRoundTripsThroughANumber() {
        String reference = new CommerceCodeGenerator().newTransactionRef();

        assertThat(reference).matches("[1-9]\\d{14}");
        assertThat(Long.toString(Long.parseLong(reference))).isEqualTo(reference);
    }

    @Test
    void orderCode_utcDayAnd8Base32Characters_fitsTheColumn() {
        assertThat(new CommerceCodeGenerator(edge(false)).newOrderCode(NOW)).isEqualTo("ORD-20261115-00000000");
        assertThat(new CommerceCodeGenerator(edge(true)).newOrderCode(NOW)).isEqualTo("ORD-20261115-ZZZZZZZZ");
        String random = new CommerceCodeGenerator().newOrderCode(NOW);
        assertThat(random).matches("ORD-20261115-[0-9A-HJKMNP-TV-Z]{8}").hasSizeLessThanOrEqualTo(30);
    }
}
