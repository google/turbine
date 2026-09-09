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

package com.google.turbine.lower;

import static com.google.common.base.Verify.verify;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.io.MoreFiles.getFileExtension;
import static com.google.turbine.testing.TestClassPaths.TURBINE_BOOTCLASSPATH;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Comparator.comparing;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.joining;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Multimap;
import com.google.common.collect.MultimapBuilder;
import com.google.common.collect.SetMultimap;
import com.google.common.io.MoreFiles;
import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import com.google.turbine.binder.Binder;
import com.google.turbine.binder.Binder.BindingResult;
import com.google.turbine.binder.ClassPath;
import com.google.turbine.binder.ClassPathBinder;
import com.google.turbine.diag.SourceFile;
import com.google.turbine.options.TurbineJavacOptions;
import com.google.turbine.parallel.TurbineExecutor;
import com.google.turbine.parse.Parser;
import com.google.turbine.testing.AsmUtils;
import com.google.turbine.tree.Tree.CompUnit;
import com.sun.source.util.JavacTask;
import com.sun.tools.javac.api.JavacTool;
import com.sun.tools.javac.file.JavacFileManager;
import com.sun.tools.javac.util.Context;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.lang.classfile.AccessFlags;
import java.lang.classfile.Annotation;
import java.lang.classfile.AnnotationElement;
import java.lang.classfile.AnnotationValue;
import java.lang.classfile.Attribute;
import java.lang.classfile.AttributedElement;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassBuilder;
import java.lang.classfile.ClassElement;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassSignature;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.FieldElement;
import java.lang.classfile.FieldModel;
import java.lang.classfile.FieldTransform;
import java.lang.classfile.MethodElement;
import java.lang.classfile.MethodModel;
import java.lang.classfile.MethodSignature;
import java.lang.classfile.MethodTransform;
import java.lang.classfile.Signature;
import java.lang.classfile.Signature.ArrayTypeSig;
import java.lang.classfile.Signature.BaseTypeSig;
import java.lang.classfile.Signature.ClassTypeSig;
import java.lang.classfile.Signature.RefTypeSig;
import java.lang.classfile.Signature.ThrowableSig;
import java.lang.classfile.Signature.TypeArg;
import java.lang.classfile.Signature.TypeParam;
import java.lang.classfile.Signature.TypeVarSig;
import java.lang.classfile.TypeAnnotation;
import java.lang.classfile.attribute.DeprecatedAttribute;
import java.lang.classfile.attribute.InnerClassInfo;
import java.lang.classfile.attribute.InnerClassesAttribute;
import java.lang.classfile.attribute.NestHostAttribute;
import java.lang.classfile.attribute.NestMembersAttribute;
import java.lang.classfile.attribute.PermittedSubclassesAttribute;
import java.lang.classfile.attribute.RecordAttribute;
import java.lang.classfile.attribute.RecordComponentInfo;
import java.lang.classfile.attribute.RuntimeInvisibleAnnotationsAttribute;
import java.lang.classfile.attribute.RuntimeInvisibleParameterAnnotationsAttribute;
import java.lang.classfile.attribute.RuntimeInvisibleTypeAnnotationsAttribute;
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute;
import java.lang.classfile.attribute.RuntimeVisibleParameterAnnotationsAttribute;
import java.lang.classfile.attribute.RuntimeVisibleTypeAnnotationsAttribute;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.Utf8Entry;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.AccessFlag;
import java.nio.file.FileSystem;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.processing.Processor;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;

/** Support for bytecode diffing-integration tests. */
public final class IntegrationTestSupport {

  public static final ImmutableList<TestInput> TEST_CASES = discoverTestCases();

  public static final int DEFAULT_SOURCE_VERSION = 25;

