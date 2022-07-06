package io.github.qaralotte.meru.processor;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.expr.*;
import com.sun.source.tree.Tree;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.TreeTranslator;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.ListBuffer;
import io.github.qaralotte.meru.annotation.FormatString;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import java.util.Optional;
import java.util.Set;

@SupportedAnnotationTypes("io.github.qaralotte.meru.annotation.FormatString")
@SupportedSourceVersion(SourceVersion.RELEASE_8)
public class FormatStringProcessor extends BaseProcessor {

    /*
      1                 -> 整数
      1.14514           -> 浮点数
      'h'               -> 字符
      "1"               -> 字符串
      true              -> 布尔值
      a                 -> 变量
      "1" + a           -> 二元表达式
      a == b ? a : b    -> 三元表达式
      A.call()          -> 方法调用
      A.b               -> 成员调用
      arr[i]            -> 数组索引
      (String) a        -> 类型转换
      null              -> 空指针
     */

    /**
     * 二元运算符转换
     * @param operator JavaParser 下的运算符
     * @return JCTree 下的运算符
     */
    private JCTree.Tag convertOperation(BinaryExpr.Operator operator) {
        switch (operator) {
            case OR: return JCTree.Tag.OR;
            case AND: return JCTree.Tag.AND;
            case BINARY_OR: return JCTree.Tag.BITOR;
            case BINARY_AND: return JCTree.Tag.BITAND;
            case XOR: return JCTree.Tag.BITXOR;
            case EQUALS: return JCTree.Tag.EQ;
            case NOT_EQUALS: return JCTree.Tag.NE;
            case LESS: return JCTree.Tag.LT;
            case GREATER: return JCTree.Tag.GT;
            case LESS_EQUALS: return JCTree.Tag.LE;
            case GREATER_EQUALS: return JCTree.Tag.GE;
            case LEFT_SHIFT: return JCTree.Tag.SL;
            case SIGNED_RIGHT_SHIFT: return JCTree.Tag.SR;
            case UNSIGNED_RIGHT_SHIFT: return JCTree.Tag.USR;
            case PLUS: return JCTree.Tag.PLUS;
            case MINUS: return JCTree.Tag.MINUS;
            case MULTIPLY: return JCTree.Tag.MUL;
            case DIVIDE: return JCTree.Tag.DIV;
            case REMAINDER: return JCTree.Tag.MOD;
            default: return null;
        }
    }

    /**
     * 分开处理表达式对象
     * @param expression 表达式对象
     * @return 表达式 ast.node
     */
    private JCTree.JCExpression parseExpression(Expression expression) {

        if (expression.isIntegerLiteralExpr()) {
            // 整数
            IntegerLiteralExpr expr = expression.asIntegerLiteralExpr();
            return treeMaker.Literal(expr.asNumber());
        } else if (expression.isDoubleLiteralExpr()) {
            // 浮点数
            DoubleLiteralExpr expr = expression.asDoubleLiteralExpr();
            return treeMaker.Literal(expr.asDouble());
        } else if (expression.isCharLiteralExpr()) {
            // 字符
            CharLiteralExpr expr = expression.asCharLiteralExpr();
            return treeMaker.Literal(expr.asChar());
        } else if (expression.isStringLiteralExpr()) {
            // 字符串
            StringLiteralExpr expr = expression.asStringLiteralExpr();
            return parseStringLiteral(expr.asString());
        } else if (expression.isBooleanLiteralExpr()) {
            // 布尔值
            BooleanLiteralExpr expr = expression.asBooleanLiteralExpr();
            return treeMaker.Literal(expr.getValue());
        } else if (expression.isNameExpr()) {
            // 变量
            NameExpr expr = expression.asNameExpr();
            return treeMaker.Ident(names.fromString(expr.getNameAsString()));
        } else if (expression.isBinaryExpr()) {
            // 二元表达式
            BinaryExpr expr = expression.asBinaryExpr();
            JCTree.Tag operation = convertOperation(expr.getOperator());
            return treeMaker.Binary(
                    operation,
                    parseExpression(expr.getLeft()),
                    parseExpression(expr.getRight())
            );
        } else if (expression.isConditionalExpr()) {
            // 三元表达式
            ConditionalExpr expr = expression.asConditionalExpr();
            return treeMaker.Conditional(
                    parseExpression(expr.getCondition()),
                    parseExpression(expr.getThenExpr()),
                    parseExpression(expr.getElseExpr())
            );
        } else if (expression.isEnclosedExpr()) {
            // 括号优先
            return parseExpression(expression.asEnclosedExpr().getInner());
        } else if (expression.isMethodCallExpr()) {
            // 方法调用
            MethodCallExpr expr = expression.asMethodCallExpr();

            // 如果未显式声明调用方, 那么一定是当前类里的方法
            String scope = expr.getScope().isPresent() ? expr.getScope().get().toString() : "this";

            // 参数列表
            ListBuffer<JCTree.JCExpression> args = new ListBuffer<>();
            for (int i = 0; i < expr.getArguments().size(); i++) {
                args.append(parseExpression(expr.getArgument(i)));
            }

            return treeMaker.Apply(
                    List.nil(),
                    memberAccess(scope + "." + expr.getNameAsString()),
                    args.toList()
            );
        } else if (expression.isFieldAccessExpr()) {
            // 成员调用
            FieldAccessExpr expr = expression.asFieldAccessExpr();
            return treeMaker.Select(
                    treeMaker.Ident(names.fromString(expr.getScope().toString())),
                    names.fromString(expr.getNameAsString())
            );
        } else if (expression.isArrayAccessExpr()) {
            // 数组索引
            ArrayAccessExpr expr = expression.asArrayAccessExpr();
            return treeMaker.Indexed(
                    parseExpression(expr.getName()),
                    parseExpression(expr.getIndex())
            );
        } else if (expression.isCastExpr()) {
            // 类型转换
            CastExpr expr = expression.asCastExpr();
            return treeMaker.TypeCast(
                    treeMaker.Ident(names.fromString(expr.getTypeAsString())),
                    parseExpression(expr.getExpression())
            );
        } else {
            messager.printMessage(Diagnostic.Kind.ERROR, "不支持的表达式: " + expression);
            return null;
        }
    }

