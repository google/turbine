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

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.escape.SourceCodeEscapers;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.AnnotationValueVisitor;

/**
 * Compile-time constant expressions, including literals of primitive or String type, class
 * literals, enum constants, and annotation literals.
 */
public abstract class Const {

  @Override
  public abstract int hashCode();

  @Override
  public abstract boolean equals(Object obj);

  @Override
  public abstract String toString();

  /** The constant kind. */
  public abstract Kind kind();

  /** A constant kind. */
  public enum Kind {
    ARRAY,
    PRIMITIVE,
    CLASS_LITERAL,
    ENUM_CONSTANT,
    ANNOTATION
  }

  /** An invalid constant cast. */
  public static class ConstCastError extends RuntimeException {
    public ConstCastError(TurbineConstantTypeKind type, TurbineConstantTypeKind target) {
      super(String.format("%s cannot be converted to %s", type, target));
    }
  }

  /** Subtypes of {@link Const} for primitive and String literals. */
  public abstract static class Value extends Const implements AnnotationValue {
    public abstract TurbineConstantTypeKind constantTypeKind();

    @Override
    public Kind kind() {
      return Kind.PRIMITIVE;
    }

    public IntValue asInteger() {
      throw new ConstCastError(constantTypeKind(), TurbineConstantTypeKind.INT);
    }

    public FloatValue asFloat() {
      throw new ConstCastError(constantTypeKind(), TurbineConstantTypeKind.FLOAT);
    }

    public DoubleValue asDouble() {
      throw new ConstCastError(constantTypeKind(), TurbineConstantTypeKind.DOUBLE);
    }

    public LongValue asLong() {
      throw new ConstCastError(constantTypeKind(), TurbineConstantTypeKind.LONG);
    }

    public BooleanValue asBoolean() {
      throw new ConstCastError(constantTypeKind(), TurbineConstantTypeKind.BOOLEAN);
    }

    public StringValue asString() {
      throw new ConstCastError(constantTypeKind(), TurbineConstantTypeKind.STRING);
    }

    public CharValue asChar() {
      throw new ConstCastError(constantTypeKind(), TurbineConstantTypeKind.CHAR);
    }

    public ShortValue asShort() {
      throw new ConstCastError(constantTypeKind(), TurbineConstantTypeKind.SHORT);
    }

