// Resolver.java
package com.google.turbine.binder;

import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.tree.Tree;

import javax.annotation.Nullable;

public interface Resolver {

    @Nullable
    ClassSymbol resolveOne(ClassSymbol base, Tree.Ident name);
}
