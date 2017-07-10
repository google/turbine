/*
 * Copyright 2017 Google Inc. All Rights Reserved.
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

package com.google.turbine.zip;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.io.ByteStreams;
import com.google.common.primitives.UnsignedInts;
import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.IOError;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Iterator;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;
import java.util.zip.ZipException;

/**
 * A fast, minimal, and somewhat garbage zip implementation. This exists because graal <a
 * href="http://mail.openjdk.java.net/pipermail/graal-dev/2017-August/005039.html">doesn't yet
 * support</a> {@link java.util.zip.ZipFile}, and {@link java.util.zip.ZipInputStream} doesn't have
 * the performance we'd like (*). If you're reading this, you almost certainly want {@code ZipFile}
 * instead.
 *
 * <p>If you're reading this because you're fixing a bug, sorry.
 *
 * <p>(*) A benchmark that iterates over all of the entries in rt.jar takes 6.97ms to run with this
 * implementation and 202.99ms with ZipInputStream. (Those are averages across 100 reps, and I
 * verified they're doing the same work.) This is likely largely due to ZipInputStream reading the
 * entire file from the beginning to scan the local headers, whereas this implementation (and
 * ZipFile) only read the central directory. Iterating over the entries (but not reading the data)
 * is an interesting benchmark because we typically only read ~10% of the compile-time classpath, so
 * most time is spent just scanning entry names. And rt.jar is an interesting test case because
 * every compilation has to read it, and it dominates the size of the classpath for small
 * compilations.
 *
 * <p>Implementation notes:
 *
 * <ul>
 *   <li>Leading garbage may be supported, since the archive is read backwards using the central
 *       directory. Archives modified with zip -A may not be supported. Trailing garbage is not
 *       supported.
 *   <li>UTF-8 is the only supported encoding.
 *   <li>STORED and DEFLATE are the only supported compression methods.
 *   <li>Jar file comments and zip64 extensible data sectors are not supported.
 *   <li>Zip files larger than Integer.MAX_VALUE bytes are not supported.
 *   <li>The only supported ZIP64 field is ENDTOT. This implementation assumes that the ZIP64 end
 *       header is present only if ENDTOT in EOCD header is 0xFFFF.
 * </ul>
 */
public class Zip {

  static final int CENSIG = 0x02014b50;
  static final int ENDSIG = 0x06054b50;
  static final int ZIP64_ENDSIG = 0x06064b50;

  static final int LOCHDR = 30; // LOC header size
  static final int CENHDR = 46; // CEN header size
  static final int ENDHDR = 22; // END header size
  static final int ZIP64_LOCHDR = 20; // ZIP64 end locator header size
  static final int ZIP64_ENDHDR = 56; // ZIP64 end header size

  static final int ENDTOT = 10; // total number of entries
  static final int ENDSIZ = 12; // central directory size in bytes

  static final int CENHOW = 10; // compression method
  static final int CENLEN = 24; // uncompressed size
  static final int CENSIZ = 20; // compressed size
  static final int CENNAM = 28; // filename length
  static final int CENEXT = 30; // extra field length
  static final int CENCOM = 32; // comment length
  static final int CENOFF = 42; // LOC header offset

  static final int ZIP64_ENDSIZ = 40; // central directory size in bytes

  static final int ZIP64_MAGICCOUNT = 0xFFFF;

  /** Iterates over a zip archive. */
  static class ZipIterator implements Iterator<Entry> {

    /** A reader for the backing storage. */
    private final FileChannel chan;

    private int cdindex = 0;
    private final MappedByteBuffer cd;
    private final CharsetDecoder decoder = UTF_8.newDecoder();

    ZipIterator(FileChannel chan, MappedByteBuffer cd) {
      this.chan = chan;
      this.cd = cd;
    }

    @Override
    public boolean hasNext() {
      return cdindex < cd.limit();
    }

    /* Returns a {@link Entry} for the current CEN entry. */
    @Override
    public Entry next() {
      // TODO(cushon): technically we're supposed to throw NSEE
      checkSignature(cd, cdindex, 2, 1, "CENSIG");
      int nameLength = cd.getChar(cdindex + CENNAM);
      int extLength = cd.getChar(cdindex + CENEXT);
      int commentLength = cd.getChar(cdindex + CENCOM);
      Entry entry = new Entry(chan, string(cd, cdindex + CENHDR, nameLength), cd, cdindex);
      cdindex += CENHDR + nameLength + extLength + commentLength;
      return entry;
    }

    public String string(ByteBuffer buf, int offset, int length) {
      buf = buf.duplicate();
      buf.position(offset);
      buf.limit(offset + length);
      decoder.reset();
      try {
        return decoder.decode(buf).toString();
      } catch (CharacterCodingException e) {
        throw new IOError(e);
      }
    }
  }

  /** Provides an {@link Iterable} of {@link Entry} over a zip archive. */
  public static class ZipIterable implements Iterable<Entry>, Closeable {

