/*
 * Copyright 2010-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:JvmName("TypeMappingUtil")
package org.jetbrains.kotlin.codegen.state

import org.jetbrains.kotlin.descriptors.CallableDescriptor
import org.jetbrains.kotlin.descriptors.CallableMemberDescriptor
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.descriptors.annotations.AnnotationDescriptor
import org.jetbrains.kotlin.load.kotlin.TypeMappingMode
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.resolve.descriptorUtil.firstOverridden
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameOrNull
import org.jetbrains.kotlin.resolve.descriptorUtil.parentsWithSelf
import org.jetbrains.kotlin.resolve.descriptorUtil.propertyIfAccessor
import org.jetbrains.kotlin.resolve.isInlineClassType
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.TypeSystemCommonBackendContext
import org.jetbrains.kotlin.types.Variance
import org.jetbrains.kotlin.types.checker.convertVariance
import org.jetbrains.kotlin.types.getEffectiveVariance
import org.jetbrains.kotlin.types.model.KotlinTypeMarker
import org.jetbrains.kotlin.types.model.TypeParameterMarker
import org.jetbrains.kotlin.builtins.KotlinBuiltIns.FQ_NAMES as BUILTIN_NAMES

// TODO: probably class upper bound should be used
@Suppress("UNUSED_PARAMETER")
fun TypeSystemCommonBackendContext.isMostPreciseContravariantArgument(type: KotlinTypeMarker, parameter: TypeParameterMarker): Boolean =
    type.typeConstructor().isAnyConstructor()

fun TypeSystemCommonBackendContext.isMostPreciseCovariantArgument(type: KotlinTypeMarker): Boolean =
    !canHaveSubtypesIgnoringNullability(type)

private fun TypeSystemCommonBackendContext.canHaveSubtypesIgnoringNullability(kotlinType: KotlinTypeMarker): Boolean {
    val constructor = kotlinType.typeConstructor()

    if (!constructor.isClassTypeConstructor() || !constructor.isFinalClassOrEnumEntryOrAnnotationClassConstructor()) return true

    for (i in 0 until constructor.parametersCount()) {
        val parameter = constructor.getParameter(i)
        val argument = kotlinType.getArgument(i)
        if (argument.isStarProjection()) return true

        val projectionKind = argument.getVariance().convertVariance()
        val type = argument.getType()

        val effectiveVariance = getEffectiveVariance(parameter.getVariance().convertVariance(), projectionKind)
        if (effectiveVariance == Variance.OUT_VARIANCE && !isMostPreciseCovariantArgument(type)) return true
        if (effectiveVariance == Variance.IN_VARIANCE && !isMostPreciseContravariantArgument(type, parameter)) return true
    }

    return false
}

val CallableDescriptor?.isMethodWithDeclarationSiteWildcards: Boolean
    get() {
        if (this !is CallableMemberDescriptor) return false
        return original.firstOverridden(useOriginal = true) {
            METHODS_WITH_DECLARATION_SITE_WILDCARDS.contains(it.propertyIfAccessor.fqNameOrNull())
        } != null
    }

private fun FqName.child(name: String): FqName = child(Name.identifier(name))
private val METHODS_WITH_DECLARATION_SITE_WILDCARDS = setOf(
        BUILTIN_NAMES.mutableCollection.child("addAll"),
        BUILTIN_NAMES.mutableList.child("addAll"),
        BUILTIN_NAMES.mutableMap.child("putAll")
)

fun TypeMappingMode.updateArgumentModeFromAnnotations(type: KotlinType): TypeMappingMode {
    type.suppressWildcardsMode()?.let {
        return TypeMappingMode.createWithConstantDeclarationSiteWildcardsMode(
                skipDeclarationSiteWildcards = it,
                isForAnnotationParameter = isForAnnotationParameter,
                needInlineClassWrapping = needInlineClassWrapping
        )
    }

    if (type.annotations.hasAnnotation(JVM_WILDCARD_ANNOTATION_FQ_NAME)) {
        return TypeMappingMode.createWithConstantDeclarationSiteWildcardsMode(
                skipDeclarationSiteWildcards = false,
                isForAnnotationParameter = isForAnnotationParameter,
                fallbackMode = this,
                needInlineClassWrapping = needInlineClassWrapping
        )
    }

    return this
}

internal fun extractTypeMappingModeFromAnnotation(
        callableDescriptor: CallableDescriptor?,
        outerType: KotlinType,
        isForAnnotationParameter: Boolean
): TypeMappingMode? =
        (outerType.suppressWildcardsMode() ?: callableDescriptor?.suppressWildcardsMode())?.let {
            if (outerType.arguments.isNotEmpty())
                TypeMappingMode.createWithConstantDeclarationSiteWildcardsMode(
                        skipDeclarationSiteWildcards = it,
                        isForAnnotationParameter = isForAnnotationParameter,
                        needInlineClassWrapping = !outerType.isInlineClassType()
                )
            else
                TypeMappingMode.DEFAULT
        }

private fun DeclarationDescriptor.suppressWildcardsMode(): Boolean? =
        parentsWithSelf.mapNotNull {
            it.annotations.findAnnotation(JVM_SUPPRESS_WILDCARDS_ANNOTATION_FQ_NAME)
        }.firstOrNull().suppressWildcardsMode()

private fun KotlinType.suppressWildcardsMode(): Boolean? =
        annotations.findAnnotation(JVM_SUPPRESS_WILDCARDS_ANNOTATION_FQ_NAME).suppressWildcardsMode()

private fun AnnotationDescriptor?.suppressWildcardsMode(): Boolean? {
    return (this ?: return null).allValueArguments.values.firstOrNull()?.value as? Boolean ?: true
}

val JVM_SUPPRESS_WILDCARDS_ANNOTATION_FQ_NAME = FqName("kotlin.jvm.JvmSuppressWildcards")
val JVM_WILDCARD_ANNOTATION_FQ_NAME = FqName("kotlin.jvm.JvmWildcard")