  private static ImmutableList<TestInput> discoverTestCases() {
    try {
      ImmutableList<TestInput> result =
          com.google.common.reflect.ClassPath.from(IntegrationTestSupport.class.getClassLoader())
              .getResources()
              .stream()
              .filter(
                  r ->
                      r.getResourceName().startsWith("com/google/turbine/lower/testdata/")
                          && r.getResourceName().endsWith(".test")
                          // TODO(cushon): crashes ASM, see:
                          // https://gitlab.ow2.org/asm/asm/issues/317776
                          && !r.getResourceName().endsWith("/canon_array.test")
                          // contains broken imports intended to test turbine's lazy error handling
                          && !r.getResourceName().endsWith("/importlazy.test"))
              .map(
                  r -> {
                    try {
                      String name =
                          r.getResourceName()
                              .substring("com/google/turbine/lower/testdata/".length());
                      return TestInput.parse(name, r.asCharSource(UTF_8).read());
                    } catch (IOException e) {
                      throw new UncheckedIOException(e);
                    }
                  })
              .sorted(comparing(TestInput::name))
              .collect(toImmutableList());
      verify(result.size() >= 300, "expected at least 300 test cases, got %s", result.size());
      return result;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static final ClassFile CLASS_FILE = ClassFile.of();

  private static final Comparator<InnerClassInfo> INNER_CLASS_INFO_COMPARATOR =
      Comparator.comparing((InnerClassInfo x) -> x.innerClass().asInternalName())
          .thenComparing(x -> x.outerClass().map(ClassEntry::asInternalName).orElse(""))
          .thenComparing(x -> x.innerName().map(Utf8Entry::stringValue).orElse(""))
          .thenComparingInt(InnerClassInfo::flagsMask);

  private static final Comparator<Annotation> ANNOTATION_COMPARATOR =
      Comparator.comparing((Annotation a) -> a.className().stringValue())
          .thenComparing(a -> String.valueOf(a.elements()));

  private static final Comparator<TypeAnnotation> TYPE_ANNOTATION_COMPARATOR =
      Comparator.comparing((TypeAnnotation a) -> a.annotation().className().stringValue())
          .thenComparing(a -> String.valueOf(a.targetInfo().targetType()))
          .thenComparing(a -> String.valueOf(a.targetInfo()))
          .thenComparing(a -> String.valueOf(a.targetPath()))
          .thenComparing(a -> String.valueOf(a.annotation().elements()));

  private static Attribute<?> sortAttribute(Attribute<?> attr) {
    return switch (attr) {
      case RuntimeVisibleAnnotationsAttribute a ->
          RuntimeVisibleAnnotationsAttribute.of(sortAnnotations(a.annotations()));
      case RuntimeInvisibleAnnotationsAttribute a ->
          RuntimeInvisibleAnnotationsAttribute.of(sortAnnotations(a.annotations()));
      case RuntimeVisibleTypeAnnotationsAttribute a ->
          RuntimeVisibleTypeAnnotationsAttribute.of(sortTypeAnnotations(a.annotations()));
      case RuntimeInvisibleTypeAnnotationsAttribute a ->
          RuntimeInvisibleTypeAnnotationsAttribute.of(sortTypeAnnotations(a.annotations()));
      case RuntimeVisibleParameterAnnotationsAttribute a ->
          RuntimeVisibleParameterAnnotationsAttribute.of(
              sortParameterAnnotations(a.parameterAnnotations()));
      case RuntimeInvisibleParameterAnnotationsAttribute a ->
          RuntimeInvisibleParameterAnnotationsAttribute.of(
              sortParameterAnnotations(a.parameterAnnotations()));
      case InnerClassesAttribute a -> InnerClassesAttribute.of(sortInnerClasses(a.classes()));
      case NestMembersAttribute a ->
          NestMembersAttribute.of(
              ImmutableList.sortedCopyOf(
                  Comparator.comparing(ClassEntry::asInternalName), a.nestMembers()));
      case RecordAttribute a ->
          RecordAttribute.of(
              a.components().stream()
                  .map(IntegrationTestSupport::sortRecordComponentInfo)
                  .toList());
      default -> attr;
    };
  }

  private static RecordComponentInfo sortRecordComponentInfo(RecordComponentInfo r) {
    return RecordComponentInfo.of(
        r.name(),
        r.descriptor(),
        r.attributes().stream().<Attribute<?>>map(IntegrationTestSupport::sortAttribute).toList());
  }

  private static ImmutableList<Annotation> sortAnnotations(List<Annotation> annotations) {
    return ImmutableList.sortedCopyOf(ANNOTATION_COMPARATOR, annotations);
  }

  private static ImmutableList<TypeAnnotation> sortTypeAnnotations(
      List<TypeAnnotation> annotations) {
    return ImmutableList.sortedCopyOf(TYPE_ANNOTATION_COMPARATOR, annotations);
  }

  private static ImmutableList<List<Annotation>> sortParameterAnnotations(
      List<List<Annotation>> parameterAnnotations) {
    return parameterAnnotations.stream()
        .map(IntegrationTestSupport::sortAnnotations)
        .collect(toImmutableList());
  }

  private static ImmutableList<InnerClassInfo> sortInnerClasses(List<InnerClassInfo> classes) {
    return ImmutableList.sortedCopyOf(INNER_CLASS_INFO_COMPARATOR, classes);
  }

  /**
   * Normalizes order of members, attributes, and constant pool entries, to allow diffing bytecode.
   */
  public static ImmutableMap<String, byte[]> sortMembers(Map<String, byte[]> in) {
    ImmutableMap.Builder<String, byte[]> result = ImmutableMap.builder();
    for (byte[] entryValue : in.values()) {
      ClassModel cm = CLASS_FILE.parse(entryValue);
      byte[] bytes = CLASS_FILE.transformClass(cm, IntegrationTestSupport::sortMembers);
      result.put(cm.thisClass().asInternalName() + ".class", bytes);
    }
    return result.buildOrThrow();
  }

  private static void sortMembers(ClassBuilder builder, ClassElement element) {
    switch (element) {
      case Attribute<?> attr -> builder.with((ClassElement) sortAttribute(attr));
      case MethodModel mm ->
          builder.transformMethod(
              mm,
              (mb, me) -> {
                switch (me) {
                  case Attribute<?> attr -> mb.with((MethodElement) sortAttribute(attr));
                  default -> mb.with(me);
                }
              });
      case FieldModel fm ->
          builder.transformField(
              fm,
              (fb, fe) -> {
                switch (fe) {
                  case Attribute<?> attr -> fb.with((FieldElement) sortAttribute(attr));
                  default -> fb.with(fe);
                }
              });
      default -> builder.with(element);
    }
  }

  /**
   * Canonicalizes bytecode produced by javac to match the expected output of turbine. Includes the
   * same normalization as {@link #sortMembers}, as well as removing everything not produced by the
   * header compiler (code, debug info, etc.)
   */
  public static Map<String, byte[]> canonicalize(Map<String, byte[]> in) {
    List<ClassModel> classes = new ArrayList<>();
    for (byte[] bytes : in.values()) {
      classes.add(CLASS_FILE.parse(bytes));
    }

    // drop local and anonymous classes
    classes.removeIf(n -> isAnonymous(n) || isLocal(n));

    // collect all inner classes attributes
    Map<String, InnerClassInfo> infos = new HashMap<>();
    for (ClassModel n : classes) {
      n.findAttribute(Attributes.innerClasses())
          .ifPresent(
              attr -> {
                for (InnerClassInfo innerClassInfo : attr.classes()) {
                  infos.put(innerClassInfo.innerClass().asInternalName(), innerClassInfo);
                }
              });
    }

    SetMultimap<String, ClassModel> byOutermostClass =
        MultimapBuilder.hashKeys().hashSetValues().build();
    for (ClassModel n : classes) {
      byOutermostClass.put(outermostClass(n.thisClass().asInternalName(), infos), n);
    }

    Map<String, ClassModel> allRemaining = new HashMap<>();
    Map<String, Set<String>> removedByUnit = new HashMap<>();
    for (var entry : byOutermostClass.asMap().entrySet()) {
      Map<String, ClassModel> remaining =
          pruneUnusedPrivateClasses(new HashSet<>(entry.getValue()), infos);
      allRemaining.putAll(remaining);
      ImmutableSet<String> removed =
          entry.getValue().stream()
              .map(n -> n.thisClass().asInternalName())
              .filter(n -> !remaining.containsKey(n))
              .collect(toImmutableSet());
      removedByUnit.put(entry.getKey(), removed);
    }

    Map<String, byte[]> result = new LinkedHashMap<>();
    for (var entry : byOutermostClass.asMap().entrySet()) {
      Set<String> removed = removedByUnit.get(entry.getKey());
      for (ClassModel n : entry.getValue()) {
        if (allRemaining.containsKey(n.thisClass().asInternalName())) {
          result.put(
              n.thisClass().asInternalName() + ".class",
              canonicalizeClass(n, infos, allRemaining.keySet(), removed));
        }
      }
    }
    return result;
  }

  private static byte[] canonicalizeClass(
      ClassModel cm,
      Map<String, InnerClassInfo> infos,
      Set<String> remainingClassNames,
      Set<String> removed) {
    boolean isEnum = cm.flags().has(AccessFlag.ENUM);
    boolean hasDeprecatedAnno = hasDeprecatedAnnotation(cm);
    ImmutableList<InnerClassInfo> innerClasses =
        canonicalizeInnerClasses(cm, infos, remainingClassNames, removed);
    ImmutableList<ClassEntry> nestMembers = canonicalizeNestMembers(cm, innerClasses);

    return CLASS_FILE.transformClass(
        cm,
        (builder, element) -> {
          switch (element) {
            case AccessFlags flags when isEnum ->
                // Javac may add ACC_ABSTRACT to enums; turbine normalizes enums to omit
                // ACC_ABSTRACT.
                builder.withFlags(flags.flagsMask() & ~ClassFile.ACC_ABSTRACT);
            case InnerClassesAttribute _ -> {
              // Replace with canonicalized inner classes.
              if (!innerClasses.isEmpty()) {
                builder.with(InnerClassesAttribute.of(innerClasses));
              }
            }
            case NestMembersAttribute _ -> {
              // Replace with canonicalized nest members.
              if (!nestMembers.isEmpty()) {
                builder.with(NestMembersAttribute.of(nestMembers));
              }
            }
            // Drop PermittedSubclasses for enums (javac adds them for enums with constant class
            // bodies).
            case PermittedSubclassesAttribute _ when isEnum -> {}
            // Turbine only emits Deprecated attributes when @Deprecated annotation is present.
            case DeprecatedAttribute _ when !hasDeprecatedAnno -> {}
            case MethodModel mm when isSkippedMethod(mm) -> {}
            case MethodModel mm -> builder.transformMethod(mm, canonicalizeMethod(mm));
            case FieldModel fm when isSkippedField(fm) -> {}
            case FieldModel fm -> builder.transformField(fm, canonicalizeField(fm));
            case Attribute<?> attr -> builder.with((ClassElement) sortAttribute(attr));
            default -> builder.with(element);
          }
        });
  }

  private static boolean isSkippedMethod(MethodModel mm) {
    return mm.flags().has(AccessFlag.SYNTHETIC)
        || mm.flags().has(AccessFlag.PRIVATE)
        // Turbine does not emit class initializers in header jars.
        || mm.methodName().equalsString("<clinit>");
  }

  private static boolean isSkippedField(FieldModel fm) {
    return fm.flags().has(AccessFlag.SYNTHETIC) || fm.flags().has(AccessFlag.PRIVATE);
  }

  private static MethodTransform canonicalizeMethod(MethodModel mm) {
    return (mb, me) -> {
      switch (me) {
        case DeprecatedAttribute _ when !hasDeprecatedAnnotation(mm) -> {}
        case Attribute<?> attr -> mb.with((MethodElement) sortAttribute(attr));
        default -> mb.with(me);
      }
    };
  }

  private static FieldTransform canonicalizeField(FieldModel fm) {
    return (fb, fe) -> {
      switch (fe) {
        case DeprecatedAttribute _ when !hasDeprecatedAnnotation(fm) -> {}
        case Attribute<?> attr -> fb.with((FieldElement) sortAttribute(attr));
        default -> fb.with(fe);
      }
    };
  }

  private static ImmutableList<InnerClassInfo> canonicalizeInnerClasses(
      ClassModel cm,
      Map<String, InnerClassInfo> infos,
      Set<String> remainingClassNames,
      Set<String> removed) {
    String thisClassName = cm.thisClass().asInternalName();
    List<InnerClassInfo> innerClasses =
        cm.findAttribute(Attributes.innerClasses())
            .map(InnerClassesAttribute::classes)
            .orElse(ImmutableList.of());
    Set<String> types = getReferencedTypes(cm, infos, /* includeNestMembers= */ true);
    Map<String, InnerClassInfo> used = new LinkedHashMap<>();
    for (InnerClassInfo i : innerClasses) {
      if (i.has(AccessFlag.SYNTHETIC) || i.innerName().isEmpty()) {
        continue;
      }
      String name = i.innerClass().asInternalName();
      if (removed.contains(name)) {
        continue;
      }
      if (i.outerClass().isPresent()
          && i.outerClass().get().asInternalName().equals(thisClassName)) {
        used.put(name, i);
      } else if (types.contains(name)) {
        for (InnerClassInfo enclosing : enclosingInnerClassNodes(name, infos)) {
          used.put(enclosing.innerClass().asInternalName(), enclosing);
        }
      }
    }
    List<InnerClassInfo> processed = new ArrayList<>();
    for (InnerClassInfo x : used.values()) {
      if (x.has(AccessFlag.ENUM) && remainingClassNames.contains(x.innerClass().asInternalName())) {
        processed.add(
            InnerClassInfo.of(
                x.innerClass(),
                x.outerClass(),
                x.innerName(),
                x.flagsMask() & ~ClassFile.ACC_ABSTRACT));
      } else {
        processed.add(x);
      }
    }
    return sortInnerClasses(processed);
  }

  private static ImmutableList<ClassEntry> canonicalizeNestMembers(
      ClassModel cm, List<InnerClassInfo> innerClasses) {
    ImmutableSet<String> memberNames =
        innerClasses.stream().map(i -> i.innerClass().asInternalName()).collect(toImmutableSet());
    return cm.findAttribute(Attributes.nestMembers())
        .map(
            nm ->
                nm.nestMembers().stream()
                    .filter(m -> memberNames.contains(m.asInternalName()))
                    .sorted(Comparator.comparing(ClassEntry::asInternalName))
                    .collect(toImmutableList()))
        .orElse(ImmutableList.of());
  }

  private static Map<String, ClassModel> pruneUnusedPrivateClasses(
      Set<ClassModel> classes, Map<String, InnerClassInfo> infos) {
    Set<String> declared = new HashSet<>();
    for (ClassModel n : classes) {
      declared.add(n.thisClass().asInternalName());
    }
    SetMultimap<String, String> usages = MultimapBuilder.hashKeys().hashSetValues().build();
    List<String> roots = new ArrayList<>();
    for (ClassModel n : classes) {
      String name = n.thisClass().asInternalName();
      if (!isPrivateMemberClass(n, infos)) {
        roots.add(name);
      }
      for (String ref : getReferencedTypes(n, infos, /* includeNestMembers= */ false)) {
        if (declared.contains(ref)) {
          usages.put(name, ref);
        }
      }
    }
    Set<String> reachable = new HashSet<>();
    for (String root : roots) {
      closure(reachable, root, usages);
    }
    Map<String, ClassModel> pruned = new HashMap<>();
    for (ClassModel n : classes) {
      if (reachable.contains(n.thisClass().asInternalName())) {
        pruned.put(n.thisClass().asInternalName(), n);
      }
    }
    return pruned;
  }

  static void closure(Set<String> reachable, String current, Multimap<String, String> usages) {
    if (!reachable.add(current)) {
      return;
    }
    for (String next : usages.get(current)) {
      closure(reachable, next, usages);
    }
  }

  private static ImmutableList<InnerClassInfo> enclosingInnerClassNodes(
      String className, Map<String, InnerClassInfo> infos) {
    ImmutableList.Builder<InnerClassInfo> builder = ImmutableList.builder();
    String curr = className;
    while (curr != null && infos.containsKey(curr)) {
      InnerClassInfo info = infos.get(curr);
      builder.add(info);
      curr = info.outerClass().map(ClassEntry::asInternalName).orElse(null);
    }
    return builder.build().reverse();
  }

  private static boolean isPrivateMemberClass(ClassModel cm, Map<String, InnerClassInfo> infos) {
    return enclosingInnerClassNodes(cm.thisClass().asInternalName(), infos).stream()
        .anyMatch(info -> info.has(AccessFlag.PRIVATE));
  }

  private static String outermostClass(String className, Map<String, InnerClassInfo> infos) {
    ImmutableList<InnerClassInfo> enclosing = enclosingInnerClassNodes(className, infos);
    return enclosing.isEmpty()
        ? className
        : enclosing.getFirst().outerClass().map(ClassEntry::asInternalName).orElse(className);
  }

  public static ImmutableMap<String, byte[]> removeUnsupportedAttributes(Map<String, byte[]> in) {
    ClassTransform transform =
        ClassTransform.dropping(
            e ->
                switch (e) {
                  case NestMembersAttribute _, NestHostAttribute _ -> true;
                  default -> false;
                });
    ImmutableMap.Builder<String, byte[]> result = ImmutableMap.builder();
    for (byte[] bytes : in.values()) {
      ClassModel cm = CLASS_FILE.parse(bytes);
      result.put(
          cm.thisClass().asInternalName() + ".class", CLASS_FILE.transformClass(cm, transform));
    }
    return result.buildOrThrow();
  }

  private static boolean isLocal(ClassModel cm) {
    return cm.findAttribute(Attributes.enclosingMethod()).isPresent();
  }

  private static boolean isAnonymous(ClassModel cm) {
    // JVMS 4.7.6: if C is anonymous, the value of the inner_name_index item must be zero
    String className = cm.thisClass().asInternalName();
    return cm
        .findAttribute(Attributes.innerClasses())
        .map(InnerClassesAttribute::classes)
        .orElse(ImmutableList.of())
        .stream()
        .anyMatch(
            i -> i.innerClass().asInternalName().equals(className) && i.innerName().isEmpty());
  }

  private static boolean hasDeprecatedAnnotation(AttributedElement element) {
    return element
        .findAttribute(Attributes.runtimeVisibleAnnotations())
        .map(RuntimeVisibleAnnotationsAttribute::annotations)
        .orElse(ImmutableList.of())
        .stream()
        .anyMatch(a -> a.className().equalsString("Ljava/lang/Deprecated;"));
  }

  /** Visit all descriptors and signatures in the bytecode to find references to inner classes. */
  private static Set<String> getReferencedTypes(
      ClassModel cm, Map<String, InnerClassInfo> infos, boolean includeNestMembers) {
    Set<String> types = new HashSet<>();
    types.add(cm.thisClass().asInternalName());
    cm.findAttribute(Attributes.signature())
        .ifPresent(s -> collectTypesFromClassSignature(types, s.asClassSignature()));
    cm.superclass().ifPresent(s -> types.add(s.asInternalName()));
    for (ClassEntry iface : cm.interfaces()) {
      types.add(iface.asInternalName());
    }
    addAllTypesInAnnotations(types, cm);
    for (MethodModel m : cm.methods()) {
      if (isSkippedMethod(m)) {
        continue;
      }
      collectTypesFromMethodType(types, m.methodTypeSymbol());
      m.findAttribute(Attributes.signature())
          .ifPresent(s -> collectTypesFromMethodSignature(types, s.asMethodSignature()));
      m.findAttribute(Attributes.exceptions())
          .ifPresent(e -> e.exceptions().forEach(ex -> types.add(ex.asInternalName())));

      addAllTypesInAnnotations(types, m);
      addTypesFromParameterAnnotations(types, m);

      m.findAttribute(Attributes.annotationDefault())
          .ifPresent(a -> collectTypesFromAnnotationValue(types, a.defaultValue()));
    }
    for (FieldModel f : cm.fields()) {
      if (isSkippedField(f)) {
        continue;
      }
      collectTypesFromClassDesc(types, f.fieldTypeSymbol());
      f.findAttribute(Attributes.signature())
          .ifPresent(s -> collectTypesFromSignature(types, s.asTypeSignature()));

      addAllTypesInAnnotations(types, f);
    }
    cm.findAttribute(Attributes.record())
        .ifPresent(
            record -> {
              for (RecordComponentInfo r : record.components()) {
                collectTypesFromClassDesc(types, r.descriptorSymbol());
                r.findAttribute(Attributes.signature())
                    .ifPresent(s -> collectTypesFromSignature(types, s.asTypeSignature()));

                addAllTypesInAnnotations(types, r);
              }
            });

    if (includeNestMembers) {
      cm.findAttribute(Attributes.nestMembers())
          .ifPresent(
              nest -> {
                for (ClassEntry member : nest.nestMembers()) {
                  InnerClassInfo i = infos.get(member.asInternalName());
                  if (i != null && i.outerClass().isPresent()) {
                    types.add(member.asInternalName());
                  }
                }
              });
    }
    enclosingInnerClassNodes(cm.thisClass().asInternalName(), infos)
        .forEach(i -> types.add(i.innerClass().asInternalName()));
    return types;
  }

  private static void addAllTypesInAnnotations(Set<String> types, AttributedElement element) {
    addTypesInAnnotations(types, element);
    addTypesInTypeAnnotations(types, element);
  }

  private static void addTypesFromParameterAnnotations(Set<String> types, MethodModel m) {
    m.findAttribute(Attributes.runtimeVisibleParameterAnnotations())
        .ifPresent(pa -> collectTypesFromParameterAnnotations(types, pa.parameterAnnotations()));
    m.findAttribute(Attributes.runtimeInvisibleParameterAnnotations())
        .ifPresent(pa -> collectTypesFromParameterAnnotations(types, pa.parameterAnnotations()));
  }

  private static void collectTypesFromParameterAnnotations(
      Set<String> types, List<List<Annotation>> parameterAnnotations) {
    parameterAnnotations.stream()
        .flatMap(Collection::stream)
        .forEach(a -> collectTypesFromAnnotation(types, a));
  }

  private static void addTypesInAnnotations(Set<String> types, AttributedElement element) {
    element
        .findAttribute(Attributes.runtimeVisibleAnnotations())
        .ifPresent(a -> a.annotations().forEach(anno -> collectTypesFromAnnotation(types, anno)));
    element
        .findAttribute(Attributes.runtimeInvisibleAnnotations())
        .ifPresent(a -> a.annotations().forEach(anno -> collectTypesFromAnnotation(types, anno)));
  }

  private static void addTypesInTypeAnnotations(Set<String> types, AttributedElement element) {
    element
        .findAttribute(Attributes.runtimeVisibleTypeAnnotations())
        .ifPresent(
            a ->
                a.annotations()
                    .forEach(anno -> collectTypesFromAnnotation(types, anno.annotation())));
    element
        .findAttribute(Attributes.runtimeInvisibleTypeAnnotations())
        .ifPresent(
            a ->
                a.annotations()
                    .forEach(anno -> collectTypesFromAnnotation(types, anno.annotation())));
  }

  private static void collectTypesFromAnnotation(Set<String> types, Annotation a) {
    collectTypesFromClassDesc(types, a.classSymbol());
    for (AnnotationElement element : a.elements()) {
      collectTypesFromAnnotationValue(types, element.value());
    }
  }

  private static void collectTypesFromAnnotationValue(Set<String> types, AnnotationValue v) {
    switch (v) {
      case AnnotationValue.OfArray ofArray -> {
        for (AnnotationValue elem : ofArray.values()) {
          collectTypesFromAnnotationValue(types, elem);
        }
      }
      case AnnotationValue.OfClass ofClass ->
          collectTypesFromClassDesc(types, ofClass.classSymbol());
      case AnnotationValue.OfAnnotation ofAnno ->
          collectTypesFromAnnotation(types, ofAnno.annotation());
      case AnnotationValue.OfEnum ofEnum -> collectTypesFromClassDesc(types, ofEnum.classSymbol());
      default -> {}
    }
  }

  private static final Pattern CLASS_DESCRIPTOR_PATTERN = Pattern.compile("L(.*);");

  private static void collectTypesFromClassDesc(Set<String> classes, ClassDesc desc) {
    while (desc.isArray()) {
      desc = desc.componentType();
    }
    if (!desc.isPrimitive()) {
      String descriptor = desc.descriptorString();
      Matcher matcher = CLASS_DESCRIPTOR_PATTERN.matcher(descriptor);
      verify(matcher.matches());
      classes.add(matcher.group(1));
    }
  }

  private static void collectTypesFromMethodType(Set<String> classes, MethodTypeDesc methodType) {
    collectTypesFromClassDesc(classes, methodType.returnType());
    for (ClassDesc param : methodType.parameterList()) {
      collectTypesFromClassDesc(classes, param);
    }
  }

  private static void collectTypesFromClassSignature(Set<String> classes, ClassSignature sig) {
    for (TypeParam param : sig.typeParameters()) {
      collectTypesFromTypeParam(classes, param);
    }
    collectTypesFromSignature(classes, sig.superclassSignature());
    for (ClassTypeSig iface : sig.superinterfaceSignatures()) {
      collectTypesFromSignature(classes, iface);
    }
  }

  private static void collectTypesFromMethodSignature(Set<String> classes, MethodSignature sig) {
    for (TypeParam param : sig.typeParameters()) {
      collectTypesFromTypeParam(classes, param);
    }
    for (Signature arg : sig.arguments()) {
      collectTypesFromSignature(classes, arg);
    }
    collectTypesFromSignature(classes, sig.result());
    for (ThrowableSig throwable : sig.throwableSignatures()) {
      collectTypesFromSignature(classes, throwable);
    }
  }

  private static void collectTypesFromTypeParam(Set<String> classes, TypeParam param) {
    param.classBound().ifPresent(b -> collectTypesFromSignature(classes, b));
    for (RefTypeSig bound : param.interfaceBounds()) {
      collectTypesFromSignature(classes, bound);
    }
  }

  private static void collectTypesFromSignature(Set<String> classes, Signature sig) {
    switch (sig) {
      case ClassTypeSig classTypeSig -> {
        collectTypesFromClassDesc(classes, classTypeSig.classDesc());
        for (ClassTypeSig curr = classTypeSig; curr != null; curr = curr.outerType().orElse(null)) {
          for (TypeArg arg : curr.typeArgs()) {
            switch (arg) {
              case TypeArg.Bounded bounded ->
                  collectTypesFromSignature(classes, bounded.boundType());
              case TypeArg.Unbounded _ -> {}
            }
          }
        }
      }
      case ArrayTypeSig arrayTypeSig ->
          collectTypesFromSignature(classes, arrayTypeSig.componentSignature());
      case TypeVarSig _, BaseTypeSig _ -> {}
    }
  }

  public static Map<String, byte[]> runTurbine(
      Map<String, String> input, ImmutableList<Path> classpath) throws IOException {
    return runTurbine(input, classpath, ImmutableList.of());
  }

  public static Map<String, byte[]> runTurbine(
      Map<String, String> input, ImmutableList<Path> classpath, ImmutableList<String> javacopts)
      throws IOException {
    return runTurbine(
        input, classpath, TURBINE_BOOTCLASSPATH, /* moduleVersion= */ Optional.empty(), javacopts);
  }

  static ImmutableMap<String, byte[]> runTurbine(
      Map<String, String> input,
      ImmutableList<Path> classpath,
      ClassPath bootClassPath,
      Optional<String> moduleVersion,
      ImmutableList<String> javacopts)
      throws IOException {
    BindingResult bound = turbineAnalysis(input, classpath, bootClassPath, moduleVersion);
    return Lower.lowerAll(
            TurbineExecutor.direct(),
            TurbineJavacOptions.parse(javacopts).lowerOptions(),
            bound.units(),
            bound.modules(),
            bound.classPathEnv())
        .bytes();
  }

  public static BindingResult turbineAnalysis(
      Map<String, String> input,
      ImmutableList<Path> classpath,
      ClassPath bootClassPath,
      Optional<String> moduleVersion)
      throws IOException {
    ImmutableList<CompUnit> units =
        input.entrySet().stream()
            .map(e -> new SourceFile(e.getKey(), e.getValue()))
            .map(Parser::parse)
            .collect(toImmutableList());

    return Binder.bind(
        TurbineExecutor.direct(),
        units,
        ClassPathBinder.bindClasspath(classpath),
        bootClassPath,
        moduleVersion);
  }

  public static JavacTask runJavacAnalysis(
      Map<String, String> sources, Collection<Path> classpath, ImmutableList<String> options)
      throws Exception {
    return runJavacAnalysis(sources, classpath, options, new DiagnosticCollector<>());
  }

  public static JavacTask runJavacAnalysis(
      Map<String, String> sources,
      Collection<Path> classpath,
      ImmutableList<String> options,
      DiagnosticCollector<JavaFileObject> collector)
      throws Exception {
    FileSystem fs = Jimfs.newFileSystem(Configuration.unix());
    Path out = fs.getPath("out");
    return setupJavac(sources, classpath, options, collector, fs, out, ImmutableList.of());
  }

  public static JavacTask runJavacAnalysis(
      Map<String, String> sources,
      Collection<Path> classpath,
      ImmutableList<String> options,
      DiagnosticCollector<JavaFileObject> collector,
      ImmutableList<Processor> processors)
      throws Exception {
    FileSystem fs = Jimfs.newFileSystem(Configuration.unix());
    Path out = fs.getPath("out");
    return setupJavac(sources, classpath, options, collector, fs, out, processors);
  }

  public static Map<String, byte[]> runJavac(
      Map<String, String> sources, Collection<Path> classpath) throws Exception {
    return runJavac(
        sources, classpath, ImmutableList.of("-parameters", "-source", "8", "-target", "8"));
  }

  public static Map<String, byte[]> runJavac(
      Map<String, String> sources, Collection<Path> classpath, ImmutableList<String> options)
      throws Exception {

    DiagnosticCollector<JavaFileObject> collector = new DiagnosticCollector<>();
    FileSystem fs = Jimfs.newFileSystem(Configuration.unix());
    Path out = fs.getPath("out");

    JavacTask task =
        setupJavac(sources, classpath, options, collector, fs, out, ImmutableList.of());

    if (!task.call()) {
      fail(collector.getDiagnostics().stream().map(Object::toString).collect(joining("\n")));
    }

    List<Path> classes = new ArrayList<>();
    Files.walkFileTree(
        out,
        new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult visitFile(Path path, BasicFileAttributes attrs)
              throws IOException {
            if (getFileExtension(path).equals("class")) {
              classes.add(path);
            }
            return FileVisitResult.CONTINUE;
          }
        });
    Map<String, byte[]> result = new LinkedHashMap<>();
    for (Path path : classes) {
      String r = out.relativize(path).toString();
      result.put(r.substring(0, r.length() - ".class".length()), Files.readAllBytes(path));
    }
    return result;
  }

  private static JavacTask setupJavac(
      Map<String, String> sources,
      Collection<Path> classpath,
      ImmutableList<String> options,
      DiagnosticCollector<JavaFileObject> collector,
      FileSystem fs,
      Path out,
      Iterable<? extends Processor> processors)
      throws IOException {
    Path srcs = fs.getPath("srcs");

    Files.createDirectories(out);

    ArrayList<Path> inputs = new ArrayList<>();
    for (Map.Entry<String, String> entry : sources.entrySet()) {
      Path path = srcs.resolve(entry.getKey());
      if (path.getParent() != null) {
        Files.createDirectories(path.getParent());
      }
      MoreFiles.asCharSink(path, UTF_8).write(entry.getValue());
      inputs.add(path);
    }

    JavacTool compiler = JavacTool.create();
    JavacFileManager fileManager = new JavacFileManager(new Context(), true, UTF_8);
    fileManager.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, ImmutableList.of(out));
    fileManager.setLocationFromPaths(StandardLocation.CLASS_PATH, classpath);
    fileManager.setLocationFromPaths(StandardLocation.locationFor("MODULE_PATH"), classpath);
    if (inputs.stream()
            .filter(i -> requireNonNull(i.getFileName()).toString().equals("module-info.java"))
            .count()
        > 1) {
      // multi-module mode
      fileManager.setLocationFromPaths(
          StandardLocation.locationFor("MODULE_SOURCE_PATH"), ImmutableList.of(srcs));
    }

    JavacTask task =
        compiler.getTask(
            new PrintWriter(new BufferedWriter(new OutputStreamWriter(System.err, UTF_8)), true),
            fileManager,
            collector,
            options,
            ImmutableList.of(),
            fileManager.getJavaFileObjectsFromPaths(inputs));
    task.setProcessors(processors);
    return task;
  }

