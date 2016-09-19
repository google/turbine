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

import com.google.common.collect.ImmutableList;
import com.google.turbine.model.Const.ShortValue;
import com.google.turbine.model.Const.StringValue;
import com.google.turbine.model.Const.Value;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** A constant pool builder, used when writing class files. */
public class ConstantPool {

  /** The next available constant pool entry. */
  short nextEntry = 1;

  /** A map from CONSTANT_Utf8_info entries to their offset. */
  Map<String, Short> utf8Pool = new HashMap<>();

  /** A map from CONSTANT_Class_info entries to their offset. */
  Map<Short, Short> classInfoPool = new HashMap<>();

  private final List<Entry> constants = new ArrayList<>();

  /** The ordered list of constant pool entries. */
  public ImmutableList<Entry> constants() {
    return ImmutableList.copyOf(constants);
  }

  /** The number of constant pool entries the given kind takes up. */
  private static short width(Kind kind) {
    switch (kind) {
      case CLASS_INFO:
      case STRING:
      case INTEGER:
      case UTF8:
      case FLOAT:
        return 1;
      case LONG:
      case DOUBLE:
        // "In retrospect, making 8-byte constants take two constant pool entries
        // was a poor choice." -- JVMS 4.4.5
        return 2;
      default:
        throw new AssertionError(kind);
    }
  }

  /** A constant pool entry. */
  static class Entry {
    private final Kind kind;
    private final Value value;

    Entry(Kind kind, Value value) {
      this.kind = kind;
      this.value = value;
    }

    /** The entry kind. */
    public Kind kind() {
      return kind;
    }

    /** The entry's value. */
    public Value value() {
      return value;
    }
  }

  /** Adds a CONSTANT_Class_info entry to the pool. */
  short classInfo(String value) {
    short utf8 = utf8(value);
    if (classInfoPool.containsKey(utf8)) {
      return classInfoPool.get(utf8);
    }
    return insert(new Entry(Kind.CLASS_INFO, new ShortValue(utf8)));
  }

  /** Adds a CONSTANT_Utf8_info entry to the pool. */
  short utf8(String value) {
    if (utf8Pool.containsKey(value)) {
      return utf8Pool.get(value);
    }
    return insert(new Entry(Kind.UTF8, new StringValue(value)));
  }

  private short insert(Entry key) {
    short entry = nextEntry;
    constants.add(key);
    nextEntry += width(key.kind());
    return entry;
  }

  /** Constant pool entry kinds. */
  enum Kind {
    CLASS_INFO(7),
    STRING(8),
    INTEGER(3),
    DOUBLE(6),
    FLOAT(4),
    LONG(5),
    UTF8(1);

    private final short tag;

    Kind(int tag) {
      this.tag = (short) tag;
    }

    /** The JVMS Table 4.4-A tag. */
    public short tag() {
      return tag;
    }
  }
}
