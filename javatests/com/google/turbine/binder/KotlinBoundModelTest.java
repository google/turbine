/*
 * Copyright 2026 Google Inc. All Rights Reserved.
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

package com.google.turbine.binder;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.turbine.binder.bound.KotlinMemberInfo;
import com.google.turbine.binder.bound.KotlinMemberInfo.Role;
import com.google.turbine.binder.bound.KotlinMemberInfo.ValueParameter;
import com.google.turbine.binder.bound.KotlinTypeParameterInfo;
import com.google.turbine.binder.bound.SourceTypeBoundClass;
import com.google.turbine.binder.bound.SourceTypeBoundClass.SourceKind;
import com.google.turbine.binder.bound.TypeBoundClass.TyVarInfo;
import com.google.turbine.binder.lookup.CompoundScope;
import com.google.turbine.binder.lookup.MemberImportIndex;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.diag.SourceFile;
import com.google.turbine.kotlin.type.KtType;
import com.google.turbine.kotlin.type.KtType.KtClassType;
import com.google.turbine.kotlin.type.KtType.KtFlexibleType;
import com.google.turbine.kotlin.type.KtType.KtNullness;
import com.google.turbine.kotlin.type.KtType.KtProjection;
import com.google.turbine.kotlin.type.KtType.KtTypeArgument;
import com.google.turbine.model.TurbineFlag;
import com.google.turbine.model.TurbineTyKind;
import com.google.turbine.type.Type;
import com.google.turbine.type.Type.IntersectionTy;
import org.jspecify.annotations.Nullable;
import org.junit.Test;

/// Unit tests for [KtType], [KotlinMemberInfo], [KotlinTypeParameterInfo], and
/// [SourceTypeBoundClass.SourceKind].
public class KotlinBoundModelTest {

  private static final KtClassType STRING = KtClassType.of(new ClassSymbol("kotlin/String"));
  private static final KtClassType INT = KtClassType.of(new ClassSymbol("kotlin/Int"));
  private static final KtClassType UNIT = KtClassType.of(new ClassSymbol("kotlin/Unit"));

  @Test
  public void typeArgumentStarProjectionHasNoType() {
    assertThat(KtTypeArgument.STAR.type()).isNull();
    assertThrows(
        IllegalArgumentException.class, () -> new KtTypeArgument(KtProjection.STAR, STRING));
    assertThrows(
        IllegalArgumentException.class,
        () -> new KtTypeArgument(KtProjection.INVARIANT, /* type= */ null));
  }

  @Test
  public void flexibleTypeNullnessIsLowerBounds() {
    KtClassType nullableString =
        new KtClassType(
            STRING.classifier(),
            ImmutableList.of(),
            /* outer= */ null,
            KtNullness.NULLABLE,
            /* abbreviation= */ null,
            ImmutableList.of());
    assertThat(new KtFlexibleType(STRING, nullableString).nullness())
        .isEqualTo(KtNullness.NOT_NULL);
    assertThat(new KtFlexibleType(nullableString, STRING).nullness())
        .isEqualTo(KtNullness.NULLABLE);
  }

  private static KotlinMemberInfo function(
      @Nullable KtType receiver, ImmutableList<ValueParameter> valueParameters) {
    return new KotlinMemberInfo(
        Role.NORMAL,
        /* propertyName= */ null,
        UNIT,
        receiver,
        valueParameters,
        /* isOverride= */ false,
        /* noSignature= */ false);
  }

  @Test
  public void extensionFunctionParameters() {
    KotlinMemberInfo extFun =
        function(STRING, ImmutableList.of(new ValueParameter(INT, /* hasDefault= */ true)));

    assertThat(KotlinMemberInfo.valueParameterOffset(extFun, /* numParams= */ 2)).isEqualTo(1);
    assertThat(KotlinMemberInfo.jvmParameterTypes(extFun, /* numParams= */ 2))
        .containsExactly(STRING, INT)
        .inOrder();
    assertThat(KotlinMemberInfo.jvmParameterDefaults(extFun, /* numParams= */ 2))
        .containsExactly(false, true)
        .inOrder();
  }

  @Test
  public void extensionFunctionWithoutReceiverParameterDoesNotLineUp() {
    // The JVM method has as many parameters as the Kotlin function has value parameters, so the
    // receiver can't be the first one.
    KotlinMemberInfo extFun =
        function(STRING, ImmutableList.of(new ValueParameter(INT, /* hasDefault= */ true)));

    assertThat(KotlinMemberInfo.valueParameterOffset(extFun, /* numParams= */ 1)).isEqualTo(-1);
    assertThat(KotlinMemberInfo.jvmParameterTypes(extFun, /* numParams= */ 1))
        .containsExactly((KtType) null);
    assertThat(KotlinMemberInfo.jvmParameterDefaults(extFun, /* numParams= */ 1)).isNull();
  }

  @Test
  public void functionParameters() {
    KotlinMemberInfo fun =
        function(/* receiver= */ null, ImmutableList.of(new ValueParameter(INT, false)));

    assertThat(KotlinMemberInfo.valueParameterOffset(fun, /* numParams= */ 1)).isEqualTo(0);
    assertThat(KotlinMemberInfo.valueParameterOffset(fun, /* numParams= */ 2)).isEqualTo(-1);
    assertThat(KotlinMemberInfo.jvmParameterTypes(fun, /* numParams= */ 1)).containsExactly(INT);
  }

  @Test
  public void propertyAnnotationsMethodHasNoParameterInfo() {
    KotlinMemberInfo propAnnos = new KotlinMemberInfo(Role.PROPERTY_ANNOTATIONS, "prop");

    assertThat(KotlinMemberInfo.isSyntheticHelper(propAnnos)).isTrue();
    assertThat(KotlinMemberInfo.valueParameterOffset(propAnnos, /* numParams= */ 0)).isEqualTo(-1);
    assertThat(KotlinMemberInfo.jvmParameterDefaults(propAnnos, /* numParams= */ 0)).isNull();
  }

  @Test
  public void membersWithoutKotlinInfo() {
    assertThat(KotlinMemberInfo.roleOf(null)).isEqualTo(Role.NORMAL);
    assertThat(KotlinMemberInfo.hasOverride(null)).isFalse();
    assertThat(KotlinMemberInfo.omitsSignature(null)).isFalse();
    assertThat(KotlinMemberInfo.propertyNameOf(null)).isNull();
    assertThat(KotlinMemberInfo.isSyntheticHelper(null)).isFalse();
  }

  @Test
  public void withUpperBoundKeepsKotlinInfo() {
    KotlinTypeParameterInfo ktTyParam = new KotlinTypeParameterInfo(ImmutableList.of(STRING));
    TyVarInfo tyVarInfo =
        new TyVarInfo(
            IntersectionTy.create(ImmutableList.of(Type.ClassTy.OBJECT)),
            /* lowerBound= */ null,
            ImmutableList.of(),
            ktTyParam);

    assertThat(
            tyVarInfo
                .withUpperBound(IntersectionTy.create(ImmutableList.of(Type.ClassTy.STRING)))
                .kotlin())
        .isEqualTo(ktTyParam);
  }

  private static SourceTypeBoundClass classWithSourceKind(SourceKind sourceKind) {
    return new SourceTypeBoundClass(
        ImmutableList.of(),
        ImmutableList.of(),
        Type.ClassTy.OBJECT,
        ImmutableMap.of(),
        TurbineFlag.ACC_PUBLIC | TurbineFlag.ACC_FINAL,
        ImmutableList.of(),
        ImmutableList.of(),
        ImmutableList.of(),
        /* owner= */ null,
        TurbineTyKind.CLASS,
        ImmutableMap.of(),
        ImmutableMap.of(),
        CompoundScope.base(lookupKey -> null),
        MemberImportIndex.empty(),
        /* annotationMetadata= */ null,
        ImmutableList.of(),
        new SourceFile("Util.kt", ""),
        /* decl= */ null,
        sourceKind,
        /* position= */ 0);
  }

  @Test
  public void sourceKind() {
    assertThat(classWithSourceKind(SourceKind.JAVA).isKotlin()).isFalse();
    assertThat(classWithSourceKind(SourceKind.KOTLIN_CLASS).isKotlin()).isTrue();
    assertThat(classWithSourceKind(SourceKind.KOTLIN_CLASS).isKotlinFileFacade()).isFalse();
    assertThat(classWithSourceKind(SourceKind.KOTLIN_FILE_FACADE).isKotlin()).isTrue();
    assertThat(classWithSourceKind(SourceKind.KOTLIN_FILE_FACADE).isKotlinFileFacade()).isTrue();
  }
}
