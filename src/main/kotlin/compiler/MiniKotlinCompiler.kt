package org.example.compiler

import MiniKotlinBaseVisitor
import MiniKotlinParser

class MiniKotlinCompiler : MiniKotlinBaseVisitor<String>() {

    fun compile(program: MiniKotlinParser.ProgramContext, className: String = "MiniProgram"): String {
        var functions: String = ""
        for(func in program.functionDeclaration()){
            functions += compileFunction(func) + "\n\n"
        }
        return "public class ${className} {\n\n$functions}"
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
        val param_str = paramList.joinToString(", ")
            {"${javaType(it.type().text)}} ${it.IDENTIFIER().text}"}
        val block = compileBlock(function.block());
        return if (name == "main")
            "\tpublic static void main (String[] args) $block"
        else
            "TODO"
    }

    fun compileBlock(block: MiniKotlinParser.BlockContext): String {
        val statements = block.statement()
        var statements_str = "{\n"
        for (statement in statements) {
            statements_str += "\t\t" + compileStatement(statement) + "\n"
        }
        return statements_str + "\t}"
    }

    fun compileStatement(statement: MiniKotlinParser.StatementContext): String {
        return when {
            statement.variableDeclaration() != null -> { compileVariableDeclaration(statement.variableDeclaration()) }
            else -> "TODO"
        }
    }

    fun compileVariableDeclaration(var_decl: MiniKotlinParser.VariableDeclarationContext): String {
        val type = javaType(var_decl.type().text)
        val name = var_decl.IDENTIFIER().text
        val expression = compileExpression(var_decl.expression())
        return "$type $name = $expression;"
    }

    fun compileExpression(expression: MiniKotlinParser.ExpressionContext): String {
        return when (expression) {
            is MiniKotlinParser.PrimaryExprContext -> compilePrimary(expression.primary())
            else -> "TODO"
        }
    }

    fun compilePrimary(primary: MiniKotlinParser.PrimaryContext): String {
        return when (primary) {
            is MiniKotlinParser.IntLiteralContext -> primary.INTEGER_LITERAL().text
            is MiniKotlinParser.BoolLiteralContext -> primary.BOOLEAN_LITERAL().text
            is MiniKotlinParser.StringLiteralContext -> primary.STRING_LITERAL().text
            is MiniKotlinParser.IdentifierExprContext -> primary.IDENTIFIER().text
            else -> "TODO"
        }
    }

}
