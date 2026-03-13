package org.example.compiler

import MiniKotlinBaseVisitor
import MiniKotlinParser

class MiniKotlinCompiler : MiniKotlinBaseVisitor<String>() {

    private var argCounter = 0

    private fun allocArg(): Int { return argCounter++; }

    fun compile(program: MiniKotlinParser.ProgramContext, className: String = "MiniProgram"): String {
        var functions = ""
        for (func in program.functionDeclaration()) {
            functions += compileFunction(func, "\t") + "\n\n"
        }
        return "public class $className {\n\n$functions}"
    }

    fun javaType(kotlinType: String) = when (kotlinType) {
        "Int" -> "Integer"
        "Boolean" -> "Boolean"
        "String" -> "String"
        "Unit" -> "Void"
        else -> kotlinType
    }

    fun compileFunction(function: MiniKotlinParser.FunctionDeclarationContext, indent: String): String {
        val name = function.IDENTIFIER().text
        val type = javaType(function.type().text)
        val paramList = function.parameterList()?.parameter() ?: emptyList()
        val paramStr = if (paramList.isEmpty()) {
            "Continuation<$type> __continuation"
        } else {
            paramList.joinToString(", ") { "${javaType(it.type().text)} ${it.IDENTIFIER().text}" } +
                    ", Continuation<$type> __continuation"
        }
        val block = compileBlock(function.block(), indent)
        return if (name == "main")
            "${indent}public static void main(String[] args) $block"
        else
            "${indent}public static void $name($paramStr) $block"
    }

    fun compileBlock(block: MiniKotlinParser.BlockContext, indent: String): String {
        val inner = "$indent\t"
        val statements = block.statement()
        val result = "{\n" + compileStatements(statements, 0, inner) + "$indent}"
        return result
    }

    fun compileStatements(statements: List<MiniKotlinParser.StatementContext>, index: Int, indent: String): String {
        if (index >= statements.size) return ""
        val rest = compileStatements(statements, index + 1, indent)
        return compileStatement(statements[index], rest, indent)
    }

    fun compileStatement(statement: MiniKotlinParser.StatementContext, rest: String, indent: String): String {
        return when {
            statement.variableDeclaration() != null ->
                compileVariableDeclaration(statement.variableDeclaration(), rest, indent)
            statement.variableAssignment() != null ->
                compileVariableAssignment(statement.variableAssignment(), rest, indent)
            statement.returnStatement() != null ->
                indent + compileReturnStatement(statement.returnStatement(), indent)
            statement.whileStatement() != null ->
                indent + compileWhileStatement(statement.whileStatement(), indent) + "\n" + rest
            statement.ifStatement() != null ->
                compileIfStatement(statement.ifStatement(), rest, indent)
            statement.expression() != null &&
                    statement.expression() is MiniKotlinParser.FunctionCallExprContext ->
                compileFunctionCall(
                    statement.expression() as MiniKotlinParser.FunctionCallExprContext,
                    rest, indent
                )
            else -> throw IllegalArgumentException("unknown statement $statement")
        }
    }

    fun compileVariableDeclaration(
        varDecl: MiniKotlinParser.VariableDeclarationContext,
        rest: String,
        indent: String
    ): String {
        val type = javaType(varDecl.type().text)
        val name = varDecl.IDENTIFIER().text
        return if (containsCall(varDecl.expression())) {
            liftExpr(varDecl.expression(), indent) { result ->
                "$indent$type $name = $result;\n$rest"
            }
        } else {
            "$indent$type $name = ${compileExpression(varDecl.expression())};\n$rest"
        }
    }

    fun compileExpression(expression: MiniKotlinParser.ExpressionContext): String {
        return when (expression) {
            is MiniKotlinParser.PrimaryExprContext -> compilePrimary(expression.primary())
            is MiniKotlinParser.NotExprContext -> "!" + compileExpression(expression.expression())
            else -> compileBinExpression(expression)
        }
    }

