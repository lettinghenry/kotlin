/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.findUsages

import com.intellij.psi.PsiElement
import com.intellij.usages.UsageTarget
import com.intellij.usages.impl.rules.UsageType
import com.intellij.usages.impl.rules.UsageTypeProviderEx
import org.jetbrains.kotlin.idea.KotlinBundleIndependent
import org.jetbrains.kotlin.idea.findUsages.UsageTypeEnum.*
import org.jetbrains.kotlin.idea.references.*
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.getNonStrictParentOfType
import org.jetbrains.kotlin.psi.psiUtil.getParentOfTypeAndBranch
import org.jetbrains.kotlin.psi.psiUtil.isAncestor

abstract class KotlinUsageTypeProvider : UsageTypeProviderEx {

    abstract fun getUsageTypeEnumByReference(refExpr: KtReferenceExpression): UsageTypeEnum?

    private fun getUsageTypeEnum(element: PsiElement?): UsageTypeEnum? {
        when (element) {
            is KtForExpression -> return IMPLICIT_ITERATION
            is KtDestructuringDeclarationEntry -> return READ
            is KtPropertyDelegate -> return PROPERTY_DELEGATION
            is KtStringTemplateExpression -> return USAGE_IN_STRING_LITERAL
        }

        val refExpr = element?.getNonStrictParentOfType<KtReferenceExpression>() ?: return null

        return getCommonUsageType(refExpr) ?: getUsageTypeEnumByReference(refExpr)
    }

    override fun getUsageType(element: PsiElement?): UsageType? = getUsageType(element, UsageTarget.EMPTY_ARRAY)

    override fun getUsageType(element: PsiElement?, targets: Array<out UsageTarget>): UsageType? {
        val usageType = getUsageTypeEnum(element) ?: return null
        return convertEnumToUsageType(usageType)
    }

    private fun getCommonUsageType(refExpr: KtReferenceExpression): UsageTypeEnum? = when {
        refExpr.getNonStrictParentOfType<KtImportDirective>() != null -> CLASS_IMPORT
        refExpr.getParentOfTypeAndBranch<KtCallableReferenceExpression> { callableReference } != null -> CALLABLE_REFERENCE
        else -> null
    }

    protected fun getClassUsageType(refExpr: KtReferenceExpression): UsageTypeEnum? {
        if (refExpr.getNonStrictParentOfType<KtTypeProjection>() != null) return TYPE_PARAMETER

        val property = refExpr.getNonStrictParentOfType<KtProperty>()
        if (property != null) {
            when {
                property.typeReference.isAncestor(refExpr) ->
                    return if (property.isLocal) CLASS_LOCAL_VAR_DECLARATION else NON_LOCAL_PROPERTY_TYPE

                property.receiverTypeReference.isAncestor(refExpr) ->
                    return EXTENSION_RECEIVER_TYPE
            }
        }

        val function = refExpr.getNonStrictParentOfType<KtFunction>()
        if (function != null) {
            when {
                function.typeReference.isAncestor(refExpr) ->
                    return FUNCTION_RETURN_TYPE
                function.receiverTypeReference.isAncestor(refExpr) ->
                    return EXTENSION_RECEIVER_TYPE
            }
        }

        return when {
            refExpr.getParentOfTypeAndBranch<KtTypeParameter> { extendsBound } != null || refExpr.getParentOfTypeAndBranch<KtTypeConstraint> { boundTypeReference } != null -> TYPE_CONSTRAINT

            refExpr is KtSuperTypeListEntry || refExpr.getParentOfTypeAndBranch<KtSuperTypeListEntry> { typeReference } != null -> SUPER_TYPE

            refExpr.getParentOfTypeAndBranch<KtParameter> { typeReference } != null -> VALUE_PARAMETER_TYPE

            refExpr.getParentOfTypeAndBranch<KtIsExpression> { typeReference } != null || refExpr.getParentOfTypeAndBranch<KtWhenConditionIsPattern> { typeReference } != null -> IS

            with(refExpr.getParentOfTypeAndBranch<KtBinaryExpressionWithTypeRHS> { right }) {
                val opType = this?.operationReference?.getReferencedNameElementType()
                opType == org.jetbrains.kotlin.lexer.KtTokens.AS_KEYWORD || opType == org.jetbrains.kotlin.lexer.KtTokens.AS_SAFE
            } -> CLASS_CAST_TO

            with(refExpr.getNonStrictParentOfType<KtDotQualifiedExpression>()) {
                when {
                    this == null -> {
                        false
                    }
                    receiverExpression == refExpr -> {
                        true
                    }
                    else -> {
                        selectorExpression == refExpr
                                && getParentOfTypeAndBranch<KtDotQualifiedExpression>(strict = true) { receiverExpression } != null
                    }
                }
            } -> CLASS_OBJECT_ACCESS

            refExpr.getParentOfTypeAndBranch<KtSuperExpression> { superTypeQualifier } != null -> SUPER_TYPE_QUALIFIER

            refExpr.getParentOfTypeAndBranch<KtTypeAlias> { getTypeReference() } != null -> TYPE_ALIAS

            else -> null
        }
    }

