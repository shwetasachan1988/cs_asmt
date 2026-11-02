package com.sa.agentic.shortener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sa.agentic.shortener.service.CodeGenerator;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CodeGeneratorTest {

    private final CodeGenerator generator = new CodeGenerator(
            new ShortenerProperties("http://x", 7, 2048, 60, false,
                    new ShortenerProperties.RateLimit(true, 5, 60), new ShortenerProperties.Analytics(10, 1)));

    @Test
    void codesHaveFixedLengthAndAlphabet() {
        for (long id = 100_000; id < 100_100; id++) {
            assertThat(generator.fromId(id)).matches("[0-9A-Za-z]{7}");
        }
    }

    @Test
    void codesAreUniqueAcrossLargeRange() {
        Set<String> seen = new HashSet<>();
        for (long id = 100_000; id < 300_000; id++) {
            assertThat(seen.add(generator.fromId(id))).as("collision at id %d", id).isTrue();
        }
    }

    @Test
    void consecutiveIdsAreNotSequentialCodes() {
        assertThat(generator.fromId(100_000)).isNotEqualTo(generator.fromId(100_001));
        assertThat(generator.fromId(100_001).substring(0, 3)).isNotEqualTo(generator.fromId(100_000).substring(0, 3));
    }

    @Test
    void rejectsIdsOutsideCodeSpace() {
        assertThatThrownBy(() -> generator.fromId(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> generator.fromId(Long.MAX_VALUE)).isInstanceOf(IllegalArgumentException.class);
    }
}
