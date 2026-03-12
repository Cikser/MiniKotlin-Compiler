package org.example.compiler

import MiniKotlinBaseVisitor
import MiniKotlinParser

class MiniKotlinCompiler : MiniKotlinBaseVisitor<String>() {

    private var tabs: Int = 1

    fun compile(program: MiniKotlinParser.ProgramContext, className: String = "MiniProgram"): String {
        var functions = ""
        for(func in program.functionDeclaration()){
            functions += compileFunction(func) + "\n\n"
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

    fun compileFunction(function: MiniKotlinParser.FunctionDeclarationContext): String {
        val name = function.IDENTIFIER().text
        val type = javaType(function.type().text)
        val paramList = function.parameterList()?.parameter() ?: emptyList()
        val paramStr = paramList.joinToString(", ")
            {"${javaType(it.type().text)} ${it.IDENTIFIER().text}"}
        val block = compileBlock(function.block())
        return if (name == "main")
            "${"\t".repeat(tabs)}public static void main (String[] args) $block"
        else
            "${"\t".repeat(tabs)}public static $type $name ($paramStr) $block"
    }

    fun compileBlock(block: MiniKotlinParser.BlockContext): String {
        tabs++;
        val statements = block.statement()
        var statementsStr = "{\n"
        for (statement in statements) {
            statementsStr += "\t".repeat(tabs) + compileStatement(statement) + "\n"
        }
        tabs--;
        return "$statementsStr\t}"
    }

    fun compileStatement(statement: MiniKotlinParser.StatementContext): String {
        return when {
            statement.variableDeclaration() != null -> compileVariableDeclaration(statement.variableDeclaration())
            statement.variableAssignment() != null -> compileVariableAssignment(statement.variableAssignment())
            statement.returnStatement() != null -> compileReturnStatement(statement.returnStatement())
            else -> "TODO"
        }
    }

    fun compileVariableDeclaration(varDecl: MiniKotlinParser.VariableDeclarationContext): String {
        val type = javaType(varDecl.type().text)
        val name = varDecl.IDENTIFIER().text
        val expression = compileExpression(varDecl.expression())
        return "$type $name = $expression;"
    }

    fun compileExpression(expression: MiniKotlinParser.ExpressionContext): String {
        return when (expression) {
            is MiniKotlinParser.FunctionCallExprContext -> compileFunctionCall(expression)
            is MiniKotlinParser.PrimaryExprContext -> compilePrimary(expression.primary())
            is MiniKotlinParser.NotExprContext -> "!" + compileExpression(expression.expression())
            else -> compileBinExpression(expression)
        }
    }

    fun compileFunctionCall(expression: MiniKotlinParser.FunctionCallExprContext): String {
        val name = expression.IDENTIFIER().text
        val argList = expression.argumentList()?.expression() ?: emptyList()
        val argStr = argList.joinToString(", ")
            {"${javaType(compileExpression(it))}"}
        return "$name($argStr)"
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
            is MiniKotlinParser.MulDivExprContext -> {
                when {
                    expression.DIV() != null -> resolveBinExpression(expression.expression(0),
                        expression.expression(1),"/")
                    expression.MULT() != null -> resolveBinExpression(expression.expression(0),
                        expression.expression(1),"*")
                    expression.MOD() != null -> resolveBinExpression(expression.expression(0),
                        expression.expression(1),"%")
                    else -> throw IllegalArgumentException("invalid bin expression")
                }
            }
            is MiniKotlinParser.AddSubExprContext -> {
                when {
                    expression.PLUS() != null -> resolveBinExpression(expression.expression(0),
                        expression.expression(1),"+")
                    expression.MINUS() != null -> resolveBinExpression(expression.expression(0),
                        expression.expression(1),"-")
                    else -> throw IllegalArgumentException("invalid bin expression")
                }
            }
            is MiniKotlinParser.ComparisonExprContext -> {
                when {
                    expression.GE() != null -> resolveBinExpression(expression.expression(0),
                        expression.expression(1),">=")
                    expression.GT() != null -> resolveBinExpression(expression.expression(0),
                        expression.expression(1),">")
                    expression.LT() != null -> resolveBinExpression(expression.expression(0),
                        expression.expression(1),"<")
                    expression.LE() != null -> resolveBinExpression(expression.expression(0),
                        expression.expression(1),"<=")
                    else -> throw IllegalArgumentException("invalid comparison expression")
                }
            }
            is MiniKotlinParser.EqualityExprContext -> {
                when {
                    expression.EQ() != null -> resolveBinExpression(expression.expression(0),
                        expression.expression(1),"==")
                    expression.NEQ() != null -> resolveBinExpression(expression.expression(0),
                        expression.expression(1),"!=")
                    else -> throw IllegalArgumentException("invalid equality expression")
                }
            }
            is MiniKotlinParser.AndExprContext -> {
                if (expression.AND() != null) resolveBinExpression(expression.expression(0),
                    expression.expression(1),"&&")
                else throw IllegalArgumentException("invalid bin expression")
            }
            is MiniKotlinParser.OrExprContext -> {
                if (expression.OR() != null) resolveBinExpression(expression.expression(0),
                    expression.expression(1),"||")
                else throw IllegalArgumentException("invalid bin expression")
            }
            else -> throw IllegalArgumentException("invalid expression")
        }
    }

    fun resolveBinExpression(left: MiniKotlinParser.ExpressionContext,
                             right: MiniKotlinParser.ExpressionContext,
                             op: String): String {
        return compileExpression(left) + " " + op + " " + compileExpression(right)
    }

    fun compileVariableAssignment(varAssign: MiniKotlinParser.VariableAssignmentContext): String {
        val name = varAssign.IDENTIFIER().text
        val expression = compileExpression(varAssign.expression())
        return "$name = $expression;"
    }

    fun compileReturnStatement(returnStatement: MiniKotlinParser.ReturnStatementContext): String {
        var expression = ""
        if (returnStatement.expression() != null) {
            expression = compileExpression(returnStatement.expression())
        }
        return "return $expression;"
    }

}
