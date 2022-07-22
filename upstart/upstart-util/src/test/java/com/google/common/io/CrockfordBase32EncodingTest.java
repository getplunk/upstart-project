package com.google.common.io;

import org.junit.jupiter.api.Test;
import upstart.util.strings.CrockfordBase32;

import static com.google.common.truth.Truth.assertThat;

class CrockfordBase32EncodingTest {
  @Test
  void roundTrips() {
    BaseEncoding subject = CrockfordBase32.lowerCaseInstance();
    String encoded = subject.lowerCase().upperCase().encode("hello".getBytes());
    assertThat(encoded).isEqualTo("D1JPRV3F");
    String decoded = new String(subject.lowerCase().decode(encoded));
    assertThat(decoded).isEqualTo("hello");
    String confused = "D1JPRu3F"; // crockford32 is case-insensitive, and treats 'u' as 'V'
    assertThat(new String(subject.decode(confused))).isEqualTo("hello");
  }
}