# MiniKotlin CPS Compiler — Solution

## Overview

This compiler translates MiniKotlin source code into Java using Continuation-Passing Style (CPS). In CPS, functions never return values directly — instead, each function receives an extra `Continuation<T>` parameter and calls it with the result when done. This makes the control flow explicit and enables asynchronous execution patterns.

---

## Architecture

The compiler is a single-pass tree visitor built on top of the ANTLR4-generated `MiniKotlinBaseVisitor`. The call chain for compiling a program is:

```
compile
  └── compileFunction          (one per function declaration)
        └── compileBlock
              └── compileStatements     (recursive, index-based)
                    └── compileStatement
                          ├── compileVariableDeclaration
                          ├── compileVariableAssignment
                          ├── compileReturnStatement
                          ├── compileWhileStatement
                          ├── compileIfStatement
                          └── compileFunctionCall
```

The two core helpers that power CPS transformation are:

- **`liftExpr`** — takes an expression and a continuation lambda `consume`. If the expression contains function calls, it generates nested CPS calls and passes the result into `consume`. If not, it compiles the expression directly and passes it to `consume`.
- **`liftArgs`** — evaluates a list of arguments left-to-right in CPS, collecting results, then calls `consume` with the full list of compiled argument expressions.

---

## Key Design Decisions

### 1. Mutable variables as `Integer[]` arrays

Java lambdas require captured variables to be effectively final. Since MiniKotlin allows variable reassignment (`x = factorial(5)`), and assignments often happen inside CPS lambdas, a plain `Integer x` would cause a Java compile error.

The solution is to box all mutable variables as single-element arrays:

```java
Integer[] x = { 0 };
factorial(5, (__arg0) -> {
    x[0] = __arg0;   // legal — x is final, x[0] is mutable
});
```

Function parameters are also shadowed this way. A parameter `_n` is immediately wrapped:

```java
public static void factorial(Integer _n, Continuation<Integer> __k) {
    final Integer[] n = { _n };
    // rest of body uses n[0]
}
```

The compiler tracks which variables are arrays via a `scope: Map<String, String>` that maps variable names to their Java types (suffixed with `[]` if they are array-boxed). `compilePrimary` uses this to emit `n[0]` instead of `n` when reading an array-boxed variable.

### 2. `while` loops as recursive helper methods

A `while (cond) { body }` loop is impossible to express as a Java `while` when `cond` contains a function call, because the condition evaluation is asynchronous in CPS — it returns via a callback, not a value.

The solution is to generate a static helper method `__while_N` that:
1. Evaluates the condition in CPS
2. If true, executes the body, then calls itself recursively
3. If false, calls the exit continuation `__k`

```java
public static void __while_0(Integer[] n, Integer[] i, Continuation<Void> __k) {
    shouldContinue(i[0], (__cond) -> {
        if (__cond) {
            // body
            i[0] = i[0] - 1;
            __while_0(n, i, __k);   // recurse
        } else {
            __k.accept(null);        // exit
        }
    });
}
```

All variables in scope at the point of the `while` are passed as parameters so the helper can read and mutate them. The call site passes those variables and a continuation for the code after the loop:

```java
__while_0(n, i, (__ignored) -> {
    // rest of program after while
});
```

### 3. `if/else` with a join-point continuation

When an `if` statement has code after it (`rest` is non-empty), both branches need to continue into the same code. Rather than duplicating `rest` in both branches (which can be large), the compiler creates a single `__join_N` continuation and calls it from both:

```java
Continuation<Void> __join_0 = (__x) -> {
    // rest of program
};

if (condition) {
    // then branch
    __join_0.accept(null);
    return;
} else {
    // else branch
    __join_0.accept(null);
    return;
}
```

When `rest` is empty (e.g. inside a while body or at the end of a function), no join point is needed and the branches are compiled directly.

### 4. Scope tracking

Every compile function receives a `scope: Map<String, String>` that maps variable names to their Java types. This serves two purposes:

- **Reading variables**: `compilePrimary` checks if a variable is array-boxed and emits `x[0]` instead of `x`.
- **Writing variables**: `compileVariableAssignment` checks the same to emit `x[0] = ...` instead of `x = ...`.
- **While helper parameters**: `compileWhileStatement` uses the scope to know which variables to pass as parameters to the generated helper method.

The scope is built up incrementally in `compileStatements` — each `variableDeclaration` adds a new entry before compiling subsequent statements.

---

## Non-trivial Examples

### Example 1: `return n * factorial(n - 1)`

A return statement where the RHS contains a function call inside a binary expression. `liftExpr` detects the call, generates a CPS call for `factorial`, and passes the result into the continuation which computes the multiplication:

```java
factorial(n[0] - 1, (__arg0) -> {
    __continuation.accept((n[0] * __arg0));
    return;
});
```

### Example 2: `while (shouldContinue(counter)) { counter = counter - 1 }`

The condition contains a function call, so a helper method is generated. All variables in scope are passed as parameters so the helper can read and mutate them:

```java
public static void __while_0(Integer[] n, Integer[] counter, Continuation<Void> __k) {
    shouldContinue(counter[0], (__arg) -> {
        if (__arg) {
            counter[0] = counter[0] - 1;
            __while_0(n, counter, __k);   // recurse
        } else {
            __k.accept(null);              // exit
        }
    });
}

// call site:
__while_0(n, counter, (__ignored) -> {
    __continuation.accept(counter[0]);
    return;
});
```

### Example 3: `if (isEven(n)) { return 1 } else { return 2 }`

When an `if` has code after it, both branches call a shared `__join_N` continuation to avoid duplicating the rest of the program. When there is no code after (as here, inside a function that returns in both branches), no join point is needed:

```java
isEven(n[0], (__arg0) -> {
    if (__arg0) {
        __continuation.accept(1);
        return;
    } else {
        __continuation.accept(2);
        return;
    }
});
```

With code after the `if`:

```java
Continuation<Void> __join_0 = (__x) -> {
    // rest of program after if
};

if (condition) {
    // then branch
    __join_0.accept(null);
    return;
} else {
    // else branch
    __join_0.accept(null);
    return;
}
```

### Example 4: `x = factorial(3) + factorial(4)`

A variable assignment where the RHS contains two function calls. `liftExpr` recurses into the binary expression, lifting each operand left-to-right via `liftArgs`:

```java
factorial(3, (__arg0) -> {
    factorial(4, (__arg1) -> {
        x[0] = (__arg0 + __arg1);
        // rest continues here
    });
});
```

---

## Known Limitations

- Functions must have at least one argument (grammar constraint — `argumentList` requires at least one expression).
- The `main` function does not receive a continuation since it maps directly to Java's `public static void main(String[] args)`.
- The indentation of the generated Java code is not always clean. This is a consequence of the compilation model — `compileStatements` builds code recursively from the end of the statement list backwards, embedding each statement's code inside the continuation of the next. Since indentation is applied at generation time and statements are nested rather than sequential, the output can appear misaligned. The generated code is always syntactically and semantically correct.