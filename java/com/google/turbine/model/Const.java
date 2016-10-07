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

package com.google.turbine.model;

/**
 * Compile-time constant expressions, including literals of primitive or String type, class
 * literals, enum constants, and annotation literals.
 */
public abstract class Const {

  /** Subtypes of {@link Const} for primitive and String literals. */
  public abstract static class Value extends Const {
    public abstract TurbineConstantTypeKind constantTypeKind();

    public IntValue asInteger() {
      throw new AssertionError(constantTypeKind());
    }

    public FloatValue asFloat() {
      throw new AssertionError(constantTypeKind());
    }

    public DoubleValue asDouble() {
      throw new AssertionError(constantTypeKind());
    }

    public LongValue asLong() {
      throw new AssertionError(constantTypeKind());
    }

    public BooleanValue asBoolean() {
      throw new AssertionError(constantTypeKind());
    }

    public StringValue asString() {
      throw new AssertionError(constantTypeKind());
    }

    public CharValue asChar() {
      throw new AssertionError(constantTypeKind());
    }

    public ShortValue asShort() {
      throw new AssertionError(constantTypeKind());
    }

    public ByteValue asByte() {
      throw new AssertionError(constantTypeKind());
    }
  }

  /** A boolean literal value. */
  public static class BooleanValue extends Value {
    private final boolean value;

    public BooleanValue(boolean value) {
      this.value = value;
    }

    @Override
    public String toString() {
      return String.valueOf(value);
    }

    @Override
    public TurbineConstantTypeKind constantTypeKind() {
      return TurbineConstantTypeKind.BOOLEAN;
    }

    public boolean value() {
      return value;
    }

    @Override
    public BooleanValue asBoolean() {
      return this;
    }
  }

  /** An int literal value. */
  public static class IntValue extends Value {

    private final int value;

    public IntValue(int value) {
      this.value = value;
    }

    @Override
    public String toString() {
      return String.valueOf(value);
    }

    @Override
    public TurbineConstantTypeKind constantTypeKind() {
      return TurbineConstantTypeKind.INT;
    }

    public int value() {
      return value;
    }

    @Override
    public IntValue asInteger() {
      return this;
    }

    @Override
    public ByteValue asByte() {
      return new ByteValue((byte) value);
    }

    @Override
    public LongValue asLong() {
      return new LongValue(value);
    }

    @Override
    public StringValue asString() {
      return new StringValue(String.valueOf(value));
    }

    @Override
    public CharValue asChar() {
      return new CharValue((char) value);
    }

    @Override
    public ShortValue asShort() {
      return new ShortValue((short) value);
    }
  }

  /** A long literal value. */
  public static class LongValue extends Value {
    private final long value;

    public LongValue(long value) {
      this.value = value;
    }

    @Override
    public String toString() {
      return value + "L";
    }

    @Override
    public TurbineConstantTypeKind constantTypeKind() {
      return TurbineConstantTypeKind.LONG;
    }

    public long value() {
      return value;
    }

    @Override
    public LongValue asLong() {
      return this;
    }

    @Override
    public ShortValue asShort() {
      return new ShortValue((short) value);
    }
  }

  /** A char literal value. */
  public static class CharValue extends Value {
    private final char value;

    public CharValue(char value) {
      this.value = value;
    }

    @Override
    public String toString() {
      return "'" + value + "'";
    }

    @Override
    public TurbineConstantTypeKind constantTypeKind() {
      return TurbineConstantTypeKind.CHAR;
    }

    public char value() {
      return value;
    }

    @Override
    public CharValue asChar() {
      return this;
    }

    @Override
    public IntValue asInteger() {
      return new IntValue(value);
    }
  }

  /** A float literal value. */
  public static class FloatValue extends Value {
    private final float value;

    public FloatValue(float value) {
      this.value = value;
    }

    @Override
    public String toString() {
      return value + "f";
    }

    @Override
    public TurbineConstantTypeKind constantTypeKind() {
      return TurbineConstantTypeKind.FLOAT;
    }

    public float value() {
      return value;
    }

    @Override
    public FloatValue asFloat() {
      return this;
    }
  }

  /** A double literal value. */
  public static class DoubleValue extends Value {
    private final double value;

    public DoubleValue(double value) {
      this.value = value;
    }

    @Override
    public String toString() {
      return String.valueOf(value);
    }

    @Override
    public TurbineConstantTypeKind constantTypeKind() {
      return TurbineConstantTypeKind.DOUBLE;
    }

    public double value() {
      return value;
    }

    @Override
    public DoubleValue asDouble() {
      return this;
    }
  }

  /** A String literal value. */
  public static class StringValue extends Value {
    private final String value;

    public StringValue(String value) {
      this.value = value;
    }

    @Override
    public String toString() {
      return String.format("\"%s\"", value);
    }

    @Override
    public TurbineConstantTypeKind constantTypeKind() {
      return TurbineConstantTypeKind.STRING;
    }

    public String value() {
      return value;
    }

    @Override
    public StringValue asString() {
      return this;
    }
  }

  /** A short literal value. */
  public static class ShortValue extends Value {
    private final short value;

    public ShortValue(short value) {
      this.value = value;
    }

    @Override
    public String toString() {
      return String.valueOf(value);
    }

    @Override
    public TurbineConstantTypeKind constantTypeKind() {
      return TurbineConstantTypeKind.SHORT;
    }

    public short value() {
      return value;
    }

    @Override
    public ShortValue asShort() {
      return this;
    }

    @Override
    public IntValue asInteger() {
      return new IntValue(value);
    }
  }

  /** A short literal value. */
  public static class ByteValue extends Value {

    private final byte value;

    public ByteValue(byte value) {
      this.value = value;
    }

    @Override
    public TurbineConstantTypeKind constantTypeKind() {
      return TurbineConstantTypeKind.BYTE;
    }

    @Override
    public ByteValue asByte() {
      return this;
    }

    @Override
    public LongValue asLong() {
      return new LongValue(value);
    }

    @Override
    public IntValue asInteger() {
      return new IntValue(value);
    }

    public byte value() {
      return value;
    }

    @Override
    public String toString() {
      return String.valueOf(value);
    }
  }
}
