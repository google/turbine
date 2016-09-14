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

/** A JVMS §4.7.6 InnerClasses attribute. */
public class InnerClass {

  private final String innerClass;
  private final String outerClass;
  private final String innerName;
  private final int access;

  public InnerClass(String innerClass, String outerClass, String innerName, int access) {
    this.innerClass = innerClass;
    this.outerClass = outerClass;
    this.innerName = innerName;
    this.access = access;
  }

  /** The binary name of the inner class. */
  public String innerClass() {
    return innerClass;
  }

  /** The binary name of the enclosing class. */
  public String outerClass() {
    return outerClass;
  }

  /** The simple name of the inner class. */
  public String innerName() {
    return innerName;
  }

  /** Access and property flags of the inner class; see JVMS table 4.8. */
  public int access() {
    return access;
  }
}