    fun compileFunctionCall(
        expression: MiniKotlinParser.FunctionCallExprContext,
        rest: String,
        indent: String,
        arg: String = "__arg${allocArg()}"
    ): String {
        var name = expression.IDENTIFIER().text
        val argList = expression.argumentList()?.expression() ?: emptyList()
        if (name == "println") name = "Prelude.println"
        return liftArgs(argList, 0, indent, emptyList()) { argExprs ->
            val argStr = argExprs.joinToString(", ")
            "$indent$name($argStr, ($arg) -> {\n$rest$indent});\n"
        }
    }

    fun compilePrimary(primary: MiniKotlinParser.PrimaryContext): String {
        return when (primary) {
            is MiniKotlinParser.IntLiteralContext -> primary.INTEGER_LITERAL().text
            is MiniKotlinParser.BoolLiteralContext -> primary.BOOLEAN_LITERAL().text
            is MiniKotlinParser.StringLiteralContext -> primary.STRING_LITERAL().text
            is MiniKotlinParser.IdentifierExprContext -> primary.IDENTIFIER().text
            is MiniKotlinParser.ParenExprContext -> "(" + compileExpression(primary.expression()) + ")"
            else -> throw IllegalArgumentException("invalid primary expression")
        }
    }

    fun compileBinExpression(expression: MiniKotlinParser.ExpressionContext): String {
        return when (expression) {
            is MiniKotlinParser.MulDivExprContext -> when {
                expression.DIV() != null -> resolveBinExpression(expression.expression(0), expression.expression(1), "/")
                expression.MULT() != null -> resolveBinExpression(expression.expression(0), expression.expression(1), "*")
                expression.MOD() != null -> resolveBinExpression(expression.expression(0), expression.expression(1), "%")
                else -> throw IllegalArgumentException("invalid bin expression")
            }
            is MiniKotlinParser.AddSubExprContext -> when {
                expression.PLUS() != null -> resolveBinExpression(expression.expression(0), expression.expression(1), "+")
                expression.MINUS() != null -> resolveBinExpression(expression.expression(0), expression.expression(1), "-")
                else -> throw IllegalArgumentException("invalid bin expression")
            }
            is MiniKotlinParser.ComparisonExprContext -> when {
                expression.GE() != null -> resolveBinExpression(expression.expression(0), expression.expression(1), ">=")
                expression.GT() != null -> resolveBinExpression(expression.expression(0), expression.expression(1), ">")
                expression.LT() != null -> resolveBinExpression(expression.expression(0), expression.expression(1), "<")
                expression.LE() != null -> resolveBinExpression(expression.expression(0), expression.expression(1), "<=")
                else -> throw IllegalArgumentException("invalid comparison expression")
            }
            is MiniKotlinParser.EqualityExprContext -> when {
                expression.EQ() != null -> resolveBinExpression(expression.expression(0), expression.expression(1), "==")
                expression.NEQ() != null -> resolveBinExpression(expression.expression(0), expression.expression(1), "!=")
                else -> throw IllegalArgumentException("invalid equality expression")
            }
            is MiniKotlinParser.AndExprContext ->
                if (expression.AND() != null) resolveBinExpression(expression.expression(0), expression.expression(1), "&&")
                else throw IllegalArgumentException("invalid bin expression")
            is MiniKotlinParser.OrExprContext ->
                if (expression.OR() != null) resolveBinExpression(expression.expression(0), expression.expression(1), "||")
                else throw IllegalArgumentException("invalid bin expression")
            else -> throw IllegalArgumentException("invalid expression")
        }
    }

    fun resolveBinExpression(
        left: MiniKotlinParser.ExpressionContext,
        right: MiniKotlinParser.ExpressionContext,
        op: String
    ): String {
        return compileExpression(left) + " " + op + " " + compileExpression(right)
    }

