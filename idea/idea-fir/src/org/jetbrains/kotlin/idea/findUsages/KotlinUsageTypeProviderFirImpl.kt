/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.findUsages

import com.intellij.openapi.application.ApplicationManager
import com.intellij.psi.PsiPackage
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.idea.KotlinBundle
import org.jetbrains.kotlin.idea.caches.resolve.analyze
import org.jetbrains.kotlin.idea.references.KtArrayAccessReference
import org.jetbrains.kotlin.idea.references.KtInvokeFunctionReference
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.getParentOfTypeAndBranch
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.idea.findUsages.UsageTypeEnum.*
import org.jetbrains.kotlin.idea.frontend.api.KtAnalysisSession
import org.jetbrains.kotlin.idea.frontend.api.analyseInModalWindow
import org.jetbrains.kotlin.idea.frontend.api.analyzeWithReadAction
import org.jetbrains.kotlin.idea.frontend.api.symbols.*
import org.jetbrains.kotlin.idea.references.KtSimpleReference
import org.jetbrains.kotlin.psi.psiUtil.referenceExpression
import org.jetbrains.kotlin.resolve.DescriptorUtils
import org.jetbrains.kotlin.resolve.lazy.BodyResolveMode

class KotlinUsageTypeProviderFirImpl : KotlinUsageTypeProvider() {

    override fun getUsageTypeEnumByReference(refExpr: KtReferenceExpression): UsageTypeEnum? {

        val reference = refExpr.mainReference
        check(reference is KtSimpleReference<*>) { "Reference should be KtSimpleReference but not ${reference::class}" }

        fun getFunctionUsageType(functionSymbol: KtFunctionLikeSymbol): UsageTypeEnum? {
            when (reference) {
                is KtArrayAccessReference -> {
                    TODO("FIR Implement implicit get/set")
//                    return when {
//                        //KtFirArrayAccessReference
////                        context[BindingContext.INDEXED_LVALUE_GET, refExpr] != null -> IMPLICIT_GET
////                        context[BindingContext.INDEXED_LVALUE_SET, refExpr] != null -> IMPLICIT_SET
//                        else -> null
//                    }
                }
                is KtInvokeFunctionReference -> return IMPLICIT_INVOKE
            }

            return when {
                refExpr.getParentOfTypeAndBranch<KtSuperTypeListEntry> { typeReference } != null -> SUPER_TYPE

                functionSymbol is KtConstructorSymbol && refExpr.getParentOfTypeAndBranch<KtAnnotationEntry> { typeReference } != null -> ANNOTATION

                with(refExpr.getParentOfTypeAndBranch<KtCallExpression> { calleeExpression }) {
                    this?.calleeExpression is KtSimpleNameExpression
                } -> if (functionSymbol is KtConstructorSymbol) CLASS_NEW_OPERATOR else FUNCTION_CALL //HLAPI resolveCall -> CallInfo ->

                refExpr.getParentOfTypeAndBranch<KtBinaryExpression> { operationReference } != null || refExpr.getParentOfTypeAndBranch<KtUnaryExpression> { operationReference } != null || refExpr.getParentOfTypeAndBranch<KtWhenConditionInRange> { operationReference } != null -> FUNCTION_CALL

                else -> null
            }
        }

        fun <T> analyze(contextElement: KtElement, body: KtAnalysisSession.() -> T): T =
            if (ApplicationManager.getApplication().isDispatchThread)
                analyseInModalWindow(contextElement, "TODO", body)
            else
                analyzeWithReadAction(contextElement, body)


        return analyze(refExpr) {
            when (val targetElement = reference.resolveToSymbol()) {
                is KtClassOrObjectSymbol ->
                    when {
                        // Treat object accesses as variables to simulate the old behaviour (when variables were created for objects)
                        targetElement is KtEnumEntrySymbol -> getVariableUsageType(refExpr)
                        targetElement.classKind == KtClassKind.COMPANION_OBJECT -> COMPANION_OBJECT_ACCESS
                        targetElement.classKind == KtClassKind.OBJECT -> getVariableUsageType(refExpr)
                        else -> getClassUsageType(refExpr)
                    }
                is KtPackageSymbol -> //TODO FIR Implement package symbol type
                    if (targetElement is PsiPackage) getPackageUsageType(refExpr) else getClassUsageType(refExpr)
                is KtVariableSymbol -> getVariableUsageType(refExpr)
                is KtFunctionLikeSymbol -> getFunctionUsageType(targetElement)
                else -> null
            }
        }
    }
}