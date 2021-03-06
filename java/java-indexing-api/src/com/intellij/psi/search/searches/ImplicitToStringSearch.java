// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.psi.search.searches;

import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.search.SearchScope;
import com.intellij.util.Query;
import org.jetbrains.annotations.NotNull;

public class ImplicitToStringSearch extends ExtensibleQueryFactory<PsiExpression, ImplicitToStringSearch.SearchParameters> {
  public static final ImplicitToStringSearch INSTANCE = new ImplicitToStringSearch();

  public static class SearchParameters {
    private final PsiMethod myTargetMethod;
    @NotNull
    private final SearchScope myScope;

    public SearchParameters(@NotNull PsiMethod targetMethod, @NotNull SearchScope scope) {
      myTargetMethod = targetMethod;
      myScope = scope;
    }

    @NotNull
    public PsiMethod getTargetMethod() {
      return myTargetMethod;
    }

    @NotNull
    public SearchScope getSearchScope() {
      return myScope;
    }
  }

  public static Query<PsiExpression> search(@NotNull PsiMethod targetMethod, @NotNull SearchScope scope) {
    return INSTANCE.createUniqueResultsQuery(new SearchParameters(targetMethod, scope));
  }
}
