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

package com.google.turbine.testing;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.util.Printer;
import org.objectweb.asm.util.Textifier;
import org.objectweb.asm.util.TraceClassVisitor;

/**
 * ASM-based test utilities, in their own class mostly to avoid namespace issues with e.g. {@link
 * com.google.turbine.bytecode.ClassReader}.
 */
public final class AsmUtils {

  private static final int MAX_ASM_VERSION = getMaxAsmVersion();

  private static int getMaxAsmVersion() {
    int max = 0;
    for (Field field : Opcodes.class.getFields()) {
      if (field.getName().matches("V\\d+.*")) {
        try {
          max = Math.max(max, field.getInt(null) & 0xFFFF);
        } catch (ReflectiveOperationException _) {
          // Ignore inaccessible or unreadable fields.
        }
      }
    }
    return max;
  }

  private static byte[] maybeLowerVersion(byte[] bytes) {
    if (bytes == null
        || bytes.length < 8
        || bytes[0] != (byte) 0xCA
        || bytes[1] != (byte) 0xFE
        || bytes[2] != (byte) 0xBA
        || bytes[3] != (byte) 0xBE) {
      return bytes;
    }
    int major = ((bytes[6] & 0xFF) << 8) | (bytes[7] & 0xFF);
    if (MAX_ASM_VERSION > 0 && major > MAX_ASM_VERSION) {
      bytes = bytes.clone();
      bytes[6] = (byte) (MAX_ASM_VERSION >> 8);
      bytes[7] = (byte) MAX_ASM_VERSION;
    }
    return bytes;
  }

  public static String textify(byte[] bytes, boolean skipDebug) {
    bytes = maybeLowerVersion(bytes);
    Printer textifier = new Textifier();
    StringWriter sw = new StringWriter();
    new ClassReader(bytes)
        .accept(
            new TraceClassVisitor(null, textifier, new PrintWriter(sw, true)),
            ClassReader.SKIP_FRAMES
                | ClassReader.SKIP_CODE
                | (skipDebug ? ClassReader.SKIP_DEBUG : 0));
    return sw.toString();
  }

  private AsmUtils() {}
}