    protected fun getVariableUsageType(refExpr: KtReferenceExpression): UsageTypeEnum? {
        if (refExpr.getParentOfTypeAndBranch<KtDelegatedSuperTypeEntry> { delegateExpression } != null) return DELEGATE

        if (refExpr.parent is KtValueArgumentName) return NAMED_ARGUMENT

        val dotQualifiedExpression = refExpr.getNonStrictParentOfType<KtDotQualifiedExpression>()

        if (dotQualifiedExpression != null) {
            val parent = dotQualifiedExpression.parent
            when {
                dotQualifiedExpression.receiverExpression.isAncestor(refExpr) ->
                    return RECEIVER

                parent is KtDotQualifiedExpression && parent.receiverExpression.isAncestor(refExpr) ->
                    return RECEIVER
            }
        }

        return when (refExpr.readWriteAccess(useResolveForReadWrite = true)) {
            ReferenceAccess.READ -> READ
            ReferenceAccess.WRITE, ReferenceAccess.READ_WRITE -> WRITE
        }
    }

    protected fun getPackageUsageType(refExpr: KtReferenceExpression): UsageTypeEnum? = when {
        refExpr.getNonStrictParentOfType<KtPackageDirective>() != null -> PACKAGE_DIRECTIVE
        refExpr.getNonStrictParentOfType<KtQualifiedExpression>() != null -> PACKAGE_MEMBER_ACCESS
        else -> getClassUsageType(refExpr)
    }

    private fun convertEnumToUsageType(usageType: UsageTypeEnum): UsageType = when (usageType) {
        TYPE_CONSTRAINT -> KotlinUsageTypes.TYPE_CONSTRAINT
        VALUE_PARAMETER_TYPE -> KotlinUsageTypes.VALUE_PARAMETER_TYPE
        NON_LOCAL_PROPERTY_TYPE -> KotlinUsageTypes.NON_LOCAL_PROPERTY_TYPE
        FUNCTION_RETURN_TYPE -> KotlinUsageTypes.FUNCTION_RETURN_TYPE
        SUPER_TYPE -> KotlinUsageTypes.SUPER_TYPE
        IS -> KotlinUsageTypes.IS
        CLASS_OBJECT_ACCESS -> KotlinUsageTypes.CLASS_OBJECT_ACCESS
        COMPANION_OBJECT_ACCESS -> KotlinUsageTypes.COMPANION_OBJECT_ACCESS
        EXTENSION_RECEIVER_TYPE -> KotlinUsageTypes.EXTENSION_RECEIVER_TYPE
        SUPER_TYPE_QUALIFIER -> KotlinUsageTypes.SUPER_TYPE_QUALIFIER
        TYPE_ALIAS -> KotlinUsageTypes.TYPE_ALIAS

        FUNCTION_CALL -> KotlinUsageTypes.FUNCTION_CALL
        IMPLICIT_GET -> KotlinUsageTypes.IMPLICIT_GET
        IMPLICIT_SET -> KotlinUsageTypes.IMPLICIT_SET
        IMPLICIT_INVOKE -> KotlinUsageTypes.IMPLICIT_INVOKE
        IMPLICIT_ITERATION -> KotlinUsageTypes.IMPLICIT_ITERATION
        PROPERTY_DELEGATION -> KotlinUsageTypes.PROPERTY_DELEGATION

        RECEIVER -> KotlinUsageTypes.RECEIVER
        DELEGATE -> KotlinUsageTypes.DELEGATE

        PACKAGE_DIRECTIVE -> KotlinUsageTypes.PACKAGE_DIRECTIVE
        PACKAGE_MEMBER_ACCESS -> KotlinUsageTypes.PACKAGE_MEMBER_ACCESS

        CALLABLE_REFERENCE -> KotlinUsageTypes.CALLABLE_REFERENCE

        READ -> UsageType.READ
        WRITE -> UsageType.WRITE
        CLASS_IMPORT -> UsageType.CLASS_IMPORT
        CLASS_LOCAL_VAR_DECLARATION -> UsageType.CLASS_LOCAL_VAR_DECLARATION
        TYPE_PARAMETER -> UsageType.TYPE_PARAMETER
        CLASS_CAST_TO -> UsageType.CLASS_CAST_TO
        ANNOTATION -> UsageType.ANNOTATION
        CLASS_NEW_OPERATOR -> UsageType.CLASS_NEW_OPERATOR
        NAMED_ARGUMENT -> KotlinUsageTypes.NAMED_ARGUMENT

        USAGE_IN_STRING_LITERAL -> UsageType.LITERAL_USAGE
    }
}