    fun compileVariableAssignment(varAssign: MiniKotlinParser.VariableAssignmentContext, rest: String, indent: String): String {
        val name = varAssign.IDENTIFIER().text
        return if (containsCall(varAssign.expression())) {
            liftExpr(varAssign.expression(), indent) { result ->
                "$indent$name = $result;\n$rest"
            }
        } else {
            "$indent$name = ${compileExpression(varAssign.expression())};\n$rest"
        }
    }

    fun compileReturnStatement(returnStatement: MiniKotlinParser.ReturnStatementContext, indent: String): String {
        if (returnStatement.expression() == null) {
            return "__continuation.accept(null);\n${indent}return;"
        }
        return liftExpr(returnStatement.expression(), indent) { result ->
            "__continuation.accept($result);\n${indent}return;"
        }
    }

    fun compileWhileStatement(whileStatement: MiniKotlinParser.WhileStatementContext, indent: String): String {
        val expression = compileExpression(whileStatement.expression())
        val block = compileWhileBlock(whileStatement.block(), indent)
        return "while ($expression) $block"
    }

    fun compileIfStatement(ifStatement: MiniKotlinParser.IfStatementContext, rest: String, indent: String): String {
        return if (containsCall(ifStatement.expression())) {
            liftExpr(ifStatement.expression(), indent) { cond ->
                val block = compileIfBlock(ifStatement.block()[0], indent)
                var result = "${indent}if ($cond) $block"
                if (ifStatement.ELSE() != null) {
                    result += "\n${indent}else ${compileIfBlock(ifStatement.block()[1], indent)}"
                }
                result + "\n$rest"
            }
        } else {
            val expression = compileExpression(ifStatement.expression())
            val block = compileIfBlock(ifStatement.block()[0], indent)
            var result = "${indent}if ($expression) $block"
            if (ifStatement.ELSE() != null) {
                result += "\n${indent}else ${compileIfBlock(ifStatement.block()[1], indent)}"
            }
            result + "\n$rest"
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
        consume: (String) -> String
    ): String {
        return when {
            !containsCall(expression) -> consume(compileExpression(expression))
            expression is MiniKotlinParser.FunctionCallExprContext -> {
                val tmp = "__arg${allocArg()}"
                val funcName = if (expression.IDENTIFIER().text == "println") "Prelude.println"
                else expression.IDENTIFIER().text
                val argList = expression.argumentList()?.expression() ?: emptyList()
                val inner = "$indent\t"
                liftArgs(argList, 0, indent, emptyList()) { argExprs ->
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
                liftExpr(left, indent) { l ->
                    liftExpr(right, indent) { r ->
                        consume("($l $op $r)")
                    }
                }
            }
            expression is MiniKotlinParser.NotExprContext -> {
                liftExpr(expression.expression(), indent) { inner ->
                    consume("!$inner")
                }
            }
            else -> consume(compileExpression(expression))
        }
    }

    fun liftArgs(
        args: List<MiniKotlinParser.ExpressionContext>,
        index: Int,
        indent: String,
        acc: List<String>,
        consume: (List<String>) -> String
    ): String {
        if (index >= args.size) return consume(acc)
        return liftExpr(args[index], indent) { argCode ->
            liftArgs(args, index + 1, indent, acc + argCode, consume)
        }
    }

    fun compileWhileBlock(block: MiniKotlinParser.BlockContext, indent: String): String {
        val inner = "$indent\t"
        val statements = block.statement()
        val body = statements.joinToString("\n") {
            compileStatement(it, "", inner)
        }
        return "{\n$body\n$indent}"
    }

    fun compileIfBlock(block: MiniKotlinParser.BlockContext, indent: String): String {
        val inner = "$indent\t"
        val statements = block.statement()
        val body = statements.joinToString("\n") {
            compileStatement(it, "", inner)
        }
        return "{\n$body\n$indent}"
    }
}