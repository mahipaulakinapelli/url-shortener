package com.urlshortener.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class Base62EncoderTest {

  private final Base62Encoder encoder = new Base62Encoder();

  // ---- encode correctness ----

  @Test
  void encode_id1_shouldProduceSixCharCode() {
    assertThat(encoder.encode(1L)).isEqualTo("000001");
  }

  @Test
  void encode_id62_isFirstTwoDigitCode() {
    // 62 = 1×62¹ + 0×62⁰  →  "10" in base-62  →  padded to "000010"
    assertThat(encoder.encode(62L)).isEqualTo("000010");
  }

  @Test
  void encode_exactlyBase5_hasSixChars() {
    // 62^5 = 916,132,832  →  "100000"  (no padding needed)
    assertThat(encoder.encode(916_132_832L)).isEqualTo("100000");
  }

  @Test
  void encode_largeId_producesNoLeadingZeros() {
    String code = encoder.encode(Long.MAX_VALUE / 2);
    assertThat(code).doesNotStartWith("0");
  }

  @Test
  void encode_outputContainsOnlyBase62Characters() {
    assertThat(encoder.encode(System.currentTimeMillis())).matches("[0-9a-zA-Z]+");
  }

  @Test
  void encode_differentIds_produceDifferentCodes() {
    assertThat(encoder.encode(1L)).isNotEqualTo(encoder.encode(2L));
    assertThat(encoder.encode(61L)).isNotEqualTo(encoder.encode(62L));
  }

  @Test
  void encode_zero_shouldThrow() {
    assertThatThrownBy(() -> encoder.encode(0L)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void encode_negativeId_shouldThrow() {
    assertThatThrownBy(() -> encoder.encode(-1L)).isInstanceOf(IllegalArgumentException.class);
  }

  // ---- round-trip symmetry ----

  @ParameterizedTest
  @ValueSource(longs = {1L, 61L, 62L, 3844L, 999_999L, 123_456_789L, Long.MAX_VALUE / 2})
  void encode_thenDecode_returnsOriginalId(long id) {
    assertThat(encoder.decode(encoder.encode(id))).isEqualTo(id);
  }

  // ---- decode correctness ----

  @Test
  void decode_knownValue_isCorrect() {
    // "000001" should decode back to 1
    assertThat(encoder.decode("000001")).isEqualTo(1L);
    assertThat(encoder.decode("000010")).isEqualTo(62L);
    assertThat(encoder.decode("100000")).isEqualTo(916_132_832L);
  }

  @Test
  void decode_invalidCharacter_shouldThrow() {
    assertThatThrownBy(() -> encoder.decode("abc!23"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Invalid Base62 character");
  }

  @Test
  void decode_nullInput_shouldThrow() {
    assertThatThrownBy(() -> encoder.decode(null)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void decode_emptyInput_shouldThrow() {
    assertThatThrownBy(() -> encoder.decode("")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void decode_overflowingString_shouldThrow() {
    // 13 'Z' chars far exceeds Long.MAX_VALUE in base-62
    assertThatThrownBy(() -> encoder.decode("ZZZZZZZZZZZZZ"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("overflow");
  }
}