  /** Normalizes and stringifies a collection of class files. */
  public static String dump(Map<String, byte[]> compiled) throws Exception {
    StringBuilder sb = new StringBuilder();
    List<String> keys = new ArrayList<>(compiled.keySet());
    Collections.sort(keys);
    for (String key : keys) {
      String na = key;
      if (na.startsWith("/")) {
        na = na.substring(1);
      }
      sb.append(String.format("=== %s ===\n", na));
      sb.append(AsmUtils.textify(compiled.get(key), /* skipDebug= */ true));
    }
    return sb.toString();
  }

  public record TestInput(
      String name,
      int sourceVersion,
      boolean preview,
      ImmutableList<String> extraJavacopts,
      Map<String, String> sources,
      Map<String, String> classes) {

    public TestInput(Map<String, String> sources, Map<String, String> classes) {
      this("", DEFAULT_SOURCE_VERSION, false, ImmutableList.of(), sources, classes);
    }

    public ImmutableList<String> javacopts() {
      int actualVersion = Runtime.version().feature();
      int requiredVersion = sourceVersion();
      assumeTrue(actualVersion >= requiredVersion);
      ImmutableList.Builder<String> builder = ImmutableList.builder();
      if (preview()) {
        requiredVersion = actualVersion;
        builder.add("--enable-preview");
      }
      return builder
          .addAll(extraJavacopts())
          .add(
              "-source",
              String.valueOf(requiredVersion),
              "-target",
              String.valueOf(requiredVersion))
          .add("-Xpkginfo:always")
          .build();
    }

    @Override
    public String toString() {
      return name;
    }

    public static TestInput parse(String text) {
      return parse("", text);
    }

    public static TestInput parse(String name, String text) {
      Map<String, String> sources = new LinkedHashMap<>();
      Map<String, String> classes = new LinkedHashMap<>();
      int sourceVersion = DEFAULT_SOURCE_VERSION;
      boolean preview = false;
      ImmutableList.Builder<String> javacopts = ImmutableList.builder();

      List<String> allLines = Splitter.on('\n').splitToList(text);
      int startLine = 0;
      if (!allLines.isEmpty() && allLines.get(0).trim().equals("---")) {
        int endIdx = -1;
        for (int i = 1; i < allLines.size(); i++) {
          if (allLines.get(i).trim().equals("---")) {
            endIdx = i;
            break;
          }
        }
        if (endIdx != -1) {
          for (int i = 1; i < endIdx; i++) {
            String line = allLines.get(i).trim();
            if (line.isEmpty() || line.startsWith("#")) {
              continue;
            }
            List<String> parts = Splitter.on(':').limit(2).trimResults().splitToList(line);
            if (parts.size() != 2) {
              throw new IllegalArgumentException(
                  String.format("Malformed header line in %s: %s", name, line));
            }
            switch (parts.get(0)) {
              case "source_version" -> sourceVersion = Integer.parseInt(parts.get(1));
              case "preview" -> preview = Boolean.parseBoolean(parts.get(1));
              case "javacopts" ->
                  javacopts.addAll(Splitter.on(' ').omitEmptyStrings().split(parts.get(1)));
              default ->
                  throw new IllegalArgumentException(
                      String.format("Unknown header key in %s: %s", name, parts.get(0)));
            }
          }
          startLine = endIdx + 1;
        }
      }

      String className = null;
      String sourceName = null;
      List<String> lines = new ArrayList<>();
      for (int i = startLine; i < allLines.size(); i++) {
        String line = allLines.get(i);
        if (line.startsWith("===")) {
          if (sourceName != null) {
            sources.put(sourceName, Joiner.on('\n').join(lines) + "\n");
          }
          if (className != null) {
            classes.put(className, Joiner.on('\n').join(lines) + "\n");
          }
          lines.clear();
          sourceName = line.substring(3, line.length() - 3).trim();
          className = null;
        } else if (line.startsWith("%%%")) {
          if (className != null) {
            classes.put(className, Joiner.on('\n').join(lines) + "\n");
          }
          if (sourceName != null) {
            sources.put(sourceName, Joiner.on('\n').join(lines) + "\n");
          }
          className = line.substring(3, line.length() - 3).trim();
          lines.clear();
          sourceName = null;
        } else {
          lines.add(line);
        }
      }
      if (sourceName != null) {
        sources.put(sourceName, Joiner.on('\n').join(lines) + "\n");
      }
      if (className != null) {
        classes.put(className, Joiner.on('\n').join(lines) + "\n");
      }
      lines.clear();
      return new TestInput(name, sourceVersion, preview, javacopts.build(), sources, classes);
    }
  }

  private IntegrationTestSupport() {}
}