object KotlinUsageTypes {
    // types
    val TYPE_CONSTRAINT = UsageType(KotlinBundleIndependent.lazyMessage("find.usages.type.type.constraint"))
    val VALUE_PARAMETER_TYPE = UsageType(KotlinBundleIndependent.lazyMessage("find.usages.type.value.parameter.type"))
    val NON_LOCAL_PROPERTY_TYPE = UsageType(KotlinBundleIndependent.lazyMessage("find.usages.type.nonLocal.property.type"))
    val FUNCTION_RETURN_TYPE = UsageType(KotlinBundleIndependent.lazyMessage("find.usages.type.function.return.type"))
    val SUPER_TYPE = UsageType(KotlinBundleIndependent.lazyMessage("find.usages.type.superType"))
    val IS = UsageType(KotlinBundleIndependent.lazyMessage("find.usages.type.is"))
    val CLASS_OBJECT_ACCESS = UsageType(KotlinBundleIndependent.lazyMessage("find.usages.type.class.object"))
    val COMPANION_OBJECT_ACCESS = UsageType(KotlinBundleIndependent.lazyMessage("find.usages.type.companion.object"))
    val EXTENSION_RECEIVER_TYPE = UsageType(KotlinBundleIndependent.lazyMessage("find.usages.type.extension.receiver.type"))
    val SUPER_TYPE_QUALIFIER = UsageType(KotlinBundleIndependent.lazyMessage("find.usages.type.super.type.qualifier"))
    val TYPE_ALIAS = UsageType(KotlinBundleIndependent.lazyMessage("find.usages.type.type.alias"))

    // functions
    val FUNCTION_CALL = UsageType(KotlinBundleIndependent.lazyMessage("find.usages.type.function.call"))
    val IMPLICIT_GET = UsageType(KotlinBundleIndependent.lazyMessage("find.usages.type.implicit.get"))
    val IMPLICIT_SET = UsageType(KotlinBundleIndependent.lazyMessage("find.usages.type.implicit.set"))
    val IMPLICIT_INVOKE = UsageType(KotlinBundleIndependent.lazyMessage("find.usages.type.implicit.invoke"))
    val IMPLICIT_ITERATION = UsageType(KotlinBundleIndependent.lazyMessage("find.usages.type.implicit.iteration"))
    val PROPERTY_DELEGATION = UsageType(KotlinBundleIndependent.lazyMessage("find.usages.type.property.delegation"))

    // values
    val RECEIVER = UsageType(KotlinBundleIndependent.lazyMessage("find.usages.type.receiver"))
    val DELEGATE = UsageType(KotlinBundleIndependent.lazyMessage("find.usages.type.delegate"))

    // packages
    val PACKAGE_DIRECTIVE = UsageType(KotlinBundleIndependent.lazyMessage("find.usages.type.packageDirective"))
    val PACKAGE_MEMBER_ACCESS = UsageType(KotlinBundleIndependent.lazyMessage("find.usages.type.packageMemberAccess"))

    // common usage types
    val CALLABLE_REFERENCE = UsageType(KotlinBundleIndependent.lazyMessage("find.usages.type.callable.reference"))
    val NAMED_ARGUMENT = UsageType(KotlinBundleIndependent.lazyMessage("find.usages.type.named.argument"))
}

enum class UsageTypeEnum {
    TYPE_CONSTRAINT,
    VALUE_PARAMETER_TYPE,
    NON_LOCAL_PROPERTY_TYPE,
    FUNCTION_RETURN_TYPE,
    SUPER_TYPE,
    IS,
    CLASS_OBJECT_ACCESS,
    COMPANION_OBJECT_ACCESS,
    EXTENSION_RECEIVER_TYPE,
    SUPER_TYPE_QUALIFIER,
    TYPE_ALIAS,

    FUNCTION_CALL,
    IMPLICIT_GET,
    IMPLICIT_SET,
    IMPLICIT_INVOKE,
    IMPLICIT_ITERATION,
    PROPERTY_DELEGATION,

    RECEIVER,
    DELEGATE,

    PACKAGE_DIRECTIVE,
    PACKAGE_MEMBER_ACCESS,

    CALLABLE_REFERENCE,

    READ,
    WRITE,
    CLASS_IMPORT,
    CLASS_LOCAL_VAR_DECLARATION,
    TYPE_PARAMETER,
    CLASS_CAST_TO,
    ANNOTATION,
    CLASS_NEW_OPERATOR,
    NAMED_ARGUMENT,

    USAGE_IN_STRING_LITERAL
}
