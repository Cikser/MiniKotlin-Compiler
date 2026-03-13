package org.example.compiler

import MiniKotlinBaseVisitor
import MiniKotlinParser

class MiniKotlinCompiler : MiniKotlinBaseVisitor<String>() {

    private var argCounter = 0
    private var whileCounter = 0
    private var joinCounter = 0
    private val helperMethods = mutableListOf<String>()

    private fun allocArg(): Int { return argCounter++ }
    private fun allocWhile(): Int { return whileCounter++ }
    private fun allocJoin(): Int { return joinCounter++ }

    fun compile(program: MiniKotlinParser.ProgramContext, className: String = "MiniProgram"): String {
        helperMethods.clear()
        argCounter = 0
        whileCounter = 0
        joinCounter = 0
        var functions = ""
        for (func in program.functionDeclaration()) {
            functions += compileFunction(func, "\t") + "\n\n"
        }
        val helpers = if (helperMethods.isEmpty()) "" else helperMethods.joinToString("\n") + "\n\n"
        return "public class $className {\n\n$helpers$functions}"
    }

    fun javaType(kotlinType: String) = when (kotlinType) {
        "Int" -> "Integer"
        "Boolean" -> "Boolean"
        "String" -> "String"
        "Unit" -> "Void"
        else -> kotlinType
    }

    fun compileFunction(
        function: MiniKotlinParser.FunctionDeclarationContext,
        indent: String
    ): String {
        val name = function.IDENTIFIER().text
        val type = javaType(function.type().text)
        val paramList = function.parameterList()?.parameter() ?: emptyList()
        val initialScope: Map<String, String> = paramList.associate {
            it.IDENTIFIER().text to (javaType(it.type().text) + "[]")
        }
        val shadowParams = paramList.joinToString("\n") {
            val pName = it.IDENTIFIER().text
            val pType = javaType(it.type().text)
            "$indent\tfinal $pType[] $pName = { _$pName };"
        }
        val paramStrWithShadow = if (paramList.isEmpty()) {
            "Continuation<$type> __continuation"
        } else {
            paramList.joinToString(", ") { "${javaType(it.type().text)} _${it.IDENTIFIER().text}" } +
                    ", Continuation<$type> __continuation"
        }
        val block = compileBlock(function.block(), "", indent, initialScope)
        val finalBlock = if (shadowParams.isEmpty()) block
        else "{\n$shadowParams\n${block.substring(1)}"

        return if (name == "main")
            "${indent}public static void main(String[] args) $block"
        else
            "${indent}public static void $name($paramStrWithShadow) $finalBlock"
    }

    fun compileBlock(
        block: MiniKotlinParser.BlockContext,
        rest: String,
        indent: String,
        scope: Map<String, String>
    ): String {
        val innerCode = compileStatements(block.statement(), 0, "$indent\t", scope, rest)
        return "{\n$innerCode$indent}"
    }

    fun compileStatements(
        statements: List<MiniKotlinParser.StatementContext>,
        index: Int,
        indent: String,
        scope: Map<String, String>,
        finalRest: String = ""
    ): String {
        if (index >= statements.size) return finalRest
        val newScope = if (statements[index].variableDeclaration() != null) {
            val decl = statements[index].variableDeclaration()
            scope + (decl.IDENTIFIER().text to javaType(decl.type().text) + "[]")
        } else scope
        val rest = compileStatements(statements, index + 1, indent, newScope, finalRest)
        return compileStatement(statements[index], rest, indent, scope)
    }

    fun compileStatement(
        statement: MiniKotlinParser.StatementContext,
        rest: String,
        indent: String,
        scope: Map<String, String>
    ): String {
        return when {
            statement.variableDeclaration() != null ->
                compileVariableDeclaration(statement.variableDeclaration(), rest, indent, scope)
            statement.variableAssignment() != null ->
                compileVariableAssignment(statement.variableAssignment(), rest, indent, scope)
            statement.returnStatement() != null ->
                indent + compileReturnStatement(statement.returnStatement(), indent, scope)
            statement.whileStatement() != null ->
                compileWhileStatement(statement.whileStatement(), rest, indent, scope)
            statement.ifStatement() != null ->
                compileIfStatement(statement.ifStatement(), rest, indent, scope)
            statement.expression() != null &&
                    statement.expression() is MiniKotlinParser.FunctionCallExprContext ->
                compileFunctionCall(
                    statement.expression() as MiniKotlinParser.FunctionCallExprContext,
                    rest, indent, scope
                )
            else -> throw IllegalArgumentException("unknown statement $statement")
        }
    }

