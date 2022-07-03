package io.qaralotte.meru.processor;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.expr.*;
import com.sun.tools.javac.code.TypeTag;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.TreeTranslator;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.ListBuffer;
import io.qaralotte.meru.annotation.EnableFormat;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@SupportedAnnotationTypes("io.qaralotte.meru.annotation.EnableFormat")
@SupportedSourceVersion(SourceVersion.RELEASE_8)
public class FormatProcessor extends BaseProcessor {

    /*
      1            -> 整数
      1.14514      -> 浮点数
      'h'          -> 字符
      "1"          -> 字符串
      a            -> 变量
      "1" + a      -> 二元运算
      A.call()     -> 方法调用
      A.b          -> 成员调用
      arr[i]       -> 数组索引
      (String) a   -> 类型转换
      null         -> 空指针
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
     * 处理表达式对象
     * @param expression 表达式对象
     * @return 表达式 ast
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
            // todo 或许还需要进一步处理 format
            return treeMaker.Literal(expr.asString());
        } else if (expression.isNameExpr()) {
            // 变量
            NameExpr expr = expression.asNameExpr();
            return treeMaker.Ident(names.fromString(expr.getNameAsString()));
        } else if (expression.isBinaryExpr()) {
            // 二元运算
            BinaryExpr expr = expression.asBinaryExpr();
            JCTree.Tag operation = convertOperation(expr.getOperator());
            return treeMaker.Binary(
                    operation,
                    parseExpression(expr.getLeft()),
                    parseExpression(expr.getRight())
            );
        } else if (expression.isEnclosedExpr()) {
            // 括号优先
            return parseExpression(expression.asEnclosedExpr().getInner());
        } else if (expression.isMethodCallExpr()) {
            // 方法调用
            MethodCallExpr expr = expression.asMethodCallExpr();

            // 如果未显式声明调用方, 那么一定是当前类里的方法
            String scope = expr.getScope().isPresent() ? expr.getScope().toString() : "this";

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
     * 处理表达式字符串生成 ast
     * @param exprStr 表达式字符串
     * @return 表达式 ast
     */
    private JCTree.JCExpression parseExpressionString(String exprStr) {

        JavaParser javaParser = new JavaParser();
        ParseResult<Expression> parseExpression = javaParser.parseExpression(exprStr);
        Optional<Expression> expressionResult = parseExpression.getResult();

        if (expressionResult.isPresent()) {
            Expression expr = expressionResult.get();
            return parseExpression(expr);
        } else {
            messager.printMessage(Diagnostic.Kind.ERROR, exprStr + " 不是一个正确的 java 表达式");
        }
        return null;
    }

    /**
     * 格式化字符串
     * @param lit 需要格式化的字符串，其中 {} 内的需要处理成表达式
     * @return 生成正确的表达式
     */
    private JCTree.JCExpression parseFormatString(String lit) {

        // 如果字面量是空字符串，直接返回空字符串
        if (lit.isEmpty()) return treeMaker.Literal("");

        // 找出所有 {} 之内的字符串
        Matcher matcher = Pattern.compile("\\{.*?}").matcher(lit);
        int lastEnd = 0;
        JCTree.JCExpression resultExpression = null;

        // 如果匹配到结果，则开始处理格式化
        while (matcher.find()) {
            int start = matcher.start();
            int end = matcher.end();

            // 开始处理格式化变量
            JCTree.JCExpression formatExpression;
            String expr = lit.substring(start + 1, end - 1);
            formatExpression = parseExpressionString(expr);

            // 上一个 end 到这一次 start 之间的字符串正常处理
            String lastEndToStart = lit.substring(lastEnd, start);
            JCTree.JCLiteral lastLiteral = treeMaker.Literal(lastEndToStart);

            // 将字符串与变量相加
            JCTree.JCExpression lastEndToStartExpr = treeMaker.Binary(
                    JCTree.Tag.PLUS,
                    lastLiteral,
                    formatExpression
            );

            lastEnd = end;

            if (resultExpression == null) {
                // 如果之前并没有任何相加语句，则这个是第一次相加
                resultExpression = lastEndToStartExpr;
            } else {
                // 如果之前有相加语句，则与之前的语句进行相加
                resultExpression = treeMaker.Binary(
                        JCTree.Tag.PLUS,
                        resultExpression,
                        lastEndToStartExpr
                );
            }
        }

        // 在最后可能还有字符串，需要再添加进来
        if (lastEnd < lit.length()) {
            resultExpression = treeMaker.Binary(
                    JCTree.Tag.PLUS,
                    resultExpression,
                    treeMaker.Literal(lit.substring(lastEnd))
            );
        }

        return resultExpression;
    }

    /**
     * 处理参数类型
     * @param arg 参数
     * @return 格式的表达式
     */
    private JCTree.JCExpression parseArg(JCTree.JCExpression arg) {
        switch (arg.getKind()) {
            case PLUS:
                // 众所周知，两个字符串字面量相加一定会被javac整合在一起变成一个字面量
                // 所以出现在这里的一定是(变量 or 表达式) + (字面量 or 变量 or 表达式)
                //       root
                //      /    \
                //     l      r
                //    / \
                //   l   r

                JCTree.JCBinary jcBinary = (JCTree.JCBinary) arg;
                return treeMaker.Binary(
                        JCTree.Tag.PLUS,
                        parseArg(jcBinary.lhs),
                        parseArg(jcBinary.rhs)
                );
            case STRING_LITERAL:
                // 字面量
                JCTree.JCLiteral jcLiteral = (JCTree.JCLiteral) arg;
                return parseFormatString(jcLiteral.value.toString());
            case IDENTIFIER:
                // 变量
            case METHOD_INVOCATION:
                // 调用方法
                return arg;
            case NULL_LITERAL:
                // null
                return treeMaker.Literal(TypeTag.BOT, null);
            default:
                messager.printMessage(Diagnostic.Kind.ERROR, "不支持的类型: " + arg.getKind());
                return null;
        }
    }


    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {

        // 处理有 @EnableFormat 注解的元素
        for (Element element : roundEnv.getElementsAnnotatedWith(EnableFormat.class)) {
            JCTree jcTree = trees.getTree(element);
            jcTree.accept(new TreeTranslator() {

                @Override
                public void visitApply(JCTree.JCMethodInvocation methodInvocation) {

                    JCTree.JCExpression select = methodInvocation.getMethodSelect();
                    if ("io.qaralotte.meru.utils.StringUtils.format".equals(select.toString()) ||
                        "StringUtils.format".equals(select.toString())) {

                        // 只有一个参数，而且必须是 String 类型
                        JCTree.JCExpression argExpr = methodInvocation.args.get(0);
                        methodInvocation.args = List.of(parseArg(argExpr));
                    }

                    super.visitApply(methodInvocation);
                }
            });
        }

        return false;
    }

}
