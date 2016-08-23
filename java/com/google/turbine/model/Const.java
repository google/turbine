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
  public abstract static class Value extends Const {}

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
  }

  /** A char literal value. */
  public static class CharValue extends Value {
    private final char value;

    public CharValue(char value) {
      this.value = value;
    }

    @Override
    public String toString() {
      return "'" + String.valueOf(value) + "'";
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
      return String.valueOf(value) + "f";
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
  }
}