    fun compileVariableDeclaration(
        varDecl: MiniKotlinParser.VariableDeclarationContext,
        rest: String,
        indent: String,
        scope: Map<String, String>
    ): String {
        val type = javaType(varDecl.type().text)
        val name = varDecl.IDENTIFIER().text
        return if (containsCall(varDecl.expression())) {
            liftExpr(varDecl.expression(), indent, scope) { result ->
                "$indent$type[] $name = { $result };\n$rest"
            }
        } else {
            "$indent$type[] $name = { ${compileExpression(varDecl.expression(), scope)} };\n$rest"
        }
    }

    fun compileExpression(
        expression: MiniKotlinParser.ExpressionContext,
        scope: Map<String, String>
    ): String {
        return when (expression) {
            is MiniKotlinParser.PrimaryExprContext -> compilePrimary(expression.primary(), scope)
            is MiniKotlinParser.NotExprContext -> "!" + compileExpression(expression.expression(), scope)
            else -> compileBinExpression(expression, scope)
        }
    }

    fun compileFunctionCall(
        expression: MiniKotlinParser.FunctionCallExprContext,
        rest: String,
        indent: String,
        scope: Map<String, String>,
        arg: String = "__arg${allocArg()}"
    ): String {
        var name = expression.IDENTIFIER().text
        val argList = expression.argumentList()?.expression() ?: emptyList()
        if (name == "println") name = "Prelude.println"
        return liftArgs(argList, 0, indent, emptyList(), scope) { argExprs ->
            val argStr = argExprs.joinToString(", ")
            "$indent$name($argStr, ($arg) -> {\n$rest$indent});\n"
        }
    }

    fun compilePrimary(
        primary: MiniKotlinParser.PrimaryContext,
        scope: Map<String, String>
    ): String {
        return when (primary) {
            is MiniKotlinParser.IntLiteralContext -> primary.INTEGER_LITERAL().text
            is MiniKotlinParser.BoolLiteralContext -> primary.BOOLEAN_LITERAL().text
            is MiniKotlinParser.StringLiteralContext -> primary.STRING_LITERAL().text
            is MiniKotlinParser.IdentifierExprContext -> {
                val name = primary.IDENTIFIER().text
                if (scope[name]?.endsWith("[]") == true) "$name[0]" else name
            }
            is MiniKotlinParser.ParenExprContext -> "(" + compileExpression(primary.expression(), scope) + ")"
            else -> throw IllegalArgumentException("invalid primary expression")
        }
    }

    fun compileBinExpression(
        expression: MiniKotlinParser.ExpressionContext,
        scope: Map<String, String>
    ): String {
        return when (expression) {
            is MiniKotlinParser.MulDivExprContext -> when {
                expression.DIV() != null -> resolveBinExpression(expression.expression(0), expression.expression(1), "/", scope)
                expression.MULT() != null -> resolveBinExpression(expression.expression(0), expression.expression(1), "*", scope)
                expression.MOD() != null -> resolveBinExpression(expression.expression(0), expression.expression(1), "%", scope)
                else -> throw IllegalArgumentException("invalid bin expression")
            }
            is MiniKotlinParser.AddSubExprContext -> when {
                expression.PLUS() != null -> resolveBinExpression(expression.expression(0), expression.expression(1), "+", scope)
                expression.MINUS() != null -> resolveBinExpression(expression.expression(0), expression.expression(1), "-", scope)
                else -> throw IllegalArgumentException("invalid bin expression")
            }
            is MiniKotlinParser.ComparisonExprContext -> when {
                expression.GE() != null -> resolveBinExpression(expression.expression(0), expression.expression(1), ">=", scope)
                expression.GT() != null -> resolveBinExpression(expression.expression(0), expression.expression(1), ">", scope)
                expression.LT() != null -> resolveBinExpression(expression.expression(0), expression.expression(1), "<", scope)
                expression.LE() != null -> resolveBinExpression(expression.expression(0), expression.expression(1), "<=", scope)
                else -> throw IllegalArgumentException("invalid comparison expression")
            }
            is MiniKotlinParser.EqualityExprContext -> when {
                expression.EQ() != null -> resolveBinExpression(expression.expression(0), expression.expression(1), "==", scope)
                expression.NEQ() != null -> resolveBinExpression(expression.expression(0), expression.expression(1), "!=", scope)
                else -> throw IllegalArgumentException("invalid equality expression")
            }
            is MiniKotlinParser.AndExprContext ->
                if (expression.AND() != null) resolveBinExpression(expression.expression(0), expression.expression(1), "&&", scope)
                else throw IllegalArgumentException("invalid bin expression")
            is MiniKotlinParser.OrExprContext ->
                if (expression.OR() != null) resolveBinExpression(expression.expression(0), expression.expression(1), "||", scope)
                else throw IllegalArgumentException("invalid bin expression")
            else -> throw IllegalArgumentException("invalid expression")
        }
    }

