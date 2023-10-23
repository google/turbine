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

import static java.util.stream.Collectors.joining;

import com.google.common.base.CharMatcher;
import com.google.common.base.Splitter;
import java.io.PrintWriter;
import java.io.StringWriter;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.util.Printer;
import org.objectweb.asm.util.Textifier;
import org.objectweb.asm.util.TraceClassVisitor;

/**
 * ASM-based test utilities, in their own class mostly to avoid namespace issues with e.g. {@link
 * com.google.turbine.bytecode.ClassReader}.
 */
public final class AsmUtils {
  public static String textify(byte[] bytes, boolean skipDebug) {
    Printer textifier = new Textifier();
    StringWriter sw = new StringWriter();
    new ClassReader(bytes)
        .accept(
            new TraceClassVisitor(null, textifier, new PrintWriter(sw, true)),
            ClassReader.SKIP_FRAMES
                | ClassReader.SKIP_CODE
                | (skipDebug ? ClassReader.SKIP_DEBUG : 0));
    // TODO(cushon): Remove this after next ASM update
    // See https://gitlab.ow2.org/asm/asm/-/commit/af4ee811fde0b14bd7db84aa944a1b3733c37289
    return Splitter.onPattern("\\R")
        .splitToStream(sw.toString())
        .map(CharMatcher.is(' ')::trimTrailingFrom)
        .collect(joining("\n"));
  }

  private AsmUtils() {}
}