    private final FileChannel chan;
    private final MappedByteBuffer cd;

    public ZipIterable(Path path) throws IOException {
      this.chan = FileChannel.open(path, StandardOpenOption.READ);
      // Locate the EOCD, assuming that the archive does not contain trailing garbage or a zip file
      // comment.
      long size = chan.size();
      if (size < ENDHDR) {
        throw new ZipException("invalid zip archive");
      }
      long eocdOffset = size - ENDHDR;
      MappedByteBuffer eocd = chan.map(MapMode.READ_ONLY, eocdOffset, ENDHDR);
      eocd.order(ByteOrder.LITTLE_ENDIAN);
      checkSignature(eocd, 0, 6, 5, "CENSIG");
      int totalEntries = eocd.getChar(ENDTOT);
      long cdsize = UnsignedInts.toLong(eocd.getInt(ENDSIZ));
      // If the number of entries is 0xffff, check if the archive has a zip64 EOCD locator.
      if (totalEntries == ZIP64_MAGICCOUNT) {
        // Assume the zip64 EOCD has the usual size; we don't support zip64 extensible data sectors.
        long zip64eocdOffset = size - ENDHDR - ZIP64_LOCHDR - ZIP64_ENDHDR;
        MappedByteBuffer zip64eocd = chan.map(MapMode.READ_ONLY, zip64eocdOffset, ZIP64_ENDHDR);
        zip64eocd.order(ByteOrder.LITTLE_ENDIAN);
        // Note that zip reading is necessarily best-effort, since an archive could contain 0xFFFF
        // entries and the last entry's data could contain a ZIP64_ENDSIG. Some implementations
        // read the full EOCD records and compare them.
        if (zip64eocd.getInt(0) == ZIP64_ENDSIG) {
          cdsize = zip64eocdOffset - zip64eocd.getLong(ZIP64_ENDSIZ);
          eocdOffset = zip64eocdOffset;
        }
      }
      this.cd = chan.map(MapMode.READ_ONLY, eocdOffset - cdsize, cdsize);
      cd.order(ByteOrder.LITTLE_ENDIAN);
    }

    @Override
    public Iterator<Entry> iterator() {
      return new ZipIterator(chan, cd);
    }

    @Override
    public void close() throws IOException {
      chan.close();
    }
  }

  /** An entry in a zip archive. */
  public static class Entry {

    private final FileChannel chan;
    private final String name;
    private final ByteBuffer cd;
    private final int cdindex;

    public Entry(FileChannel chan, String name, ByteBuffer cd, int cdindex) {
      this.chan = chan;
      this.name = name;
      this.cd = cd;
      this.cdindex = cdindex;
    }

    /** The entry name. */
    public String name() {
      return name;
    }

    /** The entry data. */
    public byte[] data() {
      // Read the offset and variable lengths from the central directory and then blindly trust that
      // they match the local header so we can map in the data section in one shot.
      // Are we being excessively brave?
      long offset = UnsignedInts.toLong(cd.getInt(cdindex + CENOFF));
      int nameLength = cd.getChar(cdindex + CENNAM);
      int extLength = cd.getChar(cdindex + CENEXT);
      long fileOffset = offset + LOCHDR + nameLength + extLength;
      int compression = cd.getChar(cdindex + CENHOW);
      switch (compression) {
        case 0x8:
          return getBytes(
              fileOffset, UnsignedInts.toLong(cd.getInt(cdindex + CENSIZ)), /*deflate=*/ true);
        case 0x0:
          return getBytes(
              fileOffset, UnsignedInts.toLong(cd.getInt(cdindex + CENLEN)), /*deflate=*/ false);
        default:
          throw new AssertionError(
              String.format("unsupported compression mode: 0x%x", compression));
      }
    }

    private byte[] getBytes(long fileOffset, long size, boolean deflate) {
      if (size > Integer.MAX_VALUE) {
        throw new IllegalArgumentException("unsupported zip entry size: " + size);
      }
      try {
        byte[] bytes = new byte[(int) size];
        MappedByteBuffer fc = chan.map(MapMode.READ_ONLY, fileOffset, size);
        fc.get(bytes);
        if (deflate) {
          bytes =
              ByteStreams.toByteArray(
                  new InflaterInputStream(
                      new ByteArrayInputStream(bytes), new Inflater(/*nowrap=*/ true)));
        }
        return bytes;
      } catch (IOException e) {
        throw new IOError(e);
      }
    }
  }

  static void checkSignature(MappedByteBuffer buf, int index, int i, int j, String name) {
    if (!((buf.get(index) == 'P')
        && (buf.get(index + 1) == 'K')
        && (buf.get(index + 2) == j)
        && (buf.get(index + 3) == i))) {
      throw new AssertionError(
          String.format(
              "bad %s (expected: 0x%02x%02x%02x%02x, actual: 0x%08x)",
              name, i, j, (int) 'K', (int) 'P', buf.getInt(index)));
    }
  }
}