    fun resolveBinExpression(
        left: MiniKotlinParser.ExpressionContext,
        right: MiniKotlinParser.ExpressionContext,
        op: String,
        scope: Map<String, String>
    ): String {
        return compileExpression(left, scope) + " " + op + " " + compileExpression(right, scope)
    }

    fun compileVariableAssignment(
        varAssign: MiniKotlinParser.VariableAssignmentContext,
        rest: String,
        indent: String,
        scope: Map<String, String>
    ): String {
        val name = varAssign.IDENTIFIER().text
        val isArray = scope[name]?.endsWith("[]") == true
        val target = if (isArray) "$name[0]" else name

        return if (containsCall(varAssign.expression())) {
            liftExpr(varAssign.expression(), indent, scope) { result ->
                "$indent$target = $result;\n$rest"
            }
        } else {
            "$indent$target = ${compileExpression(varAssign.expression(), scope)};\n$rest"
        }
    }

    fun compileReturnStatement(
        returnStatement: MiniKotlinParser.ReturnStatementContext,
        indent: String,
        scope: Map<String, String>
    ): String {
        if (returnStatement.expression() == null) {
            return "__continuation.accept(null);\n${indent}return;"
        }
        return liftExpr(returnStatement.expression(), indent, scope) { result ->
            "__continuation.accept($result);\n${indent}return;"
        }
    }

    fun compileWhileStatement(
        whileStatement: MiniKotlinParser.WhileStatementContext,
        rest: String,
        indent: String,
        scope: Map<String, String>
    ): String {
        val condExpr = whileStatement.expression()
        val helperName = "__while_${allocWhile()}"
        val bodyIndent = "\t\t"
        val scopeParams = scope.entries.joinToString(", ") { "${it.value} ${it.key}" }
        val scopeArgs = scope.keys.joinToString(", ")
        val paramStr = if (scope.isEmpty()) "Continuation<Void> __k"
        else "$scopeParams, Continuation<Void> __k"
        val kArg = "__arg${allocArg()}"
        val recurseArgs = if (scope.isEmpty()) "__k" else "$scopeArgs, __k"
        val recurse = "$bodyIndent$helperName($recurseArgs);\n"
        val bodyCode = compileBlock(whileStatement.block(), recurse, indent, scope)
        val helperBody = liftExpr(condExpr, bodyIndent, scope) { cond ->
            "${bodyIndent}if ($cond) {\n$bodyCode${bodyIndent}}\n" +
                    "${bodyIndent}else {\n${bodyIndent}\t__k.accept(null);\n${bodyIndent}}\n"
        }
        helperMethods.add("\tpublic static void $helperName($paramStr) {\n$helperBody\t}\n")
        val callArgs = if (scope.isEmpty()) "($kArg) -> {\n$rest$indent}"
        else "$scopeArgs, ($kArg) -> {\n$rest$indent}"
        return "$indent$helperName($callArgs);\n"
    }