    /**
     * 处理表达式字符串
     * @param exprStr 表达式字符串
     * @return 表达式 ast.node
     */
    private JCTree.JCExpression parseExprStringLiteral(String exprStr) {

        // 表达式内反引号当引号使用 (谁想看到成片的反斜杠？)
        exprStr = exprStr.replace("`", "\"");

        JavaParser javaParser = new JavaParser();
        ParseResult<Expression> parseExpression = javaParser.parseExpression(exprStr);
        Optional<Expression> expressionResult = parseExpression.getResult();

        if (expressionResult.isPresent()) {
            Expression expr = expressionResult.get();
            return parseExpression(expr);
        } else {
            messager.printMessage(Diagnostic.Kind.ERROR, exprStr + " 不是一个正确的 java 表达式");
            return null;
        }
    }

    /**
     * 处理模版字符串
     * @param lit 需要格式化的字符串，{} 内的字符串需要处理成表达式 (parseExpressionString)
     * @return 表达式 ast.node
     */
    private JCTree.JCExpression parseStringLiteral(String lit) {
        // 如果字面量是空字符串，直接返回空字符串
        if (lit.isEmpty()) return treeMaker.Literal("");

        JCTree.JCExpression finalExpr = null;

        // 在占位符之前的字符串位置
        int lastEnd = 0;
        for (int i = 0; i < lit.length(); i++) {

            // 如果遇到 '{' 代表进入下一层
            if (lit.charAt(i) == '{') {
                // 前面未格式化的字符串
                JCTree.JCLiteral beforeLit = treeMaker.Literal(lit.substring(lastEnd, i));

                // 向后找配对的 '}'
                int level = 0;
                for (int j = i + 1; j < lit.length(); j++) {
                    // 如果有嵌套的占位符，层数 + 1
                    if (lit.charAt(j) == '{') level += 1;
                    if (lit.charAt(j) == '}') {
                        // 如果层数 = 0, 则代表配对成功
                        // 开始处理占位符内的内容
                        if (level == 0) {
                            String placeholder = lit.substring(i + 1, j);
                            JCTree.JCExpression expression = parseExprStringLiteral(placeholder);

                            // 将前面字面量部分和表达式部分相加
                            if (finalExpr == null) {
                                // 如果之前并没有任何相加语句，则这个是第一次相加
                                finalExpr = treeMaker.Binary(
                                        JCTree.Tag.PLUS,
                                        beforeLit,
                                        expression
                                );
                            } else {
                                // 如果之前有相加语句，则与之前的语句进行相加
                                finalExpr = treeMaker.Binary(
                                        JCTree.Tag.PLUS,
                                        finalExpr,
                                        treeMaker.Binary(
                                                JCTree.Tag.PLUS,
                                                beforeLit,
                                                expression
                                        )
                                );
                            }

                            // 从下一个字符继续开始循环
                            i = j;
                            lastEnd = j + 1;
                            break;
                        }
                    }
                }
            }
        }

        // 在最后可能还有字符串，需要再添加进来
        if (lastEnd < lit.length()) {
            if (finalExpr == null) {
                finalExpr = treeMaker.Literal(lit.substring(lastEnd));
            } else {
                finalExpr = treeMaker.Binary(
                        JCTree.Tag.PLUS,
                        finalExpr,
                        treeMaker.Literal(lit.substring(lastEnd))
                );
            }
        }

        return finalExpr;
    }

    /**
     * 检查模版字符串格式是否正确
     * @param lit 模版字符串
     * @return 是否正确
     */
    private boolean checkIsCorrect(String lit) {
        int level = 0;
        for (int i = 0; i < lit.length(); i++) {
            if (level < 0) return false;
            if (lit.charAt(i) == '{') level += 1;
            if (lit.charAt(i) == '}') level -= 1;
        }
        return level == 0;
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {

        // 处理有 @FormatString 注解的元素
        for (Element element : roundEnv.getElementsAnnotatedWith(FormatString.class)) {
            JCTree jcTree = trees.getTree(element);

            jcTree.accept(new TreeTranslator() {

                @Override
                public void visitLiteral(JCTree.JCLiteral jcLiteral) {

                    if (jcLiteral.getKind() == Tree.Kind.STRING_LITERAL) {

                        String lit = jcLiteral.value.toString();

                        if (!checkIsCorrect(lit)) {
                            messager.printMessage(Diagnostic.Kind.ERROR, "不合法的模版字符串: " + lit);
                            return;
                        }

                        JCTree.JCExpression expression = parseStringLiteral(lit);

                        expression.accept(new TreeTranslator());
                        result = expression;

                        // super.visitLiteral(jcLiteral);
                    } else {
                        super.visitLiteral(jcLiteral);
                    }
                }

            });
        }

        return false;
    }

}
