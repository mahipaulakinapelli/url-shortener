package com.urlshortener.util;

import java.util.Arrays;

import org.springframework.stereotype.Component;

/**
 * Converts positive {@code long} IDs to/from URL-safe Base62 strings.
 *
 * <p>Alphabet (62 chars):
 *
 * <pre>
 *   index  0– 9 : '0'–'9'
 *   index 10–35 : 'a'–'z'
 *   index 36–61 : 'A'–'Z'
 * </pre>
 *
 * <p>Short-code examples (MIN_LENGTH = 6):
 *
 * <pre>
 *   encode(1)          → "000001"
 *   encode(62)         → "000010"   (62¹  in base-62)
 *   encode(238_328)    → "001000"   (62³  in base-62)
 *   encode(56_800_235_584) → "100000" (62⁵ – no padding needed)
 * </pre>
 *
 * <p>Decode is the exact inverse: {@code decode(encode(n)) == n} for all n ≥ 1.
 */
@Component
public class Base62Encoder {

  // ---- alphabet ----

  private static final char[] CHARS =
      "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();

  private static final int BASE = CHARS.length; // 62
  private static final int MIN_LENGTH = 6; // shortest code we ever emit

  /**
   * O(1) reverse-lookup table: ASCII code → alphabet index, or -1 if invalid. Avoids the O(n)
   * String.indexOf() on every character during decode.
   */
  private static final int[] DECODE_TABLE;

  static {
    DECODE_TABLE = new int[128];
    Arrays.fill(DECODE_TABLE, -1);
    for (int i = 0; i < CHARS.length; i++) {
      DECODE_TABLE[CHARS[i]] = i;
    }
  }

  // ---- public API ----

  /**
   * Encodes a positive {@code long} ID into a Base62 string.
   *
   * <p>Algorithm:
   *
   * <ol>
   *   <li>Repeatedly take {@code num % 62} to get the least-significant digit, map it to the
   *       corresponding character, then divide by 62.
   *   <li>The digits are collected in least-significant-first order, so reverse at the end.
   *   <li>Left-pad with {@code '0'} (index 0) until {@link #MIN_LENGTH} is reached.
   * </ol>
   *
   * @param id a positive database sequence ID (≥ 1)
   * @return a Base62 string of at least {@value #MIN_LENGTH} characters
   * @throws IllegalArgumentException if {@code id} is not positive
   */
  public String encode(long id) {
    if (id <= 0) {
      throw new IllegalArgumentException("encode() requires a positive ID, got: " + id);
    }

    // Step 1 & 2 — extract digits (LSB first), then reverse
    char[] buf = new char[13]; // ceil(log62(Long.MAX_VALUE)) = 11, +2 slack
    int pos = 0;
    long num = id;

    while (num > 0) {
      buf[pos++] = CHARS[(int) (num % BASE)];
      num /= BASE;
    }

    reverse(buf, 0, pos - 1);

    // Step 3 — left-pad to MIN_LENGTH
    int codeLen = Math.max(pos, MIN_LENGTH);
    char[] code = new char[codeLen];
    int padCount = codeLen - pos;

    Arrays.fill(code, 0, padCount, CHARS[0]); // fill leading '0's
    System.arraycopy(buf, 0, code, padCount, pos);

    return new String(code);
  }

  /**
   * Decodes a Base62 string back to its original {@code long} ID.
   *
   * <p>Algorithm: treat the string as a base-62 number and evaluate it left-to-right using Horner's
   * method:
   *
   * <pre>
   *   result = 0
   *   for each char c:
   *     result = result × 62 + index(c)
   * </pre>
   *
   * @param code a non-empty string containing only Base62 characters
   * @return the original ID
   * @throws IllegalArgumentException if {@code code} contains an invalid character or would
   *     overflow {@code long}
   */
  public long decode(String code) {
    if (code == null || code.isEmpty()) {
      throw new IllegalArgumentException("code must not be null or empty");
    }

    long result = 0;

    for (int i = 0; i < code.length(); i++) {
      char c = code.charAt(i);

      // O(1) lookup instead of String.indexOf()
      int idx = (c < DECODE_TABLE.length) ? DECODE_TABLE[c] : -1;
      if (idx == -1) {
        throw new IllegalArgumentException("Invalid Base62 character '" + c + "' at index " + i);
      }

      // Overflow guard — Long.MAX_VALUE = 9_223_372_036_854_775_807
      if (result > (Long.MAX_VALUE - idx) / BASE) {
        throw new IllegalArgumentException("Decoded value overflows long: " + code);
      }

      result = result * BASE + idx;
    }

    return result;
  }

  // ---- private helpers ----

  private static void reverse(char[] arr, int lo, int hi) {
    while (lo < hi) {
      char tmp = arr[lo];
      arr[lo++] = arr[hi];
      arr[hi--] = tmp;
    }
  }
}
