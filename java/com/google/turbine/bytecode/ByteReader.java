/*
 * Copyright 2016 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.turbine.bytecode;

import static com.google.common.base.Verify.verify;
import static java.nio.charset.StandardCharsets.ISO_8859_1;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteStreams;
import java.io.ByteArrayInputStream;

/** A {@link ByteArrayDataInput} wrapper that tracks the current byte array index. */
public class ByteReader {

  private final byte[] bytes;
  private final IndexedByteArrayInputStream indexed;
  private final ByteArrayDataInput input;

  public ByteReader(byte[] bytes, int pos) {
    this.bytes = bytes;
    this.indexed = new IndexedByteArrayInputStream(bytes, pos, bytes.length);
    this.input = ByteStreams.newDataInput(indexed);
  }

  public ByteArrayDataInput seek(int i) {
    return ByteStreams.newDataInput(bytes, i);
  }

  /** Reads an unsigned 8-bit integer at the given position, without changing {@link #pos}. */
  int u1At(int pos) {
    return bytes[pos] & 0xff;
  }

  /**
   * Reads an unsigned 16-bit integer in big-endian byte order at the given position, without
   * changing {@link #pos}.
   */
  int u2At(int pos) {
    return ((bytes[pos] & 0xff) << 8) | (bytes[pos + 1] & 0xff);
  }

  /**
   * Reads a length-prefixed modified UTF-8 string (as in {@link java.io.DataInput#readUTF}) at the
   * given position, without changing {@link #pos}.
   */
  String utf8At(int pos) {
    // Fast path for ASCII strings, which are encoded identically in modified UTF-8 and ISO-8859-1.
    // Bytes that are zero or have the high bit set fall back to the full modified UTF-8 decoder.
    int length = u2At(pos);
    int start = pos + 2;
    if (isAscii(start, start + length)) {
      return new String(bytes, start, length, ISO_8859_1);
    }
    return seek(pos).readUTF();
  }

  private boolean isAscii(int start, int end) {
    if (end > bytes.length) {
      return false;
    }
    for (int i = start; i < end; i++) {
      if (bytes[i] <= 0) {
        return false;
      }
    }
    return true;
  }

  /** The position in the input buffer. */
  public int pos() {
    return indexed.pos();
  }

  /** Reads an unsigned 8-bit integer. */
  public int u1() {
    return input.readUnsignedByte();
  }

  /** Reads an unsigned 16-bit integer in big-endian byte order. */
  public int u2() {
    return input.readUnsignedShort();
  }

  /** Reads an unsigned 32-bit integer in big-endian byte order. */
  public int u4() {
    return input.readInt();
  }

  /** Skips n bytes of input. */
  public void skip(int n) {
    int skipped = input.skipBytes(n);
    // this is only used with {@link ByteArrayInputStream}.
    verify(skipped == n, "wanted %s, read %s", n, skipped);
  }

  /** {@link #pos} is protected in {@link java.io.ByteArrayInputStream}. */
  static class IndexedByteArrayInputStream extends ByteArrayInputStream {

    IndexedByteArrayInputStream(byte[] buf, int offset, int length) {
      super(buf, offset, length);
    }

    int pos() {
      return pos;
    }
  }
}