    public ByteValue asByte() {
      throw new ConstCastError(constantTypeKind(), TurbineConstantTypeKind.BYTE);
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
    public <R, P> R accept(AnnotationValueVisitor<R, P> v, P p) {
      return v.visitBoolean(value, p);
    }

    @Override
    public TurbineConstantTypeKind constantTypeKind() {
      return TurbineConstantTypeKind.BOOLEAN;
    }

    public boolean value() {
      return value;
    }

    @Override
    public Object getValue() {
      return value;
    }

    @Override
    public BooleanValue asBoolean() {
      return this;
    }

    @Override
    public StringValue asString() {
      return new StringValue(String.valueOf(value));
    }

    @Override
    public int hashCode() {
      return Boolean.hashCode(value);
    }

    @Override
    public boolean equals(Object obj) {
      return obj instanceof BooleanValue && value == ((BooleanValue) obj).value();
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
    public <R, P> R accept(AnnotationValueVisitor<R, P> v, P p) {
      return v.visitInt(value, p);
    }

    @Override
    public TurbineConstantTypeKind constantTypeKind() {
      return TurbineConstantTypeKind.INT;
    }

    public int value() {
      return value;
    }

    @Override
    public Object getValue() {
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
      return new LongValue((long) value);
    }

    @Override
    public CharValue asChar() {
      return new CharValue((char) value);
    }

    @Override
    public ShortValue asShort() {
      return new ShortValue((short) value);
    }

    @Override
    public DoubleValue asDouble() {
      return new DoubleValue((double) value);
    }

    @Override
    public FloatValue asFloat() {
      return new FloatValue((float) value);
    }

    @Override
    public StringValue asString() {
      return new StringValue(String.valueOf(value));
    }

    @Override
    public int hashCode() {
      return Integer.hashCode(value);
    }

    @Override
    public boolean equals(Object obj) {
      return obj instanceof IntValue && value == ((IntValue) obj).value;
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
    public <R, P> R accept(AnnotationValueVisitor<R, P> v, P p) {
      return v.visitLong(value, p);
    }

    @Override
    public TurbineConstantTypeKind constantTypeKind() {
      return TurbineConstantTypeKind.LONG;
    }

    public long value() {
      return value;
    }

    @Override
    public Object getValue() {
      return value;
    }

    @Override
    public IntValue asInteger() {
      return new IntValue((int) value);
    }

    @Override
    public ByteValue asByte() {
      return new ByteValue((byte) value);
    }

    @Override
    public LongValue asLong() {
      return this;
    }

    @Override
    public CharValue asChar() {
      return new CharValue((char) value);
    }

    @Override
    public ShortValue asShort() {
      return new ShortValue((short) value);
    }

    @Override
    public DoubleValue asDouble() {
      return new DoubleValue((double) value);
    }

    @Override
    public FloatValue asFloat() {
      return new FloatValue((float) value);
    }

    @Override
    public StringValue asString() {
      return new StringValue(String.valueOf(value));
    }

    @Override
    public int hashCode() {
      return Long.hashCode(value);
    }

    @Override
    public boolean equals(Object obj) {
      return obj instanceof LongValue && value == ((LongValue) obj).value;
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
      return "'" + SourceCodeEscapers.javaCharEscaper().escape(String.valueOf(value)) + "'";
    }

    @Override
    public <R, P> R accept(AnnotationValueVisitor<R, P> v, P p) {
      return v.visitChar(value, p);
    }

    @Override
    public TurbineConstantTypeKind constantTypeKind() {
      return TurbineConstantTypeKind.CHAR;
    }

    public char value() {
      return value;
    }

    @Override
    public Object getValue() {
      return value;
    }

    @Override
    public IntValue asInteger() {
      return new IntValue((int) value);
    }

    @Override
    public ByteValue asByte() {
      return new ByteValue((byte) value);
    }

    @Override
    public LongValue asLong() {
      return new LongValue((long) value);
    }

    @Override
    public CharValue asChar() {
      return this;
    }

    @Override
    public ShortValue asShort() {
      return new ShortValue((short) value);
    }

    @Override
    public DoubleValue asDouble() {
      return new DoubleValue((double) value);
    }

    @Override
    public FloatValue asFloat() {
      return new FloatValue((float) value);
    }

    @Override
    public StringValue asString() {
      return new StringValue(String.valueOf(value));
    }

    @Override
    public int hashCode() {
      return Character.hashCode(value);
    }

    @Override
    public boolean equals(Object obj) {
      return obj instanceof CharValue && value == ((CharValue) obj).value;
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
    public <R, P> R accept(AnnotationValueVisitor<R, P> v, P p) {
      return v.visitFloat(value, p);
    }

    @Override
    public TurbineConstantTypeKind constantTypeKind() {
      return TurbineConstantTypeKind.FLOAT;
    }

    public float value() {
      return value;
    }

    @Override
    public Object getValue() {
      return value;
    }

    @Override
    public IntValue asInteger() {
      return new IntValue((int) value);
    }

    @Override
    public ByteValue asByte() {
      return new ByteValue((byte) value);
    }

    @Override
    public LongValue asLong() {
      return new LongValue((long) value);
    }

    @Override
    public CharValue asChar() {
      return new CharValue((char) value);
    }

    @Override
    public ShortValue asShort() {
      return new ShortValue((short) value);
    }

    @Override
    public DoubleValue asDouble() {
      return new DoubleValue((double) value);
    }

    @Override
    public FloatValue asFloat() {
      return this;
    }

    @Override
    public StringValue asString() {
      return new StringValue(String.valueOf(value));
    }

    @Override
    public int hashCode() {
      return Float.hashCode(value);
    }

    @Override
    public boolean equals(Object obj) {
      return obj instanceof FloatValue && value == ((FloatValue) obj).value;
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
    public <R, P> R accept(AnnotationValueVisitor<R, P> v, P p) {
      return v.visitDouble(value, p);
    }

    @Override
    public TurbineConstantTypeKind constantTypeKind() {
      return TurbineConstantTypeKind.DOUBLE;
    }

    public double value() {
      return value;
    }

    @Override
    public Object getValue() {
      return value;
    }

    @Override
    public IntValue asInteger() {
      return new IntValue((int) value);
    }

    @Override
    public ByteValue asByte() {
      return new ByteValue((byte) value);
    }

    @Override
    public LongValue asLong() {
      return new LongValue((long) value);
    }

    @Override
    public CharValue asChar() {
      return new CharValue((char) value);
    }

    @Override
    public ShortValue asShort() {
      return new ShortValue((short) value);
    }

    @Override
    public DoubleValue asDouble() {
      return this;
    }

    @Override
    public FloatValue asFloat() {
      return new FloatValue((float) value);
    }

    @Override
    public StringValue asString() {
      return new StringValue(String.valueOf(value));
    }

    @Override
    public int hashCode() {
      return Double.hashCode(value);
    }

    @Override
    public boolean equals(Object obj) {
      return obj instanceof DoubleValue && value == ((DoubleValue) obj).value;
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
      return '"' + SourceCodeEscapers.javaCharEscaper().escape(value) + '"';
    }

    @Override
    public <R, P> R accept(AnnotationValueVisitor<R, P> v, P p) {
      return v.visitString(value, p);
    }

    @Override
    public TurbineConstantTypeKind constantTypeKind() {
      return TurbineConstantTypeKind.STRING;
    }

    public String value() {
      return value;
    }

    @Override
    public Object getValue() {
      return value;
    }

    @Override
    public StringValue asString() {
      return this;
    }

    @Override
    public int hashCode() {
      return value.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
      return obj instanceof StringValue && value.equals(((StringValue) obj).value);
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
      return String.format("(short)%d", value);
    }

    @Override
    public <R, P> R accept(AnnotationValueVisitor<R, P> v, P p) {
      return v.visitShort(value, p);
    }

    @Override
    public TurbineConstantTypeKind constantTypeKind() {
      return TurbineConstantTypeKind.SHORT;
    }

    public short value() {
      return value;
    }

    @Override
    public Object getValue() {
      return value;
    }

    @Override
    public IntValue asInteger() {
      return new IntValue((int) value);
    }

    @Override
    public ByteValue asByte() {
      return new ByteValue((byte) value);
    }

    @Override
    public LongValue asLong() {
      return new LongValue((long) value);
    }

    @Override
    public CharValue asChar() {
      return new CharValue((char) value);
    }

    @Override
    public ShortValue asShort() {
      return this;
    }

    @Override
    public DoubleValue asDouble() {
      return new DoubleValue((double) value);
    }

    @Override
    public FloatValue asFloat() {
      return new FloatValue((float) value);
    }

    @Override
    public StringValue asString() {
      return new StringValue(String.valueOf(value));
    }

    @Override
    public int hashCode() {
      return Short.hashCode(value);
    }

    @Override
    public boolean equals(Object obj) {
      return obj instanceof ShortValue && value == ((ShortValue) obj).value;
    }
  }

  /** A byte literal value. */
  public static class ByteValue extends Value {

    private final byte value;

    public ByteValue(byte value) {
      this.value = value;
    }

    @Override
    public TurbineConstantTypeKind constantTypeKind() {
      return TurbineConstantTypeKind.BYTE;
    }

    public byte value() {
      return value;
    }

    @Override
    public Object getValue() {
      return value;
    }

    @Override
    public IntValue asInteger() {
      return new IntValue((int) value);
    }

    @Override
    public ByteValue asByte() {
      return this;
    }

    @Override
    public LongValue asLong() {
      return new LongValue((long) value);
    }

    @Override
    public CharValue asChar() {
      return new CharValue((char) value);
    }

    @Override
    public ShortValue asShort() {
      return new ShortValue((short) value);
    }

    @Override
    public DoubleValue asDouble() {
      return new DoubleValue((double) value);
    }

    @Override
    public FloatValue asFloat() {
      return new FloatValue((float) value);
    }

    @Override
    public StringValue asString() {
      return new StringValue(String.valueOf(value));
    }

    @Override
    public int hashCode() {
      return Byte.hashCode(value);
    }

    @Override
    public boolean equals(Object obj) {
      return obj instanceof ByteValue && value == ((ByteValue) obj).value;
    }

    @Override
    public String toString() {
      return String.format("(byte)0x%02x", value);
    }

    @Override
    public <R, P> R accept(AnnotationValueVisitor<R, P> v, P p) {
      return v.visitByte(value, p);
    }
  }

  /** A constant array literal (e.g. in an annotation). */
  public static class ArrayInitValue extends Const {

    private final ImmutableList<Const> elements;

    public ArrayInitValue(ImmutableList<Const> elements) {
      this.elements = elements;
    }

    @Override
    public Kind kind() {
      return Kind.ARRAY;
    }

    public ImmutableList<Const> elements() {
      return elements;
    }

    @Override
    public int hashCode() {
      return elements.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
      return obj instanceof ArrayInitValue && elements.equals(((ArrayInitValue) obj).elements);
    }

    @Override
    public String toString() {
      return "{" + Joiner.on(", ").join(elements) + "}";
    }
  }
}