    fun compileIfStatement(
        ifStmt: MiniKotlinParser.IfStatementContext,
        rest: String,
        indent: String,
        scope: Map<String, String>
    ): String {
        return liftExpr(ifStmt.expression(), indent, scope) { condition ->
            if (rest.trim().isEmpty()) {
                val thenPart = compileBlock(ifStmt.block(0), "", indent, scope)
                val elsePart = if (ifStmt.block().size > 1) {
                    " else " + compileBlock(ifStmt.block(1), "", indent, scope)
                } else ""
                "${indent}if ($condition) $thenPart$elsePart"
            } else {
                val joinName = "__join_${allocJoin()}"
                val joinPointDef = "${indent}Continuation<Void> $joinName = (__arg${allocArg()}) -> {\n$rest$indent};\n"
                val callJoin = "$indent$joinName.accept(null);\n${indent}return;\n"

                val thenPart = compileBlock(ifStmt.block(0), callJoin, indent, scope)
                val elsePart = if (ifStmt.block().size > 1) {
                    compileBlock(ifStmt.block(1), callJoin, indent, scope)
                } else "{\n$callJoin$indent}"
                "$joinPointDef\n${indent}if ($condition) $thenPart else $elsePart"
            }
        }
    }

    fun containsCall(expression: MiniKotlinParser.ExpressionContext): Boolean {
        return when (expression) {
            is MiniKotlinParser.FunctionCallExprContext -> true
            is MiniKotlinParser.PrimaryExprContext -> false
            is MiniKotlinParser.NotExprContext -> containsCall(expression.expression())
            is MiniKotlinParser.MulDivExprContext ->
                containsCall(expression.expression(0)) || containsCall(expression.expression(1))
            is MiniKotlinParser.AddSubExprContext ->
                containsCall(expression.expression(0)) || containsCall(expression.expression(1))
            is MiniKotlinParser.ComparisonExprContext ->
                containsCall(expression.expression(0)) || containsCall(expression.expression(1))
            is MiniKotlinParser.EqualityExprContext ->
                containsCall(expression.expression(0)) || containsCall(expression.expression(1))
            is MiniKotlinParser.AndExprContext ->
                containsCall(expression.expression(0)) || containsCall(expression.expression(1))
            is MiniKotlinParser.OrExprContext ->
                containsCall(expression.expression(0)) || containsCall(expression.expression(1))
            else -> false
        }
    }

    fun liftExpr(
        expression: MiniKotlinParser.ExpressionContext,
        indent: String,
        scope: Map<String, String>,
        consume: (String) -> String
    ): String {
        return when {
            !containsCall(expression) -> consume(compileExpression(expression, scope))
            expression is MiniKotlinParser.FunctionCallExprContext -> {
                val tmp = "__arg${allocArg()}"
                val funcName = if (expression.IDENTIFIER().text == "println") "Prelude.println"
                else expression.IDENTIFIER().text
                val argList = expression.argumentList()?.expression() ?: emptyList()
                val inner = "$indent\t"
                liftArgs(argList, 0, indent, emptyList(), scope) { argExprs ->
                    val argStr = argExprs.joinToString(", ")
                    "$indent$funcName($argStr, ($tmp) -> {\n$inner${consume(tmp)}\n$indent});"
                }
            }
            expression is MiniKotlinParser.MulDivExprContext ||
                    expression is MiniKotlinParser.AddSubExprContext ||
                    expression is MiniKotlinParser.ComparisonExprContext ||
                    expression is MiniKotlinParser.EqualityExprContext ||
                    expression is MiniKotlinParser.AndExprContext ||
                    expression is MiniKotlinParser.OrExprContext -> {
                val op = expression.getChild(1).text
                val left = expression.getChild(0) as MiniKotlinParser.ExpressionContext
                val right = expression.getChild(2) as MiniKotlinParser.ExpressionContext
                liftExpr(left, indent, scope) { l ->
                    liftExpr(right, indent, scope) { r ->
                        consume("($l $op $r)")
                    }
                }
            }
            expression is MiniKotlinParser.NotExprContext -> {
                liftExpr(expression.expression(), indent, scope) { inner ->
                    consume("!$inner")
                }
            }
            else -> consume(compileExpression(expression, scope))
        }
    }

    fun liftArgs(
        args: List<MiniKotlinParser.ExpressionContext>,
        index: Int,
        indent: String,
        acc: List<String>,
        scope: Map<String, String>,
        consume: (List<String>) -> String
    ): String {
        if (index >= args.size) return consume(acc)
        return liftExpr(args[index], indent, scope) { argCode ->
            liftArgs(args, index + 1, indent, acc + argCode, scope, consume)
        }
    }

}