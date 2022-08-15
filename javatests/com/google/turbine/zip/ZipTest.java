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

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableMap;
import com.google.common.hash.Hashing;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** {@link Zip}Test */
@RunWith(JUnit4.class)
public class ZipTest {

  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Test
  public void testEntries() throws IOException {
    testEntries(1000);
  }

  @Test
  public void zip64_testEntries() throws IOException {
    testEntries(70000);
  }

  @Test
  public void compression() throws IOException {
    Path path = temporaryFolder.newFile("test.jar").toPath();
    try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(path))) {
      for (int i = 0; i < 2; i++) {
        String name = "entry" + i;
        byte[] bytes = name.getBytes(UTF_8);
        jos.putNextEntry(new JarEntry(name));
        jos.write(bytes);
      }
    }
    assertThat(actual(path)).isEqualTo(expected(path));
  }

  private void testEntries(int entries) throws IOException {
    Path path = temporaryFolder.newFile("test.jar").toPath();
    try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(path))) {
      for (int i = 0; i < entries; i++) {
        String name = "entry" + i;
        byte[] bytes = name.getBytes(UTF_8);
        createEntry(jos, name, bytes);
      }
    }
    assertThat(actual(path)).isEqualTo(expected(path));
  }

  private static void createEntry(ZipOutputStream jos, String name, byte[] bytes)
      throws IOException {
    JarEntry je = new JarEntry(name);
    je.setMethod(JarEntry.STORED);
    je.setSize(bytes.length);
    je.setCrc(Hashing.crc32().hashBytes(bytes).padToLong());
    jos.putNextEntry(je);
    jos.write(bytes);
  }

  private static Map<String, Long> actual(Path path) throws IOException {
    Map<String, Long> result = new LinkedHashMap<>();
    for (Zip.Entry e : new Zip.ZipIterable(path)) {
      result.put(e.name(), Hashing.goodFastHash(128).hashBytes(e.data()).padToLong());
    }
    return result;
  }

  private static Map<String, Long> expected(Path path) throws IOException {
    Map<String, Long> result = new LinkedHashMap<>();
    try (JarFile jf = new JarFile(path.toFile())) {
      Enumeration<JarEntry> entries = jf.entries();
      while (entries.hasMoreElements()) {
        JarEntry je = entries.nextElement();
        result.put(
            je.getName(),
            Hashing.goodFastHash(128).hashBytes(jf.getInputStream(je).readAllBytes()).padToLong());
      }
    }
    return result;
  }

  @Test
  public void attributes() throws Exception {
    Path path = temporaryFolder.newFile("test.jar").toPath();
    Files.delete(path);
    try (FileSystem fs =
        FileSystems.newFileSystem(
            URI.create("jar:" + path.toUri()), ImmutableMap.of("create", "true"))) {
      for (int i = 0; i < 3; i++) {
        String name = "entry" + i;
        byte[] bytes = name.getBytes(UTF_8);
        Path entry = fs.getPath(name);
        Files.write(entry, bytes);
        Files.setLastModifiedTime(entry, FileTime.fromMillis(0));
      }
    }
    assertThat(actual(path)).isEqualTo(expected(path));
  }

  @Test
  public void zipFileCommentsAreSupported() throws Exception {
    Path path = temporaryFolder.newFile("test.jar").toPath();
    Files.delete(path);
    try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(path))) {
      createEntry(zos, "hello", "world".getBytes(UTF_8));
      zos.setComment("this is a comment");
    }
    assertThat(actual(path)).isEqualTo(expected(path));
  }

  @Test
  public void malformedComment() throws Exception {
    Path path = temporaryFolder.newFile("test.jar").toPath();
    Files.delete(path);

    try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(path))) {
      createEntry(zos, "hello", "world".getBytes(UTF_8));
      zos.setComment("this is a comment");
    }
    Files.writeString(path, "trailing garbage", StandardOpenOption.APPEND);

    ZipException e = assertThrows(ZipException.class, () -> actual(path));
    assertThat(e).hasMessageThat().isEqualTo("zip file comment length was 33, expected 17");
  }

  // Create a zip64 archive with an extensible data sector
  @Test
  public void zip64extension() throws IOException {

    ByteBuffer buf = ByteBuffer.allocate(1000);
    buf.order(ByteOrder.LITTLE_ENDIAN);

    // The jar has a single entry named 'hello', with the value 'world'
    byte[] name = "hello".getBytes(UTF_8);
    byte[] value = "world".getBytes(UTF_8);
    int crc = Hashing.crc32().hashBytes(value).asInt();

    int localHeaderPosition = buf.position();

    // local file header signature     4 bytes  (0x04034b50)
    buf.putInt((int) ZipFile.LOCSIG);
    // version needed to extract       2 bytes
    buf.putShort((short) 0);
    // general purpose bit flag        2 bytes
    buf.putShort((short) 0);
    // compression method              2 bytes
    buf.putShort((short) 0);
    // last mod file time              2 bytes
    buf.putShort((short) 0);
    // last mod file date              2 bytes
    buf.putShort((short) 0);
    // crc-32                          4 bytes
    buf.putInt(crc);
    // compressed size                 4 bytes
    buf.putInt(value.length);
    // uncompressed size               4 bytes
    buf.putInt(value.length);
    // file name length                2 bytes
    buf.putShort((short) name.length);
    // extra field length              2 bytes
    buf.putShort((short) 0);
    // file name (variable size)
    buf.put(name);
    // extra field (variable size)
    // file data
    buf.put(value);

    int centralDirectoryPosition = buf.position();

    // central file header signature   4 bytes  (0x02014b50)
    buf.putInt((int) ZipFile.CENSIG);
    // version made by                 2 bytes
    buf.putShort((short) 0);
    // version needed to extract       2 bytes
    buf.putShort((short) 0);
    // general purpose bit flag        2 bytes
    buf.putShort((short) 0);
    // compression method              2 bytes
    buf.putShort((short) 0);
    // last mod file time              2 bytes
    buf.putShort((short) 0);
    // last mod file date              2 bytes
    buf.putShort((short) 0);
    // crc-32                          4 bytes
    buf.putInt(crc);
    // compressed size                 4 bytes
    buf.putInt(value.length);
    // uncompressed size               4 bytes
    buf.putInt(value.length);
    // file name length                2 bytes
    buf.putShort((short) name.length);
    // extra field length              2 bytes
    buf.putShort((short) 0);
    // file comment length             2 bytes
    buf.putShort((short) 0);
    // disk number start               2 bytes
    buf.putShort((short) 0);
    // internal file attributes        2 bytes
    buf.putShort((short) 0);
    // external file attributes        4 bytes
    buf.putInt(0);
    // relative offset of local header 4 bytes
    buf.putInt(localHeaderPosition);
    // file name (variable size)
    buf.put(name);

    int centralDirectorySize = buf.position() - centralDirectoryPosition;
    int zip64eocdPosition = buf.position();

    // zip64 end of central dir
    // signature                       4 bytes  (0x06064b50)
    buf.putInt(Zip.ZIP64_ENDSIG);
    // size of zip64 end of central
    // directory record                8 bytes
    buf.putLong(Zip.ZIP64_ENDSIZ + 5);
    // version made by                 2 bytes
    buf.putShort((short) 0);
    // version needed to extract       2 bytes
    buf.putShort((short) 0);
    // number of this disk             4 bytes
    buf.putInt(0);
    // number of the disk with the
    // start of the central directory  4 bytes
    buf.putInt(0);
    // total number of entries in the
    // central directory on this disk  8 bytes
    buf.putLong(1);
    // total number of entries in the
    // central directory               8 bytes
    buf.putLong(1);
    // size of the central directory   8 bytes
    buf.putLong(centralDirectorySize);
    // offset of start of central
    // directory with respect to
    // offset of start of central
    // the starting disk number        8 bytes
    buf.putLong(centralDirectoryPosition);
    // zip64 extensible data sector    (variable size)
    buf.put((byte) 3);
    buf.putInt(42);

    // zip64 end of central dir locator
    // signature                       4 bytes  (0x07064b50)
    buf.putInt(Zip.ZIP64_LOCSIG);
    // number of the disk with the
    // start of the zip64 end of
    // central directory               4 bytes
    buf.putInt(0);
    // relative offset of the zip64
    // end of central directory record 8 bytes
    buf.putLong(zip64eocdPosition);
    // total number of disks           4 bytes
    buf.putInt(0);

    // end of central dir signature    4 bytes  (0x06054b50)
    buf.putInt((int) ZipFile.ENDSIG);
    // number of this disk             2 bytes
    buf.putShort((short) 0);
    // number of the disk with the
    // start of the central directory  2 bytes
    buf.putShort((short) 0);
    // total number of entries in the
    // central directory on this disk  2 bytes
    buf.putShort((short) 1);
    // total number of entries in
    // the central directory           2 bytes
    buf.putShort((short) Zip.ZIP64_MAGICCOUNT);
    // size of the central directory   4 bytes
    buf.putInt(centralDirectorySize);
    // offset of start of central
    // directory with respect to
    // the starting disk number        4 bytes
    buf.putInt(centralDirectoryPosition);
    //         .ZIP file comment length        2 bytes
    buf.putShort((short) 0);
    //         .ZIP file comment       (variable size)

    byte[] bytes = new byte[buf.position()];
    buf.rewind();
    buf.get(bytes);
    Path path = temporaryFolder.newFile("test.jar").toPath();
    Files.write(path, bytes);
    assertThat(actual(path)).isEqualTo(expected(path));
  }
}
