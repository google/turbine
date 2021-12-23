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

import static com.google.common.truth.Truth.assertThat;
import static com.google.turbine.testing.TestResources.getResource;
import static java.util.stream.Collectors.toList;
import static org.junit.Assume.assumeTrue;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import java.io.IOError;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class LowerIntegrationTest {

  private static final ImmutableMap<String, Integer> SOURCE_VERSION =
      ImmutableMap.of("record.test", 16, "record2.test", 16, "sealed.test", 17);

  @Parameters(name = "{index}: {0}")
  public static Iterable<Object[]> parameters() {
    String[] testCases = {
      // keep-sorted start
      "B33513475.test",
      "B33513475b.test",
      "B33513475c.test",
      "B70953542.test",
      "B8056066.test",
      "B8056066b.test",
      "B8075274.test",
      "B8148131.test",
      "abstractenum.test",
      "access1.test",
      "ambiguous_identifier.test",
      "anno_const_coerce.test",
      "anno_const_scope.test",
      "anno_nested.test",
      "anno_repeated.test",
      "anno_self_const.test",
      "anno_void.test",
      "annoconstvis.test",
      "annotation_bool_default.test",
      "annotation_class_default.test",
      "annotation_clinit.test",
      "annotation_declaration.test",
      "annotation_enum_default.test",
      "annotation_scope.test",
      "annotations_default.test",
      "annouse.test",
      "annouse10.test",
      "annouse11.test",
      "annouse12.test",
      "annouse13.test",
      "annouse14.test",
      "annouse15.test",
      "annouse16.test",
      "annouse17.test",
      "annouse2.test",
      "annouse3.test",
      "annouse4.test",
      "annouse5.test",
      "annouse6.test",
      "annouse7.test",
      "annouse8.test",
      "annouse9.test",
      "annovis.test",
      "anonymous.test",
      "array_class_literal.test",
      "ascii_sub.test",
      "asset.test",
      "basic_field.test",
      "basic_nested.test",
      "bcp.test",
      "bmethod.test",
      "bounds.test",
      "boxed_const.test",
      "builder.test",
      "byte.test",
      "byte2.test",
      "bytecode_boolean_const.test",
      "bytenoncanon.test",
      "c_array.test",
      "canon.test",
      "canon_class_header.test",
      "canon_recursive.test",
      "cast_tail.test",
      "circ_cvar.test",
      "clash.test",
      "complex_param_anno.test",
      "concat.test",
      "const.test",
      "const_all.test",
      "const_arith.test",
      "const_boxed.test",
      "const_byte.test",
      "const_char.test",
      "const_conditional.test",
      "const_conv.test",
      "const_field.test",
      "const_hiding.test",
      "const_moreexpr.test",
      "const_multi.test",
      "const_nonfinal.test",
      "const_octal_underscore.test",
      "const_operation_order.test",
      "const_types.test",
      "const_underscore.test",
      "constlevel.test",
      "constpack.test",
      "ctor_anno.test",
      "ctorvis.test",
      "cvar_qualified.test",
      "cycle.test",
      "default_fbound.test",
      "default_rawfbound.test",
      "default_simple.test",
      "deficient_types_classfile.test",
      "dollar.test",
      "empty_package_info.test",
      "enum1.test",
      "enum_abstract.test",
      "enum_final.test",
      "enumctor.test",
      "enumctor2.test",
      "enumimpl.test",
      "enumingeneric.test",
      "enuminner.test",
      "enumint.test",
      "enumint2.test",
      "enumint3.test",
      "enumint_byte.test",
      "enumint_objectmethod.test",
      "enumint_objectmethod2.test",
      "enumint_objectmethod_raw.test",
      "enuminthacks.test",
      "enummemberanno.test",
      "enumstat.test",
      "erasurebound.test",
      "existingctor.test",
      "extend_inner.test",
      "extends_bound.test",
      "extends_otherbound.test",
      "extendsandimplements.test",
      "extrainnerclass.test",
      "fbound.test",
      "field_anno.test",
      "firstcomparator.test",
      "float_exponent.test",
      "fuse.test",
      "genericarrayfield.test",
      "genericexn.test",
      "genericexn2.test",
      "genericnoncanon.test",
      "genericnoncanon1.test",
      "genericnoncanon10.test",
      "genericnoncanon2.test",
      "genericnoncanon3.test",
      "genericnoncanon4.test",
      "genericnoncanon5.test",
      "genericnoncanon6.test",
      "genericnoncanon8.test",
      "genericnoncanon9.test",
      "genericnoncanon_byte.test",
      "genericnoncanon_method3.test",
      "genericret.test",
      "hex_int.test",
      "hierarchy.test",
      "ibound.test",
      "icu.test",
      "icu2.test",
      "import_wild_order.test",
      "importconst.test",
      "importinner.test",
      "inner_static.test",
      "innerannodecl.test",
      "innerclassanno.test",
      "innerctor.test",
      "innerenum.test",
      "innerint.test",
      "innerstaticgeneric.test",
      "interface_field.test",
      "interface_member_public.test",
      "interface_method.test",
      "interfacemem.test",
      "interfaces.test",
      // TODO(cushon): crashes ASM, see:
      // https://gitlab.ow2.org/asm/asm/issues/317776
      // "canon_array.test",
      "java_lang_object.test",
      "javadoc_deprecated.test",
      "lexical.test",
      "lexical2.test",
      "lexical4.test",
      "list.test",
      "local.test",
      "long_expression.test",
      "loopthroughb.test",
      "mapentry.test",
      "marker.test",
      "member.test",
      "member_import_clash.test",
      // TODO(cushon): support for source level 9 in integration tests
      // "B74332665.test",
      "memberimport.test",
      "mods.test",
      "morefields.test",
      "moremethods.test",
      "multifield.test",
      "nested.test",
      "nested2.test",
      "nested_member_import.test",
      "nested_member_import_noncanon.test",
      "non_const.test",
      "noncanon.test",
      "noncanon_static_wild.test",
      "nonconst_unary_expression.test",
      "one.test",
      "outer.test",
      "outerparam.test",
      "package_info.test",
      "packagedecl.test",
      "packageprivateprotectedinner.test",
      "param_bound.test",
      "prim_class.test",
      "private_member.test",
      "privateinner.test",
      "proto.test",
      "proto2.test",
      "qual.test",
      "raw.test",
      "raw2.test",
      "raw_canon.test",
      "rawcanon.test",
      "rawfbound.test",
      "receiver_param.test",
      "record.test",
      "record2.test",
      "rek.test",
      "samepkg.test",
      "sealed.test",
      "self.test",
      "semi.test",
      // https://bugs.openjdk.java.net/browse/JDK-8054064 ?
      "shadow_inherited.test",
      "simple.test",
      "simplemethod.test",
      "source_anno_retention.test",
      "source_bootclasspath_order.test",
      "static_final_boxed.test",
      "static_member_type_import.test",
      "static_member_type_import_recursive.test",
      "static_type_import.test",
      "strictfp.test",
      "string.test",
      "string_const.test",
      "superabstract.test",
      "supplierfunction.test",
      "tbound.test",
      "tyanno_inner.test",
      "tyanno_varargs.test",
      "typaram.test",
      "typaram_lookup.test",
      "typaram_lookup_enclosing.test",
      "type_anno_ambiguous.test",
      "type_anno_ambiguous_param.test",
      "type_anno_ambiguous_qualified.test",
      "type_anno_array_bound.test",
      "type_anno_array_dims.test",
      "type_anno_c_array.test",
      "type_anno_cstyle_array_dims.test",
      "type_anno_hello.test",
      "type_anno_order.test",
      "type_anno_parameter_index.test",
      "type_anno_qual.test",
      "type_anno_raw.test",
      "type_anno_receiver.test",
      "type_anno_retention.test",
      "type_anno_return.test",
      "tyvar_bound.test",
      "tyvarfield.test",
      "unary.test",
      "underscore_literal.test",
      "unicode.test",
      "unicode_pkg.test",
      "useextend.test",
      "vanillaexception.test",
      "varargs.test",
      "visible_nested.test",
      "visible_package.test",
      "visible_package_private_toplevel.test",
      "visible_private.test",
      "visible_qualified.test",
      "visible_same_package.test",
      "wild.test",
      "wild2.test",
      "wild3.test",
      "wildboundcanon.test",
      "wildcanon.test",
      // keep-sorted end
    };
    List<Object[]> tests =
        ImmutableList.copyOf(testCases).stream().map(x -> new Object[] {x}).collect(toList());
    String testShardIndex = System.getenv("TEST_SHARD_INDEX");
    String testTotalShards = System.getenv("TEST_TOTAL_SHARDS");
    if (testShardIndex == null || testTotalShards == null) {
      return tests;
    }
    String shardFile = System.getenv("TEST_SHARD_STATUS_FILE");
    if (shardFile != null) {
      try {
        Files.write(Paths.get(shardFile), new byte[0]);
      } catch (IOException e) {
        throw new IOError(e);
      }
    }
    int index = Integer.parseInt(testShardIndex);
    int shards = Integer.parseInt(testTotalShards);
    return Lists.partition(tests, (tests.size() + shards - 1) / shards).get(index);
  }

  final String test;

  public LowerIntegrationTest(String test) {
    this.test = test;
  }

  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Test
  public void test() throws Exception {

    IntegrationTestSupport.TestInput input =
        IntegrationTestSupport.TestInput.parse(getResource(getClass(), "testdata/" + test));

    ImmutableList<Path> classpathJar = ImmutableList.of();
    if (!input.classes.isEmpty()) {
      Map<String, byte[]> classpath =
          IntegrationTestSupport.runJavac(input.classes, ImmutableList.of());
      Path lib = temporaryFolder.newFile("lib.jar").toPath();
      try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(lib))) {
        for (Map.Entry<String, byte[]> entry : classpath.entrySet()) {
          jos.putNextEntry(new JarEntry(entry.getKey() + ".class"));
          jos.write(entry.getValue());
        }
      }
      classpathJar = ImmutableList.of(lib);
    }

    int version = SOURCE_VERSION.getOrDefault(test, 8);
    assumeTrue(version <= Runtime.version().feature());
    ImmutableList<String> javacopts =
        ImmutableList.of("-source", String.valueOf(version), "-target", String.valueOf(version));

    Map<String, byte[]> expected =
        IntegrationTestSupport.runJavac(input.sources, classpathJar, javacopts);

    Map<String, byte[]> actual =
        IntegrationTestSupport.runTurbine(input.sources, classpathJar, javacopts);

    assertThat(IntegrationTestSupport.dump(IntegrationTestSupport.sortMembers(actual)))
        .isEqualTo(IntegrationTestSupport.dump(IntegrationTestSupport.canonicalize(expected)));
  }
}
